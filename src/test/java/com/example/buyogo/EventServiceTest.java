package com.example.buyogo;



import com.example.buyogo.entity.Event;
import com.example.buyogo.repository.EventRepository;
import com.example.buyogo.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EventServiceTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll(); // Start with a clean DB for every test
    }

    // --- Helper to create a dummy event ---
    private Event createEvent(String id, String machineId, long duration, int defects) {
        Event e = new Event();
        e.setEventId(id);
        e.setMachineId(machineId);
        e.setLineId("L1");
        e.setDurationMs(duration);
        e.setDefectCount(defects);

        // GENERIC FIX: Use basic math with epoch milliseconds.
        // System.currentTimeMillis() returns milliseconds, so this automatically
        // strips the nanoseconds that were causing the test failure.
        long sixtySecondsAgoMillis = System.currentTimeMillis() - 60000;
        e.setEventTime(Instant.ofEpochMilli(sixtySecondsAgoMillis));

        return e;
    }

    // 1. Identical duplicate eventId -> deduped
    @Test
    void testIdenticalPayloadDeduped() {
        Event e1 = createEvent("E-1", "M1", 1000, 0);

        // First batch: Insert
        eventService.processBatch(List.of(e1));

        // Second batch: Identical
        EventService.BatchSummary summary = eventService.processBatch(List.of(e1));

        assertEquals(1, summary.getDeduped());
        assertEquals(0, summary.getAccepted());
        assertEquals(1, eventRepository.count());
    }

    // 2. Different payload + newer received Time -> update happens
    @Test
    void testDifferentPayloadUpdate() {
        Event e1 = createEvent("E-1", "M1", 1000, 0);
        eventService.processBatch(List.of(e1));

        // Update: Different duration
        Event e2 = createEvent("E-1", "M1", 2000, 5);
        EventService.BatchSummary summary = eventService.processBatch(List.of(e2));

        assertEquals(1, summary.getUpdated());
        Event stored = eventRepository.findByEventId("E-1").get();
        assertEquals(2000, stored.getDurationMs()); // Verify payload changed
    }

    // 3. Different payload + older received Time -> ignored
    @Test
    void testOlderReceivedTimeIgnored() {
        // Manually insert an event with Future receivedTime (simulating a "newer" existing record)
        Event newerInDb = createEvent("E-1", "M1", 5000, 0);
        newerInDb.setReceivedTime(Instant.now().plusSeconds(3600)); // 1 hour in future
        eventRepository.save(newerInDb);

        // Try to process a batch "now" (which is older than future)
        Event incomingOld = createEvent("E-1", "M1", 1000, 0); // Different duration
        EventService.BatchSummary summary = eventService.processBatch(List.of(incomingOld));

        // Should NOT update because incoming (Now) is before DB (Future)
        Event stored = eventRepository.findByEventId("E-1").get();
        assertEquals(5000, stored.getDurationMs()); // Should remain 5000, not 1000
    }

    // 4. Invalid duration rejected
    @Test
    void testInvalidDurationRejected() {
        Event e1 = createEvent("E-1", "M1", -100, 0); // Negative
        Event e2 = createEvent("E-2", "M1", Duration.ofHours(7).toMillis(), 0); // > 6 hours

        EventService.BatchSummary summary = eventService.processBatch(List.of(e1, e2));

        assertEquals(2, summary.getRejected());
        assertEquals("INVALID_DURATION", summary.getRejections().get(0).get("reason"));
    }

    // 5. Future eventTime rejected
    @Test
    void testFutureEventTimeRejected() {
        Event e1 = createEvent("E-1", "M1", 1000, 0);
        e1.setEventTime(Instant.now().plus(Duration.ofMinutes(20))); // > 15 mins allowed

        EventService.BatchSummary summary = eventService.processBatch(List.of(e1));

        assertEquals(1, summary.getRejected());
        assertEquals("FUTURE_EVENT_TIME", summary.getRejections().get(0).get("reason"));
    }

    // 6. DefectCount = -1 ignored in defect totals
    @Test
    void testUnknownDefectsIgnored() {
        Event e1 = createEvent("E-1", "M1", 1000, 5);  // 5 defects
        Event e2 = createEvent("E-2", "M1", 1000, -1); // Unknown
        e1.setEventTime(Instant.now());
        e2.setEventTime(Instant.now());

        eventService.processBatch(List.of(e1, e2));

        Map<String, Object> stats = eventService.getMachineStats("M1", Instant.now().minusSeconds(10), Instant.now().plusSeconds(10));

        assertEquals(5, ((Number) stats.get("defectsCount")).intValue()); // Should be 5, not 4 or 5-1
    }

    // 7. Start/end boundary correctness (inclusive/exclusive)
    @Test
    void testQueryBoundaries() {
        Instant t10_00 = Instant.parse("2026-01-01T10:00:00Z");

        Event e1 = createEvent("E-1", "M1", 1000, 0);
        e1.setEventTime(t10_00); // Exact start time
        e1.setReceivedTime(Instant.now());
        eventRepository.save(e1);

        // Query 10:00 to 11:00 (Inclusive Start) -> Should match
        List<Event> result1 = eventRepository.findByMachineIdAndEventTimeBetween("M1", t10_00, t10_00.plusSeconds(3600));
        assertEquals(1, result1.size());

        // Query 09:00 to 10:00 (Exclusive End) -> Should NOT match
        List<Event> result2 = eventRepository.findByMachineIdAndEventTimeBetween("M1", t10_00.minusSeconds(3600), t10_00);
        assertEquals(0, result2.size());
    }

    // 8. Thread-safety test
    @Test
    void testConcurrency() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();

        // 10 threads trying to insert DIFFERENT events at the exact same time
        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    Event e = createEvent("E-" + index, "M1", 1000, 0);
                    eventService.processBatch(List.of(e));
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // Wait for all to finish
        executor.shutdown();

        assertEquals(10, eventRepository.count());
        assertEquals(10, successCount.get());
    }

    @Test
    void benchmark1000Events() {
        // 1. Prepare 1000 dummy events
        List<Event> batch = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Event e = new Event();
            e.setEventId("BENCH-" + i);
            e.setMachineId("M-BENCH");
            e.setLineId("L1");
            e.setDurationMs(100);
            e.setDefectCount(0);
            // Use the fix we made earlier for generic time
            e.setEventTime(Instant.ofEpochMilli(System.currentTimeMillis()));
            batch.add(e);
        }

        // 2. Start Timer
        long startTime = System.currentTimeMillis();

        // 3. Process Batch
        eventService.processBatch(batch);

        // 4. Stop Timer
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // 5. Print Result for your Report
        System.out.println("\n\n========================================");
        System.out.println("BENCHMARK RESULT:");
        System.out.println("Batch Size: 1000 Events");
        System.out.println("Time Taken: " + duration + " ms");
        System.out.println("========================================\n\n");
    }
}
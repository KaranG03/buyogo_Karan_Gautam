package com.example.buyogo.service;

import com.example.buyogo.entity.Event;
import com.example.buyogo.repository.EventRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final MongoTemplate mongoTemplate;

    // --- 1. Batch Ingestion Logic ---

    @Data
    @Builder
    public static class BatchSummary {
        private int accepted;
        private int deduped;
        private int updated;
        private int rejected;
        private List<Map<String, String>> rejections;
    }

    public BatchSummary processBatch(List<Event> incomingEvents) {
        Instant now = Instant.now();
        List<Event> validEvents = new ArrayList<>();
        List<Map<String, String>> rejections = new ArrayList<>();

        // A. Validation Phase
        for (Event event : incomingEvents) {
            String error = validateEvent(event, now);
            if (error != null) {
                rejections.add(Map.of("eventId", event.getEventId(), "reason", error));
            } else {
                // Set receivedTime explicitly as per rule
                event.setReceivedTime(now);
                validEvents.add(event);
            }
        }

        if (validEvents.isEmpty()) {
            return BatchSummary.builder().rejected(rejections.size()).rejections(rejections).build();
        }

        // B. Fetch Existing Events (Optimized: 1 Query)
        Set<String> eventIds = validEvents.stream().map(Event::getEventId).collect(Collectors.toSet());
        List<Event> existingList = mongoTemplate.find(
                Query.query(Criteria.where("eventId").in(eventIds)), Event.class);

        // Create Map (Handle DB duplicates gracefully just in case)
        Map<String, Event> existingMap = existingList.stream()
                .collect(Collectors.toMap(
                        Event::getEventId,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        // C. Prepare Bulk Operations
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Event.class);
        int accepted = 0;
        int deduped = 0;
        int updated = 0;

        for (Event newEvent : validEvents) {
            Event existing = existingMap.get(newEvent.getEventId());

            if (existing == null) {
                // Case 1: New Event -> Insert
                bulkOps.insert(newEvent);

                // FIX 1: Update map immediately so intra-batch duplicates are caught
                existingMap.put(newEvent.getEventId(), newEvent);

                accepted++;
            } else {
                // Case 2: Dedupe (Identical Payload)
                if (isPayloadIdentical(existing, newEvent)) {
                    deduped++; // [cite: 43]
                }
                else {
                    // FIX 2: Check for "Older Received Time"
                    // If the DB version is somehow newer than our current "now", ignore this update.
                    if (existing.getReceivedTime() != null &&
                            existing.getReceivedTime().isAfter(newEvent.getReceivedTime())) {
                        // Ignore this update because our data is stale compared to DB
                        continue;
                    }

                    // Case 3: Valid Update
                    Query query = Query.query(Criteria.where("eventId").is(newEvent.getEventId()));

                    Update update = new Update()
                            .set("machineId", newEvent.getMachineId())
                            .set("lineId", newEvent.getLineId())
                            .set("eventTime", newEvent.getEventTime())
                            .set("receivedTime", newEvent.getReceivedTime())
                            .set("durationMs", newEvent.getDurationMs())
                            .set("defectCount", newEvent.getDefectCount());

                    bulkOps.updateOne(query, update);

                    // FIX 1: Update map immediately for consistency
                    existingMap.put(newEvent.getEventId(), newEvent);

                    updated++;
                }
            }
        }

        // D. Execute Bulk Write
        if (accepted > 0 || updated > 0) {
            bulkOps.execute();
        }

        return BatchSummary.builder()
                .accepted(accepted)
                .deduped(deduped)
                .updated(updated)
                .rejected(rejections.size())
                .rejections(rejections)
                .build();
    }

    private String validateEvent(Event e, Instant now) {
        // Rule: durationMs between 0 and 6 hours
        long sixHoursMs = 6 * 60 * 60 * 1000;
        if (e.getDurationMs() < 0 || e.getDurationMs() > sixHoursMs) {
            return "INVALID_DURATION";
        }
        // Rule: eventTime not > 15 mins in future
        if (e.getEventTime().isAfter(now.plus(Duration.ofMinutes(15)))) {
            return "FUTURE_EVENT_TIME";
        }
        if (e.getEventId() == null || e.getMachineId() == null) {
            return "MISSING_MANDATORY_FIELDS";
        }
        return null; // Valid
    }

    private boolean isPayloadIdentical(Event existing, Event incoming) {
        // Compare business fields (excluding metadata like receivedTime/internal ID)
        return Objects.equals(existing.getMachineId(), incoming.getMachineId()) &&
                Objects.equals(existing.getLineId(), incoming.getLineId()) &&
                Objects.equals(existing.getEventTime(), incoming.getEventTime()) &&
                existing.getDurationMs() == incoming.getDurationMs() &&
                existing.getDefectCount() == incoming.getDefectCount();
    }

    // --- 2. Query Stats Logic ---

    public Map<String, Object> getMachineStats(String machineId, Instant start, Instant end) {
        List<Event> events = eventRepository.findByMachineIdAndEventTimeBetween(machineId, start, end);

        long validEventsCount = events.size();

        // Filter out unknown defects (-1)
        long defectEventsCount = events.stream()
                .filter(e -> e.getDefectCount() != -1)
                .mapToInt(Event::getDefectCount)
                .sum();

        // Calculate Window in Hours
        double windowSeconds = Duration.between(start, end).getSeconds();
        double windowHours = windowSeconds / 3600.0;
        if (windowHours == 0) windowHours = 1; // Prevent division by zero

        double avgDefectRate = defectEventsCount / windowHours;

        String status = avgDefectRate < 2.0 ? "Healthy" : "Warning"; //

        return Map.of(
                "machineId", machineId,
                "start", start,
                "end", end,
                "eventsCount", validEventsCount,
                "defectsCount", defectEventsCount,
                "avgDefectRate", avgDefectRate,
                "status", status
        );
    }

    // --- 3. Top Defect Lines Logic ---

    public List<Map> getTopDefectLines(String factoryId, Instant from, Instant to, int limit) {
        // Aggregation to calculate totals grouped by lineId
        Aggregation aggregation = newAggregation(
                match(Criteria.where("eventTime").gte(from).lt(to)), // Filter by time window .and("factoryId").is(factoryId) if factoryid is given

                // Group by lineId
                group("lineId")
                        .sum(ConditionalOperators.when(Criteria.where("defectCount").ne(-1))
                                .then("$defectCount").otherwise(0)) // Sum defects only if != -1
                        .as("totalDefects")
                        .count().as("eventCount"),

                // Project fields and calculate percentage
                project("totalDefects", "eventCount")
                        .and("_id").as("lineId")
                        .andExpression("totalDefects * 100 / eventCount").as("defectsPercent"), //

                sort(Sort.Direction.DESC, "totalDefects"),
                limit(limit)
        );

        AggregationResults<Map> results = mongoTemplate.aggregate(aggregation, "events", Map.class);
        return results.getMappedResults();
    }
}
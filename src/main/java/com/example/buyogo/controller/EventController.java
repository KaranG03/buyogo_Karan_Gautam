package com.example.buyogo.controller;
import com.example.buyogo.entity.Event;
import com.example.buyogo.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    // A) Ingest Batch
    @PostMapping("/events/batch")
    public ResponseEntity<EventService.BatchSummary> ingestBatch(@RequestBody List<Event> events) {
        EventService.BatchSummary summary = eventService.processBatch(events);
        return ResponseEntity.ok(summary);
    }

    // B) Query Stats
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam String machineId,
            @RequestParam String start, // ISO-8601 Strings e.g., 2026-01-15T00:00:00Z
            @RequestParam String end) {

        Instant startInstant = Instant.parse(start);
        Instant endInstant = Instant.parse(end);

        Map<String, Object> stats = eventService.getMachineStats(machineId, startInstant, endInstant);
        return ResponseEntity.ok(stats);
    }

    // C) Top Defect Lines
    @GetMapping("/stats/top-defect-lines")
    public ResponseEntity<List<Map>> getTopDefectLines(
            @RequestParam(required = false) String factoryId, // Not explicitly used in schema but requested in URL
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "10") int limit) {

        Instant fromInstant = Instant.parse(from);
        Instant toInstant = Instant.parse(to);

        List<Map> topLines = eventService.getTopDefectLines(factoryId, fromInstant, toInstant, limit);
        return ResponseEntity.ok(topLines);
    }
}
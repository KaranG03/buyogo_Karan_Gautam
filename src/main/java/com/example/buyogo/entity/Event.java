package com.example.buyogo.entity;



import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "events")
public class Event {

    @Id
    private String id; // Internal MongoDB ID

    @Indexed(unique = true) // Ensures fast lookups and uniqueness constraints
    private String eventId;

    private String machineId;

    // Added to support the 'Top Defect Lines' endpoint
    private String lineId;

    private Instant eventTime;     // Used for query windows
    private Instant receivedTime;  // Set by the server upon ingestion

    private long durationMs;
    private int defectCount;       // -1 indicates 'unknown'
}
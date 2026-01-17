package com.example.buyogo.repository;




import com.example.buyogo.entity.Event;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends MongoRepository<Event, String> {

    // Helper for the Query Stats endpoint
    // Fetches events for a specific machine within the time window
    @Query("{ 'machineId': ?0, 'eventTime': { $gte: ?1, $lt: ?2 } }")
    List<Event> findByMachineIdAndEventTimeBetween(String machineId, Instant start, Instant end);

    Optional<Event> findByEventId(String eventId);
}
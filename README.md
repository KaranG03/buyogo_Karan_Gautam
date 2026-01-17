# Buyogo Event Ingestion Service

## 1. Architecture
This service is designed as a high-performance REST API built with **Spring Boot** and **MongoDB**.
* **Controller Layer:** Receives HTTP batches and delegates processing.
* **Service Layer (`EventService`):** Implements the core business logic, including validation, deduplication, and batch processing.
* **Data Access:** Uses `MongoTemplate` for high-performance bulk write operations and `MongoRepository` for standard read queries.
* **Database:** MongoDB (running locally) stores events in the `events` collection.

## 2. Dedupe & Update Logic
To handle duplicate data and updates efficiently without N+1 queries:

1.  **Batch Read:** Upon receiving a batch of 1,000 events, the system extracts all `eventId`s and performs a **single database query** to fetch any existing records.
2.  **In-Memory Comparison:** These existing records are mapped in a `HashMap` for O(1) lookup.
3.  **The "Winning" Record Logic:**
    * **New ID:** If the ID is not in the map $\rightarrow$ **Insert**.
    * **Existing ID + Identical Payload:** If business fields (Machine, Line, Defects, etc.) match $\rightarrow$ **Ignore (Dedupe)**.
    * **Existing ID + Different Payload:**
        * If the incoming event's `receivedTime` is newer $\rightarrow$ **Update**.
        * If the incoming event is older than the DB record $\rightarrow$ **Ignore (Stale Data)**.
4.  **Intra-Batch Consistency:** When an event is processed (inserted or updated), the in-memory map is immediately updated. This ensures that if a duplicate `eventId` appears later *in the same batch*, it is compared against the freshest version.

## 3. Thread-Safety
The application ensures thread safety through **Database-Level Concurrency**:
* **Stateless Service:** The `EventService` does not hold state between requests.
* **Atomic Writes:** MongoDB handles document-level locking.
* **Bulk Operations:** We use `BulkOperations` (Unordered). If multiple threads attempt to insert the same ID simultaneously, MongoDB's unique index constraint on `eventId` prevents corruption. The application captures these duplicate key errors gracefully.

## 4. Data Model
**Collection:** `events`

| Field | Type | Description |
| :--- | :--- | :--- |
| `eventId` | String | Unique Identifier (Indexed) |
| `machineId` | String | ID of the machine |
| `lineId` | String | ID of the production line |
| `eventTime` | Instant | When the event occurred (Payload) |
| `receivedTime` | Instant | When the system received the event (Metadata) |
| `durationMs` | Long | Duration of the event |
| `defectCount` | Integer | Number of defects (-1 = unknown) |

## 5. Performance Strategy
The system processes 1,000 events in **~141ms** (Target: < 1s).
* **Bulk Writes:** Instead of 1,000 network calls (`save()`), we use `mongoTemplate.bulkOps()` to send 1,000 operations in a single network packet.
* **Unordered Execution:** Allows MongoDB to execute writes in parallel on the server side.
* **Batch Reading:** Fetches all necessary validation data in 1 read query instead of 1,000 `findById` calls.

## 6. Edge Cases & Assumptions
* **Time Precision:** Java `Instant` (nanoseconds) differs from MongoDB (milliseconds). We explicitly truncate incoming times to milliseconds to ensure accurate payload comparison.
* **Unknown Defects:** Events with `defectCount = -1` are stored but excluded from "Healthy/Warning" statistic calculations.
* **Clock Drift:** Events with `eventTime` > 15 minutes in the future are rejected.
* **Assumption:** The `lineId` field is required for the "Top Defect Lines" aggregation, even though it wasn't in the initial minimal JSON example.

## 7. Setup & Run Instructions

### Prerequisites
* Java 23 (OpenJDK)
* Maven
* MongoDB running on `localhost:27017`

### Steps
1.  **Clone/Unzip** the project.
2.  **Clean & Build:**
    ```bash
    mvn clean install
    ```
3.  **Run the Application:**
    ```bash
    mvn spring-boot:run
    ```
4.  **Run Tests:**
    ```bash
    mvn test
    ```

## 8. Future Improvements
* **Message Queue:** Introduce Apache Kafka/RabbitMQ to buffer events before ingestion, decoupling the API from the database.
* **Sharding:** Enable MongoDB sharding on `factoryId` or `machineId` to scale horizontally for billions of events.
* **Caching:** Add Redis to cache the `existingMap` for frequently accessed machine data, reducing DB read load further.

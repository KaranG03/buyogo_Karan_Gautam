# Benchmark Report

## 1. System Specifications
* **CPU:** AMD Ryzen 7 5800H with Radeon Graphics
* **RAM:** 16 GB
* **Disk:** 512 GB SSD
* **OS:** Windows

## 2. Benchmark Command
To validate the ingestion performance, I executed a dedicated JUnit integration test that generates and processes a batch of 1,000 events against a local MongoDB instance.

**Command used:**
```bash
mvn test -Dtest=EventServiceTest#benchmark1000Events
```
## 3. Measured Timing

The following results were captured for ingesting a single batch of **1,000 events**:

| Metric | Result | Target | Status |
| --- | --- | --- | --- |
| **Time Taken** | **141 ms** | < 1000 ms | ✅ PASS |
| **Throughput** | **~7,092 events/sec** | N/A | - |

*(Calculated: 1000 events / 0.141 seconds)*

## 4. Optimizations Attempted

To achieve sub-second processing for 1,000 events, the following optimizations were implemented:

1. **Bulk Operations (BulkOps):**
* Instead of executing 1,000 individual `save()` calls (which would require 1,000 network round-trips), I used `mongoTemplate.bulkOps()`.
* This groups all Inserts and Updates into a **single network packet**, drastically reducing latency.


2. **Unordered Execution Strategy:**
* Configured `BulkMode.UNORDERED` to allow MongoDB to execute writes in parallel on the server side, rather than sequentially waiting for each to finish.


3. **Batch Read (In-Memory Map):**
* Implemented a "Pre-Fetch" strategy. Instead of querying the database 1,000 times (once per event ID), the code extracts all 1,000 IDs and fetches existing records in **one single database query**.
* These records are stored in a `HashMap` for O(1) instant lookup during the deduplication logic.





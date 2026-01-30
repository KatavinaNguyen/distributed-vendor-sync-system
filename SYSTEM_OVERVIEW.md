# System Overview

## 1. Purpose

This app is an event-driven system for ingesting, normalizing, and preparing
vendor-supplied inventory and order updates that arrive with **inconsistent
schemas, identifiers, and delivery semantics**.

The system is designed to operate reliably in environments where vendors may:

* Retry requests non-deterministically
* Change schemas over time
* Deliver partial or out-of-order updates
* Provide limited delivery guarantees

This app converts untrusted external updates into durable, validated, and standardized
event representations suitable for downstream consumption.

---

## 2. System Overview

This app is built as a **distributed, event-driven processing pipeline** designed to scale horizontally with vendor traffic and isolate failures across processing stages.

The system separates concerns into independently deployable components:
- **Ingestion**, which focuses on durability, idempotency, and acceptance guarantees
- **Normalization**, which focuses on schema validation and standardized data transformation

By decoupling stages through asynchronous events and durable storage, This app scales linearly with load while preserving correctness under failure.

---

## 3. High-Level Architecture

```
External Vendor
│
▼
API Gateway
│
▼
Ingest Lambda
│
├── Persist raw payload → S3 (durable, immutable)
├── Persist ingest record → DynamoDB (idempotency, audit)
└── Emit EventBridge event
│
▼
EventBridge Bus
│
▼
Normalize Lambda
│
├── Load raw payload from S3
├── Validate schema
└── Normalize to standardized representation
```

The architecture is fully asynchronous beyond the ingest boundary, enabling:
- Independent scaling of each stage
- Backpressure handling via managed AWS services
- Failure isolation without cascading impact

> [!NOTE]
> **Additional architecture diagrams and system flows are available in the repository:**  
> https://github.com/KatavinaNguyen/distributed-vendor-order-inventory-sync-system/tree/main/docs/architecture
>
> This directory contains **both overview and deep-dive diagrams** covering ingestion flow, event boundaries, data persistence, and processing-stage isolation, providing high-level context alongside implementation-level detail.

---

## 4. Core Design Principles

### 4.1 Idempotent, Fault-Tolerant Ingestion

Each inbound request is uniquely identified by:

* `vendorId`
* `externalEventId`

These identifiers are enforced through DynamoDB-backed idempotency checks.

This ensures:
- Safe handling of non-deterministic retries
- Exactly-once logical acceptance at the ingest boundary
- Protection against duplicate side effects during retries or partial failures

Ingestion is resilient to transient AWS failures and vendor retry storms.

---

### 4.2 Durable, Immutable Source Data

All vendor payloads are persisted verbatim in S3 and treated as immutable,
append-only records.

This design:
* Eliminates data loss during downstream failures
* Enables deterministic reprocessing and backfill
* Decouples ingestion reliability from processing correctness

Raw payload storage provides a durable buffer that absorbs spikes in traffic and downstream outages without impacting vendor-facing availability.

---

### 4.3 Event-Driven Distribution and Isolation

This app uses EventBridge as a distributed coordination mechanism between stages.

Benefits include:
- Loose coupling between ingestion and processing
- Independent deployment and scaling
- Natural fan-out and extension points
- Failure containment at stage boundaries
- Events contain only lightweight references, enabling efficient distribution without duplicating payload data.

---

### 4.4 Standardized, Contract-Driven Data Normalization

Normalization converts vendor-specific payloads into **standardized event representations** governed by explicit schema contracts.

This approach:
- Shields downstream systems from vendor variability
- Centralizes schema enforcement
- Enables consistent processing across vendors at scale

Normalization logic is deterministic and repeatable, allowing safe retries and large-scale reprocessing without behavioral drift.

---

## 5. Implemented Components

### 5.1 Ingest Lambda

**Responsibilities**

* Validate request envelopes
* Enforce idempotency guarantees
* Persist ingest metadata
* Persist raw payloads immutably
* Emit ingestion events

**Recorded metadata includes**

* `vendorId`
* `externalEventId`
* `ingestId`
* `receivedAt`
* `schemaType`
* Raw payload storage location

---

### 5.2 Normalize Lambda

**Responsibilities**

* Consume ingestion events
* Load raw payloads from S3
* Validate payloads against schema contracts
* Normalize vendor-specific data into standardized representations

**Behavior**

* Deterministic read-from-source processing
* Idempotent execution
* Structured logging and metric emission
* Safe retry under failure conditions

---

## 6. Data Stores

### 6.1 DynamoDB — Ingest Records

**Purpose**

* Enforce idempotency
* Maintain an ingest audit trail
* Track processing lifecycle

**Keys**

* Partition Key: `vendorId`
* Sort Key: `externalEventId`

**Attributes**

* `ingestId`
* `receivedAt`
* `status`
* `schemaType`
* Raw payload reference

---

### 6.2 S3 — Raw Payload Storage

**Structure**

```
raw/
└── vendorId=<vendor>
    └── receivedAt=<timestamp>
        └── ingestId=<uuid>.json
```

**Properties**

* Immutable
* Append-only
* Encrypted at rest
* Partitioned for efficient replay

---

## 7. Failure Model

This app is designed to remain operational under partial failure and degraded conditions.

| Failure Scenario         | System Behavior                        |
| ------------------------ | -------------------------------------- |
| Vendor retry storms      | Absorbed via idempotent ingest         |
| Duplicate submissions    | Safely ignored                         |
| Schema validation errors | Isolated without data loss             |
| Processing failures      | Retryable; inputs preserved for replay |
| Downstream disruption    | Ingestion continues unaffected         |

Failures are isolated to individual stages and do not compromise global system integrity or data durability.

---

## 8. Security Model

* IAM roles follow least-privilege principles
* Each Lambda is scoped to required AWS resources only
* S3 buckets block public access and enforce encryption
* No secrets are committed to the repository
* Local configuration is excluded via `.gitignore`

---

## 9. Summary

This app demonstrates a scalable and fault-tolerant approach to vendor data ingestion by combining:

- Event-driven distribution
- Idempotent processing guarantees
- Durable, immutable storage
- Schema-driven normalization

The system is designed to scale horizontally with vendor load while maintaining correctness, auditability, and resilience under failure.

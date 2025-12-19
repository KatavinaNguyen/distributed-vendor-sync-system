# Invariants

This document defines the invariants (must-always-hold rules) for the Distributed Vendor Order & Inventory Synchronization System. These invariants are the guardrails that keep synchronization deterministic, replay-safe, and resilient to vendor failures.

---

## 1. Core Principles

### 1.1 Determinism
Given the same sequence of accepted inputs, the system must produce the same reconciled canonical state.

### 1.2 Idempotency
Re-processing the same vendor update must not change canonical state after it has already been applied once.

### 1.3 Replay Safety
The system must be able to replay historical ingested events (from raw landing) without corrupting current state or double-applying updates.

### 1.4 Failure Isolation
A single vendor’s malformed or inconsistent updates must not block ingestion or synchronization for other vendors.

---

## 2. Required Fields (Events)

These are required for any event to enter the pipeline.

### 2.1 Required for all vendor events
- `vendorId` (string)
  - Derived from authentication (API key), not trusted if provided by vendor payload.
- `externalEventId` (string)
  - Vendor-provided stable id for deduplication and replay.
- `receivedAt` (string, ISO-8601 UTC)
  - Server-generated timestamp set at ingest.
- `eventType` (string)
  - One of: `InventoryUpdate`, `OrderStatusUpdate`
  - (Can be implied by endpoint in MVP; still required conceptually.)

### 2.2 Required identifiers per event type
- InventoryUpdate must include:
  - `vendorProductKey` (string)
- OrderStatusUpdate must include:
  - `vendorOrderKey` (string)

### 2.3 Required value fields per event type
- InventoryUpdate must include:
  - `quantity` (number)
  - `unit` (enum/string)
  - `semantics` (`ABSOLUTE` or `DELTA`)
- OrderStatusUpdate must include:
  - `status` (OrderStatus enum)

---

## 3. Timestamp Invariants

### 3.1 receivedAt is authoritative for pipeline ordering
- `receivedAt` is always set by the ingest stage.
- `receivedAt` is always UTC.
- `receivedAt` must be present on every ingested record and every downstream stage message.

### 3.2 sourceTimestamp is optional and treated as untrusted input
- If present, `sourceTimestamp` must be parseable as ISO-8601 UTC.
- `sourceTimestamp` may be used in reconciliation ordering rules, but only with deterministic tie-breakers.
- If invalid, the update is quarantined (reason: `INVALID_TIMESTAMP`).

---

## 4. Idempotency + Deduplication

### 4.1 Global rule
An update is uniquely identified by the pair:
- `(vendorId, externalEventId)`

This pair must never be processed as a "new" update more than once.

### 4.2 Deduplication behavior
- If `(vendorId, externalEventId)` has already been seen:
  - Ingest returns success (202) but does not enqueue a new pipeline run.
  - The duplicate is counted as a metric (e.g., `duplicateEvents`).

### 4.3 Event immutability requirement
- A vendor must not reuse an `externalEventId` for different payload contents.
- If the same `(vendorId, externalEventId)` arrives with different payload hash:
  - quarantine (reason: `EVENT_ID_REUSE_DIFFERENT_PAYLOAD`)

---

## 5. Validation Invariants (Fast Reject vs Quarantine)

### 5.1 Fast Reject (do not enter pipeline)
Reject at ingest (HTTP 4xx) if:
- authentication fails
- required fields are missing
- payload is not valid JSON

### 5.2 Quarantine (accepted but isolated)
Accept at ingest (202) but quarantine during downstream stages if:
- mapping is missing / ambiguous
- canonical required fields cannot be produced
- timestamp parsing fails (if not caught earlier)
- business rules fail (e.g., unit mismatch)

Quarantine must record:
- `vendorId`
- `externalEventId`
- `receivedAt`
- reason code
- pointer to raw payload (S3 key)
- stage that quarantined it

---

## 6. Canonical Identity Invariants (after Match stage)

### 6.1 Match must produce canonical IDs or quarantine
- InventoryUpdate must resolve to:
  - `canonicalProductId`
- OrderStatusUpdate must resolve to:
  - `canonicalOrderId`

If the system cannot resolve a canonical ID deterministically:
- quarantine (reason: `UNMATCHED_IDENTIFIER` or `AMBIGUOUS_MATCH`)

### 6.2 Mapping versioning
- Match/normalize must use the currently `ACTIVE` VendorMapping version.
- Mappings are immutable once activated; new versions are created instead.
- Events processed under mapping version X must record `mappingVersion = X`.

---

## 7. Reconciliation Safety Invariants

### 7.1 Monotonic versioning of canonical state
- Each canonical state record includes `version` (integer).
- Updates must use conditional writes (compare-and-swap) to ensure:
  - no lost updates
  - deterministic increments

### 7.2 Apply-once invariant per entity
- Each canonical state record stores `lastAppliedEventId`.
- If the incoming update’s `(vendorId, externalEventId)` matches the already-applied event for that entity:
  - do nothing (idempotent no-op)

(Implementation note: for higher throughput, this may be replaced with an applied-events index; MVP can start with per-entity last applied + ingest-level dedupe.)

### 7.3 Conflict rules must be deterministic
- If two updates "compete" (same timestamp or contradictory values), tie-breakers must be deterministic:
  - vendor priority
  - externalEventId lexical order (final tie-breaker)
- Non-deterministic rules are forbidden (e.g., "whichever arrives first").

---

## 8. Inventory Semantics Invariants

### 8.1 ABSOLUTE vs DELTA must be explicit
- Every InventoryUpdate has `semantics`.
- ABSOLUTE replaces current quantity (subject to ordering rules).
- DELTA modifies current quantity by addition.

### 8.2 Unit consistency
- Inventory updates must not mix units for the same `canonicalProductId` (and `locationKey` if used).
- If a unit mismatch occurs:
  - quarantine (reason: `UNIT_MISMATCH`)

### 8.3 Negative quantity policy (MVP rule)
- If applying DELTA results in negative quantity:
  - quarantine (reason: `NEGATIVE_QUANTITY_RESULT`)
- ABSOLUTE quantity may be zero; negative ABSOLUTE is quarantined.

---

## 9. Availability Invariants (Failure Isolation)

### 9.1 Per-vendor isolation
- A vendor producing repeated failures must not degrade the pipeline for other vendors.
- Quarantine and metrics must capture failures without blocking other processing.

### 9.2 Backpressure does not block ingestion
- Ingest must remain available even if downstream stages are degraded.
- Ingest should continue landing raw payloads and recording ingest entries.

---

## 10. Observability Invariants

### 10.1 Every event is traceable end-to-end
Every stage message and every quarantine/divergence record must include:
- `vendorId`
- `externalEventId`
- `ingestId` (or ingest record key)
- `receivedAt`

### 10.2 Metrics must exist for core pipeline health
At minimum:
- ingest rate
- normalize failure rate
- match failure rate
- reconcile latency (p50/p95)
- quarantine rate by reason
- divergence rate

# Conflict Rules

This document defines the deterministic conflict-resolution rules used by the system during reconciliation. Conflicts are expected because vendor updates may be late, duplicated, contradictory, or based on inconsistent identifiers.

The goal is **convergence**: given the same accepted inputs, the system must always compute the same canonical state.

---

## 1. Definitions

### 1.1 Event timestamps
- `receivedAt` (required): server-generated UTC timestamp when the event was ingested.
- `sourceTimestamp` (optional): vendor-provided UTC timestamp representing when the vendor claims the change occurred.

### 1.2 Ordering timestamp (orderKeyTime)
For every event, compute:

1. If `sourceTimestamp` is present and valid, use it.
2. Otherwise, use `receivedAt`.

Call this computed value: `orderKeyTime`.

### 1.3 Tie-break keys (for determinism)
If two events have the same `orderKeyTime`, the system uses deterministic tie-breakers:

1. Vendor priority (lower number = higher priority)
2. `externalEventId` lexicographic ascending (final tie-breaker)

---

## 2. Global Ordering Rules (applies to all events)

### Rule G1: Idempotency first
If an incoming event is a duplicate of an already-seen `(vendorId, externalEventId)`, it is a no-op.

### Rule G2: Deterministic ordering
Events are applied in ascending `(orderKeyTime, vendorPriority, externalEventId)` order.

### Rule G3: Reject invalid timestamps
If `sourceTimestamp` exists but is invalid/unparseable:
- quarantine with reason `INVALID_TIMESTAMP`

### Rule G4: Unit consistency (inventory only)
If an update attempts to apply a different `unit` than the existing unit for that scope:
- quarantine with reason `UNIT_MISMATCH`

Scope is:
- `(canonicalProductId)` for MVP
- `(canonicalProductId, locationKey)` if location is enabled

---

## 3. Inventory Conflict Rules

Inventory conflicts occur when multiple updates attempt to set/modify inventory for the same canonical product (and optional location).

### 3.1 ABSOLUTE vs DELTA semantics
- `ABSOLUTE`: event’s `quantity` represents the full current quantity
- `DELTA`: event’s `quantity` represents an increment/decrement to apply

### Rule I1: Applying ABSOLUTE
If an incoming ABSOLUTE event is the next event by ordering:
- set `quantity = event.quantity`

### Rule I2: Applying DELTA
If an incoming DELTA event is the next event by ordering:
- set `quantity = current.quantity + event.quantity`

### Rule I3: Negative quantity policy
- If applying DELTA would result in `quantity < 0`:
  - quarantine with reason `NEGATIVE_QUANTITY_RESULT`
- If ABSOLUTE has `quantity < 0`:
  - quarantine with reason `NEGATIVE_ABSOLUTE_QUANTITY`

### Rule I4: ABSOLUTE vs DELTA conflict handling
When both types exist in the stream:
- Apply them strictly by the global ordering rules (G2).
- No special precedence beyond deterministic ordering.

(Reason: precedence rules can create non-obvious behavior; ordering + idempotency provides a predictable baseline for MVP.)

### Rule I5: Same-time inventory conflicts (equal orderKeyTime)
If two inventory events target the same scope and have equal `orderKeyTime`:
- prefer higher priority vendor (lower `vendorPriority`)
- if same vendorPriority, prefer lexicographically smaller `externalEventId`

### Rule I6: Stale updates (optional MVP rule)
If an event’s `orderKeyTime` is older than the state’s `lastUpdatedAt` by more than a configured threshold (e.g., 24h):
- write a `Divergence` record with reason `STALE_EVENT`
- still apply by ordering rules unless explicitly disabled

(Keep this enabled as "record-only" in MVP; do not block convergence.)

---

## 4. Order Status Conflict Rules

Order status conflicts occur when vendors report different statuses for the same canonical order.

### 4.1 Status progression model (MVP)
Define a canonical rank ordering:

1. `CREATED` (1)
2. `ACKED` (2)
3. `PICKED` (3)
4. `SHIPPED` (4)
5. `DELIVERED` (5)
6. `CANCELED` (special terminal)

### Rule O1: Apply by deterministic ordering
Apply OrderStatusUpdate events by global ordering rules (G2).

### Rule O2: Prevent invalid regressions (MVP rule)
Do not allow "regressions" in status rank, except to `CANCELED`.

Examples:
- `SHIPPED` → `PICKED` is rejected (quarantine: `STATUS_REGRESSION`)
- `DELIVERED` → `SHIPPED` is rejected (quarantine: `STATUS_REGRESSION`)
- Any status → `CANCELED` is allowed (but recorded as divergence if it conflicts with a later delivered state)

### Rule O3: Delivered is terminal (MVP rule)
If current status is `DELIVERED`, ignore any future non-cancel updates:
- write `Divergence` record with reason `POST_DELIVER_UPDATE_IGNORED`

### Rule O4: Same-time status conflicts (equal orderKeyTime)
If two status updates for the same order have equal `orderKeyTime`:
- prefer higher priority vendor
- if tie, prefer lexicographically smaller `externalEventId`

### Rule O5: Conflicting terminal states
If `CANCELED` and `DELIVERED` both appear:
- apply by ordering rules (G2)
- always write a `Divergence` record with reason `CONFLICTING_TERMINAL_STATES`

---

## 5. Versioning + Write Safety Rules (reconcile output)

### Rule W1: Conditional writes
All canonical state updates must use conditional write logic based on `version`:
- only update if `current.version == expected.version`
- increment `version` exactly once per successful apply

### Rule W2: lastAppliedEventId update
On successful apply, update:
- `lastAppliedEventId = externalEventId`
- `lastUpdatedAt = now (server time)`

### Rule W3: Concurrency retry policy (MVP)
If conditional write fails due to concurrent update:
- re-read current state
- re-apply deterministic rules
- retry up to a small limit (e.g., 3)

If still failing:
- quarantine with reason `RECONCILE_WRITE_CONTENTION`

---

## 6. Divergence Recording (non-blocking)

Divergence records are informational signals that something unusual happened. They must not block sync for other events.

Write a Divergence record when:
- status regression attempted (`STATUS_REGRESSION`)
- conflicting terminal states (`CONFLICTING_TERMINAL_STATES`)
- stale events observed (`STALE_EVENT`)
- ignored post-delivery updates (`POST_DELIVER_UPDATE_IGNORED`)

Each Divergence record must include:
- `vendorId`
- `externalEventId`
- `canonicalId` (product or order)
- `orderKeyTime`
- `receivedAt`
- reason code
- pointer to raw payload (S3 key) if available

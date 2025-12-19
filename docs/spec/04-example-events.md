# Example Events

This document contains concrete example payloads for vendor events entering the synchronization system. These examples serve as test vectors and documentation for expected behavior at ingest, normalization, matching, and reconciliation stages.

All timestamps are ISO-8601 UTC.

---

## 1. InventoryUpdate — ABSOLUTE (Valid)

```json
{
  "vendorId": "vnd_123",
  "externalEventId": "inv-000100",
  "receivedAt": "2025-12-19T21:10:00Z",
  "sourceTimestamp": "2025-12-19T21:09:40Z",
  "vendorProductKey": "SKU-ACME-001",
  "quantity": 120,
  "unit": "EACH",
  "semantics": "ABSOLUTE"
}
```

Expected behavior
- Accepted at ingest
- Normalized successfully
- Matched to a `canonicalProductId`
- Reconciled by replacing inventory quantity

## 2. InventoryUpdate — DELTA (Valid)
```json
{
  "vendorId": "vnd_123",
  "externalEventId": "inv-000101",
  "receivedAt": "2025-12-19T21:12:00Z",
  "vendorProductKey": "SKU-ACME-001",
  "quantity": -3,
  "unit": "EACH",
  "semantics": "DELTA",
  "metadata": {
    "reason": "damage_adjustment"
  }
}
```

Expected behavior
- Accepted and deduplicated by (`vendorId`, `externalEventId`)
- Quantity decremented by 3 during reconciliation
- New version written with updated quantity

## 3. InventoryUpdate — Duplicate Event (Idempotent No-Op)

```json
{
  "vendorId": "vnd_123",
  "externalEventId": "inv-000101",
  "receivedAt": "2025-12-19T21:13:30Z",
  "vendorProductKey": "SKU-ACME-001",
  "quantity": -3,
  "unit": "EACH",
  "semantics": "DELTA"
}
```

Expected behavior
- Detected as duplicate
- No new pipeline execution
- Counted in duplicate metrics

## 4. InventoryUpdate — Invalid (Negative ABSOLUTE)

```json
{
  "vendorId": "vnd_123",
  "externalEventId": "inv-000102",
  "receivedAt": "2025-12-19T21:14:00Z",
  "vendorProductKey": "SKU-ACME-001",
  "quantity": -10,
  "unit": "EACH",
  "semantics": "ABSOLUTE"
}
```

Expected behavior
- Accepted at ingest
- Quarantined during normalization or reconciliation
- Reason: `NEGATIVE_ABSOLUTE_QUANTITY`

## 5. InventoryUpdate — Unit Mismatch (Quarantine)

```json
{
  "vendorId": "vnd_123",
  "externalEventId": "inv-000103",
  "receivedAt": "2025-12-19T21:15:00Z",
  "vendorProductKey": "SKU-ACME-001",
  "quantity": 5,
  "unit": "BOX",
  "semantics": "DELTA"
}
```

Expected behavior
- Accepted at ingest
- Quarantined during reconciliation
- Reason: `UNIT_MISMATCH`

## 6. OrderStatusUpdate — Valid Progression

```json
{
  "vendorId": "vnd_987",
  "externalEventId": "ord-evt-2001",
  "receivedAt": "2025-12-19T21:20:00Z",
  "sourceTimestamp": "2025-12-19T21:19:50Z",
  "vendorOrderKey": "VEND-ORDER-555",
  "status": "PICKED"
}
```

Expected behavior
- Accepted and matched to a `canonicalOrderId`
- Status updated to `PICKED`
- Version incremented

## 7. OrderStatusUpdate — Status Regression (Quarantine)

```json 
{
  "vendorId": "vnd_987",
  "externalEventId": "ord-evt-2002",
  "receivedAt": "2025-12-19T21:21:00Z",
  "vendorOrderKey": "VEND-ORDER-555",
  "status": "CREATED"
}
```

Expected behavior
- Accepted at ingest
- Quarantined during reconciliation
- Reason: `STATUS_REGRESSION`

## 8. OrderStatusUpdate — Terminal Conflict (Divergence)

```json
{
  "vendorId": "vnd_987",
  "externalEventId": "ord-evt-2003",
  "receivedAt": "2025-12-19T21:22:00Z",
  "vendorOrderKey": "VEND-ORDER-555",
  "status": "CANCELED"
}
```

Assume
- Order already reconciled as `DELIVERED`

Expected behavior
- Event applied or ignored based on ordering rules
- Divergence record written
- Reason: `CONFLICTING_TERMINAL_STATES`

## 9. OrderStatusUpdate — Missing Required Field (Fast Reject)

```json
{
  "vendorId": "vnd_987",
  "receivedAt": "2025-12-19T21:23:00Z",
  "vendorOrderKey": "VEND-ORDER-999",
  "status": "CREATED"
}
```

Expected behavior
- Rejected at ingest (HTTP 4xx)
- Reason: missing `externalEventId`

## 10. InventoryUpdate — Unmatched Product (Quarantine)

```json
{
  "vendorId": "vnd_555",
  "externalEventId": "inv-009001",
  "receivedAt": "2025-12-19T21:25:00Z",
  "vendorProductKey": "UNKNOWN-SKU",
  "quantity": 10,
  "unit": "EACH",
  "semantics": "ABSOLUTE"
}
```

Expected behavior
- Accepted at ingest
- Fails Match stage
- Quarantined with reason `UNMATCHED_IDENTIFIER`

## Notes
- All examples assume vendorId is derived from authentication, not trusted from payload.
- Quarantined events must include pointers to raw payloads stored in S3.
- Divergence events must never block ingestion or reconciliation of other events.
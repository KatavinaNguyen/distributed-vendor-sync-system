# Canonical Model

This document defines the canonical (internal) data model for the Distributed Vendor Order & Inventory Synchronization System. Canonical models represent the system’s stable "shared language" across all vendors. Vendor-specific payloads are transformed into these models during the pipeline.

## Objectives

- Provide a foundational set of entities/events to support order + inventory synchronization across multiple vendors.
- Ensure identifiers and fields are stable, deterministic, and replay-safe.
- Keep the model small enough to implement quickly, while leaving room for extension.

## Naming + Types

- Field naming: `camelCase`
- Timestamps:
  - `receivedAt` is always server-generated UTC (ISO-8601 string).
  - `sourceTimestamp` is vendor-provided UTC if available (ISO-8601 string).
- IDs:
  - Canonical IDs are internal (e.g., UUID). Vendor identifiers are always preserved separately.

---

## Entity: Vendor

Represents a single external data source (manufacturer, supplier, 3PL, marketplace, etc.).

**Fields**
- `vendorId` (string, required): internal ID for the vendor (derived from auth / API key in the ingest layer).
- `vendorName` (string, optional)
- `status` (VendorStatus, required)
- `createdAt` (string ISO-8601 UTC, required)
- `priority` (number, optional): lower number = higher priority (used for conflict tie-breakers)

**VendorStatus (enum)**
- `ACTIVE`
- `PAUSED`

**Example**
```json
{
  "vendorId": "vnd_123",
  "vendorName": "Acme Supplies",
  "status": "ACTIVE",
  "createdAt": "2025-12-19T21:00:00Z",
  "priority": 10
}
```

---

## Entity: CanonicalProduct

Represents the internal, canonical identity for a product. Vendors may reference the same real-world product using different SKUs/identifiers.

**Fields**
- `canonicalProductId` (string, required): internal stable identifier (UUID recommended).
- `displayName` (string, optional)
- `attributes` (object map, optional): freeform metadata (e.g., size, color, category)

**Example**
```json
{
  "canonicalProductId": "prd_8f5d2b1a",
  "displayName": "Widget A",
  "attributes": { "category": "widgets" }
}
```

---

## Entity: CanonicalOrder

Represents the internal, canonical identity for an order. Vendors may supply different identifiers for the same order (or partial order views).

**Fields**
- `canonicalOrderId` (string, required): internal stable identifier (UUID recommended).
- `status` (OrderStatus, required)
- `updatedAt` (string ISO-8601 UTC, required)

**OrderStatus (enum)**
- `CREATED`
- `ACKED`
- `PICKED`
- `SHIPPED`
- `DELIVERED`
- `CANCELED`

**Example**
```json
{
  "canonicalOrderId": "ord_14c2b9aa",
  "status": "SHIPPED",
  "updatedAt": "2025-12-19T21:05:00Z"
}
```

---

## Event: InventoryUpdate

A vendor-originated event describing a change to inventory. This is an event, not stored state.

**Required Fields**
- `vendorId` (string): internal vendor identifier (derived from auth; not trusted from vendor payload)
- `externalEventId` (string): vendor-provided stable idempotency key for dedupe/replay
- `receivedAt` (string ISO-8601 UTC): server timestamp when received
- `vendorProductKey` (string): vendor’s product identifier (SKU, item code, etc.)
- `quantity` (number): quantity value
- `unit` (InventoryUnit): unit of measure
- `semantics` (InventorySemantics): whether `quantity` is absolute or delta

**Optional Fields**
- `sourceTimestamp` (string ISO-8601 UTC): vendor timestamp if provided
- `locationKey` (string): optional vendor location identifier (warehouse/store)
- `metadata` (object map): optional extra vendor fields carried through

**InventoryUnit (enum)**
- `EACH`

**InventorySemantics (enum)**
- `ABSOLUTE` (quantity is the current total)
- `DELTA` (quantity is a change to apply)

**Example: ABSOLUTE**
```json
{
  "vendorId": "vnd_123",
  "externalEventId": "inv-000045",
  "receivedAt": "2025-12-19T21:10:00Z",
  "sourceTimestamp": "2025-12-19T21:09:40Z",
  "vendorProductKey": "SKU-ACME-001",
  "quantity": 120,
  "unit": "EACH",
  "semantics": "ABSOLUTE"
}
```

**Example: DELTA**
```json 
{
  "vendorId": "vnd_123",
  "externalEventId": "inv-000046",
  "receivedAt": "2025-12-19T21:12:00Z",
  "vendorProductKey": "SKU-ACME-001",
  "quantity": -3,
  "unit": "EACH",
  "semantics": "DELTA",
  "metadata": { "reason": "damage_adjustment" }
}
```

---

## Event: OrderStatusUpdate

A vendor-originated event describing an order status transition (or current status snapshot).

**Required Fields**
- `vendorId` (string): internal vendor identifier (derived from auth; not trusted from vendor payload)
- `externalEventId` (string): vendor-provided stable idempotency key for dedupe/replay
- `receivedAt` (string ISO-8601 UTC): server timestamp when received
- `vendorOrderKey` (string): vendor’s order identifier
- `status` (OrderStatus): canonical status value

**Optional Fields**
- `sourceTimestamp` (string ISO-8601 UTC): vendor timestamp if provided
- `metadata` (object map): optional extra vendor fields carried through

**Example**
```json
{
  "vendorId": "vnd_987",
  "externalEventId": "ord-evt-10391",
  "receivedAt": "2025-12-19T21:15:00Z",
  "sourceTimestamp": "2025-12-19T21:14:58Z",
  "vendorOrderKey": "VEND-ORDER-555",
  "status": "PICKED"
}
```

---

## Canonical Stored State Shapes

These are the reconciled state records persisted by the system (not vendor events).

### State: ProductInventoryState

**Fields**
- `canonicalProductId` (string, required)
- `quantity` (number, required)
- `unit` (InventoryUnit, required)
- `version` (number, required): monotonic version for conditional updates
- `lastUpdatedAt` (string ISO-8601 UTC, required)
- `lastAppliedEventId` (string, required): the most recent event that modified this record

**Example**
```json
{
  "canonicalProductId": "prd_8f5d2b1a",
  "quantity": 117,
  "unit": "EACH",
  "version": 42,
  "lastUpdatedAt": "2025-12-19T21:12:02Z",
  "lastAppliedEventId": "inv-000046"
}
```

### State: OrderState
**Fields**
- `canonicalOrderId` (string, required)
- `status` (OrderStatus, required)
- `version` (number, required)
- `lastUpdatedAt` (string ISO-8601 UTC, required)
- `lastAppliedEventId` (string, required)

**Example**
```json
{
  "canonicalOrderId": "ord_14c2b9aa",
  "status": "PICKED",
  "version": 9,
  "lastUpdatedAt": "2025-12-19T21:15:01Z",
  "lastAppliedEventId": "ord-evt-10391"
}
```

````md
## Authentication

Vendors authenticate to ingestion endpoints using an API key provided in an HTTP header.

### Header

- Required header: `X-Api-Key: <api_key>`
- Required content type: `Content-Type: application/json`

Example:
```http
POST /inventory-updates HTTP/1.1
Host: api.example.com
Content-Type: application/json
X-Api-Key: kV5m1oH8x2...REDACTED
````

### API Key Semantics

* API keys are **opaque secrets** (random strings). They must not encode `vendorId` or any vendor metadata.
* Each API key maps to exactly one internal `vendorId`.
* The server derives `vendorId` from the API key. Vendor identity is **never** accepted from the request body.

### vendorId in Request Body

* If a request body contains `vendorId`, the request is rejected with `400 Bad Request`.
* Reason: prevent spoofing/misrouting and keep vendor identity server-controlled.

### Key Lifecycle (MVP)

* Keys have two states:

  * `ACTIVE`
  * `REVOKED`
* Rotation is supported by allowing multiple ACTIVE keys per vendor (optional for MVP).
* Revoked keys must fail authentication immediately.

### Authentication Failures

All authentication failures return `401 Unauthorized` with the standard error body.

Error codes:

* `MISSING_API_KEY` — `X-Api-Key` header not present
* `INVALID_API_KEY` — key not recognized or revoked

Error body shape:

```json
{
  "error": "INVALID_API_KEY",
  "message": "API key is invalid or revoked.",
  "requestId": "req_123456"
}
```

### Logging Requirements

* Never log the raw API key.
* Logs may include a truncated fingerprint (e.g., last 4 chars) if needed for debugging.

````md
## Idempotency & Deduplication

To make ingestion replay-safe and resistant to vendor retries, the system enforces idempotency using a vendor-provided event identifier.

### externalEventId (Required)

Every request to ingestion endpoints must include:

- `externalEventId` (string, required): a stable identifier for the event provided by the vendor.

The system uses the composite deduplication key:

- `(vendorId, externalEventId)`

Where `vendorId` is derived from the API key (not from request body).

### Duplicate Behavior

If a request arrives with a deduplication key that has already been accepted:

- The API returns `202 Accepted`
- The API returns the **original** `ingestId`
- The system does **not** enqueue downstream pipeline stages again for the duplicate event

This ensures vendor retries are safe and do not double-apply updates.

### Event ID Reuse (Same ID, Different Payload)

Vendors must not reuse the same `externalEventId` for different payload content.

If the same `(vendorId, externalEventId)` is received with different payload content:

- The request is accepted as received (202) for durability, but
- The event is quarantined during processing with reason `EVENT_ID_REUSE_DIFFERENT_PAYLOAD`
- A divergence/alert metric is emitted

### ingestId (Tracking Handle)

On first acceptance of a unique event, the server generates:

- `ingestId` (string, server-generated)

`ingestId` is used to track an event across:
- raw payload storage (S3)
- ingest record (DynamoDB)
- stage events (EventBridge)
- quarantine/divergence records

---

## Endpoints

All ingestion endpoints are asynchronous: `202 Accepted` means the event was durably accepted for processing, not that it has been reconciled.

### POST /inventory-updates

Accepts a vendor inventory update event.

**Headers**
- `Content-Type: application/json`
- `X-Api-Key: <api_key>`

**Request Body**
- Must conform to `schemas/inventory_update.schema.json`

**Response (First Acceptance)**
- Status: `202 Accepted`
- Body:
```json
{
  "status": "ACCEPTED",
  "ingestId": "ing_01HXYZ..."
}
````

**Response (Duplicate)**

* Status: `202 Accepted`
* Body (same ingestId as first acceptance):

```json
{
  "status": "DUPLICATE",
  "ingestId": "ing_01HXYZ..."
}
```

**Example Request**

```http
POST /inventory-updates HTTP/1.1
Content-Type: application/json
X-Api-Key: kV5m1oH8x2...REDACTED

{
  "externalEventId": "inv-000100",
  "receivedAt": "2025-12-19T21:10:00Z",
  "vendorProductKey": "SKU-ACME-001",
  "quantity": 120,
  "unit": "EACH",
  "semantics": "ABSOLUTE"
}
```

---

### POST /order-status-updates

Accepts a vendor order status update event.

**Headers**

* `Content-Type: application/json`
* `X-Api-Key: <api_key>`

**Request Body**

* Must conform to `schemas/order_status_update.schema.json`

**Response (First Acceptance)**

* Status: `202 Accepted`
* Body:

```json
{
  "status": "ACCEPTED",
  "ingestId": "ing_01HABC..."
}
```

**Response (Duplicate)**

* Status: `202 Accepted`
* Body:

```json
{
  "status": "DUPLICATE",
  "ingestId": "ing_01HABC..."
}
```

**Example Request**

```http
POST /order-status-updates HTTP/1.1
Content-Type: application/json
X-Api-Key: kV5m1oH8x2...REDACTED

{
  "externalEventId": "ord-evt-2001",
  "receivedAt": "2025-12-19T21:20:00Z",
  "vendorOrderKey": "VEND-ORDER-555",
  "status": "PICKED"
}
```

---

## Status Codes & Error Handling

### Success

* `202 Accepted`

  * Event is durably accepted for processing.
  * Returned for both first-time acceptance and duplicates.

### Client Errors (Fast Reject)

These errors indicate the request was **not** accepted (no ingest record created).

* `400 Bad Request`

  * Invalid JSON
  * Missing required fields (e.g., `externalEventId`)
  * Body includes `vendorId` (not allowed)
  * Schema validation failure

* `401 Unauthorized`

  * Missing or invalid API key

* `413 Payload Too Large`

  * Request exceeds maximum allowed size

* `415 Unsupported Media Type`

  * Missing/incorrect `Content-Type` (must be `application/json`)

### Standard Error Body

All 4xx responses return:

```json
{
  "error": "SOME_CODE",
  "message": "Human readable explanation.",
  "requestId": "req_123456"
}
```

### Error Codes

* `MISSING_API_KEY`
* `INVALID_API_KEY`
* `INVALID_JSON`
* `SCHEMA_VALIDATION_FAILED`
* `MISSING_REQUIRED_FIELD`
* `VENDOR_ID_NOT_ALLOWED`
* `PAYLOAD_TOO_LARGE`
* `UNSUPPORTED_MEDIA_TYPE`

### Notes

* `202 Accepted` does not guarantee the event will reconcile successfully.
* Events may later be quarantined due to mapping/match/reconcile rules without changing the ingest response.

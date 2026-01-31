# DVSS Infrastructure Overview

# DVSS Terraform

Provisions the DynamoDB idempotency table used by DVSS.

## Deploy
```
terraform init
terraform apply
```

## Destroy
```
terraform destroy
```

## Infrastructure Responsibilities

Infrastructure is intentionally designed to be **modular and incrementally defined**, with components introduced in an order that directly enforces the system’s strongest guarantees (durability, idempotency, and isolation). This approach ensures the architecture evolves without compromising correctness or requiring large refactors.

---

## Core Infrastructure Components

### API Gateway

* Serves as the external ingress point for vendor events
* Enforces request size limits and authentication boundaries
* Provides a stable contract for vendor integrations

### Lambda Functions

* **Ingest Lambda**

  * Validates inbound event envelopes
  * Enforces idempotency checks
  * Persists raw payloads durably
* **Normalize Lambda**

  * Performs schema-aware validation and transformation
  * Emits standardized, versioned events for downstream consumption

### DynamoDB

* Maintains the idempotency index keyed by `(vendorId, externalEventId)`
* Conditional writes enforce *at-most-once* semantics at the storage layer
* Supports safe retries and replay without duplicate logical events

### Amazon S3

* Durable, write-once storage for raw vendor payloads
* Separate storage for normalized outputs
* Enables auditability, replay, and historical backfill

### EventBridge

* Decouples ingestion from downstream processing
* Enables asynchronous fan-out, retries, and failure isolation
* Prevents downstream failures from impacting ingest availability

### Dead Letter Queues (DLQ)

* Capture normalization failures and poison-pill events
* Enable targeted investigation and controlled replay workflows

---

## Infrastructure Definition Strategy

Infrastructure is defined using **infrastructure-as-code** and added incrementally, starting with components that directly enforce DVSS’s core guarantees (e.g., idempotency and durability).

This strategy ensures that:

* Guarantees are enforced at the platform level, not solely in application logic
* Infrastructure closely reflects implemented behavior
* The system remains evolvable as operational requirements grow

> **Current IaC coverage:**
> The DynamoDB idempotency index is provisioned via Terraform in this repository. Additional infrastructure components are documented here and introduced incrementally as the system matures.

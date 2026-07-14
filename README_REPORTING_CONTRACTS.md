# Reporting & BI Module — API & Event Contracts

Retro-documented contract-first artifacts for the Reporting & BI module (merged in
**PR #61**, branch `feat/reporting-bi`). Written *after* the code to close the
contract gap — the spec describes the endpoints as merged. Companion:
`REPORTING_DELTA_0_4_RETRO.md`.

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `contracts/reporting-api_openapi.yaml` | OpenAPI 3.0.3 for the Reporting API (summary / orders / exports). | `openapi-spec-validator` — PASS |

**No event schema.** reporting-service **publishes no backbone events** — it is a
read-side projection (consumer only). Verified: no `KafkaTemplate`, no outbox table,
no `@Scheduled` relay in `src/main`.

## API conventions (carried from the platform)

- **Tenant from token** — tenant context comes from the verified bearer-token claim (paved-road `TenantContextHolder`); there is no tenant path/query parameter.
- **Errors** — `application/problem+json` (RFC 9457) with a stable machine-readable `code`.
- **Read-side isolation** — no synchronous module calls, no foreign datasource. Enforced in CI by `tools/fitness/check_reporting_isolation.py`.

## Typed error codes

| Code | HTTP | When |
|------|------|------|
| `EXPORT_NOT_FOUND` | 404 | Export id not visible to the tenant. |

All other failures are framework defaults (400 validation, 500).

## Events

**Consumed** (idempotent on the order/payments event `eventId`, enforced by the
`seen_events` table, PK `event_id`):

| Event | Topic | Effect |
|-------|-------|--------|
| `OrderCreated` | `pss-order-events` | Seeds `order_fact` (currency/pax/ancillary footprint). |
| `OrderConfirmed` | `pss-order-events` | Confirms the order fact + daily summary counters. |
| `OrderCancelled` | `pss-order-events` | Cancels the order fact + adjusts counters. |
| `RefundIssued` | `pss-payments-events` | Adds to the daily `refunds` figure. |

**Published:** none.

## Deviations from platform conventions

Documented as carried open items (the code is authoritative; these are recorded, not fixed):

1. **`Idempotency-Key` required but not honoured** on `POST /v1/reporting/exports` — a retry creates a new export row (the handler reads the header but ignores it).
2. **`order_fact.origin` / `destination` / `channel` are always null** — no correlatable event currently carries them (FLAG-006). The OpenAPI marks them nullable.
3. **Export `from`/`to` are accepted but not persisted** on the export row (no columns); they parameterise the synchronous generation only.
4. **Export is generated synchronously in-request** but modelled as async (returns `PENDING`, then generates before the response). Real blob storage is deferred behind `ReportStoragePort`.
5. **`ReportStoragePort` has no conformance suite** — exercised only indirectly by `ReportingIT`. Open item.

## Regenerating / validating

```bash
pip install openapi-spec-validator pyyaml
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('contracts/reporting-api_openapi.yaml')))"
```

*Retro-documentation — companion to REPORTING_DELTA_0_4_RETRO.md.*

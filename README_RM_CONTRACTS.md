# Revenue Management Module — API & Event Contracts

Retro-documented contract-first artifacts for the Revenue Management (RM) module
(merged in **PR #64**, branch `feat/revenue-management`). Written *after* the code to
close the contract gap. Companion: `RM_DELTA_0_6_RETRO.md`.

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `contracts/rm-api_openapi.yaml` | OpenAPI 3.0.3 for the RM API (flight-class config / AU compute / recommendations). | `openapi-spec-validator` — PASS |

**No event schema.** rm-service **publishes no backbone events** — verified: no
`KafkaTemplate`, no outbox table, no `@Scheduled` relay. The Inventory feed is a
synchronous HTTP call (`AdjustInventory`), **not** a published event.

## API conventions (carried from the platform)

- **Tenant from token** — verified bearer-token claim (`TenantContextHolder`); no tenant path/query parameter.
- **Errors** — `application/problem+json` (RFC 9457) with a stable `code`.

## Typed error codes

| Code | HTTP | When |
|------|------|------|
| `FLIGHT_CLASS_CONFIG_NOT_FOUND` | 404 | Config id not visible to the tenant. |
| `RM_ADMIN_REQUIRED` | 403 | `X-Rm-Admin` not present/true. |

## Events

**Consumed** (idempotent on `eventId` via `seen_events`):

| Event | Topic | Effect |
|-------|-------|--------|
| `OrderConfirmed` | `pss-order-events` | Reactive AU-compute trigger. See deviation #6 — dormant in practice. |

**Published:** none.

**Downstream write (not an event):** `computeAndApply` issues
`POST {inventory}/v1/inventory/flight-classes/{flightClassConfigId}/adjust-au` with body
`{ tenantId, auValue, reason }`. One-directional; RM never reads Inventory counts.

## Deviations from platform conventions

Documented as carried open items (code is authoritative):

1. **`X-Rm-Admin` admin gate** is an app-level header check (must equal `true`), not a security scope/filter (demo posture).
2. **`Idempotency-Key` required but not honoured** on both POSTs. `register` is idempotent on the natural key regardless; **`compute` is NOT idempotent** — each call appends a recommendation and re-calls Inventory.
3. **Outbound `AdjustInventory` carries no `Idempotency-Key` and no retry** — any error is swallowed to a `PENDING` adjustment row.
4. **Outbound auth is an unsigned `alg:none` demo JWT**; `tenantId` is duplicated in both the bearer and the request body.
5. **Success-status inconsistency** — `register` returns 201, `compute` returns 200.
6. **Reactive path effectively dormant** — real `OrderConfirmed` carries no flight key, so the event-driven compute no-ops in production; the headline path is the manual `compute` endpoint. The booking snapshot is hardcoded to 0 this slice, so compute returns the conservative zero-history AU.

## ⚠️ Finding (see RM_DELTA_0_6_RETRO.md §Findings)

**The target Inventory endpoint does not exist in `inventory-service`.**
`inventory-service` serves only `/v1/holds`, `/v1/seats`, `/v1/availability` — there is
no `/v1/inventory/flight-classes/{id}/adjust-au` mount and no `adjust-au`/`AdjustInventory`
symbol anywhere in its source. The path lives **only** in rm-service's own client + its
WireMock stub, so the RM→Inventory feed would 404 against the real service. Recorded as a
Finding; **not fixed** (out of scope — no `src/main` changes).

## Regenerating / validating

```bash
pip install openapi-spec-validator pyyaml
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('contracts/rm-api_openapi.yaml')))"
```

*Retro-documentation — companion to RM_DELTA_0_6_RETRO.md.*

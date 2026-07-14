# Personalisation / CDP Module — API & Event Contracts

Retro-documented contract-first artifacts for the Personalisation / CDP module
(merged in **PR #65**, branch `feat/personalisation-cdp`). Written *after* the code to
close the contract gap. Companion: `CDP_DELTA_0_7_RETRO.md`.

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `contracts/cdp-api_openapi.yaml` | OpenAPI 3.0.3 for the CDP API (consent / profile / rank-offers). | `openapi-spec-validator` — PASS |

**No event schema.** cdp-service **publishes no backbone events** — verified: no
`KafkaTemplate`, no outbox table, no `@Scheduled` relay. The profile is built
exclusively from consumed events (enforced by `tools/fitness/check_cdp_isolation.py`).

## API conventions (carried from the platform)

- **Tenant from token** — verified bearer-token claim (`TenantContextHolder`); no tenant path/query parameter.
- **Errors** — `application/problem+json` (RFC 9457) with a stable `code`.
- **Event-sourced isolation** — no synchronous module calls, no module-store reads. Enforced by `check_cdp_isolation.py`.

## Typed error codes

| Code | HTTP | When |
|------|------|------|
| `PROFILE_NOT_FOUND` | 404 | No profile for the `externalRef`. |
| `CDP_ADMIN_REQUIRED` | 403 | `X-Cdp-Admin` not present/true. |

## Events

**Consumed** (idempotent on `eventId` via `profile_events_seen`):

| Event | Topic | Effect |
|-------|-------|--------|
| `OrderConfirmed` | `pss-order-events` | Creates/updates the profile (the only profile-creating event). |
| `OrderCancelled` | `pss-order-events` | Updates booking/spend counters. |
| `MilesAccrued` | `pss-loyalty-events` | Updates loyalty footprint. |
| `TierChanged` | `pss-loyalty-events` | Updates `loyaltyTier`. |
| `PassengerCheckedIn` | `pss-dcs-events` | Updates travel footprint. |

**Published:** none.

## Deviations from platform conventions

Documented as carried open items (code is authoritative):

1. **`X-Cdp-Admin` admin gate** is an app-level header check, not a security scope/filter (demo posture; permit-all in non-prod).
2. **`Idempotency-Key` required but not honoured** on `POST .../consent` — consent is a natural-key upsert, so retries are already safe; `POST /v1/cdp/rank-offers` has no `Idempotency-Key`.
3. **Consent denial is not an error** — `rank-offers` returns a neutral `200` (empty/unpersonalised ranking) when the customer withheld PERSONALISATION consent or has no profile. There is no consent-denied error code.
4. **Offer-service → CDP wiring is deferred (FLAG-007)** — `POST /v1/cdp/rank-offers` is ready and waiting; the `PersonalisationPort` bean-swap in offer-service is not config-only, so it is a separate scoped PR. Direction is offer→cdp (cdp calls nobody).

## Regenerating / validating

```bash
pip install openapi-spec-validator pyyaml
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('contracts/cdp-api_openapi.yaml')))"
```

*Retro-documentation — companion to CDP_DELTA_0_7_RETRO.md.*

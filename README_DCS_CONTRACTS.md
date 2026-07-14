# DCS Module — API & Event Contracts

Contract-first artifacts for the Delivery/DCS module of the Aurionpro PSS platform.
These realise **DCS_DELTA_0_3_CHECKIN_BOARDING.md** — `dcs-api_openapi.yaml` covers
the delta §4 (API) and `dcs-events_schema.json` covers the delta §3 (domain events).
Drop this folder into the service repository / Claude Code project context so generated
code stays aligned to the contract.

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `dcs-api_openapi.yaml` | OpenAPI 3.0.3 spec for the DCS API (OpenFlight / CheckIn / AssignSeat / IssueBoardingPass / AcceptBag / BoardPassenger / CloseFlight / GetDeparture). | `openapi-spec-validator` — PASS |
| `dcs-events_schema.json` | JSON Schema (draft 2020-12) for the five published domain events. | `jsonschema` Draft202012 `check_schema` + 8 instance tests — PASS |

## API conventions (carried from the platform)

- **Tenant from token** — tenant context comes from a verified claim in the OAuth2/OIDC bearer token; there is no tenant path/query parameter.
- **Idempotency** — every state-changing call requires an `Idempotency-Key` header; a repeated key returns the original result rather than acting twice.
- **Optimistic concurrency** — `OpenBoarding` and `CloseFlight` require the current ETag via `If-Match`; a stale version returns `409`.
- **Errors** — `application/problem+json` (RFC 9457) with a stable machine-readable `code`.
- **Highest availability tier** — check-in/DCS is a flight-grounding path (SDD §11, ≥99.95%); strong consistency for seat and boarding state.

## Typed error codes

| Code | HTTP | When |
|------|------|------|
| `ORDER_NOT_CHECKINABLE` | 422 | Order not CONFIRMED, segment mismatch, or already flown. |
| `FLIGHT_NOT_OPEN` | 422 | Departure not in OPEN_FOR_CHECKIN (for check-in) or BOARDING (for board). |
| `GOVDATA_REFUSED` | 422 | GovDataPort (stubbed) returned REFUSED for this pax/segment. |
| `SEAT_TAKEN` | 409 | The core DCS invariant: at most one live assignment per (departureId, seatNumber). |
| `SEAT_NOT_ON_MAP` | 422 | Requested seat number is not in the cabin map. |
| `SEAT_ASSIGNMENT_REQUIRED` | 422 | Boarding pass requested but no seat assigned yet. |
| `BOARDING_PASS_REQUIRED` | 422 | Board requested but no boarding pass issued yet. |
| `WB_NOT_READY` | 422 | WeightBalancePort (stubbed) returned NOT_READY — close hard-blocked. |
| `FLIGHT_ALREADY_CLOSED` | 409 | State-machine transition not possible; flight already CLOSED. |
| `FLIGHT_REF_NOT_FOUND` | 422 | Flight reference not found in Schedule module. |

## Events

Five events, each a self-contained object discriminated by the `type` const. Consumers
MUST be idempotent on `eventId`. All carry `tenantId`, `departureId`, `occurredAt`,
`correlationId`, `causationId` in the envelope.

| Event | Published by | Order LLD §6.3 effect |
|-------|-------------|----------------------|
| `PassengerCheckedIn` | CheckIn | ServiceDelivery stays PENDING; order may move toward IN_DELIVERY. |
| `BoardingPassIssued` | IssueBoardingPass | Informational. |
| `BagAccepted` | AcceptBag | Informational. |
| `PassengerBoarded` | BoardPassenger | Air ServiceDelivery line moves toward DELIVERED. |
| `FlightClosed` | CloseFlight (once) | **G4 headline:** all delivery lines for this flight resolve; order → FULFILLED. |

## Things the build must respect (encoded in the specs, worth restating)

1. **Seat-ownership split is law (Inventory LLD §2.2).** DCS assigns the physical seat; Inventory counts seats. DCS never calls any Inventory write API. The Tranche 1 paid seat claim is a *preference input* to `AssignSeat` (source: `PAID_CLAIM`), not the assignment itself.
2. **The core DCS invariant is never weakened.** `SEAT_TAKEN` (409) is the enforcement; at most one live `SeatAssignment` per `(departureId, seatNumber)` under any concurrency. This is the G2 gate.
3. **W&B and gov-data are stubs in this tranche.** Both ports exist as interfaces with simulators and conformance suites (G1 gate). No real W&B vendor symbol appears outside `WeightBalanceAdapter`; no PII leaves the platform.
4. **FlightClosed is published exactly once.** The transactional outbox ensures this; subsequent CloseFlight calls return the existing CLOSED departure idempotently.
5. **No Order or Inventory write calls.** DCS is a downstream consumer of Order (read) and Inventory (read occupancy only). Any code that calls an Order or Inventory write API is a stop-and-flag.
6. **`dcs-events_schema.json` schema CI check** gains all five event types with fixture payloads at the G4 gate (same pattern as the order-events schema CI check at B4/D4).

## G0/G1 gate contract

These files are the G0 + G1 artefacts:
- **G0 gate** — `OpenFlight` against the BLR→BOM demo flight seeds a `FlightDeparture`; the state-machine unit tests and outbox smoke use the `FlightDeparture` schema from this spec.
- **G1 gate** — both conformance suites (WeightBalance, GovData) reference the typed error codes (`WB_NOT_READY`, `GOVDATA_REFUSED`) defined in this spec.

## Regenerating / validating

```bash
pip install --break-system-packages openapi-spec-validator jsonschema
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('dcs-api_openapi.yaml')))"
python -c "import json; from jsonschema.validators import Draft202012Validator as D; D.check_schema(json.load(open('dcs-events_schema.json')))"
```

*Working draft — companion to DCS_DELTA_0_3_CHECKIN_BOARDING.md and BUILD_PLAN_DCS_TRANCHE.md.*

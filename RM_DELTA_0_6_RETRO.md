# Revenue Management — Retro Design Delta 0.6

> **Nature:** *Retro-documentation of merged code* (**PR #64**, branch
> `feat/revenue-management`, tag `v0.3.6-revenue-management-thin`). A record of what was
> built, not a ratified-before-build design. The merged code is authoritative. Contracts:
> `contracts/rm-api_openapi.yaml`, `README_RM_CONTRACTS.md`.

## Scope & non-goals

**In scope (as built):** an Offer/Retailing-layer RM seam — register flight-class configs,
compute a class **authorisation unit (AU)** from pure forecast + optimisation engines, and
push the AU to Inventory one-directionally via an `AdjustInventory` HTTP call; plus
recommendation history.

**Non-goals:** no real ML (deterministic stub engines behind ports); RM never holds
inventory counts; no backbone event publishing; no bid-price / continuous-nesting; no
demand unconstraining beyond the stub.

## Module posture

**Offer/Retailing-layer module.** Package `pss.rm`, **port 8089**. Owns forecasts + AU
recommendations; feeds Inventory one-directionally. Optimisation/forecast are pure functions
behind ports (real ML deferred). Publishes no events.

## Entities (V1__rm.sql — all VARCHAR/TEXT, no jsonb)

- `flight_class_config` — unique natural key `(tenant, flightNumber, departureDate, cabin, rbd)`.
- `au_recommendations` — append history; `applied` flipped in place.
- `au_adjustments` — append-only, one row per `AdjustInventory` outcome (`ACCEPTED`/`REJECTED`/`PENDING`).
- `seen_events` — dedupe ledger.

## Event flows

`RmEventConsumer` (group `pss-rm`) on `pss-order-events`, handling only `OrderConfirmed`;
idempotent on `eventId` via `seen_events`. **Dormant in practice** — the real
`OrderConfirmed` carries no flight key, so the reactive compute no-ops; the headline path is
the manual `compute` endpoint.

## Ports & deferral lines

- `ForecastPort` → `StubForecastEngine`; `OptimisationPort` → `StubOptimisationEngine` — deterministic rule-based stubs; real ML deferred. Both pinned by **abstract conformance suites** (`ForecastPortConformanceSuite`, `OptimisationPortConformanceSuite`).

## API summary

5 endpoints under `/v1/rm/*` — see `contracts/rm-api_openapi.yaml`. Typed codes:
`FLIGHT_CLASS_CONFIG_NOT_FOUND` (404), `RM_ADMIN_REQUIRED` (403).

## Decisions made de facto

1. **One-directional Inventory feed** — RM fires `AdjustInventory` and records the outcome; it never reads inventory counts (owns AUs, not counts).
2. **Pure engines behind ports** — real ML is deferred without changing the seam; conformance suites pin engine behaviour.
3. **Manual `compute` is the headline path**; the reactive `OrderConfirmed` path is retained but dormant (no flight key in real events).
4. **Zero-history conservative AU** — the booking snapshot is hardcoded to 0 this slice, so `compute` returns the conservative AU (e.g. 89 for cap 100 / ob 1.05 / lf 0.85).
5. **Admin gate + idempotency deferred** — `X-Rm-Admin` header gate; `Idempotency-Key` required but not honoured.

## Findings

- **⚠️ The target Inventory endpoint does not exist.** `InventoryServiceClient.adjustAu`
  posts to `/v1/inventory/flight-classes/{flightClassConfigId}/adjust-au`, but
  `inventory-service` serves only `/v1/holds`, `/v1/seats`, `/v1/availability` — no such
  mount and no `adjust-au`/`AdjustInventory` symbol anywhere in its source. The path exists
  **only** in rm-service's client + its WireMock stub, so the feed would 404 against the real
  service. This is the RM↔Inventory integration being **half-built** (RM side only). It is a
  cross-module **write** attempt (flagged per the standing rule) *and* targets an unbuilt
  endpoint. **Not fixed** — out of scope (no `src/main` changes). Recommend a follow-up to
  either build the Inventory `adjust-au` endpoint or re-point RM at a real Inventory API.
  - **Session-23 update:** investigated both sides to close this. The AU concept maps cleanly
    (`BookingClassBucket.allocation` is literally *"the AU… RM sets it later"*), but RM and
    Inventory **share no flight-class identity** — RM mints its own `flightClassConfigId` ULID,
    inventory keys by its own `flightInventoryId`, and the only common coordinates
    `(tenant, flightNumber, departureDate, rbd)` are **not a key in inventory** (`flight_number`
    nullable, no unique index). Closing the seam needs a cross-service identity design decision,
    so it was **stop-and-flagged** (no `src/main` change; WireMock retained). Full write-up:
    `FLAG_rm_inventory_au_seam.md`. Lesson codified: `RULE_headline_gates_real_counterparty.md`.
- **Outbound call hardening missing** — no `Idempotency-Key`, no retry, unsigned `alg:none`
  JWT, `tenantId` duplicated in body + bearer.
- No suspected defects in RM's *owned* logic (engines + persistence are internally consistent and tested).

## Open items

- Build or re-point the `AdjustInventory` target so the feed works against the real Inventory.
- Wire a real booking snapshot into `compute` (currently 0).
- Honour `Idempotency-Key`; add retry + signed auth to the outbound call.
- Reconcile `register` (201) vs `compute` (200) status conventions.

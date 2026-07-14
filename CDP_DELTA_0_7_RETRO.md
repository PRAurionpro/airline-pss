# Personalisation / CDP — Retro Design Delta 0.7

> **Nature:** *Retro-documentation of merged code* (**PR #65**, branch
> `feat/personalisation-cdp`, tag `v0.3.7-personalisation-cdp-thin`). A record of what was
> built, not a ratified-before-build design. The merged code is authoritative. Contracts:
> `contracts/cdp-api_openapi.yaml`, `README_CDP_CONTRACTS.md`.

## Scope & non-goals

**In scope (as built):** a 360° customer profile built **exclusively from published events**,
consent capture/read, and a **consent-gated offer-ranking** endpoint behind a pure
`RankingPort` (deterministic stub scoring). Admin profile reads.

**Non-goals:** no real ML ranking (stub additive scoring); no module-store reads (event-sourced
only); no backbone event publishing; no offer-service wiring (deferred, FLAG-007); no
cross-tenant identity resolution.

## Module posture

**Offer/Retailing-layer read model.** Package `pss.cdp`, **port 8090**. Owns the event-sourced
profile + consent; serves ranking. Isolation (no synchronous module calls, no foreign
datasource) enforced by `check_cdp_isolation.py`. Publishes no events; calls no module.

## Entities (V1__cdp.sql)

- `customer_profiles` — unique `(tenant_id, external_ref)`; event-sourced, mutated in place; only listed PII columns.
- `consent_records` — unique `(tenant_id, external_ref, consent_type)` (upsert key).
- `profile_events_seen` — dedupe ledger.

## Event flows

Three `@KafkaListener`s (group `pss-cdp`) on `pss-order-events`, `pss-loyalty-events`,
`pss-dcs-events`. `OrderConfirmed` is the only profile-**creating** event; `OrderCancelled`,
`MilesAccrued`, `TierChanged`, `PassengerCheckedIn` are update-only. Idempotent on `eventId`
via `profile_events_seen`.

## Ports & deferral lines

- `RankingPort` → `StubRankingEngine` (pure additive scoring: base 0.5, +0.3 cabin match, +0.2 origin match, +0.15 GOLD / +0.10 SILVER, +0.10 frequent-booker ≥5; sorted desc). Real ML deferred. Pinned by `RankingPortConformanceSuite`.
- `ConsentService` — natural-key upsert; `RankingService` — consent-first gate (neutral ranking on `NO_CONSENT` / `NO_PROFILE`).

## API summary

5 endpoints under `/v1/cdp/*` (consent x2, profile x2 admin, rank-offers) — see
`contracts/cdp-api_openapi.yaml`. Typed codes: `PROFILE_NOT_FOUND` (404),
`CDP_ADMIN_REQUIRED` (403).

## Decisions made de facto

1. **Event-sourced profile, no module reads** — the 360° view is assembled solely from the backbone (structural guarantee, enforced in CI).
2. **Consent-first ranking** — `rank-offers` checks consent + profile before scoring; absence is a **neutral 200**, never an error (privacy-safe default).
3. **Pure `RankingPort`** — real ML deferred without changing the seam.
4. **`OrderConfirmed` is the sole profile-creating event**; loyalty/DCS events only enrich existing profiles.
5. **Admin gate + idempotency deferred** — `X-Cdp-Admin` header gate; `Idempotency-Key` required-but-unused on consent (natural-key upsert already safe).

## Findings

- No suspected defects and **no core-module writes** (cdp calls nobody; isolation is enforced).
- **FLAG-007 (carried):** the offer-service `PersonalisationPort` → cdp `POST /v1/cdp/rank-offers` wiring is **not** config-only (needs a new adapter class in offer-service), so it is deferred to a scoped follow-up PR. cdp is ready and waiting. Direction is offer→cdp.

## Open items

- Wire offer-service `PersonalisationPort` to `POST /v1/cdp/rank-offers` (FLAG-007).
- Real ML ranking behind `RankingPort`.
- Consider a consent-denied signal on `rank-offers` if a caller needs to distinguish neutral-from-consent vs neutral-from-no-data.

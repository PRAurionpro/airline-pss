# Phase 2 — Build Plan, Loyalty / FFP Tranche (Thin Earn + Redeem)

**Status:** Draft for ratification · Companion to: LOYALTY_DELTA_0_3_EARN_REDEEM.md
**Base documents:** PSS_System_Design_Document (§5 module catalogue, §6.3 event backbone) · PSS_Order_Module_LLD (§6 events) · README.md (Order event contracts — idempotent-on-`eventId` rule)
**How to use:** Drop this file and the loyalty delta into the repo / Claude Code project context. Work strictly milestone by milestone. A milestone is done only when its gate is green on CI.
**What this is:** The thin walking-skeleton slice of the Phase 2 Loyalty/FFP module — member enrolment, automatic earn from the Order event stream, tier recognition, and redemption to a loyalty-owned award voucher. No real "pay with miles", no partner earn, no expiry lifecycle, no member portal UI.

---

## Standing rules for this tranche

1. **Design governs.** Where code and the loyalty delta disagree, stop and flag.
2. **Loyalty is a side-ledger + event consumer — not a core change.** No Order, Pricing, Offer, or Payments code change. Accrual consumes events; redemption issues a voucher behind a port. If a core change appears necessary, stop and flag.
3. **Append-only ledger.** `PointsTransaction` is the source of truth; `pointsBalance` and `qualifyingMiles` are projections. Never mutate a balance in place.
4. **Idempotent on `eventId`.** Accrual dedupes on the order event's `eventId` via a unique `dedupeKey`. A redelivered event must never double-credit.
5. **Pure engines.** `EarnRulePort.evaluate` and `TierPolicyPort.evaluate` have no side effects; fully unit-testable without any Spring context.
6. **No new money paths.** Redemption debits points (a loyalty-internal unit) and mints a voucher via `RedemptionFulfilmentPort` (stub). Real Pricing/Payments application is deferred.
7. **Tenant + member scope.** Every query is `(tenantId, memberId)` or `(tenantId, accountId)` scoped. Isolation is structural.
8. **Port 8086.** `loyalty-service` runs on port 8086 following the established pattern (payments:8080, order:8081, offer:8082, dcs:8083, distribution:8084, corporate:8085).

## Milestones and gates

### L0 — Service skeleton + member store + Enrol *(start immediately)*
`loyalty-service` module (pkg `pss.loyalty`, port 8086) wired into the platform build. `Member` + `LoyaltyAccount` entities + Flyway V1. `Enrol POST /v1/loyalty/members` (opens account at zero balance, BASE tier) and `GetMember GET /v1/loyalty/members/{id}`. `LoyaltyOutboxEvent` table. `DemoSecurityConfig`. OTel tracing dependencies.
**Gate:** Enrol → 201; account opened with `pointsBalance=0`, `currentTier=BASE`; `GetMember` returns it; `version=0`; outbox table exists; `DUPLICATE_ENROLMENT` on repeat externalRef; `EnrolMemberIT` passes (Testcontainers).

### L1 — Points ledger + earn rule + tier engine + conformance *(after L0)*
`PointsTransaction` (append-only, unique `dedupeKey`), `EarnRule`, `TierChangeEvent` entities + Flyway V2. `EarnRulePort` + `TierPolicyPort` interfaces + in-process implementations. Balance/qualifying-miles projection over the ledger. `ManualEarn POST /v1/loyalty/members/{id}/earn` (admin/test seam). `GetBalance`, `GetTransactions`. Abstract `EarnRulePortConformanceSuite` + `TierPolicyPortConformanceSuite`.
**Gate:** ManualEarn appends a ledger entry and balance derives correctly; earn-rule conformance green (rate calc + basis selection); tier conformance green (BASE→SILVER→GOLD crossings *and* step-down on negative); `NO_ACTIVE_EARN_RULE` error when no active rule; second ManualEarn with same `dedupeKey` is a no-op (idempotent ledger).

### L2 — Accrual consumer (headline gate) *(after L1)*
Order event consumer: `OrderConfirmed` → resolve member → `EarnRulePort.evaluate` → append `ACCRUAL` (dedupe on `eventId`) → recompute tier → publish `MilesAccrued` (+ `TierChanged` on crossing). `OrderCancelled` → append `REVERSAL` → recompute tier → publish `MilesReversed`. Resolve the `OrderConfirmed` priced-amount path (payload vs read-only order read-back — delta §4 Open Item).
**Gate:** `OrderConfirmed` event → miles credited per active earn rule; `MilesAccrued` in outbox; **duplicate `eventId` → no double credit**; threshold crossing → `TierChanged` emitted; `OrderCancelled` → `REVERSAL` entry, balance restored, demotion emits `TierChanged`; unenrolled pax → no-op (logged, no error); `AccrualConsumerIT` full path passes (Testcontainers + WireMock for the order read-back if used). **Zero changes to `order-service`.**

### L3 — Redemption + fulfilment port *(after L2)*
`RedemptionOption`, `AwardRedemption` entities + Flyway V3. `RedemptionFulfilmentPort` interface + **stub** implementation (issues `voucherRef`) + `RedemptionFulfilmentPortConformanceSuite`. `ListRedemptionOptions`, `Redeem POST /v1/loyalty/members/{id}/redemptions`, `GetRedemption`. Typed errors `INSUFFICIENT_POINTS`, `REDEMPTION_OPTION_NOT_FOUND`, `MEMBER_SUSPENDED`.
**Gate:** Redeem with sufficient balance → `REDEMPTION` ledger debit + `AwardRedemption(status=ISSUED)` + voucher ref + `MilesRedeemed` in outbox; `INSUFFICIENT_POINTS` → no debit, no voucher; fulfilment-port conformance green; `GetRedemption` returns the voucher; `RedeemAwardIT` passes. **No Pricing/Payments call.**

### L4 — Statement + tier surfacing + demo Postman collection + smoke *(after L3)*
`GetStatement GET /v1/loyalty/members/{id}/statement` (opening → accruals → redemptions → closing) + tier surfacing on `GetBalance`. Demo Postman collection: `docs/demo/loyalty-demo.postman_collection.json` — polish-grade collection (enrol → simulate `OrderConfirmed` → see miles + tier → redeem → see voucher → simulate `OrderCancelled` → see reversal). CI smoke: `AccrualConsumerIT` + `RedeemAwardIT` as the unattended smoke. Tag `v0.3.3-loyalty-thin` on merge.
**Gate:** Statement API returns a coherent period view; demo collection valid JSON and chaining works (auto-minted JWT, chained variables); smoke green; tag on master.

## Dependency picture

```
L0 (skeleton + member store + Enrol)
    → L1 (ledger + earn rule + tier engine + conformance)
        → L2 (accrual consumer — headline gate: real OrderConfirmed → miles land)
            → L3 (redemption + fulfilment port)
                → L4 (statement + demo collection + smoke → v0.3.3-loyalty-thin)
```

All milestones strictly sequential (each depends on the previous).

## Integration with the rest of the platform

- **Upstream, consume from the event backbone:** `OrderConfirmed` (→ accrue), `OrderCancelled` (→ reverse). Same outbox-consumer pattern DCS and the dashboard already use. Idempotent on `eventId`.
- **Optional read-back (read-only):** `GET /v1/orders/{orderId}` to fetch the priced amount *iff* `OrderConfirmed` does not carry it (delta §4). A read, never a write — same posture as DCS reading the confirmed order.
- **Downstream, publish:** `MilesAccrued`, `MilesReversed`, `MilesRedeemed`, `TierChanged` on the backbone. The carrier dashboard *may* add a "miles accrued" beat as a follow-on.
- **Redemption fulfilment:** behind `RedemptionFulfilmentPort` (stub → voucher). Real Pricing discount context / Payments mile-funded instrument deferred.

## Stop-and-flag list

Any change to `order-service`, `pricing-service`, `offer-service`, or `payments-service` · any new field requested on `OrderConfirmed`/`OrderCancelled` · new saga steps · any real money path or "pay with miles" wiring · partner/coalition earn · points expiry/lifecycle jobs · any consumed event beyond `OrderConfirmed`/`OrderCancelled` · any published event beyond the four named above.

## Open decisions (ratify before L2; L0/L1 can start in parallel)

From loyalty delta §10:
1. ★ **Accrual trigger** — booking-time (`OrderConfirmed`) this slice, reverse on `OrderCancelled`; flown-based (DCS `FlightClosed`/`OrderFulfilled`) deferred. Recommend: confirm.
2. ★ **Earn basis** — `BASE_FARE` only, configurable on `EarnRule`. Recommend: confirm.
3. ★ **Redemption fulfilment** — loyalty voucher stub this slice; real Pricing/Payments application deferred behind the port. Recommend: confirm defer.
4. ★ **Tier qualification window** — cumulative qualifying-miles counter this slice; calendar/anniversary reset deferred. Recommend: confirm.
5. **Points expiry** — not modelled; confirm defer.
6. **`OrderConfirmed` amount carriage** — confirm payload carries the priced amount; else read-only order read-back. No Order contract change either way.

## Pre-build checklist (before L2 starts; L0/L1 can begin immediately)

1. Ratify the four ★ open decisions above.
2. Resolve the `OrderConfirmed` amount-carriage question (payload vs read-back) by inspecting `order-events.schema.json` — this determines whether L2 needs a WireMock for the order read.
3. Confirm demo intent: is the L4 Postman collection for a next prospect demo (polish-grade) or architecture proof.
4. Confirm port 8086 is not in use in the local dev environment.
5. Confirm tier thresholds (SILVER / GOLD qualifying-mile values) for the demo tenant.

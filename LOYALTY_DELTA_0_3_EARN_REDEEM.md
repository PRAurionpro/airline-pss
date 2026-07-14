# Loyalty / FFP Delta 0.3 — Thin Frequent-Flyer Slice (Enrol + Earn + Tier + Redeem)

**Status:** Draft for ratification · Companion to: BUILD_PLAN_LOYALTY_TRANCHE.md
**Base documents:** PSS_System_Design_Document (§5 module catalogue — Loyalty/FFP as a Phase 2 module; §6.3 event backbone & transactional outbox) · PSS_Order_Module_LLD (§6 events — `OrderConfirmed`, `OrderCancelled`) · PSS_Pricing_Module_LLD (redemption-as-discount hook only) · README.md (Order event contracts — *"Consumers must be idempotent on `eventId`"*)
**Phase note:** Loyalty/FFP is a **Phase 2** module in the SDD catalogue. This delta builds the *thin walking-skeleton slice only*, in the same spirit as the Corporate thin self-booking and the DCS check-in→boarding slices: prove the seam end-to-end on real services and the real event backbone, defer the heavy integrations (real "pay with miles", partner/coalition earn, expiry lifecycle) behind ports.
**Architecture position:** A **downstream side-ledger**. `loyalty-service` consumes the Order event stream to accrue, and owns a points ledger plus a redemption catalogue. It is *not* in the booking critical path and *not* in any money path. Earn is reactive (event-driven); redeem produces a loyalty-owned voucher, with application against Pricing/Payments stubbed behind a port.

---

## 1. Scope and Non-Goals

The smallest slice that proves a frequent flyer can **enrol, automatically earn miles when an order they own confirms, see their balance and tier, and redeem miles for an award** — with **zero changes to Order, Pricing, Offer, or Payments internals**.

The slice: **Enrol → (OrderConfirmed → Accrue) → balance + tier recognition → Redeem (debit → award voucher) → (OrderCancelled → Reverse)**.

This lights up loyalty as a clean event consumer of the sale the platform already produces, and proves the earn/redeem ledger without touching the core modules — the same discipline that kept Corporate and DCS additive.

**Non-goals (deferred without guilt):**
Real "pay with miles" at checkout (Pricing discount context / Payments mile-funded instrument) · mixed cash+points payment · partner/coalition earn (cards, hotels, car) · points transfer, gifting, family/household pooling · dynamic award pricing and award seat inventory · status match and tier challenges · lounge/benefit entitlement engine (fast-track, extra bag, priority) · tier-benefit application at booking time · points expiry, sweep, and lifecycle jobs · retroactive missing-miles claims · fraud / velocity controls · member self-service portal UI (this slice is API + a thin agent/admin surface) · flown-based accrual via DCS (see Open Decision 1) · points-to-cash and breakage accounting · cross-tenant alliance redemption.

**Design invariants (carried forward, unchanged):**
- **No changes to Order, Pricing, Offer, or Payments internals.** Accrual is a downstream consumer of `OrderConfirmed`/`OrderCancelled` on the existing backbone; redemption issues a loyalty-owned voucher behind a port. If a core-module change appears necessary, **stop and flag** — the build has drifted.
- **No new money paths.** Redemption debits points (a loyalty-internal unit) and mints a voucher reference. Applying that voucher to a real order/payment is behind `RedemptionFulfilmentPort` and is explicitly deferred. The slice moves no cash.
- **Append-only ledger; balance is derived.** `PointsTransaction` is the source of truth; `pointsBalance` and `qualifyingMiles` are projections. Every accrual/reversal/redemption is a ledger entry, never an in-place balance mutation.
- **Idempotent on `eventId`.** Accrual consumes Order events idempotently per the README rule — a redelivered `OrderConfirmed` must never double-credit.
- **Tenant + member scope.** Every query is `(tenantId, memberId)` or `(tenantId, accountId)` scoped. The core modules see only the tenant.
- **Contract changes are additive only.** No existing endpoint is modified; loyalty endpoints are net-new surface.

## 2. Module Posture — New Bounded Context, Net-New Surface

`loyalty-service` is a new service (pkg `pss.loyalty`, port 8086), tenant-scoped like every other module, with its own member + ledger store. It is a **consumer of Order**, not a caller into Order/Pricing/Payments write paths:

- **Owns** loyalty data: `Member`, `LoyaltyAccount`, `PointsTransaction` (the ledger), `EarnRule`, `RedemptionOption`, `AwardRedemption`, plus a `TierChangeEvent` history.
- **Consumes** `OrderConfirmed` (→ accrue) and `OrderCancelled` (→ reverse) from the event backbone via the same consumer pattern DCS and the dashboard already use.
- **Reads back** the priced order amount when the event envelope does not carry it (read-only `GET /v1/orders/{orderId}`), exactly as DCS *reads* Order without writing — see §4.
- **Publishes** `MilesAccrued`, `MilesReversed`, `MilesRedeemed`, `TierChanged` via the transactional outbox.
- **Stubs behind a port:** `RedemptionFulfilmentPort` — the *exact* PaymentProviderPort / WeightBalancePort pattern Tranche 1 and DCS proved.

**Crucially:** `loyalty-service` never holds order or payment state — it holds member/account/ledger data and a redemption view. It cannot oversell, cannot change a price, and cannot move money.

## 3. Loyalty Entities

```
Member            { memberId, tenantId, externalRef (nullable — e.g. pax ref / email),
                    givenName, surname, email, status {ACTIVE│SUSPENDED}, enrolledAt }

LoyaltyAccount    { accountId, memberId, tenantId, pointsBalance (derived/cached),
                    qualifyingMiles (derived/cached), currentTier {BASE│SILVER│GOLD},
                    openedAt }

PointsTransaction { txnId, accountId, tenantId,
                    type {ACCRUAL│REVERSAL│REDEMPTION│ADJUSTMENT},
                    points (signed integer), qualifyingMiles (signed, nullable),
                    sourceType {ORDER_EVENT│MANUAL│REDEMPTION},
                    sourceRef (orderId │ eventId │ redemptionId),
                    dedupeKey (= eventId for ORDER_EVENT; unique), occurredAt }
                    -- append-only; balance & tier are projections over this ledger

EarnRule          { ruleId, tenantId, name, earnBasis {BASE_FARE│TOTAL},
                    pointsPerCurrencyUnit, qualifyingMilesBasis {BASE_FARE│DISTANCE_STUB},
                    active }

RedemptionOption  { optionId, tenantId, name, pointsCost,
                    kind {AWARD_VOUCHER│ANCILLARY_AWARD}, active }

AwardRedemption   { redemptionId, accountId, tenantId, optionId, pointsDebited,
                    voucherRef, fulfilmentRef (from port), status {ISSUED│FULFILLED│VOID},
                    createdAt }

TierChangeEvent   { id, accountId, tenantId, fromTier, toTier,
                    qualifyingMilesAtChange, changedAt }

LoyaltyOutboxEvent{ eventId, accountId, tenantId, eventType, payload, occurredAt }
```

## 4. Accrual — Reactive, Idempotent, Read-Only Toward Order

Accrual is the headline seam. It is **purely reactive**: nothing in Order, Offer, or Pricing changes.

**On `OrderConfirmed`:**
1. Resolve the owning member from the order's pax reference (`externalRef` match) or skip if no enrolled member (`NO_MEMBER` → no-op, logged).
2. Determine the earn basis amount:
   - If the `OrderConfirmed` payload carries the priced total/base fare → use it directly.
   - **If not** → read-only `GET /v1/orders/{orderId}` to fetch the priced amount. *(Read, not write — same posture as DCS reading the confirmed order. Confirm which path applies during L2; see Open Item below.)*
3. `EarnRulePort.evaluate(orderSummary, activeRule)` → `EarnResult(points, qualifyingMiles, basisAmount)` — pure function.
4. Append a `PointsTransaction` of type `ACCRUAL` with `dedupeKey = eventId`. **Unique constraint on `dedupeKey` makes redelivery a no-op** (idempotent per README).
5. Recompute tier via `TierPolicyPort`; if it crossed a threshold, write a `TierChangeEvent` and publish `TierChanged`.
6. Publish `MilesAccrued` via outbox.

**On `OrderCancelled`:**
1. Find the prior `ACCRUAL` for that `orderId`.
2. Append a `REVERSAL` (negative points + negative qualifying miles).
3. Recompute tier (may demote → `TierChanged`).
4. Publish `MilesReversed`.

**Open Item (resolve in L2):** confirm whether `OrderConfirmed` carries the priced amount in its payload. If yes → no read-back needed. If no → loyalty does a read-only order read. Either way **no Order contract change** is requested by this delta; if accrual appears to *need* a new field on `OrderConfirmed`, stop and flag.

## 5. The Earn Rule and Tier Engines — Pure Functions

Both engines are pure functions (no side effects, fully unit-testable without Spring), exposed as ports so future rule engines slot in unchanged.

```kotlin
interface EarnRulePort {
    fun evaluate(order: OrderSummary, rule: EarnRule): EarnResult
}
data class EarnResult(val points: Long, val qualifyingMiles: Long, val basisAmount: BigDecimal)

interface TierPolicyPort {
    fun evaluate(qualifyingMiles: Long, current: Tier): TierDecision
}
data class TierDecision(val newTier: Tier, val crossed: Boolean)
```

**Earn rule (this slice):** `points = floor(basisAmount × pointsPerCurrencyUnit)`, where `basisAmount` is the base fare or total per `EarnRule.earnBasis`. Qualifying miles tracked as a simple counter from the same basis (distance-based qualifying is a stub).

**Tier thresholds (this slice):** `BASE → SILVER → GOLD` on cumulative `qualifyingMiles` (e.g. SILVER ≥ 25,000; GOLD ≥ 50,000 — tenant-configurable). Crossings are monotonic up on accrual and may step down on reversal.

Each port ships an abstract conformance suite (`EarnRulePortConformanceSuite`, `TierPolicyPortConformanceSuite`) proving rate calculation, basis selection, and threshold crossings in both directions.

## 6. Redemption — Debit + Voucher, Fulfilment Behind a Port

Redemption is where the temptation to touch Pricing/Payments lives. The thin slice resists it.

```kotlin
interface RedemptionFulfilmentPort {
    fun fulfil(option: RedemptionOption, member: Member): FulfilmentResult
}
data class FulfilmentResult(val voucherRef: String, val fulfilmentRef: String)
```

**`POST /v1/loyalty/members/{memberId}/redemptions` flow:**
1. Load member + account; reject `MEMBER_SUSPENDED`.
2. Load `RedemptionOption`; reject `REDEMPTION_OPTION_NOT_FOUND`.
3. Check `pointsBalance ≥ option.pointsCost`; else `INSUFFICIENT_POINTS` (no debit).
4. Append a `REDEMPTION` ledger entry (negative points).
5. `RedemptionFulfilmentPort.fulfil(option, member)` → **stub returns a `voucherRef`** (e.g. `AWD-XXXX`). The real port (apply to a Pricing discount context / mint a Payments mile-funded instrument) is deferred.
6. Persist `AwardRedemption` (`status=ISSUED`), publish `MilesRedeemed` via outbox.

The stub fulfilment keeps the loop honest end-to-end (points really leave the ledger, a real voucher reference is issued and queryable) while drawing the deferral line cleanly at the Pricing/Payments boundary.

## 7. API Contract (Loyalty, net-new)

All tenant-scoped. Admin/test operations require `X-Loyalty-Admin: true` (demo posture — full RBAC in a later slice).

| Operation | Method / path | Purpose |
|-----------|--------------|---------|
| Enrol | `POST /v1/loyalty/members` | Enrol a member; opens a `LoyaltyAccount` at zero balance, BASE tier |
| GetMember | `GET /v1/loyalty/members/{memberId}` | Member + account summary |
| GetBalance | `GET /v1/loyalty/members/{memberId}/balance` | Points balance + qualifying miles + current tier |
| GetTransactions | `GET /v1/loyalty/members/{memberId}/transactions` | Ledger history (accruals, reversals, redemptions) |
| ManualEarn *(admin/test)* | `POST /v1/loyalty/members/{memberId}/earn` | Explicit accrual — test seam before the event consumer lands |
| ListRedemptionOptions | `GET /v1/loyalty/redemption-options` | Redemption catalogue |
| Redeem | `POST /v1/loyalty/members/{memberId}/redemptions` | Debit points → issue award voucher (main redeem flow) |
| GetRedemption | `GET /v1/loyalty/redemptions/{redemptionId}` | Redemption detail + voucher ref |
| GetStatement | `GET /v1/loyalty/members/{memberId}/statement` | Period statement (opening balance, accruals, redemptions, closing) |

**Typed errors:** `MEMBER_NOT_FOUND` · `MEMBER_SUSPENDED` · `DUPLICATE_ENROLMENT` (externalRef already enrolled for tenant) · `NO_ACTIVE_EARN_RULE` · `INSUFFICIENT_POINTS` · `REDEMPTION_OPTION_NOT_FOUND`.

## 8. Events

**Consumed (from the existing backbone — no new producer contracts):**
`OrderConfirmed` → accrue · `OrderCancelled` → reverse. Idempotent on `eventId` via the ledger `dedupeKey` unique constraint.

**Published (net-new, via transactional outbox):**
`MilesAccrued` · `MilesReversed` · `MilesRedeemed` · `TierChanged`. Single envelope identical in shape to the Order event envelope (`eventId`, `type`, `version`, `tenantId`, `accountId`, `occurredAt`, `correlationId`, `causationId`, `payload`); consumers idempotent on `eventId`. The dashboard *may* add a "miles accrued" beat as a follow-on (not in this slice).

## 9. Demo Surfacing

A new live demo beat: a passenger enrols as a frequent flyer, books a flight through the existing flow (the order confirms), and **miles land automatically** in their loyalty account — visible via `GetBalance`. A second booking ticks `qualifyingMiles` past the SILVER threshold and the tier flips (`TierChanged`). The member then redeems miles for an award voucher (`MilesRedeemed`, points debited, voucher issued). If the booking is cancelled, the miles reverse and the balance restores. Clean earn→tier→redeem→reverse story, fully event-driven, zero core-module changes — the loyalty equivalent of the corporate cost-centre beat.

## 10. Open Decisions (ratify before L2)

1. ★ **Accrual trigger** — accrue on `OrderConfirmed` (booking-time) this slice, reverse on `OrderCancelled`. Real airlines often accrue on *flown* (DCS `FlightClosed` / `OrderFulfilled`). Recommend: booking-time accrual this slice; flown-based accrual deferred to when the DCS/Order-Accounting seam is wired. Reversal-on-cancel keeps booking-time accrual honest.
2. ★ **Earn basis** — `BASE_FARE` only (taxes/ancillaries excluded) vs `TOTAL`. Recommend: `BASE_FARE`, configurable on `EarnRule`.
3. ★ **Redemption fulfilment** — loyalty-owned voucher (stub) this slice; real "pay with miles" via Pricing discount context or Payments mile-funded instrument deferred behind `RedemptionFulfilmentPort`. Recommend: confirm defer.
4. ★ **Tier qualification window** — rolling cumulative qualifying miles (simple counter) this slice vs calendar-year / anniversary reset. Recommend: cumulative counter this slice; reset jobs deferred.
5. **Points expiry** — not modelled this slice. Recommend: confirm defer (no lifecycle jobs).
6. **`OrderConfirmed` amount carriage** — confirm the event payload carries the priced amount; if not, accrual does a read-only order read-back (§4). No Order contract change requested either way.

## 11. Stop-and-Flag List

Any change to `order-service`, `pricing-service`, `offer-service`, or `payments-service` internals · any new field requested on `OrderConfirmed`/`OrderCancelled` · any real money path or "pay with miles" wiring into Pricing/Payments · any new saga step · partner/coalition earn integration · points expiry/lifecycle jobs · any event consumed beyond `OrderConfirmed`/`OrderCancelled` · any published event beyond `MilesAccrued`/`MilesReversed`/`MilesRedeemed`/`TierChanged`.

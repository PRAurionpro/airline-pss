# Breadth Delta 0.2 — Multi-Passenger & Return Journeys

**Status:** Working draft v0.1 — ratified in design conversation, for build
**Companion to:** Offer / Pricing / Order / Inventory LLDs (v0.1) · order-api.openapi.yaml · DEMO_SCRIPT.md (Scenes 1–3, 6)
**Scope:** Grows the Phase-1 walking skeleton (1 adult, one-way, air-only) into multi-passenger return journeys. One new Offer operation; implementation growth in Pricing and Order; IBE flow changes. **No external contract breaks, no saga changes, no new compensation logic.**

---

## 1. Finding and Principle

The Phase-1 contracts were designed breadth-ready and are not changed by this delta:
- Pricing `PriceItinerary` already accepts `segments[]` and `passengers[{ptc,count}]`, returns `perPassenger[]`, and uses positional `passengerRef` (P1, P2…).
- Inventory `HoldInventory` already accepts multiple `lines[]` with `qty`, across flights, in one hold.
- Order's model and OpenAPI already carry `passengers[]` (with PTC) and per-passenger-per-segment `items[]`.

**Principle: implementations grow into the existing contracts.** Where this delta adds anything to a contract, it is additive (one new Offer operation, one new validation error code). This is the property that lets NDC/B2B distribution later expose a stable contract.

## 2. Ratified Decisions

| # | Decision | Choice |
| --- | --- | --- |
| BD-1 | Return-journey shape | **Two-stage shopping**: per-direction candidate offers, then `CombineAndPrice` on the selected pair producing the single authoritative offer. (Rejected: cartesian outbound×return pricing at shop time — call-volume explosion for no demo value.) |
| BD-2 | Passenger identity | **Count-based offers, positional refs (P1…Pn); names attach at CreateOrder**, validated against the offer's PTC counts. |
| BD-3 | Inventory & saga | **Unchanged.** One hold, N lines, one TTL, one confirm. Saga steps and compensations identical. |
| BD-4 | Pricing scope | **N×ADT only.** Non-ADT counts rejected with a clean error (`PTC_NOT_SUPPORTED`), never silently mispriced. CHD/INF rules deferred (demo-script cut). |
| BD-5 | Money mapping constraint | **Phase-2.0 filed fares are per-direction** (one fare component per O&D per passenger), so components map 1:1 to order items. Spanning components / proration: designed-for, deferred. |
| BD-6 | Ticket bridge | **One e-ticket per passenger covering the journey; one coupon per segment.** `documentRef` = ticket + coupon designator. |
| BD-7 | Caps | Max **9 passengers**; **return or one-way only** (no multi-city / open-jaw); cabin & RBD selectable **per direction**. |

## 3. Offer Module Delta (largest)

### 3.1 ShoppingContext
Gains `tripType {ONE_WAY│RETURN}`, `returnDate`, and `passengers[{ptc,count}]` (previously fixed 1×ADT). Validation: Σcount ∈ [1,9]; ADT ≥ 1; non-ADT → `PTC_NOT_SUPPORTED` (422).

### 3.2 Two-stage flow
**Stage 1 — Shop.** For RETURN, Shop fans out availability per direction and prices each candidate *direction* for the full passenger set (one `PriceItinerary` per candidate, as today — now with pax counts). Response: candidate offers grouped by direction, each flagged `stage: CANDIDATE`. Candidate prices are **indicative**.

**Stage 2 — CombineAndPrice (new operation).** `POST /v1/offers/combine` with `{outboundOfferId, returnOfferId}` (or a single candidate for one-way, where the stage is a pass-through). Offer re-resolves both candidates, issues **one** `PriceItinerary` with both directions' segments and the full passenger set, and assembles the single **JOURNEY offer** — the only offer an order may be created from. `validUntil` inherited from the combined quote. Combining expired/mismatched candidates (different shopping contexts, different pax sets) → 422 `CANDIDATES_INCOMPATIBLE`; expired → 410, IBE re-shops.

Order-side enforcement: `CreateOrder` accepts only `stage: JOURNEY` offers. A candidate offerRef → 422 `OFFER_NOT_ORDERABLE`.

### 3.3 Offer item shape
Offer items gain the association that ancillaries will snap into next:
`{itemRef, type: AIR, directionIndex, segmentRef, paxRef (P1…Pn), ptc, price, taxes[]}` — one AIR item per (passenger × segment), mirroring the order-item granularity, each carrying its slice of the quote (per-passenger fare for that direction + its tax lines). Offer total = Σ items = quote `grandTotal` — asserted at assembly, assembly fails on mismatch (never publish an offer whose arithmetic is wrong).

### 3.4 What does not change
Offer remains derived/eventual, holds no inventory, TTL store unchanged, personalisation port untouched. Re-validation at the Order seam is unchanged in principle — it now re-validates the journey offer (one quote, one hold request).

## 4. Pricing Module Delta

Implementation growth only; the contract is already correct.
- `FiledFareStrategy` computes per-passenger per-direction: for each direction, select the fare (market = that direction's O&D), produce one `FareComponent` per passenger per direction (`passengerRef` P1…Pn), tax lines itemised per passenger per applicable basis (per-segment taxes × segments; per-ticket taxes once per passenger).
- `perPassenger[]` in the response becomes real (N entries), `grandTotal` = Σ per-passenger totals. Determinism guarantee unchanged: same itinerary + pax set + fareDataVersion → byte-identical quote.
- Validation: reject non-ADT PTCs (`PTC_NOT_SUPPORTED`) and pax counts > 9 — at Pricing as well as Offer (defence in depth; Pricing is also called by Order's reprice path).
- **Golden quotes:** new regression fixtures for 1×ADT one-way (existing), 2×ADT one-way, 2×ADT return, 9×ADT return (cap), per-segment vs per-ticket tax bases across multi-segment.

## 5. Order Module Delta

### 5.1 CreateOrder validation & materialization
- Accepts the journey offerRef + `passengers[]` (named) + contacts. Validates: passenger list cardinality and PTCs match the offer's shopping context exactly; else 422 `PASSENGERS_MISMATCH_OFFER`.
- Positional mapping: P1 → first passenger in the submitted list, in order. The mapping is recorded (each Passenger row stores its `paxRef`) so every offer/quote line is traceable to a named passenger.
- Item materialization: one AIR OrderItem per (passenger × segment) — a 2-adult return = 4 AIR items — each carrying price + taxes from its 1:1 fare component (BD-5). Invariant asserted in the creation transaction: order `totalAmount` = Σ item totals = quote `grandTotal`. Mismatch aborts creation (consistency over availability, same posture as Phase 1).

### 5.2 Hold request shape
One `HoldInventory` call: lines aggregated per (flightInventoryId, cabin, rbd) with qty = passenger count. Hold/line references stored against the order items they back (an item records its hold line). **Saga unchanged**: same steps, same TTL coordination, same compensation; decline/expiry releases the one hold exactly as today.

### 5.3 Ticketing/EMD bridge (BD-6)
The bridge issues one e-ticket per passenger spanning the journey's coupons (coupon 1 = outbound, coupon 2 = return). Each AIR item's `documentRef` = `{ticketNumber}/{couponNumber}`. PNR bridge: one PNR for the order, N name fields, 2 segments — mechanical extension of the outbound mapping, no model change.

### 5.4 Cancellation (Scene 7 unchanged)
Voluntary cancel cancels the order: all items → CANCELLED, one refund for the settled total via the existing path. Per-item/partial cancellation stays deferred (it was already designed in `ChangeOrder`/`CANCEL_ITEM`; not demo scope).

## 6. Inventory Module — explicitly no change

Contract and implementation already support multi-line, qty>1 holds. Additions are tests only: the concurrency/no-oversell suite gains qty>1 variants (two competing 2-seat holds against 3 remaining seats → exactly one succeeds; the loser gets a clean 422; no partial holds — a hold is atomic across its lines, which the existing invariant already requires and the test must now prove across lines).

## 7. IBE / BFF Delta

- Search form: trip type, dates (out/return), passenger count selector (1–9 adults; other types greyed with "coming soon" rather than absent — honest UX, no silent mispricing).
- Two-stage selection: outbound list → return list → combined review (the `CombineAndPrice` result) with per-passenger breakdown — Scene 2's "one offer, one price" moment.
- Passenger details: N passenger forms (name, DOB, document ref optional in demo config), one contact block. BFF maps form order → P1…Pn.
- Confirmation/retrieve: renders N passengers, 4+ items grouped by passenger and direction, per-item documents. (Scene 5.)
- BFF orchestrates Shop → CombineAndPrice → CreateOrder → checkout; no business logic in the BFF beyond mapping (paved-road rule).

## 8. Events

No new event types. Payload growth only: `OrderCreated`/`OrderConfirmed` carry the full passengers/items arrays they were schema'd for. The dashboard (Scene 0/4 consumer) gets richer events for free — pax counts and item counts become chart-able without schema work. Event-schema regression: validate the N-pax payloads against `order-events.schema.json` in CI.

## 9. Testing Strategy (delta)

1. **Offer:** combine happy path; combine with expired/incompatible candidates; candidate-not-orderable at CreateOrder; offer arithmetic invariant (Σ items = grandTotal) property-tested.
2. **Pricing:** golden quotes per §4; determinism across N pax; PTC rejection.
3. **Order:** passenger-mismatch validation matrix; materialization (2×ADT return → exactly 4 AIR items, correct prices); money invariant abort path; bridge mapping (ticket/coupon per pax/segment).
4. **Inventory:** qty>1 concurrency suite per §6.
5. **End-to-end (CI smoke, demo-script aligned):** 2-adult return shop → combine → order → pay (sandbox) → confirm: 4 items ticketed, dashboard events observed; decline variant releases the full 2-seat hold on both flights (Scene 6 with breadth).

## 10. Demo-Scene Traceability

| Scene | Delivered by |
| --- | --- |
| 1 (search, 2 adults, return) | §3.1, §7 |
| 2 (one offer, one price) | §3.2 CombineAndPrice |
| 3 (ancillary per pax per segment) | §3.3 item shape + §5.1 granularity (ancillary catalogue itself is the next design) |
| 5 (retrieve full order) | §5, §7 |
| 6 (decline, seats return) | §5.2 + §6 qty>1 proof |

## 11. Open / Residual Decisions

1. Candidate-offer TTL vs journey-offer TTL: same window, or shorter candidates? (Proposal: same; simplest; revisit if combine-time expiry annoys in rehearsal.)
2. Indicative-price drift UX: when the combined total ≠ Σ candidate prices (possible later with return fares), how the IBE messages it. Phase 2.0: totals are equal by BD-5, so a debug assertion only.
3. Per-direction fare-family display (Saver/Flex per direction) — not needed for the demo; affects Stage-1 result shaping only.

## 12. Out of Scope (deferred without guilt)

CHD/INF pricing · multi-city / open-jaw · group bookings (>9) · per-item cancellation in the IBE · true return fares & component proration · seat-map-aware ancillaries (next design) · mixed-cabin within a direction.

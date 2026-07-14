# Ancillaries Delta 0.2 — Thin Ancillaries (Seat + Bag)

**Status:** Draft for ratification · Companion to: DEMO_SCRIPT.md (Scene 3) · BREADTH_DELTA_0.2_MULTIPAX_RETURN.md · PAYMENTS_LLD_DELTA_0.2_REAL_PSP.md · BUILD_PLAN_PHASE_2_0_TRANCHE_1.md
**Base documents:** PSS_Offer_Module_LLD · PSS_Pricing_Module_LLD · PSS_Order_Module_LLD · PSS_Inventory_Module_LLD
**Demo decision honoured (ratified in DEMO_SCRIPT §6):** real seat map from Fleet/Cabin config, **flat pricing** — no zone pricing.

---

## 1. Scope and Non-Goals

Exactly what Scene 3 needs and nothing more: a **catalogue of two** ancillary products — `SEAT` and `BAG` — attachable per passenger per segment, priced flat from tenant configuration, materialised as order line items, paid in the same single payment, refunded in the same single refund, and visible on retrieve (Scene 5) with their own document references.

**Non-goals (deferred without guilt):** zone/dynamic seat pricing · bundles and fare-family inclusions · seat changes after confirmation · per-item cancellation · meals/Wi-Fi/lounge/insurance · third-party content · weight tiers for bags · seat-map-aware pricing (exit row premiums) · interline EMDs.

**Design invariants carried forward, unchanged:**
- **No new saga steps and no compensation changes.** Seats ride the existing hold lifecycle (§5); bags carry no inventory at all. If the build appears to need a saga change, stop and flag.
- **Money invariant** Σ item amounts = grandTotal holds for offers and orders including ancillary items.
- One payment, one refund per order — ancillaries do not split the money flow.
- Contract changes are **additive only** (one enum value, one optional field group, two new endpoints).

## 2. The Catalogue — Configuration, Not Code

Per-tenant configuration (the §7.3 config-over-code mechanism), versioned like other reference data:

```yaml
ancillaryCatalogue:
  - code: SEAT
    name: "Seat selection"
    attachment: PER_PAX_PER_SEGMENT
    requiresAttributes: [seatNumber]
    price: { amount: 350.00, currency: INR }
    tax: { code: GST, rate: 0.05 }
  - code: BAG
    name: "Checked bag 15kg"
    attachment: PER_PAX_PER_DIRECTION
    requiresAttributes: []
    price: { amount: 500.00, currency: INR }
    tax: { code: GST, rate: 0.05 }
```

- **Ownership:** the catalogue is *priced reference data*, so it lives with **Pricing** (same governance as filed fares), and is read by Offer via Pricing's API — never directly from config by other modules.
- `attachment` is the granularity rule the platform enforces: SEAT binds to exactly one (pax, segment); BAG binds to one (pax, direction) — on the nonstop demo route a direction is one segment, so both collapse to (pax, segment) at order level. The direction→segments expansion rule for multi-segment directions is recorded as Open Decision 3.
- Flat price per the ratified demo decision. The `price` block is deliberately shaped so zone pricing later becomes *more entries / a strategy behind the same shape*, not a remodel.

## 3. Pricing Module Delta

### 3.1 New operation: PriceAncillaries

`POST /v1/pricing/ancillaries/quote`

Request: `{ tenantId, currency, selections: [ { code, paxRef, segmentRefs[] , attributes? } ] }`
Response: an immutable **AncillaryQuote** — `{ quoteId, lines: [ { selectionRef, base, taxLines[], total } ], grandTotal, validUntil }`.

Rules:
- Pure function of catalogue version + selections; deterministic; golden-quote testable like fares.
- `validUntil` from the same TTL policy as PriceQuote (Open Decision 1 of the breadth delta applies identically: same window).
- Unknown code, attachment-rule violation, or missing required attribute → 422 with a typed error (`ANCILLARY_INVALID_SELECTION`).

### 3.2 What is deliberately not done

**The air quote is never re-derived.** Scene 3's "price updates per item" is pure addition: offer total = air PriceQuote.grandTotal + AncillaryQuote.grandTotal. This eliminates any possibility of air-price drift during ancillary shopping — the failure mode that would otherwise need drift-UX design now rather than later.

## 4. Offer Module Delta

### 4.1 New operation: AugmentOffer

`POST /v1/offers/{offerId}/ancillaries`

Request: the selections array (as §3.1). Behaviour:
1. Validate the base offer exists, is unexpired, and selections reference its passengers/segments.
2. Call Pricing `PriceAncillaries`.
3. Return a **new immutable offer** (new `offerId`, `supersedes: <old offerId>`): items = base items + one `ANCILLARY` OfferItem per selection (carrying `code`, `paxRef`, `segmentRefs`, `attributes` — incl. `seatNumber` for SEAT — `priceRef` into the AncillaryQuote, `amount`), `grandTotal` = sum, `validUntil` = min(air quote, ancillary quote).

Offers stay immutable and append-only — same philosophy as `CombineAndPrice`; this is the same staging pattern one step later. Re-augmenting (change seat, drop bag) = call again on the *base* journey offer with the new full selection set; the IBE always holds exactly one "current" offer. No server-side invalidation of superseded offers in this tranche (TTL handles them) — recorded as Open Decision 4.

`OfferItem.type` already carries `ANCILLARY` from the Phase-1 model (Offer LLD §3) — no contract change here at all.

### 4.2 Seat availability at shop time

The IBE needs to render which seats are takeable. Two reads, both existing patterns:
- **Seat map geometry:** Fleet/Cabin config read endpoint (`GET /v1/cabins/{flightId}/seatmap`) — master data, already exists per the System Design.
- **Seat occupancy:** new Inventory read (`GET /v1/inventory/flights/{flightId}/seats`) returning claimed/confirmed seat numbers, served from the same read-model machinery as availability. Eventually consistent is fine here — the *authoritative* uniqueness check happens at hold time (§5); a stale map costs the customer one polite "seat just taken, pick another" round-trip, never an oversell.

## 5. Inventory Module Delta — Seats Ride the Hold

The one genuinely new mechanic. **Principle: a seat claim is an extension of the existing hold, not a new lifecycle.**

- The hold request's lines gain an optional `seatClaims: [ { paxRef, seatNumber } ]` per flight line.
- **Atomicity:** seat claims succeed or fail *with the hold, in the same transaction* — exactly the multi-line atomicity invariant B5 is proving, extended to seat rows. A hold whose seat claim conflicts fails cleanly (422 `SEAT_UNAVAILABLE`, naming the seats); no partial holds, ever.
- **Uniqueness:** unique constraint on (flightId, seatNumber) over live claims + confirmations. Two competing holds wanting 12A → exactly one wins. Same shape as the no-oversell proof.
- **Lifecycle is inherited, which is the whole point:**
  - Hold TTL expires → seat claims expire with it.
  - Saga compensation releases the hold (Scene 6) → **seats return automatically**. No compensation change, and Scene 6 gets richer for free: the dashboard/seat-map can show 12A coming back.
  - Hold confirmed (payment settled) → claims become confirmed assignments.
- Bags: **no inventory interaction whatsoever.** A bag is a priced line item only.

This is why the saga doesn't change: Order already sends one hold with N lines; the lines just carry more detail.

## 6. Order Module Delta

### 6.1 Validation (extends the B4 matrix)

At CreateOrder against an augmented offer: every ANCILLARY item's `paxRef` must exist in the passenger list and its `segmentRefs` in the itinerary (`ANCILLARY_REF_MISMATCH`, sibling of `PASSENGERS_MISMATCH_OFFER`); SEAT items must carry `seatNumber`; money invariant now sums across AIR + ANCILLARY items and still aborts creation on mismatch.

### 6.2 Materialization

Items = (passengers × segments) AIR items **plus** one ANCILLARY item per offer ancillary item, carrying `code`, `paxRef`, `segmentRef(s)`, `attributes`, `amount`. Scene 3's line is literally a query: *the order knows it sold a bag to passenger 1 on the outbound.*

`OrderItem.type` gains `ANCILLARY` — **additive enum value**; `order-events.schema.json` extended accordingly and CI-validated (same regression gate as B4).

### 6.3 Hold construction

Unchanged shape: one hold, N flight lines; SEAT selections fold into the corresponding line's `seatClaims`. One round-trip to Inventory, as today.

### 6.4 Documents — EMD bridge

At fulfilment, the existing ticketing bridge extends mechanically: AIR items get ticket/coupon as in the breadth delta; each ANCILLARY item gets an **EMD number** (`documentRef = {emdNumber}`), associated to the passenger's ticket; the seat number is additionally written to the PNR bridge as the SSR-equivalent remark. Synthetic numbering in the demo tranche, same realism level as ticket numbers today. (EMD-A vs EMD-S typing realism: Open Decision 5.)

### 6.5 Money flow — unchanged

One PaymentIntent for the order grandTotal (air + ancillaries). Decline (Scene 6): hold release returns seats and inventory together. Voluntary cancel (Scene 7): all items → CANCELLED, one refund of the settled total — ancillaries refund inside it. **Zero Payments-module changes.**

## 7. IBE / BFF Delta

- After combined review (Scene 2's screen): **ancillaries step.** Seat map per direction rendered from Fleet/Cabin geometry + Inventory occupancy; tapping a seat assigns it to the selected passenger at the flat price; bag stepper per passenger per direction.
- Running total updates per selection — client-side addition of catalogue prices, then **ratified server-side** by AugmentOffer before checkout (the displayed and quoted totals must agree exactly; assertion in the e2e test).
- Checkout flows from the augmented offer; confirmation and retrieve (Scene 5) group items per passenger per direction with seat numbers and EMD references visible.
- BFF remains mapping-only: Shop → CombineAndPrice → **AugmentOffer** → CreateOrder → checkout.

## 8. Events

No new event types. `OrderCreated`/`OrderConfirmed`/`OrderCancelled` item arrays now carry ANCILLARY entries — payload growth only, schema extended additively (§6.2). The dashboard gets ancillary revenue and attach-rate as chart-able dimensions for free, which feeds straight into the dashboard design (next sitting).

## 9. Testing Strategy

1. **Pricing:** golden quotes for SEAT/BAG incl. GST lines; determinism; every 422 path of §3.1.
2. **Offer:** augment happy path; augment expired offer (410); invalid pax/segment refs; re-augment supersession; grandTotal invariant property-tested across augmented offers.
3. **Inventory:** seat-claim concurrency suite — two holds competing for 12A → exactly one wins, loser's *entire* hold fails clean; claim expiry on TTL; **release-restores-seat** on compensation; uniqueness across claim+confirmed states.
4. **Order:** ANCILLARY validation matrix; materialization (2×ADT return + 2 seats + 1 bag → 4 AIR + 3 ANCILLARY items, correct money); EMD/documentRef mapping; schema CI check with ancillary payloads.
5. **End-to-end (extends the C1 smoke to full Scene 1–7):** shop → combine → seats + bag → order → pay (Razorpay sandbox) → confirm: 7 items documented, seat shows occupied; decline variant returns *both seats and the hold* to availability; cancel refunds the full total including ancillaries.

## 10. Demo-Scene Traceability

| Scene | Delivered by |
| --- | --- |
| 3 (seat per pax outbound, bag for P1, price per item) | §2 catalogue, §3 quote, §4 augment, §7 UX |
| 5 (ancillaries visible on retrieve, documents) | §6.2, §6.4 |
| 6 (decline → seats return too) | §5 lifecycle inheritance |
| 7 (refund includes ancillaries) | §6.5 |

## 11. Build Milestones (Workstream D — slots into the build plan)

Sequenced after B3 (needs CombineAndPrice's offer staging) and independent of Workstream A until the smoke:

- **D1 — Pricing: catalogue + PriceAncillaries.** Gate: golden quotes + 422 matrix green.
- **D2 — Offer: AugmentOffer + supersession.** Gate: §9.2 green; one-way and return both augmentable.
- **D3 — Inventory: seat claims on holds + occupancy read.** Gate: §9.3 concurrency suite green. *Touches the hold path — merge after B5's qty>1 suite is green so regressions are attributable.*
- **D4 — Order: validation, materialization, EMD bridge.** *(Merge after B4.)* Gate: §9.4 green; schema CI check.
- **D5 — IBE: seat map + bag step.** Gate: e2e UI shop→augment→order (simulator PSP acceptable).
- **C1 amendment:** once D4+D5 land, the C1/C2 scripted smoke drops its "Scene 3 placeholder" and runs the full Scenes 1–7 path. C2's one-command seed gains the ancillary catalogue and seat-map config for the demo carrier.

**Stop-and-flag additions:** any saga or compensation change · seat lifecycle diverging from hold lifecycle · Razorpay or payment-shaped logic anywhere in this workstream · zone pricing sneaking in.

## 12. Open Decisions

1. **Seat selection on the return direction.** Script says outbound only; the capability is symmetric for free. Recommend: enable both in the IBE, script demos outbound — decide in rehearsal.
2. **Occupancy read staleness budget** for the seat map (it's eventually consistent) — ratify a target alongside the dashboard's ≤2s.
3. **PER_PAX_PER_DIRECTION expansion rule** for multi-segment directions (bag spans all segments of the direction vs. priced per segment) — moot for the nonstop demo; decide before multi-segment routes.
4. **Superseded-offer invalidation** — TTL-only now; revisit if double-order from a stale offer proves possible in rehearsal.
5. **EMD typing realism** (EMD-A associated vs. standalone) — synthetic now; decide with the NDC/interline work, not before.

## 13. Out of Scope (deferred without guilt)

Zone/exit-row pricing · seat changes post-confirmation · per-item cancellation · bundles & fare families · meals/Wi-Fi/lounge/insurance · infant lap-seat rules · paid seat on check-in (DCS) · ancillary-only orders.

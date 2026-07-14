# Phase 2.0 — Build Plan, Tranche 1 (Real PSP + Breadth)

**Status:** Ready for build · Companion to: PAYMENTS_LLD_DELTA_0.2_REAL_PSP.md · BREADTH_DELTA_0.2_MULTIPAX_RETURN.md · DEMO_SCRIPT.md
**How to use:** Drop this file and both deltas into the repo / Claude Code project context. Work strictly milestone by milestone; a milestone is done only when its gate is green on CI (contract, tests, telemetry, runbook — the standing definition of done). Do not start a milestone whose predecessor's gate is red.
**Excluded from this plan:** thin ancillaries and the live dashboard — designs pending; do not improvise them.

---

## Standing rules for this tranche

1. **Design governs.** Where code and delta disagree, stop and flag — do not reinterpret the design mid-build.
2. **Fitness rule (CI, from milestone A2 onward):** no Razorpay-specific symbol (event names, header names, API paths, paise conversion) outside `RazorpayProviderAdapter` and its webhook translator. Build the check, not just the habit.
3. **No saga step or compensation changes anywhere in Workstream B.** If B appears to need one, the build has drifted — stop and flag.
4. **Conformance suite is the swap contract.** Promote the simulator contract tests to a provider-agnostic suite in A1; every adapter must pass it unmodified.
5. **Branch/merge:** Workstreams A and B run in parallel branches. B4 (Order changes) merges only after A4 (saga pay-step) is on master — they touch the same module at different seams; sequence, don't interleave.

---

## Workstream A — Real PSP (Payments LLD Delta 0.2)

### A0 — Environment & access *(start immediately; no code dependency)*
Razorpay test-mode account; test API keys + webhook secret into the platform secret store (per tenant, per mode); public webhook ingress through the gateway to a stub endpoint; config entries (customer-clock margin 60s provisional, drainer cadence 5m provisional).
**Gate:** a Razorpay dashboard test webhook reaches the stub, raw-body HMAC-SHA256 signature verifies, event logged. Document the ingress in the runbook.

### A1 — Port semantics & state machine
PaymentIntent gains AWAITING_CUSTOMER and EXPIRED; transitions per delta §3; customer clock = holdTTL − margin; idempotent webhook-driven transitions. Reshape `PaymentProviderPort` to initiate/signal semantics; update the **simulator** to the new shape (it stays the unit-tier double). Promote conformance suite: settle · decline · ambiguous outcome · late settlement · idempotent refund · refund failure.
**Gate:** state-machine unit tests + conformance suite green on the simulator. No adapter yet.

### A2 — RazorpayProviderAdapter
Orders API (`receipt = paymentIntentId`), decimal↔paise conversion (property test), checkout session params to BFF, live `getPaymentStatus`, refund with deterministic key in notes/receipt + fetch-and-match duplicate guard (verify native idempotency in-build; the guard stands regardless).
**Gate:** conformance suite green against Razorpay sandbox (success@/failure@razorpay, test cards). Fitness rule active in CI.

### A3 — Webhook endpoint
Verify-first (raw body, constant-time), ack-fast/process-async, dedup on event id, subscribed events per delta §5, **late-money rule** (§6): terminal intent never resurrected; late settle → immediate idempotent refund + `late_settlement_total` metric + alert.
**Gate:** signature negative tests (tampered body, wrong secret, replayed event id); late-settlement-refund test; 2xx-fast SLA test under processing backlog.

### A4 — Saga pay-step + drainer
Order saga: pay step becomes bounded park (resume on settled/failed/expired; ambiguous arm resolves via live status before EXPIRED). REFUND_FAILED drainer as scheduled job (original key, bounded attempts, age SLO alert).
**Gate:** e2e on CI against sandbox: settle path confirms; decline path compensates and releases the hold; ambiguous path resolves correctly; drainer empties an induced REFUND_FAILED.

### A5 — IBE checkout + payments smoke
Checkout embed in the IBE via BFF; client callback as optimistic hint only; confirmation renders only on SETTLED (poll fallback).
**Gate:** scripted CI smoke of demo Scenes 4/6/7 (single-pax for now — breadth joins at C1): pay by UPI succeeds on screen; failure@razorpay declines and the seat returns; cancel refunds through the sandbox.

## Workstream B — Breadth (Breadth Delta 0.2) — parallel with A

### B1 — Pricing: N×ADT
FiledFareStrategy per-passenger × per-direction; perPassenger[] real; PTC rejection (`PTC_NOT_SUPPORTED`) and >9 cap at Pricing.
**Gate:** golden quotes (1×ADT one-way; 2×ADT one-way; 2×ADT return; 9×ADT return; per-segment vs per-ticket taxes); determinism across N.

### B2 — Offer: shopping context + Stage 1
tripType/returnDate/passengers[] in ShoppingContext; per-direction candidate assembly; new item shape (paxRef × segmentRef granularity); arithmetic invariant (Σ items = grandTotal) asserted at assembly, property-tested.
**Gate:** Stage-1 contract tests; invariant test; partial-failure behaviour preserved (a failed direction degrades, not errors).

### B3 — Offer: CombineAndPrice
`POST /v1/offers/combine`; one PriceItinerary across both directions; JOURNEY vs CANDIDATE staging; 422 `CANDIDATES_INCOMPATIBLE`, 410 expired, `OFFER_NOT_ORDERABLE` enforcement at Order.
**Gate:** combine happy path + every rejection path; one-way pass-through.

### B4 — Order: validation, materialization, bridge *(merge after A4)*
`PASSENGERS_MISMATCH_OFFER` validation matrix; positional paxRef recording; items = passengers × segments; money invariant aborts creation on mismatch; one hold, N lines, qty per line; ticket-per-passenger / coupon-per-segment documentRefs; PNR bridge N names × 2 segments. Event payloads validated against order-events.schema.json with N-pax data.
**Gate:** 2×ADT return → exactly 4 AIR items, correct per-item money; invariant abort path; bridge mapping tests; event-schema CI check.

### B5 — Inventory: qty>1 proof
No code change expected. Concurrency suite gains multi-line qty>1 variants; **atomicity across lines** (two competing 2-seat holds vs 3 seats → exactly one wins; loser gets clean 422; never a partial hold).
**Gate:** suite green. If code changes are needed to pass, flag before changing — that's a latent Phase-1 bug, and it gets its own fix record.

### B6 — IBE/BFF: two-stage flow
Search form (trip type, 1–9 adults, non-ADT greyed); outbound→return→combined review with per-passenger breakdown; N passenger forms mapped to P1…Pn; retrieve view grouped by passenger/direction. BFF stays mapping-only.
**Gate:** e2e UI test of shop→combine→order against real services (simulator PSP acceptable at this gate).

## Integration milestones

### C1 — Breadth × PSP join
The full path with both deltas live: 2-adult return, combine, order, pay on Razorpay sandbox; decline variant releases the full 2-seat hold on both flights.
**Gate:** scripted CI smoke = demo Scenes 1–7 minus ancillary add (Scene 3 placeholder) and dashboard (Scene 0/4 right-screen).

### C2 — Demo environment
One-command cold rebuild (reset + seed: demo carrier config, BLR–BOM return schedules/fares for the demo window); smoke runs green against it; cluster CPU-ceiling investigation from Phase 1 actioned or explicitly dispositioned in the runbook.
**Gate:** rebuild + smoke from scratch, unattended, twice in a row.

## Tagging
`v0.2.0-payments-psp` at A5 · `v0.2.0-breadth` at B6 · `v0.2.0-tranche1` at C2.

## Stop-and-flag list (things the build must not decide alone)
Saga/compensation changes · contract changes beyond the deltas' additive list · anything ancillary- or dashboard-shaped · weakening either money invariant · Razorpay symbols outside the adapter · B5 requiring code changes.

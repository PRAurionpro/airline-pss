# Phase 2.0 — Pilot Carrier Demo Script

**Status:** Working draft v0.1 — for prioritization sign-off
**Purpose:** The narrated demo sequence is the Phase 2.0 exit criterion. Everything in this script must work end-to-end on the demo environment; anything not in this script is deferred without guilt. (Same discipline as the M9 walking-skeleton acceptance, pointed at an audience instead of CI.)

---

## 1. Setting

- **Audience:** pilot carrier / prospect — commercial lead, IT lead, possibly ops.
- **Duration:** ~20 minutes of live system, inside a longer meeting. Every scene earns its seconds.
- **Tenant:** the pooled LCC/hybrid configuration from Phase 1, branded for the demo ("demo carrier" livery via config, not code — quietly proving config-over-code).
- **Market:** India domestic, INR. Route used throughout: **BLR → BOM return**.
- **Money:** real PSP **sandbox** (selection pending — first design of the tranche). No simulated PSP anywhere in the demo.
- **Two screens:** the IBE (customer view) and the live dashboard (carrier view), side by side. The dashboard reacting to the booking *as it happens* is the recurring beat of the whole demo.

---

## 2. The Script

### Scene 0 — The carrier view (30s)
Open on the dashboard: today's funnel (searches → offers → orders → paid), bookings count, revenue. It is live and currently quiet.
> *"This is your view. Everything you're about to watch me do as a customer will appear here in real time — because every state change in the platform is an event, and this dashboard is just one consumer of them."*

**Proves:** event backbone is real; first read-side consumer exists.

### Scene 1 — Shop (2 min)
On the IBE: search **BLR → BOM, return, 2 adults**, dates ~3 weeks out. Results show both directions with live availability and prices per fare option.
> *"Availability is served sub-second from a read model that's rebuilt from the same event stream — shopping load never touches the booking path."*

**Proves:** multi-pax, return-journey shopping; Inventory read model; Pricing filed fares + taxes/GST at pax-count.

### Scene 2 — Select & price the journey (1 min)
Select outbound and return flights. The two selections become **one offer, one price, one order to come** — not two bookings stapled together.

**Proves:** multi-segment offer construction; offer re-validated at order time (mention, don't show).

### Scene 3 — Make it a retail sale (3 min)
Add ancillaries: **a seat for each passenger on the outbound, one checked bag for passenger 1**. The price updates per item, per passenger, per segment.
> *"This is the difference between a reservation system and a retail platform — the order knows it sold a bag to passenger 1 on the outbound, and downstream that's what gets fulfilled and accounted."*

**Proves:** thin ancillaries (seat + bag); order line-items per pax/segment. This scene is the differentiator slide made real.

### Scene 4 — Pay with real rails (2 min)
Passenger details, then payment by **UPI through the PSP sandbox** — a checkout flow the room recognizes. Confirmation screen: order reference, e-ticket numbers, full breakdown.
Glance right: **the dashboard ticks** — one order, revenue up, funnel completed.

**Proves:** real PSP integration; book-pay-fulfil saga on real rails; e-ticket/PNR bridge; events → dashboard latency.

### Scene 5 — Retrieve & service (1 min)
Retrieve the booking by reference + name. Everything is there: passengers, segments, ancillaries, payment, documents.

**Proves:** the order as the single record of the sale.

### Scene 6 — The integrity scene (3 min)
Start a second booking, same flight — and **pay with the sandbox's decline instrument**. Payment is declined; the order does not exist as a sale; and on the dashboard / availability view, **the held seats return to inventory**.
> *"The platform never holds your inventory hostage to a failed payment, and never books without charging or charges without booking — even when the payment provider times out, we resolve the true outcome before deciding. This is the property that distinguishes a PSS you can trust."*

**Proves:** saga compensation, hold release, no-oversell invariant — the hardest engineering of Phase 1, shown in 30 seconds.

### Scene 7 — Money comes back (2 min)
Cancel the first booking (voluntary cancel). The **refund is issued through the PSP sandbox**; the dashboard shows the reversal; the order shows its lifecycle history.

**Proves:** idempotent refund path on real rails; order lifecycle/servicing; honest financial record.

### Scene 8 — Close on the roadmap (slides, not system)
NDC/B2B distribution, corporate booking, loyalty, DCS/day-of-departure: **roadmap slides**, positioned as the phased plan — breadth on a stabilized contract, operations after the commercial core is proven.

**Proves:** discipline. Nothing is faked on screen.

---

## 3. Behind the glass (running, not shown)

Mentioned only if the IT lead asks — but all must be true:
- Idempotency on every state-changing call; deterministic refund keys.
- Transactional outbox publishing every event the dashboard consumes.
- Ambiguous-outcome resolution (GetPaymentStatus) on the pay path.
- Tenant-scoped everything; the demo carrier is just configuration.
- PCI posture: no PAN ever enters the platform; tokenization at the PSP.

---

## 4. Script → build map

| Scene | Build item | Track |
| --- | --- | --- |
| 0, 4, 7 | Live dashboard (first read-side event consumers) | New (also Phase-2 Reporting & BI seed) |
| 1, 2 | Multi-pax + multi-segment across Offer/Pricing/Order/IBE | Skipped breadth |
| 3 | Thin ancillaries: seat + bag (catalogue of two) | Phase 2 retailing |
| 4, 6, 7 | Real PSP sandbox behind PaymentProviderPort; decline + refund flows | Real PSP + OPEN_ITEMS (drainer, methods scope) |
| 6 | Compensation visible end-to-end incl. availability restore on screen | Existing — needs UI surfacing only |
| All | Demo-reliability hardening: CPU ceiling investigated; scripted E2E smoke run in CI; demo env stands up from scratch | Hardening (demo-grade) |

## 5. Exit criteria (Phase 2.0 acceptance)

1. The full script (Scenes 0–7) runs end-to-end on the demo environment **without manual intervention or seeding mid-run**.
2. The same sequence runs as an automated smoke test in CI against real services + PSP sandbox.
3. Dashboard reflects each event within demo-credible latency (target: ≤ 2s, to ratify).
4. The decline and refund scenes work against the *real* sandbox's decline/refund mechanics, not simulator behaviour.
5. A cold demo-env rebuild (data reset + reseed) takes one command.

## 6. Decisions the script forces (open)

- **PSP selection** — sandbox quality, UPI support, recognizable checkout, decline-instrument support for Scene 6. *First design of the tranche.*
- **Infant/child pax types:** script says 2 adults. Adding an infant demos pax-type pricing but drags in fare-rule breadth. **Recommend: defer — 2 adults only.** Revisit only if the prospect is known to care.
- **Seat ancillary depth:** full seat-map UX vs. simple paid-seat selection. **Recommend: real seat map from Fleet/Cabin config** (it exists as master data) but flat pricing — no zone pricing yet.
- **Scene 6 staging:** show availability restore on the dashboard vs. re-searching in the IBE. Dashboard is slicker; IBE re-search is more visceral. Decide in rehearsal.
- **Voluntary cancel policy:** Scene 7 needs a cancel-with-refund rule. Simplest credible rule (full refund within demo window) — config, not code.

## 7. Explicitly out (deferred without guilt)

NDC endpoints · B2B agent portal · Corporate/SME · FFP/loyalty · WhatsApp · GDS adapters · multi-currency · dynamic pricing · DCS/baggage/W&B · reconciliation engine (hooks only) · load/chaos testing beyond demo reliability.

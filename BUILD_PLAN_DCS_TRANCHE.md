# Phase 3.0 (preview) — Build Plan, DCS Tranche (Thin Check-in → Boarding)

**Status:** Draft for ratification · Companion to: DCS_DELTA_0_3_CHECKIN_BOARDING.md
**Base documents:** PSS_System_Design_Document (§5.2 Delivery/DCS, §11 NFRs) · PSS_Order_Module_LLD · PSS_Inventory_Module_LLD
**How to use:** Drop this file and the DCS delta into the repo / Claude Code project context. Work strictly milestone by milestone; a milestone is done only when its gate is green on CI (contract, tests, telemetry, runbook — the standing definition of done). Do not start a milestone whose predecessor's gate is red.
**What this is:** the **thin walking-skeleton slice** of the Phase 3 Delivery/DCS module — the smallest end-to-end day-of-departure path, with Weight & Balance and government data **stubbed behind ports** (the Tranche 1 PaymentProviderPort pattern). It is a Phase 3 module brought forward to prove the Offer→Order→**Deliver** loop; it is **not** an airport-deployable DCS.
**Excluded from this plan:** real Weight & Balance, real government-data/PII transmission, real baggage tracking, biometric/admissibility, IROPS, standby/upgrade-at-gate — designs deferred to later DCS tranches; do not improvise them.

---

## Standing rules for this tranche

1. **Design governs.** Where code and the DCS delta disagree, stop and flag — do not reinterpret the design mid-build.
2. **DCS is a new downstream module, not a change to Order or Inventory.** It consumes their events/reads and publishes its own. **No new Order saga step, no compensation change, no Inventory write call, no Inventory code change.** If any appears necessary, the build has drifted — stop and flag (and if it's a genuine Phase-1 gap, it gets its own fix record, the B5 rule).
3. **Two fitness rules (CI, from G1 onward):** no Weight-&-Balance-vendor symbol outside `WeightBalanceAdapter`/translator; no gov-data-vendor symbol outside `GovDataAdapter`/translator. Build the checks, not just the habits.
4. **Conformance suites are the swap contracts.** `WeightBalancePort` and `GovDataPort` each get a provider-agnostic conformance suite in G1; the real adapters (future tranches) must pass them unmodified.
5. **The seat-ownership split is law.** Inventory counts seats; DCS assigns the physical seat. Physical seat assignment must never touch Inventory's write path; the Tranche 1 paid seat *claim* enters DCS only as a preference input, never as the assignment.
6. **No PII leaves the platform.** Gov-data is stubbed; the stub transmits nothing. Any real passenger-document transmission is out of scope and a stop-and-flag.
7. **Highest availability tier discipline.** Build to strong consistency for seat and boarding state even though the demo runs single-node — this is a flight-grounding path (SDD §11).

## Milestones and gates

Milestones and gates are verbatim from DCS delta §9.

### G0 — Service skeleton + departure store + OpenFlight *(start immediately; depends only on Fleet/Cabin cabin-map read)*
Tenant-scoped `dcs-service`; per-flight departure store; `FlightDeparture` state machine (`SCHEDULED → OPEN_FOR_CHECKIN → BOARDING → CLOSED`); transactional outbox wired but publishing nothing yet; `OpenFlight` seeds a departure from the demo flight's cabin map.
**Gate:** OpenFlight seeds the BLR→BOM demo flight's departure from its Fleet/Cabin geometry; FlightDeparture state-machine unit tests; outbox smoke (a no-op event round-trips through the backbone). Runbook: service ingress + departure store documented.

### G1 — Ports + conformance suites (simulators only) *(parallel with G0; no code dependency on G0)*
`WeightBalancePort` (`requestLoadAck`, `finalLoadsheet`) and `GovDataPort` (`checkAdmissibility`, `submitManifest`) semantics; both simulators (READY/NOT_READY, CLEARED/REFUSED instruments); both conformance suites.
**Gate:** both conformance suites green on simulators — READY path, NOT_READY blocks close, idempotent re-ack, loadsheet-before-close ordering (W&B); CLEARED gate, REFUSED blocks check-in, manifest-accept idempotency (gov-data). **Both fitness rules active in CI.** No adapter yet.

### G2 — Check-in + seat assignment (the core invariant) *(after G0 + G1)*
`CheckIn` (validates order CONFIRMED + segment match; calls GovDataPort stub for admissibility); `AssignSeat` honouring `PAID_CLAIM` then `AGENT`/`AUTO` against free occupancy; the one-live-`SeatAssignment`-per-(departureId, seatNumber) invariant under concurrency.
**Gate:** two agents racing 12A on the same flight → exactly one wins, loser gets a clean 409 `SEAT_TAKEN`, **never a double-assigned seat**; paid-claim-honoured test (the Scene 3 seat is the seat assigned); `SEAT_NOT_ON_MAP` + `ORDER_NOT_CHECKINABLE` matrix; `GOVDATA_REFUSED` blocks check-in. Publishes `PassengerCheckedIn`.

### G3 — Boarding pass + board + bag accept *(after G2)*
`IssueBoardingPass` (sequence number; stub barcode ref); `BoardPassenger`; `AcceptBag` (calls WeightBalancePort stub; records against the Tranche 1 bag claim if one was sold).
**Gate:** pass-requires-assigned-seat; board-requires-BOARDING-state + issued pass; `ALREADY_BOARDED` idempotency; bag-accept ties to the sold bag ancillary. Publishes `BoardingPassIssued`, `BagAccepted`, `PassengerBoarded`.

### G4 — CloseFlight + the Order seam lights up *(after G3) — HEADLINE GATE*
`CloseFlight` (WeightBalancePort final-ack gate; `WB_NOT_READY` negative path; publishes `FlightClosed` exactly once); **Order consumer wiring verified end-to-end** — the existing, never-yet-exercised Order consumer of `PassengerBoarded`/`FlightClosed` advances `ServiceDelivery` and drives the order to FULFILLED.
**Gate:** full single-pax path green on CI against real services (W&B + gov-data stubbed): confirmed order → checked-in → seat assigned → boarding pass → boarded → flight closed → **Order reaches FULFILLED for the first time in the platform's history**; close-once idempotency; schema CI for all five delivery events. If Order needs logic beyond LLD §6.3 to make this work → **stop and flag** (Phase-1 delivery-seam gap, own fix record).

### G5 — Multi-pax return + thin agent view + scripted smoke *(after G4)*
The Tranche 1 2×ADT BLR→BOM return order flows through DCS on both segments; a thin agent view (API + minimal UI) drives the path; scripted CI smoke of the new departure scene.
**Gate:** 2×ADT return → both pax checked in, **paid seats honoured on the outbound**, both boarded, flight closed, both orders' delivery lines resolved to FULFILLED; agent-view e2e; smoke green **unattended**.

## Tagging
`v0.3.0-dcs-thin` at G5.

## Dependency picture

```
G0 (skeleton + OpenFlight) ─┐
                            ├→ G2 (check-in + seat invariant) → G3 (pass/board/bag) → G4 (close + Order seam) → G5 (multi-pax + agent view + smoke)
G1 (ports + conformance) ───┘
```
G0 and G1 parallelise (G1 has no dependency on G0). Everything from G2 onward is strictly sequential — each gate gates the next.

## Integration with the rest of the platform

- **Upstream, read-only:** Order (confirmed-order read), Inventory occupancy (read; Open Decision 2 on source), Fleet/Cabin (cabin map). **No writes to any of them.**
- **Downstream:** the five delivery events land on the existing backbone. Order's already-specified consumer is the only mandatory subscriber this tranche. The live dashboard *may* add a "flown" beat as a further consumer (DCS delta §8) — **recommended as a follow-on milestone, not part of this tranche**, and bound by the existing dashboard stop-and-flag rules if added.
- **Demo:** makes roadmap Scene 8's DCS line real enough to show as a new live scene (future DEMO_SCRIPT revision), honouring the discipline that nothing on screen is faked.

## Stop-and-flag list (things the build must not decide alone)

Order saga/compensation change · any Inventory write or code change · real W&B integration · real gov-data or any PII transmission · physical seat assignment touching Inventory, or seat claims entering DCS as anything but a preference · new events beyond the five SDD-fixed delivery events · weakening the one-seat-per-(departure,seat) invariant · vendor symbols outside the two adapters · biometric/admissibility real logic · revenue-recognition hand-off beyond a hook.

## Open decisions carried (build must not resolve these alone)

From the DCS delta §11: auto-assign policy (recommend AUTO forward-fill + agent override) · occupancy source a/b (recommend start b) · boarding-sequence per-flight vs per-cabin (recommend per-flight) · W&B close-gate hard-block vs warn (recommend hard-block) · gov-data refusal as demo beat vs CI-only (rehearsal) · dashboard "flown" beat timing (recommend follow-on) · revenue-recognition trigger timing (hook only; ratify with Order Accounting tranche).

## Pre-build checklist (ratify before G2 starts; G0/G1 may begin in parallel with ratification)

1. Confirm the four recommendations most likely to shape contracts: auto-assign policy, occupancy source, boarding-sequence scope, W&B close-gate strictness.
2. Confirm the privacy boundary statement with whoever owns SDD §10 compliance: **no PII transmitted this tranche** — the gov-data stub sends nothing.
3. Confirm the demo intent: is the new departure scene in-scope for the next prospect demo, or is this tranche a pure architecture-proof? (Changes whether G5's agent view is polish-grade or skeleton-grade.)

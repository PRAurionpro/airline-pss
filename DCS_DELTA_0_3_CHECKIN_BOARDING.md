# DCS Delta 0.3 — Thin Delivery / Departure Control (Check-in → Boarding)

**Status:** Draft for ratification · Companion to: DEMO_SCRIPT.md (roadmap Scene 8 → made real) · BUILD_PLAN_PHASE_2_0_TRANCHE_1.md · BUILD_PLAN_TRANCHE_1_ADDENDUM_1.md
**Base documents:** PSS_System_Design_Document (§5.2 Delivery/DCS) · PSS_Order_Module_LLD · PSS_Inventory_Module_LLD
**Phase note:** DCS is a **Phase 3** module in the SDD catalogue. This delta builds the *thin walking-skeleton slice only*, in the same spirit as the M9 walking skeleton and the Tranche 1 PSP slice: prove the seam end-to-end on real services, defer the heavy integrations behind ports.
**Recommendation honoured (this delta's premise):** Weight & Balance and government-data capture are **stubbed behind ports with conformance suites**, not really integrated. Real W&B and real gov-data are explicitly later tranches.

---

## 1. Scope and Non-Goals

Exactly the smallest day-of-departure path that proves DCS can stand as a module and light up the dormant delivery seam already designed into Order: **check-in a confirmed order → assign a physical seat → issue a boarding pass → board the passenger → close the flight**, single-pax first, then the multi-pax return order that Tranche 1 already produces. The slice emits the five DCS events the Order LLD already declares it consumes (`PassengerCheckedIn`, `BoardingPassIssued`, `BagAccepted`, `PassengerBoarded`, `FlightClosed`) so that Order's `ServiceDelivery` advances `PENDING → DELIVERED` and the order moves `CONFIRMED → IN_DELIVERY → FULFILLED` for the first time in the platform's life.

**This is the differentiator made operational:** the same Order that was *sold* in Tranche 1 is now *consumed* at the gate, closing the OOSD loop (Offer → Order → **Deliver**).

**Non-goals (deferred without guilt):**
real Weight & Balance load planning · real government data (API/PNR/PNRGOV) transmission · biometric/admissibility checks (IATA RP 1701p) · real baggage acceptance/tracking/reconciliation and airport-systems integration · standby/upgrade/oversell-at-gate resolution · IROPS/disruption re-accommodation · interline/through-check-in · offload and gate-reassignment · multi-flight itineraries through a single check-in · self-service/kiosk/mobile boarding-pass channels (the slice is API + a thin agent view) · DCS-side seat *changes* after assignment · revenue-recognition hand-off to Order Accounting (P3, hooks only).

**Design invariants carried forward, unchanged:**
- **No new Order saga steps and no compensation changes.** DCS is a *new downstream module*; Order reacts to its events through the existing, already-specified consumer. If the build appears to need an Order saga change, stop and flag — the build has drifted.
- **The seat-ownership split is law (Inventory LLD §2.2).** Inventory *counts* seats; **DCS assigns the specific seat (12A)**. DCS never decrements sellable counts and never creates holds. It reads occupancy and writes a `SeatAssignment` in its own per-flight store.
- **No oversell invariant untouched.** DCS operates on already-confirmed orders against an already-closed sale; it adds no path that could oversell.
- **Highest availability tier (SDD §11):** check-in/DCS is a flight-grounding path — ≥99.95% target, strong consistency for seat and boarding state. The slice is built to that consistency model even though the demo runs single-node.
- Contract changes to existing modules are **additive only** (Order and Inventory gain *no* new write APIs from this delta; DCS is net-new surface).

## 2. Module Posture — New Bounded Context, Net-New Surface

DCS is a new service (`dcs-service` / `delivery-service`), tenant-scoped like every other module (§7), with its own per-flight departure store (SDD §5.2: *"Departure store, per-flight"*). It is a **consumer of Order and Inventory**, not a caller into their write paths:

- **Reads** Order (the confirmed order: passengers, segments, ancillary seat *claims* from Tranche 1, documents) and Inventory occupancy (which physical seats on the cabin map are already taken).
- **Writes** only its own entities (`CheckIn`, `SeatAssignment`, `BoardingPass`, `RegulatoryDocument`, `FlightDeparture`) — verbatim the entity set fixed in SDD §5.2.
- **Publishes** the five delivery events onto the existing event backbone via the same transactional-outbox pattern every other module uses (SDD §6.3).
- **Stubs behind ports:** `WeightBalancePort` and `GovDataPort` (§5) — the *exact* PaymentProviderPort pattern that Tranche 1 proved.

**Crucial distinction from Tranche 1 ancillaries.** Tranche 1 sold a *seat claim against a hold* (Ancillaries Delta §1: seats ride the hold lifecycle). That claim says "this passenger is entitled to a seat"; it does **not** name 12A. DCS is where the physical seat is named, at check-in, against the cabin geometry from Fleet/Cabin config and live occupancy. The ancillary seat claim becomes a **constraint/preference input** to assignment (if you paid for a seat, DCS honours it), not the assignment itself. This is why the two never collide and why no Tranche 1 contract changes.

## 3. The Departure Domain — Entities and States

Per-flight departure store. Entities exactly as SDD §5.2 fixes them.

```
FlightDeparture   { departureId, tenantId, flightRef, cabinMapRef (→ Fleet/Cabin),
                    state, openedAt, closedAt }
CheckIn           { checkInId, departureId, orderRef, paxRef, segmentRef,
                    state, checkedInAt }
SeatAssignment    { assignmentId, checkInId, seatNumber, source {PAID_CLAIM│AGENT│AUTO} }
BoardingPass      { boardingPassId, checkInId, seatNumber, sequenceNumber,
                    barcodeRef, issuedAt }
RegulatoryDocument{ regDocId, checkInId, kind {APIS_STUB}, status, capturedAt }   # stub in this slice
```

**FlightDeparture state machine** (strong consistency):
```
SCHEDULED → OPEN_FOR_CHECKIN → BOARDING → CLOSED
                                   └────────→ CLOSED (no-board path)
```
- `OPEN_FOR_CHECKIN`: check-in accepted; seats assignable.
- `BOARDING`: boarding passes scannable; `PassengerBoarded` events flow.
- `CLOSED`: no further check-in/board; `FlightClosed` published exactly once; this is the trigger Order consumes toward FULFILLED.

**CheckIn state machine:**
```
PENDING → CHECKED_IN → BOARDED
              └──────→ CANCELLED (agent reverse, before boarding only)
```

**The core DCS invariant:** at most one live `SeatAssignment` per (departureId, seatNumber). Two agents assigning 12A on the same flight → exactly one wins, the loser gets a clean typed conflict, never a double-assigned seat. (This is the DCS analogue of Inventory's no-double-hold invariant, and it gets the same concurrency-suite treatment — §7.)

## 4. API Contract (DCS, net-new)

Conventions follow the house style (idempotency key on every state-changing call; tenant-scoped; typed errors). Primary APIs exactly as SDD §5.2 fixes them: `CheckIn · AssignSeat · IssueBoardingPass · AcceptBag · BoardPassenger · CloseFlight`.

| Operation | Method / path | Notes |
| --- | --- | --- |
| OpenFlight | `POST /v1/departures` | Seeds a FlightDeparture from a flightRef + cabinMapRef; idempotent on flightRef. |
| CheckIn | `POST /v1/departures/{id}/checkins` | Validates the order is CONFIRMED and the segment matches; calls **GovDataPort** (stub) for admissibility; → CHECKED_IN. |
| AssignSeat | `POST /v1/checkins/{id}/seat` | Honours a PAID_CLAIM if present; else AGENT/AUTO from free occupancy; enforces the one-seat invariant; 409 `SEAT_TAKEN`. |
| IssueBoardingPass | `POST /v1/checkins/{id}/boardingpass` | Requires an assigned seat; allocates sequence number; barcode is a stub ref in this slice. |
| AcceptBag | `POST /v1/checkins/{id}/bags` | **Thin**: records a BagAccepted against the Tranche 1 bag ancillary if one was sold; **calls WeightBalancePort (stub)**; no real tracking. |
| BoardPassenger | `POST /v1/checkins/{id}/board` | Requires BOARDING state + issued pass; → BOARDED; publishes PassengerBoarded. |
| CloseFlight | `POST /v1/departures/{id}/close` | **Calls WeightBalancePort (stub) for a final-load acknowledgement** before allowing close; → CLOSED; publishes FlightClosed once. |
| GetDeparture | `GET /v1/departures/{id}` | Read model: roster, seat map occupancy, boarding progress (also feeds the agent view and, later, the dashboard). |

**Error model (typed):** `ORDER_NOT_CHECKINABLE` (not CONFIRMED / wrong segment / already flown), `SEAT_TAKEN` (409, the invariant), `SEAT_NOT_ON_MAP`, `FLIGHT_NOT_OPEN`, `ALREADY_BOARDED`, `WB_NOT_READY` (close blocked by the W&B stub's negative path), `GOVDATA_REFUSED` (admissibility stub negative path). Every one of these is conformance-tested against both stub and (later) real adapters.

## 5. The Two Ports — Stubbed, with Conformance Suites (the Tranche 1 pattern)

This is the heart of the recommendation and the reason the slice is one tranche, not three.

### 5.1 WeightBalancePort
Semantics, not vendor: `requestLoadAck(departureId, manifestSummary) → READY │ NOT_READY(reason)` and `finalLoadsheet(departureId) → acknowledged`. The slice ships a **simulator** that returns READY for the demo flight and exposes a NOT_READY instrument for the negative test. **A conformance suite** (READY path, NOT_READY blocks close, idempotent re-ack, loadsheet-before-close ordering) is the swap contract — exactly as the PaymentProviderPort conformance suite was. Real W&B (certification-grade) slots behind this suite in a later tranche, unmodified.

**Fitness rule (CI):** no W&B-vendor-specific symbol outside `WeightBalanceAdapter` and its translator. Build the check, not the habit. (Mirrors the Razorpay fitness rule.)

### 5.2 GovDataPort
Semantics: `checkAdmissibility(paxRef, segmentRef, docs?) → CLEARED │ REFUSED(reason)` and `submitManifest(departureId) → accepted` (stubbed; **no real API/PNRGOV transmission, no PII leaves the platform in this slice**). Simulator clears the demo pax and exposes a REFUSED instrument for the negative test. Conformance suite: CLEARED gate, REFUSED blocks check-in, manifest-accept idempotency.

**Privacy note (must hold even with the stub):** the port is shaped so that *when* real gov-data lands, the transmission is the adapter's concern and is governed under the SDD §10 privacy/compliance posture. The stub transmits nothing. This boundary is a stop-and-flag item: no real passenger document data flows anywhere in this tranche.

## 6. What Lights Up on the Order Side — Zero Order Code Beyond Wiring

The Order LLD already specifies (verbatim, §6.3): it consumes `DeliveryConfirmed / PassengerBoarded / FlightClosed` to *"advance ServiceDelivery; move toward IN_DELIVERY/FULFILLED."* And `ServiceDelivery` already exists as an entity (§3.2) with state `PENDING│DELIVERED│FAILED`. **Nothing has ever emitted these events.** This delta makes DCS the emitter. The Order-side work is therefore *consumer wiring and verification*, not new design:

- Map `PassengerCheckedIn` → ServiceDelivery stays PENDING (in progress), order may advance to IN_DELIVERY.
- Map `PassengerBoarded` → the air ServiceDelivery line moves toward DELIVERED.
- Map `FlightClosed` → all delivery lines for that flight resolve; order → FULFILLED when every item is delivered.

If wiring this reveals that the Order consumer needs *new* logic beyond what §6.3 describes, that is a **stop-and-flag**: it means the Phase-1 Order design under-specified the delivery seam, and it gets its own fix record (the same discipline as a latent Inventory bug in B5).

## 7. Inventory Interaction — Read-Only Occupancy

DCS reads occupancy to know which seats are free for assignment. Per Inventory LLD §2.2, **Inventory does not own physical seat assignment** — so DCS does not call any Inventory write API. The occupancy read is either (a) Inventory's existing availability read model surfaced at seat granularity, or (b) derived from the order's seat claims plus the cabin map. **Open Decision 2** picks which; the slice can start with (b) (self-contained) and move to (a) when seat-level occupancy is needed beyond a single demo flight. No Inventory code change is expected. If one is needed, stop and flag (latent gap, own fix record — the B5 rule).

## 8. Demo Surfacing — One New Scene, Optional Dashboard Beat

The thin slice makes **roadmap Scene 8's "DCS/day-of-departure" real enough to show** as a new live scene (call it Scene 9 in a future demo script revision), not a slide:

> Retrieve the order sold in Scene 4. At a thin agent view: check the passenger in, watch 12A get assigned (the seat they *paid* for in Scene 3 is honoured), issue the boarding pass, board, close the flight. Glance right: the dashboard's funnel could gain a *flown* beat as `FlightClosed` lands.

The dashboard tie-in is **optional and additive** — if included, it obeys the existing dashboard stop-and-flag rules (no new invented events beyond the delivery events DCS already publishes; the dashboard is just another consumer). Recommend deferring the dashboard beat to a follow-on milestone so the DCS tranche stays about DCS.

## 9. Milestones and Gates (authoritative — the build plan references these verbatim)

A milestone is done only when its gate is green on CI (contract, tests, telemetry, runbook — the standing definition of done). Do not start a milestone whose predecessor's gate is red.

- **G0 — Service skeleton + departure store + OpenFlight.** Tenant-scoped `dcs-service`, per-flight store, FlightDeparture state machine, transactional outbox wired (publishes nothing yet). **Gate:** OpenFlight seeds a departure from the demo flight's Fleet/Cabin cabin map; state-machine unit tests; outbox smoke (a no-op event round-trips). Runbook: service ingress + store documented.
- **G1 — Ports + conformance suites (simulators only).** `WeightBalancePort` + `GovDataPort` semantics, both simulators, both conformance suites green. Fitness rules active in CI (no vendor symbols outside adapters/translators). **Gate:** both conformance suites green on simulators; READY/NOT_READY and CLEARED/REFUSED negative paths proven; no adapter yet.
- **G2 — Check-in + seat assignment (the core invariant).** CheckIn (calls GovDataPort stub), AssignSeat honouring PAID_CLAIM then AGENT/AUTO, the one-seat-per-(departure,seat) invariant under concurrency. **Gate:** two agents racing 12A → exactly one wins, loser gets clean 409 `SEAT_TAKEN`, never a double assignment; paid-claim-honoured test; `SEAT_NOT_ON_MAP`/`ORDER_NOT_CHECKINABLE` matrix. Publishes `PassengerCheckedIn`.
- **G3 — Boarding pass + board + bag accept.** IssueBoardingPass (sequence numbers, stub barcode), BoardPassenger, AcceptBag (calls WeightBalancePort stub, records against Tranche 1 bag claim). **Gate:** pass-requires-seat; board-requires-BOARDING-state + issued pass; `ALREADY_BOARDED` idempotency; bag-accept ties to the sold bag. Publishes `BoardingPassIssued`, `BagAccepted`, `PassengerBoarded`.
- **G4 — CloseFlight + Order seam lights up.** CloseFlight (W&B stub final-ack gate, `WB_NOT_READY` negative path), `FlightClosed` published exactly once, **Order consumer wiring verified end-to-end**: a confirmed order → checked-in → boarded → FlightClosed → Order's ServiceDelivery resolves and the order reaches FULFILLED. **Gate:** the full single-pax path green on CI against real services (stubs for W&B/gov-data); Order reaches FULFILLED for the first time; close-once idempotency; schema CI for all five delivery events. **This is the tranche's headline gate.**
- **G5 — Multi-pax return + thin agent view + scripted smoke.** The Tranche 1 2×ADT return order flows through DCS end-to-end on both segments; a thin agent view (API + minimal UI) drives it; scripted CI smoke of the new departure scene. **Gate:** 2×ADT return → both pax checked in, paid seats honoured on the outbound, both boarded, flight closed, both orders' delivery lines resolved; agent-view e2e; smoke green unattended.

**Tag:** `v0.3.0-dcs-thin` at G5.

## 10. Stop-and-Flag List (things this build must not decide alone)

Any Order saga or compensation change · any Inventory write call or Inventory code change · any real W&B integration or real gov-data/PII transmission (stubs only this tranche) · physical seat assignment leaking into Inventory or seat *claims* leaking into DCS as anything but a preference input · new events invented beyond the five SDD-fixed delivery events · weakening the one-seat-per-(departure,seat) invariant · vendor symbols outside the two adapters · biometric/admissibility real logic · revenue-recognition hand-off (hooks only).

## 11. Open Decisions (carried — ratify before or during build, not by the build)

1. **Auto-assign policy** when no paid claim exists — purely free-seat AUTO vs agent-driven only for the slice. *Recommend: AUTO with a simple forward-fill, agent override available.*
2. **Occupancy source** — Inventory seat-level read model (a) vs order-claim-derived (b). *Recommend: start (b), move to (a) when needed beyond one flight.*
3. **Boarding sequence semantics** — strict monotonic per flight vs per cabin. *Recommend: per flight for the slice.*
4. **W&B close-gate strictness** — does a NOT_READY hard-block close in the demo, or warn? *Recommend: hard-block, to prove the gate is real (it's safety-critical in production).*
5. **Gov-data refusal staging** — show `GOVDATA_REFUSED` as a demo beat or keep it CI-only. *Rehearsal decision.*
6. **Dashboard "flown" beat** — in this tranche or a follow-on. *Recommend: follow-on, keep DCS tranche pure.*
7. **Revenue-recognition trigger** — `FlightClosed` is the SDD's "consumption" point; wiring to Order Accounting is P3. *Carry as a hook only; ratify timing with the Order Accounting tranche.*

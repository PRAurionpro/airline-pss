# Paper 6 — Phase 3 tranche candidates

**Candidate briefs, not a plan.** One section per candidate from `PHASE2_CLOSURE_INPUTS.md` §4:
what already exists to build on, the prerequisite decisions from Papers 1–5, and the thin-slice
shape **only as the roadmap already describes it** — no new design, no ranking, no recommendation.
Selection and sequencing are the deciders' (empty block at the end).

---

## DCS deepening

- **What exists:** dcs-service ships a thin flow — open/close flight, check-in, seat, boarding
  pass, bag, board — now contracted in `dcs-api_openapi.yaml`; publishes 5 events
  (`dcs-events_schema.json`). `WeightBalancePort` / `GovDataPort` exist as simulators.
- **Prerequisite decisions:** Paper 3 Pattern 2 (error format) and Pattern 3 (Idempotency-Key)
  both live in dcs and would naturally settle with this tranche. ADR-0002 if deepened DCS needs to
  name a specific flight-class beyond `departureId`.
- **Thin-slice shape (as roadmap describes):** extend the existing departure-control surface;
  no new shape invented here.

## Baggage

- **What exists:** DCS `POST /v1/checkins/{checkInId}/bags` (AcceptBag) + `BagAccepted` event;
  `WeightBalancePort.BagInfo` carries `totalBags`. No standalone baggage service.
- **Prerequisite decisions:** none hard from Papers 1–5; would consume DCS's bag surface, so it is
  adjacent to DCS deepening (Pattern 2/3 apply if it reuses dcs endpoints).
- **Thin-slice shape (as roadmap describes):** build on the existing bag-acceptance seam; no new
  design added here.

## IROPS (irregular operations)

- **What exists:** no dedicated IROPS code. Adjacent seed material: DCS flight lifecycle
  (`open`/`close`) + `FlightClosed` event; inventory hold/release lifecycle.
- **Prerequisite decisions:** **ADR-0002** — IROPS operates per flight/departure and would reuse
  whatever cross-service flight-class identity is chosen.
- **Thin-slice shape (as roadmap describes):** greenfield against the existing flight lifecycle;
  no new design added here.

## Weight & Balance (W&B)

- **What exists:** DCS `WeightBalancePort` interface + `WeightBalanceSimulator` stub; the
  `WbNotReady` state already gates check-in/boarding until W&B is ready.
- **Prerequisite decisions:** **ADR-0002** — W&B is per-flight and reuses the flight-class /
  flight identity chosen there.
- **Thin-slice shape (as roadmap describes):** replace the W&B simulator behind the existing port;
  no new design added here.

## Cargo

- **What exists:** nothing — no cargo code, service, or contract in the repo.
- **Prerequisite decisions:** none identified in Papers 1–5 (greenfield); ADR-0002 would apply if
  cargo attaches to specific flights.
- **Thin-slice shape (as roadmap describes):** greenfield; no new design added here.

## Settlement & revenue-accounting

- **What exists:** reporting-service read-side (`order_fact` derived from OrderConfirmed /
  RefundIssued); RM forecast/optimisation read-sides; payments-service refund / late-money
  reconcilers. No settlement ledger or rev-accounting store exists.
- **Prerequisite decisions:** none hard from Papers 1–5; money figures must derive from
  OrderConfirmed / RefundIssued only (standing platform rule), which the existing read-sides honour.
- **Thin-slice shape (as roadmap describes):** build a settlement/accounting read-model on the
  existing money-event backbone; no new design added here.

---

## Cross-candidate prerequisite map (fact summary)

| Candidate | Hard prerequisite from Papers 1–5 |
|---|---|
| DCS deepening | Paper 3 Patterns 2 & 3 (ride-along); ADR-0002 (if naming flight-class) |
| Baggage | — (adjacent to DCS) |
| IROPS | **ADR-0002** |
| W&B | **ADR-0002** |
| Cargo | — (ADR-0002 if flight-attached) |
| Settlement & rev-accounting | — (money-event derivation rule already satisfied) |

## DECISION

<!-- EMPTY — tranche selection and sequencing are the deciders'. This paper does not rank or
     recommend. Record chosen Phase-3 tranche(s) and order here, with prerequisites resolved. -->

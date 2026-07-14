# Paper 4 — Phase 2 scope reconciliation sign-off sheet

**Ratification sheet.** The factual status of each Phase-2 roadmap item (from
`PHASE2_CLOSURE_INPUTS.md` §1) with a **per-row empty decision cell**. No cell is pre-filled;
no row is ranked.

**Decision vocabulary for the "Ratify" column** (deciders pick one per row):
`DONE (thin accepted)` · `DEFER to Phase N` · `BACKLOG (no phase)` · `DO in Phase 3`.

## Roadmap §9 deliverables

| Roadmap §9 item | Factual status | Thinness / gap (fact) | Evidence | Ratify (empty) |
|---|---|---|---|---|
| Offer management | built-full | — | `v0.1.0-offer`, `v0.2.0-breadth-b6`, `v0.2.0-tranche1` | |
| Ancillaries | built | Seat + bag via AugmentOffer; EMD/PER_PAX_PER_DIRECTION resolved de-facto (D4) | `v0.2.0-ancillaries-d3/-d4/-d5`; PR #32 | |
| NDC distribution | built-thin | `payment=null` at OrderCreate (no payment step); GDS/interline are simulator ports | `v0.3.1-ndc-thin`; PRs #46–#50 | |
| **B2B agent portal** | **not built** | No service/contract/code exists | — | |
| Corporate & SME | built-thin | Policy-compliant booking + directory; `budgetLimit` informational (no enforcement); demo admin header | `v0.3.2-corporate-thin`; PRs #50–#53 | |
| FFP / loyalty | built-thin | Voucher-stub redemption; "pay with miles" stubbed behind a port | `v0.3.3-loyalty(-thin)`; PRs #54,#58–#60 | |
| WhatsApp conversational | built-thin | Keyword state machine (no NLP); stub WhatsApp gateway | `v0.3.5-conversational-commerce-thin`; PR #62 | |
| Reporting & BI | built-thin | Read-side projection (`order_fact`); telemetry-grade | `v0.3.4-reporting-bi-thin`; PR #61 | |
| **GDS adapters** | **not built** | Internal ports + simulators only; absence enforced by `check_no_gds_symbols` / `check_no_interline_symbols` | — | |

## Additional Phase-2 thin slices shipped (not in the §9 core list)

| Deliverable | Factual status | Thinness / gap (fact) | Evidence | Ratify (empty) |
|---|---|---|---|---|
| Revenue Management | built-thin | Forecast + AU optimisation; AU→Inventory feed unresolved (ADR-0002) | `v0.3.6-revenue-management-thin`; PR #64 | |
| Personalisation / CDP | built-thin | Consent-gated ranking; offer→CDP wiring open (FLAG-007 / Paper 2) | `v0.3.7-personalisation-cdp-thin`; PR #65 | |
| Operational dashboard | built-thin | Read-side SSE; P99 validated on CI Docker only | `v0.2.0-dashboard-e4` | |
| DCS (departure control) | built-thin | Open/close, check-in, seat, boarding pass, bag, board; W&B/gov-data are simulators | `v0.3.0-dcs-thin`; PRs #42–#45 | |
| Channel BFF (IBE) | built | Mapping-only browser BFF | `v0.2.0-payments-a5` | |

## Notes

- Every row's evidence is a tag and/or merge PR verifiable in `git`. Contracts for all 15 services
  exist under `docs/contracts/` (exemption list empty as of session 24).
- Rows that ratify to `DO in Phase 3` should be cross-checked against Paper 6 (tranche candidates)
  and any prerequisite decisions (ADR-0002, Papers 2–3).
- The two **not built** rows (B2B agent portal, GDS adapters) are the explicit "did we intend to ship
  this in Phase 2?" calls — each has its own row above.

## DECISION

<!-- EMPTY — fill the "Ratify" cell for every row above. No cell pre-filled. -->

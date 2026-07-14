# PSS Platform — Latest Status

> **How to use this file:** At the start of every session, read this first. It tells you exactly where the project stands without needing to check GitHub. At the end of each session, ask Claude to update it.

---

## Last updated
**Date:** 24 June 2026
**Session summary:** Reviewed all 38 merged PRs on GitHub. Confirmed Tranche 1 fully complete. Read C2 gate report (P99 1095ms, SLO met). Published release `v0.2.0-tranche1` on GitHub (marked Latest, commit a2ff41d).

---

## Current phase
**Phase 2.0 — Tranche 1: COMPLETE ✓**
Tag: `v0.2.0-tranche1` — **RELEASED** (published 24 June 2026, marked Latest)

---

## Milestone status

### Workstream A — Real PSP (Razorpay)
| Milestone | Description | PR | Status |
|-----------|-------------|-----|--------|
| A0 | Environment & access (Razorpay keys, webhook ingress) | #21 | ✅ Merged |
| A1 | Port semantics, AWAITING_CUSTOMER state machine, conformance suite | #19 + #22 | ✅ Merged |
| A2 | RazorpayProviderAdapter (fitness rule active in CI) | #23 | ✅ Merged |
| A3 | Webhook endpoint (verify-first, late-money rule) | #24 | ✅ Merged |
| A4a | Customer clock + late-money + REFUND_FAILED drainer (shim retained) | #25 | ✅ Merged |
| A4b | Cross-module async pay-step: saga park/resume + create-order inversion | #26 | ✅ Merged |
| A5 | IBE checkout + payments smoke (Scenes 4/6/7) | #27 | ✅ Merged |

### Workstream B — Breadth (multi-pax return)
| Milestone | Description | PR | Status |
|-----------|-------------|-----|--------|
| B1 | Pricing: N×ADT + return journeys | #13 | ✅ Merged |
| B2 | Offer: shopping context + Stage 1 (direction-grouped) | #16 | ✅ Merged |
| B3 | Offer: CombineAndPrice + order stage enforcement | #17 | ✅ Merged |
| B4 | Order: multi-passenger validation, materialization, ticket bridge | #28 | ✅ Merged |
| B5 | Inventory: qty>1 proof (multi-line atomicity under concurrency) | #29 | ✅ Merged |
| B6 | IBE/BFF: two-stage offer flow (shop → combine → order) | #30 | ✅ Merged |

### Workstream D — Thin Ancillaries (seat + bag)
| Milestone | Description | PR | Status |
|-----------|-------------|-----|--------|
| D1 | Pricing: ancillary catalogue + PriceAncillaries | #12 | ✅ Merged |
| D2 | Offer: AugmentOffer + supersession | #18 | ✅ Merged |
| D3 | Inventory: seat claims (riding the hold lifecycle) | #31 | ✅ Merged |
| D4 | Order: ANCILLARY materialization (seats + bags) + EMD bridge | #32 | ✅ Merged |
| D5 | IBE: seat map + bag step + server-ratified totals | #33 | ✅ Merged |

### Workstream E — Live Dashboard
| Milestone | Description | PR | Status |
|-----------|-------------|-----|--------|
| E1 | ShopPerformed telemetry event (Offer emitter) | #14 | ✅ Merged |
| E2 | dashboard-service: consumers + projections + dedupe | #15 | ✅ Merged |
| E3 | dashboard-service: SSE live push + carrier SPA | #20 | ✅ Merged |
| E4 | Availability tile + ancillary metrics | #34 | ✅ Merged |

### Integration milestones
| Milestone | Description | PR | Status |
|-----------|-------------|-----|--------|
| C1 | Breadth × PSP join smoke (DEMO_SCRIPT Scenes 0–7) | #37 | ✅ Merged |
| C2 | Demo environment: one-command cluster + latency measurement | #38 | ✅ Merged |

---

## C2 gate report — key findings (from BUILD_LOG.md)
- **Idempotency:** smoke ran twice in a row, both exit 0 ✓
- **Dashboard latency P50:** 1,025 ms
- **Dashboard latency P99:** 1,095 ms
- **SLO (P99 ≤ 2,000 ms):** MET ✓ — ~900ms headroom
- **Measurement environment:** CI Docker (Linux). Local Testcontainers skipped on Windows JVM.
- ⚠️ **Action before live demo:** re-validate P99 on the real demo cluster (not just CI Docker)
- **CPU-ceiling concern from Phase 1:** NOT a blocker — headroom is healthy, no architecture intervention needed this tranche

---

## Post-merge fixes / notable PRs
| PR | Description | Notes |
|----|-------------|-------|
| #35 | fix(payments): stop background drainer racing RefundDrainIT | Concurrency bug patched. **Action:** confirm drainer retry SLO alert is correctly wired. |
| #36 | docs: record live Razorpay sandbox operator run | Razorpay sandbox conformance: 2 passed (initiate, tokenize), 6 expected-gap failures (require customer-captured payment — covered by C1 smoke). Keys valid, adapter reaches live rails confirmed. |
| #10 | Phase-1 docs closeout: OPEN_ITEMS register + Offer module LLD | Check OPEN_ITEMS register for anything to carry into next tranche. |

---

## Open decisions — must be ratified before next tranche starts
- **Capture mode for production** — auto-capture chosen for demo tranche; production posture may need manual two-phase before pilot traffic. Decide before pilot, not before next demo.
- **Customer-clock margin** — 60s provisional; ratify with Inventory once measured against real sandbox latencies.
- **Drainer cadence** — 5m provisional; confirm with SLO alert wiring check after #35 fix.
- **Drainer retry SLO alert** — verify correctly wired after #35 fix.
- **Deterministic refund-key formula** — per paymentIntentId + compensation cause vs per saga step id. Carry into next tranche spec.
- **Ancillaries open decisions** — return-direction seat rehearsal, occupancy staleness budget, PER_PAX_PER_DIRECTION expansion, EMD typing.
- **Dashboard open decisions** — day-bucket timezone (IST proposed), rebuild-by-replay design, ticker visibility, gross vs net headline.
- **OPEN_ITEMS register** — review from Phase-1 docs closeout (#10); carry forward anything unresolved.
- **Latency re-validation** — P99 must be re-measured on the real demo cluster before the live demo.

---

## Next steps — what to work on next session

1. **Ratify open decisions** — run the list above through the architecture board. Capture mode and drainer SLO are the most time-sensitive.
2. **Check drainer SLO alert wiring** — verify the #35 fix actually hooks up the alert correctly.
3. **Plan Phase 2.1 / next tranche** — roadmap candidates per Scene 8 of the demo script: NDC/B2B distribution, corporate booking, loyalty, DCS/day-of-departure.
4. **Re-validate latency on real demo cluster** — before scheduling any live demo.

---

## Release history
| Tag | Description | Date |
|-----|-------------|------|
| `v0.2.0-tranche1` | Real PSP + Breadth + Ancillaries + Dashboard | 24 June 2026 |

---

## Repo
`github.com/babakghf/pss-platform` (private) — branch: `master`

## Key documents in this project
| Document | What it covers |
|----------|---------------|
| `BUILD_PLAN_PHASE_2_0_TRANCHE_1.md` | Workstreams A + B milestones and gates |
| `BUILD_PLAN_TRANCHE_1_ADDENDUM_1.md` | Workstreams D + E milestones and gates |
| `PAYMENTS_LLD_DELTA_0_2_REAL_PSP.md` | Razorpay integration design delta |
| `BREADTH_DELTA_0_2_MULTIPAX_RETURN.md` | Multi-pax return design delta |
| `ANCILLARIES_DELTA_0_2_SEAT_BAG.md` | Seat + bag ancillaries design delta |
| `DASHBOARD_DELTA_0_2_LIVE_READSIDE.md` | Live dashboard design delta |
| `DEMO_SCRIPT.md` | Full 8-scene demo walkthrough (Scenes 0–7 live; Scene 8 = next roadmap) |
| `PSS_System_Design_Document.docx` | System-level architecture |
| `PSS_Payments_Module_LLD.docx` | Payments module LLD |
| `PSS_Order_Module_LLD.docx` | Order module LLD |

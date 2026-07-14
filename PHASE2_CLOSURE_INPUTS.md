# Phase 2 closure / Phase 3 kickoff — input pack

**Assembled:** 2026-07-03 (session 24). **Status:** fact assembly only — this document makes
no decisions and states no recommendations. It exists so the human can run the closure /
kickoff review from a single evidence base. Every row is sourced to a tag, PR, or file in
this repo. The review itself (scope ratification, triage, Phase-3 selection) is out of scope
for the session that produced this pack.

Baseline: master @ `v0.4.0-phase2-close` (merge of PR #68). All Phase-2 build work is merged.

---

## 1. Scope vs roadmap §9

Roadmap §9 Phase-2 deliverables. "built-thin" rows state the specific thinness. Evidence is
the git tag and/or merge PR(s).

| Roadmap §9 deliverable | Status | Thinness (if thin) | Evidence |
|---|---|---|---|
| Offer management | built-full | — | `v0.1.0-offer`, `v0.2.0-breadth-b6`, `v0.2.0-tranche1`; offer-service + `offer-api` contracts |
| Ancillaries | built | Seat + bag ancillaries via AugmentOffer; EMD typing / PER_PAX_PER_DIRECTION resolved de-facto in D4 | `v0.2.0-ancillaries-d3/-d4/-d5`; PR #32 |
| NDC distribution | built-thin | `payment=null` at OrderCreate — `CreateOrderPayload` carries no payment field (no payment step); GDS/interline are internal simulator ports, no live adapter | `v0.3.1-ndc-thin`; PRs #46–#50; `distribution-api_openapi.yaml` |
| B2B agent portal | **not built** | — | No service, contract, or code found |
| Corporate & SME | built-thin | Policy-compliant booking + directory (accounts/cost-centres/travellers/policies/statements); `budgetLimit` informational (no enforcement); demo `X-Corporate-Admin` header gate; one event `CorporateBookingCreated` | `v0.3.2-corporate-thin`; PRs #50–#53; `corporate-api_openapi.yaml` + `corporate-events_schema.json` |
| FFP / loyalty | built-thin | Enrol + event-driven earn + tier + redeem to a loyalty-owned **voucher stub**; real "pay with miles" (Pricing/Payments) stubbed behind a port | `v0.3.3-loyalty` / `v0.3.3-loyalty-thin`; PRs #54, #58–#60; `loyalty-api_openapi.yaml` + `loyalty-events_schema.json` |
| WhatsApp conversational | built-thin | Keyword state machine (no NLP); stub WhatsApp gateway; reuses public Order/DCS APIs as a channel | `v0.3.5-conversational-commerce-thin`; PR #62; `conversation-api_openapi.yaml` |
| Reporting & BI | built-thin | Read-side projection (`order_fact` from OrderConfirmed/RefundIssued); best-effort telemetry-grade | `v0.3.4-reporting-bi-thin`; PR #61; `reporting-api_openapi.yaml` |
| GDS adapters | **not built** | — | Internal ports + simulators only; absence enforced by `check_no_gds_symbols` / `check_no_interline_symbols` |

**Additional Phase-2 thin slices shipped (not in the §9 core list above):**

| Deliverable | Status | Thinness | Evidence |
|---|---|---|---|
| Revenue Management | built-thin | Forecast + AU optimisation → intended `AdjustInventory`; the AU→Inventory feed seam is **unresolved** (see §2) | `v0.3.6-revenue-management-thin`; PR #64; `rm-api_openapi.yaml` |
| Personalisation / CDP | built-thin | Event-sourced profile + consent-gated ranking; offer-service `PersonalisationPort` wiring not config-only (FLAG-007 open) | `v0.3.7-personalisation-cdp-thin`; PR #65; `cdp-api_openapi.yaml` |
| Operational dashboard | built-thin | Read-side SSE projection; P99 validated on CI Docker only (see §3 carried items) | `v0.2.0-dashboard-e4`; `dashboard-api_openapi.yaml` |
| DCS (departure control) | built-thin | Open/close flight, check-in, seat, boarding pass, bag, board; `WeightBalancePort`/`GovDataPort` are simulators | `v0.3.0-dcs-thin`; PRs #42–#45; `dcs-api_openapi.yaml` + `dcs-events_schema.json` |
| Channel BFF (IBE) | built | Mapping-only browser BFF (no owned domain) | `v0.2.0-payments-a5`; `bff-api_openapi.yaml` |

---

## 2. Open decisions awaiting the review

Flagged and **unresolved** as of era close.

| # | Decision | Source | State |
|---|---|---|---|
| 1 | **RM ↔ Inventory flight-class identity model** — RM mints its own `flightClassConfigId`; inventory keys by `flightInventoryId`; the shared coordinates `(tenant, flightNumber, departureDate, rbd)` are not a key in inventory (`flight_number` nullable, no unique index). The RM→Inventory AU feed cannot close without a cross-service identity decision. | `docs/FLAG_rm_inventory_au_seam.md`; `RM_DELTA_0_6_RETRO.md` §Findings | **Open** — stop-and-flagged, no code written (session 23) |
| 2 | **FLAG-007** — offer-service `PersonalisationPort` bean swap to the CDP HTTP adapter is not config-only; deferred to a scoped PR. | `LATEST_STATUS.md` Governance log; BUILD_LOG.md | **Open** |

All other governance flags (FLAG-001…FLAG-006) are resolved (see `LATEST_STATUS.md` Governance log).

A standing rule was added in session 23 as a by-product of decision #1: `docs/RULE_headline_gates_real_counterparty.md` (a cross-service headline gate must include a test against the real counterparty, not only WireMock).

---

## 3. Carried open items

The full current list from `LATEST_STATUS.md` "Carried open items". One line of context each;
the **Triage** column is blank for the human to fill (`must-close-before-Phase-3` / `rides-along`).

### Decisions (ratify before the relevant milestone)
| Item | Context | Triage |
|---|---|---|
| Capture mode for production | Auto-capture (demo) vs manual two-phase; decide before pilot traffic | |
| Customer-clock margin | 60 s provisional; ratify against real sandbox latencies with Inventory | |
| Drainer cadence | 5 m provisional (`refund-drainer.cadence-ms:300000`); confirm | |
| Deterministic refund-key formula | Per `paymentIntentId`+cause vs per saga-step id; carry into next payments-tranche spec | |
| Ancillaries | Return-direction seat rehearsal; occupancy staleness budget | |
| Dashboard | Rebuild-by-replay design; ticker visibility; gross vs net headline | |

### Verification tasks (not decisions)
| Item | Context | Triage |
|---|---|---|
| Dashboard P99 on real demo cluster | CI Docker P99 = 1,095 ms (SLO ≤ 2,000 ms met); re-measure on real cluster before a live demo | |
| NDC `payment=null` at OrderCreate | `CreateOrderPayload` carries no payment field; add a payment step before a live NDC prospect demo | |
| DCS `X-Tenant-Id` (external contract) | Code-verified not load-bearing; removal from the external (Claude-project) DCS contract is safe; no in-repo action | |

### Backfill findings (documented; code is authoritative)
| Item | Context | Triage |
|---|---|---|
| RM → Inventory feed half-built | RM posts to `/v1/inventory/flight-classes/{id}/adjust-au`, which inventory-service does not serve (feed would 404 against the real service) — see §2 decision #1 | |
| Conversation → DCS Idempotency-Key | **Resolved** in session 23 (deterministic key). Residual: DCS *requires but ignores* the key (natural-key dedup) — dcs-service change out of scope | |
| Cross-cutting demo-posture deviations | App-level `X-*-Admin` header gates (not security filters) and `Idempotency-Key` declared-required-but-unhonoured across services; listed in each README `## Deviations` | |
| `check_contract_presence` exemption list | **Closed** in session 24 — all five contracts authored; exemption list emptied; gate passes 15/15, 0 exempt | |

`docs/OPEN_ITEMS.md` is the maintained Phase-1 closeout open-items register.

---

## 4. Phase 3 tranche candidates (roadmap §9 Phase 3)

No ranking. Each row states, factually, what already exists in the codebase to build on.

| Candidate | What exists in the codebase to build on |
|---|---|
| DCS deepening | dcs-service ships a thin flow (open/close flight, check-in, seat, boarding pass, bag, board) now contracted in `dcs-api_openapi.yaml`; publishes 5 events (`dcs-events_schema.json`). `WeightBalancePort`/`GovDataPort` exist as simulators. |
| Baggage | DCS `POST /v1/checkins/{checkInId}/bags` (AcceptBag) + `BagAccepted` event exist; `WeightBalancePort.BagInfo` carries `totalBags`. No standalone baggage service. |
| IROPS (irregular ops) | No dedicated IROPS code found. Adjacent seed material: DCS flight lifecycle (`open`/`close`) + `FlightClosed` event; inventory hold/release lifecycle. |
| Weight & Balance (W&B) | DCS `WeightBalancePort` interface + `WeightBalanceSimulator` stub; `WbNotReady` state gates check-in/boarding until W&B is ready. |
| Cargo | No cargo code, service, or contract found in the repo. |
| Settlement / revenue-accounting | reporting-service read-side (`order_fact` derived from OrderConfirmed/RefundIssued); RM forecast/optimisation read-sides; payments-service refund/late-money reconcilers. No settlement ledger or rev-accounting store exists. |

---

## 5. Platform health snapshot

- **Services registered:** 15 (per `check_contract_presence`: "Services registered: 15 | checked: 15 | exempt: 0").
- **API contracts:** 15/15 services ship an OpenAPI contract under `docs/contracts/` (exemption list empty as of session 24).
- **Event schemas:** `order-events`, `loyalty-events`, `dcs-events`, `distribution-events`, `corporate-events` (session 24) under `docs/contracts/`.
- **Fitness functions:** 12 — `check_actuator`, `check_cdp_isolation`, `check_contract_presence`, `check_dashboard_isolation`, `check_enum_contract_parity`, `check_no_cross_module_db`, `check_no_gds_symbols`, `check_no_interline_symbols`, `check_no_razorpay_symbols`, `check_otel`, `check_reporting_isolation`, `check_tenant_context`.
- **Test source files per module** (`.kt` files under `src/test`, not test-method count): order 14, payments 22, pricing 14, offer 11, inventory 10, loyalty 12, dcs 11, distribution 11, dashboard 11, corporate 9, rm 8, cdp 7, conversation 5, channel-bff 5, reporting 4. **Total ≈ 154 test files.**
- **CI status:** `build-and-test`, `fitness-functions`, `dependency-scan` all green on PR #68 (the last merge to master). Note: the workflows trigger on `pull_request` and `push: [main]`; the default branch is `master`, so a merge to master does not spawn a fresh run — master's green status derives from the merged PR head.
- **Tag history (31 tags):** Phase-1 `v0.1.0-*` (inventory/order/pricing/offer/payments, walking-skeleton, phase1-complete) → Phase-2 Tranche-1 `v0.2.0-*` (payments a2–a5, breadth b4–b6, ancillaries d3–d5, dashboard-e4, tranche1-c1, tranche1) → Phase-2 thin slices `v0.3.0-dcs-thin`, `v0.3.1-ndc-thin`, `v0.3.2-corporate-thin`, `v0.3.3-loyalty(-thin)`, `v0.3.4-reporting-bi-thin`, `v0.3.5-conversational-commerce-thin`, `v0.3.6-revenue-management-thin`, `v0.3.7-personalisation-cdp-thin` → **`v0.4.0-phase2-close`** (current).

# Open-decisions reconciliation — session 21b

**Date:** 2026-07-02
**Why:** `LATEST_STATUS.md` was rewritten (commit `693dbdb`) to "Phase 2 complete, all
green." Rewrites can silently drop carried debt. This table reconciles the pre-rewrite
open-decisions register (from the 24-June `LATEST_STATUS.md`, recoverable at
`git show 693dbdb~1:LATEST_STATUS.md`, plus the deleted session-19 drop-in) against the
current file. Every item is classified **CLOSED** / **STILL TRACKED** / **VANISHED**.

Vanished items are restored to `LATEST_STATUS.md` under **## Carried open items** (additive
edit, same PR). Closed items are left closed with evidence.

| # | Item | Fate | Evidence |
|---|------|------|----------|
| 1 | Capture mode for production (auto vs manual two-phase) | **VANISHED** → restored | Old register "Open decisions" (`693dbdb~1:LATEST_STATUS.md`): "decide before pilot". Not in current file; no ratification found. Genuine pre-pilot decision. |
| 2 | Customer-clock margin (60 s provisional) | **VANISHED** → restored | Old register: "ratify vs real sandbox latencies". Absent now. Still provisional in code. |
| 3 | Drainer cadence (5 m provisional) | **VANISHED** → restored | Old register. Absent now. Code default still `pss.payments.refund-drainer.cadence-ms:300000` (= 5 m), `RefundFailedDrainer.kt:37`. |
| 4 | Drainer retry / age-SLO alert (were the `age_slo_fix.md` changes applied?) | **CLOSED** | **Applied in code, not just the alert file.** `PaymentsTimingProperties.ageSlo = 1h` (`config/PaymentsTimingProperties.kt:39`); `RefundDrainService.checkAgeSlo()` (`:68`,`:79`) increments `payments.refund_drain.age_slo_breached`; the **third** `RefundDrainIT` test — *"age SLO alert fires when a REFUND_FAILED intent is older than the SLO window"* (`RefundDrainIT.kt:80`); alert rule `PaymentsRefundAgeSloBreached` wired to `payments_refund_drain_age_slo_breached_total` (`platform/observability/k8s/prometheus/payments-alerts.yaml:26-38`). All four legs present. |
| 5 | Deterministic refund-key formula (per intent + cause vs per saga step) | **VANISHED** → restored | Old register: "carry into next tranche spec". Absent now; no formula spec landed. Still a spec item for the next payments tranche. |
| 6a | Ancillaries — return-direction seat rehearsal | **VANISHED** → restored | Old register. Absent now; no ratification in `BUILD_LOG.md`. |
| 6b | Ancillaries — occupancy staleness budget | **VANISHED** → restored | Old register. Absent now; no ratification found. |
| 6c | Ancillaries — PER_PAX_PER_DIRECTION expansion | **CLOSED** (de-facto in D4) | Implemented in D4: `BUILD_LOG.md:645`,`:1037` ("PER_PAX_PER_DIRECTION → single segment"); PR #32. |
| 6d | Ancillaries — EMD typing | **CLOSED** (de-facto in D4) | D4 EMD bridge — `BUILD_LOG.md` §D4, PR #32 ("ANCILLARY materialization + EMD bridge"). |
| 7a | Dashboard — day-bucket timezone (IST proposed) | **CLOSED** (de-facto) | Day-bucket rule + `DayBucketTest` — `BUILD_LOG.md:815`,`:827` ("Open Decision 1"). Rule implemented and tested. |
| 7b | Dashboard — rebuild-by-replay design | **VANISHED** → restored | Old register. Absent now; not built (deferred design). |
| 7c | Dashboard — ticker visibility | **VANISHED** → restored | Old register. Absent now. |
| 7d | Dashboard — gross vs net headline | **VANISHED** → restored | Old register. Absent now. |
| 8 | OPEN_ITEMS register review (PR #10, Phase-1 closeout) | **STILL TRACKED** → pointer restored | The register lives and is actively maintained: `docs/OPEN_ITEMS.md` ("Update it whenever a gap moves…"). It was simply no longer linked from `LATEST_STATUS.md`; a pointer is restored. |
| 9 | Dashboard P99 re-validation on the REAL demo cluster | **VANISHED** → restored | Old register (CI P99 = 1,095 ms; "re-validate on the real demo cluster, not CI Docker"). Verification task, still outstanding. Absent now. |
| 10 | NDC `payment=null` at OrderCreate | **VANISHED** → restored | Old register. **Still true:** `CreateOrderPayload` (`OrderServiceClient.kt:72`) carries only `offerRef` / `passengers` / `contact` — no payment field, no payment step. Needed before a live NDC prospect demo. Absent now. |
| 11 | DCS — remove `X-Tenant-Id` from `dcs-api_openapi.yaml` | **VANISHED** → restored (as external-doc note) | Old register (low priority). **Target artefact is not in this repo** — no `dcs-api_openapi.yaml` in the tree or git history; contracts under `docs/contracts/` hold only `dcs-events_schema.json`. The DCS API contract lives in the Claude project knowledge, so this cleanup is not actionable in-repo; carried as an external-doc task. |
| 12a | ★ Loyalty — accrual trigger (booking-time vs flown) | **CLOSED** (decided + documented) | `docs/LOYALTY_DELTA_0_3_EARN_REDEEM.md` §"Architecture"/§4: accrual is a downstream `OrderConfirmed`/`OrderCancelled` consumer = **booking/confirmation-time**; "flown-based accrual via DCS" is explicitly **Out of Scope** (§ line 17). Built & merged L2 (PR #59). |
| 12b | ★ Loyalty — earn basis (BASE_FARE vs TOTAL) | **CLOSED** (decided + documented) | Delta §3 `EarnRule.earnBasis {BASE_FARE|TOTAL}`; L2 uses **BASE_FARE = Σ AIR item price** (old status L2 row). PR #59. |
| 12c | ★ Loyalty — redemption fulfilment (voucher stub vs real) | **CLOSED** (decided + documented) | Delta §Out-of-Scope + §6: redemption mints a **loyalty-owned voucher behind `RedemptionFulfilmentPort`**; real "pay with miles" deferred. Built & merged L3 (PR #60). |
| 12d | ★ Loyalty — tier qualification window (cumulative vs reset) | **CLOSED** (decided + documented) | Delta §5 line 104: `BASE → SILVER → GOLD` on **cumulative** `qualifyingMiles` (SILVER ≥ 25,000; GOLD ≥ 50,000, tenant-configurable). PR #58/#59. |

## Summary

- **CLOSED:** 4 (age-SLO alert), 6c, 6d, 7a, and all four ★ loyalty decisions (12a–d) —
  the loyalty four were decided **and** documented in the delta, so not "undocumented".
- **STILL TRACKED:** 8 (the `docs/OPEN_ITEMS.md` register is alive; pointer restored).
- **VANISHED → restored to `LATEST_STATUS.md` ## Carried open items:** 1, 2, 3, 5, 6a, 6b,
  7b, 7c, 7d, 9, 10, 11.

Nothing was found to be silently *lost* in a way that erased a real obligation without a
home: every vanished item is now either closed-with-evidence above or carried forward in the
status file.

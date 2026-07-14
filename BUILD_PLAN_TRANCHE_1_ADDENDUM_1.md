# Phase 2.0 — Build Plan, Tranche 1 · Addendum 1 (Workstreams D & E)

**Status:** Ratified — extends BUILD_PLAN_PHASE_2_0_TRANCHE_1.md; does not replace it.
**Incorporates:** ANCILLARIES_DELTA_0.2_SEAT_BAG.md (Workstream D) · DASHBOARD_DELTA_0.2_LIVE_READSIDE.md (Workstream E)
**How to use:** drop this file and both deltas into the repo alongside the original plan. All standing rules of the base plan apply unchanged. Where this addendum and the base plan conflict, this addendum governs (it only amends the exclusion clause and the C-gates).

---

## 1. Amendments to the base plan

1. **Exclusion lifted.** The clause "Excluded from this plan: thin ancillaries and the live dashboard" is removed. Both are now in scope as Workstreams D and E, governed by their ratified deltas. The stop-and-flag entry "anything ancillary- or dashboard-shaped" is replaced by the workstream-specific stop-and-flag lists below.
2. **C1 gate amended.** Once D4 + D5 are green, the C1 scripted smoke drops its "Scene 3 placeholder"; once E3 is green, it drops "dashboard right-screen excluded." Target end-state of C1: Scenes 1–7 with ancillaries, dashboard assertions per E.
3. **C2 gate amended.** The one-command demo environment additionally stands up: ancillary catalogue + seat-map config for the demo carrier (D), dashboard-service + its store + demo-flight availability-tile config (E). The smoke gains the dashboard latency/movement assertions (Dashboard delta §7.4). **C2's smoke is the full demo script, Scenes 0–7, both screens, unattended, twice in a row.**
4. **Tagging extended:** `v0.2.0-ancillaries` at D5 · `v0.2.0-dashboard` at E4 · `v0.2.0-tranche1` remains the C2 tag and now implies all four workstreams.

## 2. Workstream D — Thin Ancillaries (seat + bag)

Authoritative spec: ANCILLARIES_DELTA_0.2_SEAT_BAG.md. Milestones and gates verbatim from its §11:

- **D1 — Pricing:** catalogue (tenant config, SEAT + BAG) + `PriceAncillaries`. Gate: golden quotes incl. GST + 422 matrix.
- **D2 — Offer:** `AugmentOffer` + supersession. Gate: augment/expiry/ref-mismatch suite; grandTotal invariant property test. *(Starts after B3 — needs CombineAndPrice staging.)*
- **D3 — Inventory:** optional `seatClaims` on hold lines + occupancy read. Gate: seat-claim concurrency suite (one winner for 12A; loser's whole hold fails clean; TTL expiry; release-restores-seat). **Merge after B5 is green** so hold-path regressions are attributable.
- **D4 — Order:** ANCILLARY validation, materialization, EMD bridge, schema CI with ancillary payloads. **Merge after B4.** Gate: 2×ADT return + 2 seats + 1 bag → 4 AIR + 3 ANCILLARY items, correct money; EMD/documentRef mapping.
- **D5 — IBE:** seat map (Fleet/Cabin geometry + occupancy read) + bag step + server-ratified totals. Gate: e2e UI shop→augment→order (simulator PSP acceptable).

**Stop-and-flag (D):** any saga or compensation change · seat lifecycle diverging from the hold lifecycle · payment-shaped logic anywhere in D · zone pricing.

## 3. Workstream E — Live Dashboard (first read-side consumer)

Authoritative spec: DASHBOARD_DELTA_0.2_LIVE_READSIDE.md. Milestones and gates verbatim from its §9:

- **E1 — `ShopPerformed`** emitted from Offer (shop + combine, stage-tagged), schema in CI. Gate: events on the backbone; telemetry-grade loss tolerance in the runbook.
- **E2 — dashboard-service projections:** consumers idempotent on eventId, dedupe, per-tenant/day counters. Gate: projection + duplicate-delivery suites; **fitness checks live in CI** (no module API clients, no foreign datasources in this service).
- **E3 — SSE + SPA:** KPI row, funnel, ticker; reconnect/refresh-proof. Gate: manual booking moves the screen; ratify the outbox polling interval against the ≤2s budget here, measured on the demo cluster (feed findings to the C2 CPU-ceiling investigation).
- **E4 — availability tile + ancillary metrics.** *(After D4 — needs enriched payloads.)* Gate: Scene 6 tile restores on decline; attach-rate/ancillary-revenue move on a Scene 3 booking.

E1–E3 have **no dependency on A, B or D** and may start immediately.

**Stop-and-flag (E):** any synchronous call from dashboard-service into a module · new events invented for the dashboard beyond ShopPerformed · money figures derived from anything but OrderConfirmed/RefundIssued.

## 4. Dependency picture (full tranche)

```
A0 → A1 → A2 → A3 → A4 → A5 ─────────────┐
B1 → B2 → B3 → B4 → B5 → B6 ─────────────┤
            └─ D2 → (D4 after B4)         ├→ C1 → C2
D1 ──────────┘    (D3 after B5) → D5 ─────┤
E1 → E2 → E3 ──── (E4 after D4) ──────────┘
```

Sequencing rules already in force are unchanged: B4 after A4; D3 after B5; D4 after B4; E4 after D4. Everything else parallelises.

## 5. Open decisions carried (build must not resolve these alone)

From the ancillaries delta: return-direction seats (rehearsal) · occupancy staleness budget (ratify with E) · PER_PAX_PER_DIRECTION expansion (pre-multi-segment) · superseded-offer invalidation (rehearsal) · EMD typing (with NDC work).
From the dashboard delta: day-bucket timezone (proposed: tenant TZ, IST demo) · rebuild-by-replay (Reporting & BI design) · ticker visibility (rehearsal) · outbox interval (E3, measured) · gross vs net headline (rehearsal).

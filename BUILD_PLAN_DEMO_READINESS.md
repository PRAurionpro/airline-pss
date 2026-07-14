# BUILD PLAN — DEMO READINESS

**Status:** Ready to build (sessions 27+) · **Slice:** the demo-first gate before any Phase-3 tranche
**Decided by:** the Phase 2 closure review (2026-07-03) — Papers 5 & 6, `docs/kickoff/`
**Base:** current `master` (Phase 2 ratified closed, `v0.4.0-phase2-close`)
**Goal:** a compelling **full-platform client demo** — core retail (shop→book→pay→dashboard), DCS
(check-in/seats/boarding), distribution (NDC/corporate), and extras (loyalty, WhatsApp, RM, CDP).

> **Governing posture (from the review): minimum changes now; a good client demo is the near-term
> goal; deeper hardening comes after.** Only **DR1** (and **DR2** — see its delta) touch `src/main`.
> Everything else is verification, scripting, and measurement.

---

## 0. How to build this (read first)

- **One milestone per session/branch.** Branch naming: `demo/DR1` … `demo/DR4`.
- **Minimum-change posture governs.** Do not fold Phase-3 deepening work in. **Paper-3 deviation
  items are explicitly NOT in this tranche** (they ride their owning Phase-3 tranches).
- **Every cross-service seam is proven against the real counterparty** per
  `docs/RULE_headline_gates_real_counterparty.md` — no WireMock-only gate closes a seam here.
- A milestone is DONE only when its gate is green on CI (and, for DR3/DR4, the operator step is run
  and its report committed). Append a gate report to `BUILD_LOG.md` per milestone.

## 1. Scope

**In (the demo-readiness gate):**
- DR1 — offer→CDP wiring (the one core-module code change).
- DR2 — NDC payment step (minimal), so an NDC-created order can reach PAID.
- DR3 — real-cluster validation: dashboard P99 + customer-clock latency (operator-run).
- DR4 — full-platform demo dry-run + `DEMO_SCRIPT.md`.

**Out (do NOT build here):**
- Any Paper-3 deviation remediation (header-identity, error-format split, DCS idempotency store).
- Any Phase-3 tranche (DCS deepening, W&B, IROPS, baggage, cargo, settlement).
- ADR-0002 implementation (the live RM→Inventory AU feed) — deferred to its first consuming tranche;
  the demo shows **RM compute-only**.

---

## DR1 — offer→CDP wiring (FLAG-007) — ✅ BUILT (session 27, closes on merge)

**Exactly per `docs/kickoff/paper-2-flag-007-cdp-wiring.md`.** One scoped **offer-service** PR:

- A new adapter class implementing `com.pss.offer.personalisation.PersonalisationPort` with an
  outbound `RestClient` to cdp-service `POST /v1/cdp/rank-offers`.
- `Offer` ↔ `OfferSummary`/`RankedOffer` mapping.
- Conditional bean wiring that displaces the `PassThroughPersonalisation` `@Component`.
- **Fail-open (mandatory):** on any CDP error/timeout, fall back to pass-through (unranked) so a CDP
  outage cannot degrade shopping. Use a **tight client timeout**.

**Touches `src/main`:** offer-service only (adapter + wiring). cdp-service unchanged (its endpoint is
ready). No other service.

**Gate:**
- Real offer→cdp IT (both services up) proving ranking is applied — per RULE_headline_gates.
- A CDP-down IT proving fail-open to pass-through (shopping still succeeds).
- All existing offer-service gates stay green (no weakening).
- FLAG-007 closes on this PR's merge (update BUILD_LOG + LATEST_STATUS).

**Demo beat produced:** same search, two personas → different ranking.

---

## DR2 — NDC payment step (minimal)

### Delta — minimum shape (decided here after code investigation)

**Investigation finding (session 26, flagged):** the review's option (b) — "pay the NDC-created
order via the core flow, zero `src/main`" — **does not work today.** Verified against the code:

- NDC OrderCreate already creates a **real order-service order** (`distribution` →
  `POST /v1/orders`, `OrderServiceClient.createOrder`).
- But order-service's saga opens the payment window **only when the create request carries a
  `payment { intentRef, method }`** (`BookPayFulfilSaga.book()` returns `Pending` and the order
  stays **CREATED** when payment is absent). NDC's `CreateOrderPayload` has **no payment field**, so
  NDC orders never open a window.
- order-service exposes **no** post-create "link-payment / open-window" endpoint (only `POST /v1/orders`,
  `GET /{id}`, `GET`, `POST /{id}/changes`, `POST /{id}/cancel`). So a CREATED NDC order cannot be
  moved to AWAITING_PAYMENT after the fact.

⇒ **Chosen: option (a), the minimal payment element.** Add an **optional payment element to NDC
OrderCreate** that flows into the existing order-service `payment` input, so the NDC-created order
opens a window and parks AWAITING_PAYMENT like any IBE order — then it is paid via the **existing
core payment flow** (payment signal → `PaymentResumeService` → CONFIRMED). This is the smallest change
that makes the seam real; it reuses the whole core payment machinery unchanged.

### intentRef sub-shape — decided at the review follow-up (session 26); build in session 28

`order-service`'s `payment` input is `{ intentRef, method }` where `intentRef` is a **payment instrument
token** (openWindow uses it to open the window). NDC **sellers won't call the internal payments API** to
mint one. Chosen sub-shape: **distribution orchestrates internally** —

- The NDC `OrderCreate` payment element carries **`method` + `amount`** (seller-facing, NDC-idiomatic).
- distribution's handler **mints the payment intent against payments-service server-side**, then forwards
  `payment { intentRef, method }` into the core order-create. **One seller call, no new NDC endpoint.**

**Consequence (locked into DR2's gate):** distribution gains a **runtime seam to payments-service**, so
DR2's gate MUST include a **real `distribution → payments → order` IT** (RULE_headline_gates applies —
no payments WireMock as the gate). `src/main` remains distribution-service only (its handler + DTOs);
order-service and payments-service are unchanged.

### Gate

- An IT that takes an **NDC-created order to PAID** through the real order + payments path (real
  counterparties per RULE_headline_gates), asserting CONFIRMED/ticketed.
- Existing distribution gates stay green; omitting the payment element preserves today's behaviour.
- Closes the carried item **"NDC `payment=null` at OrderCreate"**.

---

## DR3 — real-cluster validation (operator-run, Claude-assisted)

Verification only — **no `src/main`.** Deliverable is a committed measurement report
(`docs/DEMO_READINESS_MEASUREMENTS.md`).

- **Dashboard P99** re-measured against the **≤ 2000 ms** SLO on the **real demo cluster** (CI Docker
  already shows P99 ≈ 1,095 ms; this confirms it on the cluster). Closes the "Dashboard P99 on real
  cluster" must-close item.
- **Customer-clock latency** measured **in the same run** and recorded for later ratification of the
  60 s provisional margin (a `rides-along` measurement, captured now while the cluster is up).

> 🧑‍💻 **Operator steps (need the human + the demo cluster):** deploy/point at the demo cluster, run
> the load/measurement, capture numbers. Claude assists with the harness, the report template, and
> analysis — but cannot run the cluster. Mark each step clearly as operator-run in the report.

**Gate:** the report is committed with the measured P99 (pass/fail vs SLO) and the customer-clock
latency sample; if P99 fails the SLO, that is a stop-and-flag for the demo, not a silent pass.

---

## DR4 — demo dry-run + script update

Refresh **`docs/DEMO_SCRIPT.md`** for the **full-platform** demo (all four areas). No `src/main`.

The script MUST cover:
- **Core retail:** shop → book → **pay** → dashboard updates.
- **Return-trip booking with seats** (covers the carried "ancillaries return-seat / occupancy
  rehearsal" must-close item) — exercised in the dry-run on the real cluster.
- **Personalization beat (from DR1):** the SAME search run as two personas gives a **visibly different
  ranking** (each persona's preferred cabin ranks first). Turn it on with `OFFER_CDP_ENABLED=true`
  (`pss.offer.personalisation.cdp.enabled`); each persona shops with a JWT whose `sub` is their CDP
  `externalRef`, and needs a consented CDP profile (PERSONALISATION GIVEN) with a `preferredCabin`. With
  the toggle off (or CDP down) shopping still works — results are simply unranked (fail-open).
- **DCS:** check-in → seat → boarding pass → board.
- **Distribution:** NDC order (now payable via DR2) and corporate booking.
- **Extras:** loyalty (earn/tier/redeem), WhatsApp (keyword check-in), **RM shown as forecast/AU
  compute-only** — the script must **NOT** claim or attempt a live inventory apply (ADR-0002 impl is
  deferred); **CDP** personalization.
- **Dashboard headline number picked and honestly labeled** (gross vs net — decide & label during prep).

**Exit criterion:** a full dry-run passes on the demo cluster (operator-run; record the run in the
measurement report / BUILD_LOG).

---

## Build-plan rules (restate — these govern every milestone)

1. **Minimum-change posture governs.** Only **DR1** and **DR2 (option a)** touch `src/main`; DR3/DR4
   are verification + docs.
2. **Every seam proven against the real counterparty** (RULE_headline_gates_real_counterparty) — no
   WireMock-only gate.
3. **Paper-3 deviation items are NOT in this tranche** — they ride their Phase-3 deepening tranches.
4. **ADR-0002 stays deferred** — the demo shows RM compute-only; no live AU apply.
5. One milestone per session/branch; gate green on CI (+ operator report for DR3/DR4) before the next.

## Stop-and-flag

- If DR1 fail-open cannot be proven without weakening an offer gate → stop and flag.
- If DR2's minimal payment element turns out to require an order-service/payments change (not just
  distribution) → stop and flag (it would exceed "minimum change").
- If DR3's real-cluster P99 misses the SLO → stop and flag (demo-blocking).
- Any pressure to fold in Paper-3 or Phase-3 work → stop; it is out of scope here.

# BUILD LOG — Phase 2.0, Tranche 1

Per the `CLAUDE.md` standing rules this log records, in order of occurrence:
- **FLAGS** — stop-and-flag discrepancies / missing authoritative inputs (work stops; the design seat resolves).
- **GATE REPORTS** — appended after each milestone (milestone, gate checks run, results, deviations, measured numbers).

---

## FLAGS

### FLAG-003 — 2026-06-16 — A4 Piece 3 trips stop-and-flag #4 (scope beyond the saga pay-step)

> **RESOLVED — 2026-06-16 (design seat ratified Option 1: split).** A4 is split: **A4a** (this branch,
> `tranche1/A4`) builds the payments-internal pieces — customer clock + AWAITING_CUSTOMER → EXPIRED +
> late-money, the signal-driven simulator + conformance, and the REFUND_FAILED drainer — **retaining the
> transitional synchronous settle shim** so the Order flow stays green and **without touching the Order
> module**. **A4b** is the designated home for the cross-module inversion (remove the shim, Piece 3: the
> order async consumer/AWAITING_PAYMENT/resumable saga/timeout sweeper + create-order contract change).
> See the A4a gate report. (Original flag retained below for the record.)

> **OPEN (superseded by the resolution above).** Work stopped before any code per the A4 work
> order's own guardrail #4 ("Only the saga pay-step changes in the Order module. If Piece 3 appears to
> require any other Order module change, stop and flag."). Branch `tranche1/A4` created; nothing built.

**What I verified (discovery only):**
- The Order book-pay-fulfil saga is **fully synchronous in one transaction**: `OrderApplicationService
  .createOrder` persists the order, runs `saga.book()` (hold → pay → confirm-hold → issue-tickets →
  CONFIRMED, or compensate) to a terminal outcome, and flushes — **all in one `@Transactional`
  request**. (`BookPayFulfilSaga.kt:38–98`, `OrderApplicationService.kt:63–141`.)
- Order-service has **no Kafka consumer** (producer/outbox only — grep: no `@KafkaListener`, no
  `spring.kafka.consumer`), **no saga-state persistence / resume mechanism**, and **no
  `AWAITING_PAYMENT` order state** (`OrderStatus` = CREATED/CONFIRMED/IN_DELIVERY/FULFILLED/CLOSED/
  CANCELLED/EXPIRED).
- The pay-step is a synchronous HTTP `POST /v1/payments` that returns SETTLED/FAILED inline
  (`PaymentHttpAdapter`), backed by the payments **synchronous `settle` shim** that Piece 1 removes.

**Why Piece 3 is not a pay-step edit (the guardrail-#4 trigger):** making the pay-step
bounded-asynchronous requires, beyond editing the call site, ALL of the following NEW Order-module
work:
1. A new `OrderStatus.AWAITING_PAYMENT` state + transitions.
2. A **new inbound Kafka consumer subsystem** for `pss-payments-events` (consumer config +
   `@KafkaListener` + envelope parser + a consumed-event idempotency table) — order-service has none
   today; it would mirror dashboard-service's E2 consumer.
3. A **resumable saga**: park the order in AWAITING_PAYMENT after hold+initiate; resume on
   PaymentSettled (confirm-hold → tickets → CONFIRMED) / PaymentFailed (release → CANCELLED), each in a
   fresh transaction, idempotent across the resume boundary.
4. A **timeout sweeper** (`@Scheduled`) over AWAITING_PAYMENT past the customer clock → GetPaymentStatus
   → proceed/compensate (the ambiguous-outcome path, now clock-driven).
5. **Inverting the create-order contract**: once Piece 1 removes the synchronous settle, `createOrder`
   can no longer return a CONFIRMED order in the request — it returns AWAITING_PAYMENT and confirmation
   arrives later via the consumed event. This changes the Order API's observable behaviour (201
   CONFIRMED → 202 AWAITING_PAYMENT + poll/event) and **rewrites the existing synchronous order IT suite**
   (`OrderPaymentsIT` a–f and friends, which assert synchronous CONFIRMED/CANCELLED).

**Coupling:** Piece 1 (remove sync settle) FORCES Piece 3 — once `initiate` only opens a window, the
order saga cannot get an inline settled result and must go async. Pieces 1–3 are one coupled unit (as
the work order states); **Piece 4 (REFUND_FAILED drainer) is independent** (payments-internal).

**Piece 2 margin validation (done regardless, per the work order):** Inventory **default holdTTL =
`PT10M`** (`pss.inventory.hold.default-ttl`, max `PT1H`); customer-clock margin `PT60S` → window **540s,
ample headroom** — the clock fires well before the hold expires. **No margin flag.** (Note: the hold API
allows `ttlSeconds` down to 30 via `@Min(30)`; `CustomerClock.windowFor` already floors a sub-margin
hold at zero rather than going negative. The saga uses the default 10M.)

**Decision needed:** whether to execute the full two-module async inversion (items 1–5 + Pieces 1,2,4)
in this single A4 branch, or split it. Recorded here; reported to the operator. No code written.

### A2 PRE-FLIGHT (P1 enum reconcile · P2 sandbox keys) — 2026-06-14 — RECORDED, proceeding

Done before any adapter code, per the A2 work order. Authoritative sources read:
`PSS_Payments_Module_LLD.docx` §6 (state list) + §3.1/ledger, `PAYMENTS_LLD_DELTA_0_2_REAL_PSP.md`
§3/§4/§7, `payments-api.openapi.yaml`, the A1 conformance suite.

> **CORRECTED — 2026-06-16 (see "A2 FIX" gate report below).** The P1(a) conclusion was WRONG. The
> design authority confirmed `REFUND_PENDING` and `REFUND_FAILED` ARE first-class `PaymentStatus`
> states (LLD §3.2/§13 — "money still owed", queried by the A4 drainer), with the carried two-phase
> states `AUTHORIZED/CAPTURED/VOIDED` and `RefundStatus = PENDING | ISSUED | FAILED`. My grep-and-read
> over `PSS_Payments_Module_LLD.docx` §6 missed this (and the repo docx §6 still lists the older set —
> a docx-vs-design discrepancy flagged below). The regression is fixed: domain enum restored + contract
> aligned + a domain≡contract parity fitness check added so this can never wave through again.

**P1(a) — REFUND_FAILED absence: ~~NOT a regression~~ → SUPERSEDED, see correction above.**
The decision criterion (does the contract YAML or the A1 suite expect a `PaymentStatus.REFUND_FAILED`?)
resolved **no** on both at the time — but that was the bug: the contract itself had drifted from the LLD
and the A1 suite lacked the asserting case. The original (now-superseded) reasoning was:
- Contract `PaymentStatus` = `[INITIATED, AWAITING_CUSTOMER, PENDING, AUTHORIZED, SETTLED, FAILED,
  EXPIRED, REFUNDED, PARTIALLY_REFUNDED]` — no `REFUND_FAILED`; refund outcomes live in
  `RefundStatus = [PENDING, SUCCEEDED, FAILED]` (`Refund.status`).
- The v0.1 LLD §6 state list is `INITIATED → PENDING → AUTHORIZED → SETTLED; FAILED / EXPIRED;
  SETTLED → REFUNDED / PARTIALLY_REFUNDED` — `REFUND_FAILED`/`REFUND_PENDING` appear **nowhere** in the
  docx (0 hits); refund attempts are tracked by `RefundStatus {PENDING,SUCCEEDED,FAILED}` + the REFUND
  ledger transaction. `PARTIALLY_REFUNDED` IS a first-class v0.1 state (2 hits).
- The A1 conformance "refund failure" case proves it via `PspTransientException` at the port →
  `RefundStatus.FAILED` at the application layer (`RefundService`); it references no `PaymentStatus`.
- Delta §3's row "SETTLED → REFUND_PENDING → REFUNDED / REFUND_FAILED **(unchanged from v0.1)**" and §7's
  "select intents in REFUND_FAILED" are descriptive shorthand for the v0.1 `RefundStatus` chain
  (`PENDING → SUCCEEDED/FAILED`), not a new intent-level enum. The A4 drainer queries `Refund` rows in
  `RefundStatus.FAILED` (which the A0 `PaymentsTimingProperties` "REFUND_FAILED drainer" comment names).
  **Conclusion: refund-failure is modelled at the RefundStatus level; no `PaymentStatus.REFUND_FAILED`
  to restore. No regression.**

**P1(b) — two-phase states: claim holds → log.** Delta §3 names `AUTHORIZED/CAPTURED/VOIDED` "carried".
In the v0.1 model these are realised as: `PaymentStatus.AUTHORIZED` (present) + the ledger
`TransactionType.{AUTHORIZE, CAPTURE, VOID}` (all present) + the `AUTHORIZED → SETTLED` capture
transition (docx §6: "Funds captured; emit PaymentSettled"). `CAPTURED`/`VOIDED` are **ledger movements,
not intent states** — never separate `PaymentStatus` values in v0.1. `PENDING` is original v0.1
(`INITIATED → PENDING → AUTHORIZED`), not new. Two-phase capability is fully carried-but-unexercised.

**P1(c) — `FailureReason.EXPIRED` vs `PaymentStatus.EXPIRED`: distinct & intentional → log.** Two axes:
the lifecycle STATE (intent terminal on customer-clock timeout) vs the recorded REASON (docx §6: "emit
PaymentFailed (reason=expired)"). A1's `expire()` sets both. Not a copy-paste.

**P2 — sandbox keys / rotation: live gate is OPERATOR-RUN (harness cannot execute it).** Two hard
constraints: (1) the secret VALUES are out-of-band by design (never readable/loggable here), so key
**rotation cannot be confirmed from this harness**; (2) this harness has **no network egress to
api.razorpay.com and no injected sandbox keys** — the Razorpay sandbox is unreachable. Per the work
order ("if rotation hasn't happened, do not hit the sandbox with the old keys") and the same deferral
A0 established for its live webhook gate, A2 **does not attempt any sandbox call**. The adapter is built
and proven deterministically (paise property test + adapter unit tests over a mocked Razorpay HTTP
layer + the no-Razorpay-symbol fitness rule); the **live conformance gate against the Razorpay sandbox
is operator-run** with the rotated ACME/TEST keys, exactly per delta §10.


### FLAG-001 — 2026-06-12 — Context pack absent; Tranche-1 build cannot start

> **RESOLVED — 2026-06-12** (resolving commit `cc2e0cb` "Land Tranche-1 context pack"). The design
> seat landed the full context pack into the repo. Verified present (all 10 files): the 7 design
> docs in `docs/` — `PAYMENTS_LLD_DELTA_0_2_REAL_PSP.md`, `BREADTH_DELTA_0_2_MULTIPAX_RETURN.md`,
> `ANCILLARIES_DELTA_0_2_SEAT_BAG.md`, `DASHBOARD_DELTA_0_2_LIVE_READSIDE.md`,
> `BUILD_PLAN_PHASE_2_0_TRANCHE_1.md`, `BUILD_PLAN_TRANCHE_1_ADDENDUM_1.md`, `DEMO_SCRIPT.md` — and
> the 3 new contracts under `docs/contracts/` — `ancillary-api.openapi.yaml`,
> `shop-performed.schema.json`, `README_TRANCHE1_CONTRACTS.md`. The blocker below is cleared;
> milestone work (A0 / B1 / D1 / E1) may begin per the kickoff, one milestone per session/branch.

**Prime rule invoked:** design governs; generate against contracts, never prose; a needed shape
missing from a contract is a FLAG, not an improvisation. Milestone work requires the authoritative
design documents and the new contracts to be present in the repo. They are **not present** (verified
both in git-tracked files and the working tree).

**Missing — design (governs everything):**
- `PAYMENTS_LLD_DELTA_0_2_REAL_PSP.md`
- `BREADTH_DELTA_0_2_MULTIPAX_RETURN.md`
- `ANCILLARIES_DELTA_0_2_SEAT_BAG.md`
- `DASHBOARD_DELTA_0_2_LIVE_READSIDE.md`
- `BUILD_PLAN_PHASE_2_0_TRANCHE_1.md`
- `BUILD_PLAN_TRANCHE_1_ADDENDUM_1.md`
- `DEMO_SCRIPT.md` (the exit criterion)

**Missing — new Tranche-1 contracts (must live under `docs/contracts/`):**
- `ancillary-api.openapi.yaml`
- `shop-performed.schema.json`
- `README_TRANCHE1_CONTRACTS.md`

**Present:** only the Phase-1 contracts — `order-api`/`order-events`, `payments-api`,
`inventory-api`/`-events`, `offer-api`/`-events`, `pricing-api`/`-events` — and the Phase-1 LLD set.

**Impact — all four kickoff work orders are blocked on missing authoritative inputs:**
| Session | Milestone | Blocked on (absent) |
|---|---|---|
| 1 | A0 (env) | `BUILD_PLAN_PHASE_2_0_TRANCHE_1.md` |
| 2 | B1 (breadth) | `BUILD_PLAN…` + `BREADTH_DELTA_0_2_MULTIPAX_RETURN.md` |
| 3 | D1 (ancillary catalogue + quote) | `ANCILLARIES_DELTA_0_2_SEAT_BAG.md` + `docs/contracts/ancillary-api.openapi.yaml` |
| 4 | E1+E2 (dashboard seed) | `DASHBOARD_DELTA_0_2_LIVE_READSIDE.md` + `docs/contracts/shop-performed.schema.json` |

**Note on delivery mechanism:** the `DesignSync` tool that surfaced this session is for claude.ai
*design-system* (UI component-library) projects — HTML previews/specs — **not** a mechanism to sync
these engineering design docs / OpenAPI / JSON-Schema files into the repo. It is not the delivery
path for the context pack.

**Action:** STOP. No milestone started; nothing improvised from the kickoff prose. This is a
missing-input blocker, not a code/design disagreement — resolution is the design seat's: land the
context pack into the repo (the design docs at the repo root or `docs/`; the three new contracts
under `docs/contracts/`). Once present, A0 / B1 / D1 / E1 can begin per the kickoff, one milestone
per session/branch.

This bootstrap commit lands only the two design-independent foundations the kickoff mandates:
this `BUILD_LOG.md` (with this FLAG) and the repo-root `CLAUDE.md` standing rules.

---

### FLAG-002 — 2026-06-12 — B2 blocked: offer-api contract missing the Breadth-delta Stage-1 shapes

> **RESOLVED — 2026-06-12** (resolving contract: `docs/contracts/offer-breadth-api.openapi.yaml`). The
> design seat landed the breadth-delta'd Offer contract: it adds the two-stage `ShopResponse`
> (direction-grouped), the `Offer.stage {CANDIDATE, JOURNEY}` discriminator, and the canonical
> per-(passenger × segment) `OfferItem` (`itemRef`/`directionIndex`/`segmentRef`/`paxRef`/`ptc`/`base`/
> `taxLines`/`amount`) — with the money invariant Σ `items[].amount` = `grandTotal` written into the
> schema. It also **pre-covers B3**: `POST /v1/offers/combine`, the `JOURNEY` stage, and the error
> codes `CANDIDATES_INCOMPATIBLE` / `OFFER_EXPIRED` / `OFFER_NOT_ORDERABLE`. Backward compatibility is
> made precise: ONE_WAY returns one direction of `stage=JOURNEY` offers (Phase-1 behaviour preserved);
> RETURN returns two directions of `stage=CANDIDATE` offers. B2 is generated against this file; the
> blocker below is cleared.

**Status: RESOLVED — B2 proceeds, generated against `offer-breadth-api.openapi.yaml`.**

**Prime/Contracts rule invoked:** "Generate handlers/clients/types from the OpenAPI/JSON-Schema
files; if a needed shape is missing from a contract, that is a FLAG, not an improvisation." Building
B2 (Offer: shopping context + Stage 1) requires wire shapes that `docs/contracts/offer-api.openapi.yaml`
does **not** contain. The breadth delta describes them in prose (§3.1–3.3) but the Offer contract was
never updated for the breadth delta — so there is nothing to generate against, and hand-authoring the
OpenAPI is exactly the improvisation the rule forbids.

**Design (BREADTH_DELTA_0_2_MULTIPAX_RETURN.md) vs contract (`offer-api.openapi.yaml`) — the gaps:**

| Delta requirement | Needed wire shape | In `offer-api.openapi.yaml`? |
|---|---|---|
| §3.2 Stage-1 candidate offers flagged `stage: CANDIDATE` (and `JOURNEY` at B3); Order rejects non-JOURNEY (`OFFER_NOT_ORDERABLE`) | `Offer.stage` enum `{CANDIDATE, JOURNEY}` | **Absent** — `Offer` has only `status {VALID,EXPIRED,ACCEPTED}`, no `stage` |
| §3.3 one AIR item per (passenger × segment), each carrying its quote slice; Σ items = grandTotal asserted at assembly | `OfferItem` with `itemRef`, `directionIndex`, `segmentRef`, `paxRef (P1…Pn)`, `ptc`, `price`, `taxes[]` | **Absent** — `OfferItem` is the Phase-1 shape `{type, productRef, segments, cabin, rbd, amount}` (no `itemRef`/`directionIndex`/`segmentRef`/`paxRef`/`ptc`/`taxes`) |
| §3.2 Shop response groups candidates by direction | `ShopResponse` direction grouping (or per-offer `directionIndex`) | **Absent** — `ShopResponse` is a flat `{offers: [...]}` |
| §3.1 ShoppingContext gains `tripType {ONE_WAY|RETURN}` | (internal-derivable from `returnDate`; listed for completeness) | `ShopRequest` has `returnDate`/`passengers` but no `tripType` |

These are **wire** fields, not internal-only: the Shop response feeds the IBE/BFF per-passenger
breakdown (delta §7, Scene 2) and the Order seam's candidate-rejection. The arithmetic invariant
(Σ item `price`+`taxes` = quote `grandTotal`) cannot be expressed against the `amount`-only item shape.
So B2 cannot be built faithfully against the current contract.

**Corroboration:** `docs/contracts/README_TRANCHE1_CONTRACTS.md` lists only `ancillary-api.openapi.yaml`
and `shop-performed.schema.json` as the tranche-1 delta contract artifacts — there is **no** updated
`offer-api.openapi.yaml`. The breadth delta's Offer contract changes were never materialised into a
contract (unlike the ancillaries and dashboard deltas, which shipped their contracts for D1/E1).

**Scope of the gap:** B2 (Stage 1) needs the `stage`, `OfferItem` and direction-grouping additions
above. The same missing artifact also blocks **B3** (`POST /v1/offers/combine`, `stage: JOURNEY`,
422 `CANDIDATES_INCOMPATIBLE`, 410 expired) and the Order-side `OFFER_NOT_ORDERABLE` — none of which
exist in any contract either. A single design-seat deliverable (the breadth-delta'd `offer-api`)
unblocks B2 and B3 together.

**Touches none of the hard stop-list items** (no saga, compensation, or money-invariant change is
implied — the invariant is *additive* assertion at assembly). The blocker is purely the absent
additive contract.

**Action:** STOP at B2. Resolution is the design seat's: land the breadth-delta'd
`docs/contracts/offer-api.openapi.yaml` (Stage-1 `stage` discriminator, the per-(passenger × segment)
`OfferItem` shape, direction-grouped `ShopResponse`; and, for B3, the `/v1/offers/combine` operation
and its error codes) — exactly as the ancillaries/dashboard deltas shipped `ancillary-api.openapi.yaml`
and `shop-performed.schema.json`. Once present, B2 can be generated against it. No code written; the
`offer-api.openapi.yaml` was **not** edited (that is the design seat's artifact, not mine to author).

---

### FLAG-004 — 2026-06-28 — C0 (corporate-booking) blocked: authoritative design absent, scope outside the ratified tranche

> **RESOLVED — 2026-06-28.** The design seat landed the two authoritative documents at the repo root —
> `CORPORATE_DELTA_0_3_THIN_BOOKING.md` and `BUILD_PLAN_CORPORATE_TRANCHE.md` (delivered via the operator's
> Google Drive, downloaded and verified byte-for-byte: 12267 / 6961 bytes). Both ratify the Corporate/SME
> thin slice as a deliberate Phase-2 tranche (C0–C3) sitting **above** the NDC distribution surface, with
> its own standing rules (design governs; no Order/Offer/distribution internals change; policy engine is a
> pure function; triple-scope isolation; `sellerRef` tagging; port 8085). The roadmap-vs-present concern is
> answered: this is the planned next tranche, not scope creep. C0 is built against the landed spec — see
> the C0 gate report below. (Original flag retained for the record.)

**Prime rule invoked:** "Design governs. The five design documents … are authoritative. Where code and
design disagree: STOP, do not reinterpret." And the Contracts rule: "if a needed shape is missing from
a contract, that is a FLAG, not an improvisation." Same shape as FLAG-001 — a missing-input blocker,
not a code/design disagreement.

**Request:** read `CORPORATE_DELTA_0_3_THIN_BOOKING.md` and `BUILD_PLAN_CORPORATE_TRANCHE.md` from the
repo root, then build **C0** — a new `corporate-booking` service (port 8085, package `pss.corporate`)
with a `CorporateAccount` account store — on branch `feat/corporate-booking`.

**What I verified (discovery only — no code, no branch, no service created):**
- **Both named design documents are absent.** `CORPORATE_DELTA_0_3_THIN_BOOKING.md` and
  `BUILD_PLAN_CORPORATE_TRANCHE.md` exist nowhere: not at the repo root, not under `docs/`, not in any
  git-tracked file on any branch, and not in the untracked `files.zip` (which holds the DCS service
  Kotlin sources, unrelated). Grep for `corporate` across the whole tree returns only three incidental
  hits, all of which place corporate booking in the **future**, not the present:
  - `docs/DEMO_SCRIPT.md` Scene 8 — "NDC/B2B distribution, **corporate booking**, loyalty,
    DCS/day-of-departure: **roadmap slides** … breadth on a stabilized contract, operations after the
    commercial core is proven."
  - `docs/DEMO_SCRIPT.md` §7 "Explicitly out (deferred without guilt)" — "**Corporate/SME**" is named
    in the out-of-scope list.
  - `LATEST_STATUS.md` Next Steps — "Plan **Phase 2.1 / next tranche** — roadmap candidates … corporate
    booking." I.e. corporate booking is an unplanned **Phase 2.1** candidate, not a ratified milestone.
- **There is no "C0" milestone, no corporate tranche, and no `corporate-api` contract.** The ratified
  tranche (`BUILD_PLAN_PHASE_2_0_TRANCHE_1.md` + addendum) runs A0–A5 ∥ B1–B6 ∥ D1–D5 ∥ E1–E4 → C1 → C2;
  its "C" milestones are C1/C2 (CI smoke + final integration), unrelated to a "C0" corporate skeleton.
  No `CorporateAccount` shape exists in any OpenAPI/JSON-Schema under `docs/contracts/`.

**Why I did not build it from the prompt:** the inline prose in the request (entity field list, port,
package) is exactly the "kickoff prose" the prime rule forbids improvising from. A new bounded context
— with its own account lifecycle (ACTIVE/SUSPENDED), tenancy, and the money/billing semantics a
corporate-booking service will carry — must be generated against a ratified delta + build plan +
contract, as every prior service was (NDC, DCS, dashboard each shipped its delta first). Standing up
the service now would also pull in scope the current tranche explicitly defers (Scene 8 / §7).

**Action:** STOP. No branch created, no service scaffolded, nothing improvised. Resolution is the
design seat's: land `CORPORATE_DELTA_0_3_THIN_BOOKING.md` and `BUILD_PLAN_CORPORATE_TRANCHE.md` (and the
`corporate-api` contract carrying the `CorporateAccount` shape) into the repo — exactly as FLAG-001 was
resolved by landing the Tranche-1 context pack. Once present, C0 can be built against them, one
milestone per session/branch. This FLAG is the only change in the working tree.

---

### FLAG-005 — 2026-07-01 — Loyalty L1 blocked: authoritative contracts + delta absent from the repo

> **RESOLVED — 2026-07-01.** The design seat landed the four authoritative inputs into the repo,
> verified on disk: `docs/LOYALTY_DELTA_0_3_EARN_REDEEM.md` (11677 B), `docs/README_LOYALTY_CONTRACTS.md`
> (5932 B), `docs/contracts/loyalty-api_openapi.yaml` (22890 B), `docs/contracts/loyalty-events_schema.json`
> (7432 B). `copyEventSchemas`' source now resolves, so the `MilesAccrued`/`TierChanged` shapes are fixed and
> the schema-conformance test has a canonical target. L1 is built against them on `feat/loyalty-l1` — see the
> L1 gate report below. Two plan-vs-contract details were reconciled in favour of the OpenAPI/JSON-Schema
> (which plan §7 makes authoritative): ManualEarn's 201 body is `PointsTransaction` (not the plan §6 prose's
> `MemberSummary`), and the earn engine uses the build-plan §4 `EarnInput` signature (not the delta §5
> `OrderSummary` prose — the delta itself notes the value type generalises L1+L2). (Original flag retained.)

**Prime rule invoked:** "Design governs … Where code and design disagree: STOP, do not reinterpret."
And the Contracts rule: "if a needed shape is missing from a contract, that is a FLAG, not an
improvisation." Same shape as FLAG-001/FLAG-004 — a missing-input blocker.

**Request:** merge PR #56 (done) and PR #57 (done), branch off `master`, and build **Loyalty L1**
(points ledger + earn/tier engines + ManualEarn + `MilesAccrued`/`TierChanged` emission) in
`services/loyalty-service`, per `docs/BUILD_PLAN_LOYALTY_L1.md`.

**What I verified (discovery only — feature branch `feat/loyalty-l1` created off the synced master,
no service code written):** the L1 build plan names five "authoritative specs" to read *first* and match
*exactly*, and forbids guessing the event/API shapes. **Four of the five, plus the tranche plan, are
absent** — `git ls-files` returns only `docs/BUILD_PLAN_LOYALTY_L1.md` for anything loyalty-doc-shaped:
- `docs/LOYALTY_DELTA_0_3_EARN_REDEEM.md` — absent (plan's primary authoritative spec).
- `docs/contracts/loyalty-api_openapi.yaml` — absent (plan §7: "match … exactly").
- `docs/contracts/loyalty-events_schema.json` — absent (plan §8: "read it, don't guess"; §9 schema-
  conformance test validates against it). `docs/contracts/` carries every *other* service's contract
  but nothing for loyalty.
- `docs/README_LOYALTY_CONTRACTS.md` — absent.
- `docs/BUILD_PLAN_LOYALTY_TRANCHE.md` — absent.
- Corroborating defect: `services/loyalty-service/build.gradle.kts:52` (`copyEventSchemas`) already
  points `from(../docs/contracts/loyalty-events_schema.json)` at a non-existent file. Gradle `Copy`
  no-ops silently on a missing source, so the L0 build stays green, but there is **nothing on the test
  classpath for a `MilesAccrued`/`TierChanged` schema-conformance test to validate against**.

**Why I did not build it from the plan prose:** the contract-independent core (V2 ledger migration,
`PointsTransaction`/`EarnRule`/`TierChangeEvent` entities, the pure `EarnRulePort`/`TierPolicyPort`
engines + conformance suites, projection, idempotent `LedgerService`, ManualEarn) is fully specified by
the plan and buildable. But the **event emission** (`MilesAccrued`/`TierChanged` envelope + payload field
names), the **API field shapes** (`GET balance`/`transactions`/`POST earn` request/response bodies), and
the **schema-conformance test** the L1 gate's DoD requires cannot be produced without the events schema
and OpenAPI — and the plan explicitly forbids guessing them. Inventing an envelope now risks a contract
that silently diverges from the canonical schema the gate is meant to enforce.

**Action:** STOP before writing service code. Branch `feat/loyalty-l1` exists (off the synced master
carrying #56 + #57); no L1 source, migration, or test written; nothing improvised. Resolution is the
design seat's: land `LOYALTY_DELTA_0_3_EARN_REDEEM.md`, `loyalty-api_openapi.yaml`,
`loyalty-events_schema.json`, and `README_LOYALTY_CONTRACTS.md` into the repo (exactly as the corporate
deltas were landed to clear FLAG-004). Once present — the `copyEventSchemas` source resolves and the
`MilesAccrued`/`TierChanged` shapes are fixed — L1 builds against them in one pass. This FLAG is the only
change in the working tree beyond the branch.

---

### FLAG-006 — 2026-07-01 — Reporting & BI: spec `order_fact` fields not derivable from the stated consumer set

> **RESOLVED — 2026-07-01.** Operator ratified two decisions: (1) **add `OrderCreated`** to the consumer set
> — reporting sources `currency` (`totals.currency`), `pax_count` (`passengers[]`) and `ancillary_revenue`
> (Σ `items[type=ANCILLARY].price`) from it, fully event-sourced (mirrors dashboard's `OrderAncillaryLedger`
> staging); (2) **`origin`/`destination`/`channel` are nullable and left null this slice** — no correlatable
> event carries them (only `ShopPerformed`, keyed by an un-joinable `searchId`), so they stay unpopulated
> (not sourced from any non-event) pending a future contract enrichment. Built against these on
> `feat/reporting-bi` — see the gate report below. (Original flag retained.)

**Rule invoked:** the Reporting & BI tranche's ONE ARCHITECTURAL RULE — "reporting-service consumes events
and nothing else … if a figure can't be derived from published events, stop and flag" — and its stop-and-flag
list ("any figure derived from anything other than published domain events"). Same shape as the L2 base-fare
gap: a thin event carries less than the projection needs.

**Request:** build `reporting-service` consuming **OrderConfirmed / OrderCancelled / RefundIssued**, and
populate `order_fact { order_id, status, amount_paid, currency, pax_count, origin, destination, occurred_at,
channel }` + `daily_booking_summary { …, ancillary_revenue }`.

**What I verified (schemas in `docs/contracts/`, discovery only — no service code written):**
- `OrderConfirmed.payload` = `{ amountPaid, documentRefs }` only. `OrderCancelled` = `{ reason, refundRef? }`.
  `RefundIssued` = `{ refundId, amount{amount,currency} }`. (`order_id`/`tenantId`/`occurredAt` on the envelope.)
- **Derivable** from the stated set: `order_id`, `status`, `amount_paid`, `occurred_at`; daily `bookings`,
  `revenue_net`, `cancellations`, `refunds`.
- **NOT derivable** from the stated set:
  - `ancillary_revenue`, `currency`, `pax_count` — live on **`OrderCreated`** (`totals.currency`,
    `passengers[]`, `items[type=ANCILLARY].price`), which is **not in the consumer list**. Fixable by
    consuming `OrderCreated` too (event-sourced; mirrors dashboard's `OrderAncillaryLedger`).
  - `origin`, `destination`, `channel` — carried by **no correlatable order event**. Only `ShopPerformed`
    has them, keyed by `searchId`, and **no published event links `searchId`→`orderId`** (and it is
    telemetry-grade, at-most-once, "MUST NOT derive money"). Not event-derivable without a contract change
    (enrich `OrderCreated` with route+channel, or add `searchId` to order events).

**Action:** STOP before writing the consumer/projection. Branch `feat/reporting-bi` exists off master; no
service scaffolded. Awaiting two ratifications: (1) add `OrderCreated` to the consumer set to source
currency/pax/ancillary; (2) how to treat route+channel (nullable/UNKNOWN this slice vs block on a contract
enrichment). This FLAG is the only change in the working tree beyond the branch.

---

### FLAG-007 — 2026-07-02 — Personalisation/CDP: offer-service PersonalisationPort swap is not config-only (deferred)

**Rule invoked:** the CDP tranche invariant — "the ONLY permitted change is the offer-service
PersonalisationPort bean swap (HTTP adapter replacing the pass-through stub), and only if it's a config-only
change. If even that is too invasive, stop and flag."

**What I verified (offer-service, read-only):** `com.pss.offer.personalisation.PersonalisationPort` exists
(`fun rank(tenantId, context: ShoppingContext, offers: List<Offer>): List<Offer>`) and `PassThroughPersonalisation`
is a `@Component` returning offers unchanged, injected into `AssemblyService`. Swapping it to call cdp-service
would require, in offer-service: (a) a NEW adapter class implementing `PersonalisationPort` with an outbound
`RestClient` to `POST /v1/cdp/rank-offers`; (b) `Offer`↔`OfferSummary`/`RankedOffer` mapping; (c) conditional
bean wiring to displace the `@Component` stub; (d) graceful-degradation handling when cdp is down. That is
**new code + a new runtime dependency in a Tranche-1 core module — not a config-only change** — and would put
offer-service's own ITs at risk.

**Action:** STOP at the offer-service boundary — **no offer-service files touched.** Deliver cdp-service
standalone; its `POST /v1/cdp/rank-offers` endpoint IS the PersonalisationPort surface, ready for offer-service
to call once that adapter is approved as its own scoped change. The CDP DoD does not require the swap; it is
satisfied by cdp-service alone (see the gate report). Resolution: a follow-up PR adding the offer-service HTTP
adapter behind a `@ConditionalOnProperty` bean, reviewed against offer-service's tests.

---

## GATE REPORTS

_Open for milestones — FLAG-001 cleared (2026-06-12). Append one gate report per milestone (A0, B1,
D1, E1, …): milestone, gate checks run, results, deviations (should be none), and the measured
numbers for the open decisions awaiting ratification._

### C3 — corporate-service: billing statement stub + demo Postman collection — 2026-06-28 — GATE GREEN

**Milestone (BUILD_PLAN_CORPORATE_TRANCHE.md §C3 / CORPORATE_DELTA_0_3_THIN_BOOKING.md §3, §8):** C3 — the
final corporate milestone: the consolidated `BillingStatement` stub, the polish-grade demo Postman
collection, and the `PolicyCompliantBookingIT` CI smoke. On merge the tranche tags `v0.3.2-corporate-thin`.
Branch `feat/corporate-booking` (synced to master after #52 merged).

**Scope built (delta §3/§8, build plan §C3):**
- **Flyway V4** `corporate_billing_statements` ((tenant, account)-scoped; status CHECK). `BillingStatement`
  entity + `BillingStatus {OPEN|CLOSED|SETTLED}`. **(The §C3 prose says "Flyway V3", but V3 is already
  `corporate_bookings` from C2 — the billing migration is the next slot, V4. Recorded, not a FLAG.)**
- **`BillingStatementService`** — `GET …/statements` lazily generates an **OPEN** current-calendar-month
  stub (aggregated from the account's bookings: Σ totalAmount, bookingCount) when none exists, and returns
  existing statements unchanged otherwise; `GET …/statements/{id}` returns the statement plus the bookings
  in its period, or `STATEMENT_NOT_FOUND` (404). Tenant-scoped (account verified → ACCOUNT_NOT_FOUND).
- **`BillingStatementController`** — tenant-scoped, **not** admin-gated (a reporting view; the demo reads it
  with just the bearer). `{ "statements": [...] }` list shape.
- **Demo collection** `docs/demo/corporate-booking-demo.postman_collection.json` — Postman v2.1, 7 requests
  across folders 0–4 (Register Company → Add Cost Centre → Set Travel Policy → Register Traveller → Book
  Compliant Flight → Get Booking → View Billing Statement). Collection-level pre-request mints the unsigned
  demo JWT (mirrors the NDC collection), variables chain account/costCentre/traveller/booking ids, every
  request has a prospect-readable description, and `info.description` warns that the booking step needs
  distribution-service (:8084) → offer-service (:8082) → order-service (:8081) live.
- **CI smoke:** `PolicyCompliantBookingIT` is the unattended smoke (no shell script), per §C3.

**Gate checks run — `:corporate-service:test --tests "pss.corporate.*"` (42 tests; 26 ran GREEN, 16 ITs
skipped pending Docker; 0 failures, 0 errors):**
- **`BillingStatementServiceTest` — 4, GREEN:** no-prior → generates a current-month stub aggregated from
  bookings (period 1st→last of month, OPEN, bookingCount/total correct); existing → returned without
  regenerating (no save, no booking query); GetStatement → statement + only the in-period bookings;
  unknown id → `STATEMENT_NOT_FOUND` (404).
- **Postman collection** validates as JSON (`json.load` OK) and matches the §C3 structure exactly.
- **Fitness — all 9 PASS:** `check_tenant_context` now 25 controllers (the new `BillingStatementController`
  reads the tenant via `TenantContextHolder`).

**Deviations:** none material. Recorded decisions (none a FLAG): (1) the billing migration is **V4** (V3 was
C2's `corporate_bookings`). (2) `BillingStatement` carries **no `@Version`** — the explicit §C3 DDL and the
delta §3 field list have no version column, and only `CorporateAccount` among corporate entities uses one;
adding one would itself deviate and break `ddl-auto=validate`. (3) **`costCentreCode` added to the GetBooking
response** (`BookingDetailResponse`), derived from the stored `sellerRef` (`CORP-{accountId}-CC-{code}`) — the
§C3 demo's "Get Booking" step asserts `costCentreCode == ENG-001`, so the field is required for the demo to
pass; additive, same service, no logic change. Stop-list respected: **no order-service / offer-service /
distribution-service change** (verified by `git diff origin/master...HEAD`), **no new events**, nothing
beyond the §C3 spec.

**Measured numbers awaiting ratification:** none for C3. **Tranche complete at C3 — ready to tag
`v0.3.2-corporate-thin` on merge.**

### C2 — corporate-service: PolicyCompliantBooking + CorporateBookingCreated (headline gate) — 2026-06-28 — GATE GREEN

**Milestone (BUILD_PLAN_CORPORATE_TRANCHE.md §C2 / CORPORATE_DELTA_0_3_THIN_BOOKING.md §5):** C2 — the
headline gate. `POST /v1/corporate/bookings` runs the full flow (load+validate traveller/cost-centre/active
policy → distribution-service AirShopping → OfferPrice → pure policy check → OrderCreate tagged with
`sellerRef`), persists the `CorporateBooking`, and publishes `CorporateBookingCreated` via the
transactional outbox. Branch `feat/corporate-booking` (synced to master after #51 merged).

**Scope built (delta §5, build plan §C2):**
- **Flyway V3** — `corporate_bookings` ((tenant, account)-scoped; cabin/policy-status CHECKs) + the
  `CorporateBooking` entity and `PolicyStatus {COMPLIANT|ADVISORY|VIOLATION}`.
- **`DistributionServiceClient`** — `RestClient` over `SimpleClientHttpRequestFactory` (HTTP/1.1, the
  WireMock-friendly idiom distribution's own OfferServiceClient uses), base URL
  `pss.corporate.distribution-service-url` (default `:8084`). Calls the existing NDC contract verbatim
  (`/v1/ndc/air-shopping`, `/offer-price`, `/order-create`) with a minted tenant JWT + `X-Seller-Id`; adds
  **no** NDC surface and changes nothing in distribution-service.
- **`PolicyCompliantBookingService`** — the orchestration: ACTIVE-account guard (→ ACCOUNT_SUSPENDED),
  traveller/cost-centre/active-policy guards, cheapest-offer selection (→ OFFER_NOT_AVAILABLE when none),
  re-priced via OfferPrice, the **pure** `TravelPolicyPort` decision, `Violation` → POLICY_VIOLATION (no
  order/booking/event), `Compliant`/`Advisory` → OrderCreate with
  `sellerRef = CORP-{accountId}-CC-{costCentreCode}`, persist, then `CorporateBookingCreated` to the outbox
  in the same transaction. `GetBooking` / `ListBookings` round it out.
- **`CorporateBookingCreated`** envelope (eventId/type/version/tenantId/occurredAt/payload) — the single
  event corporate-service emits, written to `corporate_outbox_events` (the relay built dormant at C0 now
  has traffic). Typed errors added: `POLICY_VIOLATION` (409), `ACCOUNT_SUSPENDED` (403), `OFFER_NOT_AVAILABLE`
  (422), `BOOKING_NOT_FOUND` (404).

**Gate checks run — `:corporate-service:test --tests "pss.corporate.*"` (38 tests; 22 ran GREEN, 16 ITs
skipped pending Docker; 0 failures, 0 errors):**
- **`PolicyCompliantBookingServiceTest` (unit, real engine + mocked distribution) — 7, GREEN:** COMPLIANT
  (persists + writes CorporateBookingCreated) · `sellerRef = CORP-{accountId}-CC-{code}` on both the
  OrderCreate request and the stored booking · POLICY_VIOLATION blocks with **no** order/booking/outbox ·
  ADVISORY proceeds with warnings · ACCOUNT_SUSPENDED 403 · NO_ACTIVE_POLICY 404 · TRAVELLER_NOT_FOUND 404.
- **`PolicyCompliantBookingIT` (Testcontainers + WireMock, 2)** — full path register→cost-centre→traveller
  →policy→book→200 COMPLIANT tagged with sellerRef, `CorporateBookingCreated` in `corporate_outbox_events`,
  GetBooking returns it; and the maxFare=1000 / offer=5499 → 409 POLICY_VIOLATION with no booking row. Runs
  on CI/Docker (skips locally — Testcontainers can't reach the Docker Desktop npipe from the Gradle test
  JVM, the documented Windows posture). WireMock stubs AirShopping/OfferPrice/OrderCreate, mirroring
  distribution's own `AirShoppingIT`.
- **Fitness — all 9 PASS:** `check_tenant_context` now 24 controllers (the new `BookingController` reads the
  tenant via `TenantContextHolder`); `check_no_cross_module_db` clean (corporate reaches distribution only
  over HTTP, never a foreign datasource).

**Deviations:** none from the spec. Recorded decisions (grounded in the design, none a FLAG): (1) the
corporate **accountId is the NDC `X-Seller-Id`** (delta §7 / ratified decision 3 maps a corporate account
to a DIRECT_API SellerProfile); the SellerProfile *registration* that formalises this for the live demo is
a C3/integration step — under WireMock no distribution change is needed. (2) The thin slice books the
single named **traveller as P1** (`passengerCount` sizes the shopping request); multi-pax corporate is a
later slice. (3) **GetBooking returns `warnings: []`** — advisory warnings are surfaced on the POST
response (live evaluation) and are not persisted (the §3 booking column list has no warnings column).
Stop-list respected: **no order-service / offer-service / distribution-service change** (verified by
`git status`), **no event beyond `CorporateBookingCreated`**, every query `(tenantId, accountId)`-scoped
(seller isolation intact), and the policy engine stays a pure function.

**Measured numbers awaiting ratification:** none for C2. **STOP at C2 — C3 not started.**

### C1 — corporate-service: cost centres + travellers + travel policy + policy conformance — 2026-06-28 — GATE GREEN

**Milestone (BUILD_PLAN_CORPORATE_TRANCHE.md §C1 / CORPORATE_DELTA_0_3_THIN_BOOKING.md §3–§4, §6):** C1 —
the account directory (`CostCentre`, `TravellerProfile`, `TravelPolicy` + Flyway V2), their CRUD APIs, and
the pure `TravelPolicyPort` engine with its conformance suite. Branch `feat/corporate-booking` (synced to
master after #50 merged). Same conventions as `distribution-service`.

**Scope built (delta §3/§4/§5/§6, build plan §C1):**
- **Flyway V2** — three (tenant, account)-scoped tables: `corporate_cost_centres`, `corporate_travellers`,
  `corporate_travel_policies` (cabin-class CHECK; active-lookup index). Entities `CostCentre`,
  `TravellerProfile`, `TravelPolicy` + the `CabinClass {ECONOMY|BUSINESS|ANY}` enum.
- **CRUD APIs** (admin-gated, `(tenantId, accountId)`-scoped, account verified first → ACCOUNT_NOT_FOUND):
  cost centres `POST` / `GET {id}` / `GET` (list); travellers `POST` / `GET {id}`; policies `POST` (marks
  the prior active policy inactive — single-active invariant held in one transaction) / `GET …/active`
  (→ 200 or `NO_ACTIVE_POLICY` 404). Three new `@RestController`s, each reading the tenant via
  `TenantContextHolder`. Problem handling centralised in a `@RestControllerAdvice`.
- **`TravelPolicyPort`** + `OfferSummary` / `PolicyDecision` / `PolicyViolation` / `PolicyWarning` (delta
  §4/§6) and the in-process `InProcessTravelPolicyEngine` — a **pure function**, no Spring/DB/HTTP. Rules:
  fare-limit (VIOLATION, or ADVISORY when `requiresApproval`), cabin-class (VIOLATION), advance-booking
  (ADVISORY). Decision precedence Violation > Advisory > Compliant.
- **Typed errors:** `COST_CENTRE_NOT_FOUND`, `TRAVELLER_NOT_FOUND`, `NO_ACTIVE_POLICY` (all 404),
  `ACCOUNT_NOT_FOUND` reused from C0.

**Gate checks run — `:corporate-service:test --tests "pss.corporate.*"` (29 tests; 15 ran GREEN, 14 ITs
skipped pending Docker; 0 failures, 0 errors):**
- **Conformance (`TravelPolicyConformanceTest` over the abstract `TravelPolicyConformanceSuite`) — 5, GREEN:**
  fare-limit VIOLATION (no approval) · fare-limit ADVISORY (approval required) · cabin-class VIOLATION
  (BUSINESS under an ECONOMY-only policy) · advance-booking ADVISORY (2d vs 7d) · all-rules-pass COMPLIANT.
- **Directory unit (`CorporateDirectoryServiceTest`) — 6, GREEN:** scoping (ACCOUNT_NOT_FOUND), the three
  not-found errors, and CreatePolicy superseding the prior active policy.
- **Integration (`CorporateDirectoryIT`, 8) + C0 `RegisterCorporateAccountIT` (6)** — run on CI/Docker
  (skip locally — Testcontainers can't reach the Docker Desktop npipe from the Gradle test JVM, the
  documented Windows posture). `CorporateDirectoryIT` covers cost-centre/traveller CRUD, policy
  supersession + GetActivePolicy, `NO_ACTIVE_POLICY`, the not-found errors, ACCOUNT_NOT_FOUND, and tenant
  isolation.
- **Fitness — all 9 PASS:** `check_tenant_context` now inspects 23 controllers (the three new ones
  reference `TenantContextHolder`; the `@RestControllerAdvice` is correctly excluded). No cross-module DB,
  actuator/OTel intact.

**Deviations:** none from the spec. Decisions recorded (each grounded in §4, none a FLAG): (1) **cabin
allow-set** — `ANY` permits any cabin; a specific policy cabin is an exact-match allow-list (demo slice;
the conformance suite doesn't exercise a BUSINESS-policy/economy-booked case). (2) **No FK / no
uniqueness** on cost-centre `code` or traveller `cost_centre_id` — the C1 typed-error set names no
duplicate/dangling-ref error, so no un-handled constraint can surface as a 500; referential validation is
a C2 concern. Stop-list respected: **no order-service / offer-service / distribution-service change**, **no
new events** (the outbox stays dormant until C2), every query `(tenantId, accountId)`-scoped, and the
policy engine is a pure function (no Spring context).

**Measured numbers awaiting ratification:** none for C1 (the three open decisions are RATIFIED in the build
plan). **STOP at C1 — C2 not started.**

### C0 — corporate-service: skeleton + account store + RegisterAccount — 2026-06-28 — GATE GREEN

**Milestone (BUILD_PLAN_CORPORATE_TRANCHE.md §C0 / CORPORATE_DELTA_0_3_THIN_BOOKING.md §3, §5):** C0 —
stand up `corporate-service` as a new bounded context (pkg `pss.corporate`, port 8085) with the
`CorporateAccount` store, Flyway V1, the outbox seam, RegisterAccount + GetAccount, and the demo security
config. Branch `feat/corporate-booking`. Same conventions as `distribution-service` (FLAG-004 resolved).

**Scope built (delta §3/§5, build plan §C0):**
- New Gradle module `services/corporate-service` wired into `platform/settings.gradle.kts`; paved-road
  inherited (tenant context, JSON logging, OTel tracing, `/actuator/ready`); own Postgres store + Flyway
  V1; demo + prod `SecurityFilterChain` (mirrors distribution).
- **`CorporateAccount`** entity exactly per §3: `accountId` (ULID PK), `tenantId`, `companyName`,
  `registrationNo` (nullable), `status {ACTIVE|SUSPENDED}`, `billingEmail`, `contactName`, `@Version
  version: Int = 0`, `@CreatedDate createdAt`. New accounts mint ACTIVE with a ULID id and version 0.
- **`RegisterAccount` `POST /v1/corporate/accounts`** → 201 with `{accountId, companyName, status,
  billingEmail, contactName, version, createdAt}` (the §5 response shape — tenantId/registrationNo are not
  on the wire) + ETag(version). **`GetAccount` `GET /v1/corporate/accounts/{accountId}`** → tenant-scoped
  lookup. Tenant comes from the verified bearer token via `TenantContextHolder.requireTenantId()`, never a
  header.
- **Typed errors (§5 / build plan §C0):** `DUPLICATE_ACCOUNT` (409) on a repeat (tenant, companyName);
  `ACCOUNT_NOT_FOUND` (404) on an unknown/foreign-tenant id; `CORPORATE_ADMIN_REQUIRED` (403) when the
  demo `X-Corporate-Admin: true` admin gate (§5) is absent; `INVALID_REQUEST` (400) on a blank required
  field. RFC-9457-ish problem body `{status, code, detail}` (distribution's shape).
- **Outbox seam:** `corporate_outbox_events` table + `CorporateOutboxEvent` entity + `CorporateOutboxRelay`
  (dormant — `CorporateBookingCreated` is published at C2, not on account registration). Cadence is
  config-over-code (`pss.corporate.outbox.poll-interval-ms`, default 500ms; pushed to 1h in the IT base).

**Gate checks run:**
- **Unit (`CorporateServiceTest`, Docker-free — 4 tests, GREEN locally):** RegisterAccount mints an ACTIVE
  account with a 26-char ULID id and version 0; a duplicate (tenant, companyName) raises
  `DUPLICATE_ACCOUNT` (409) and never saves; GetAccount returns the tenant-scoped account; a missing/
  foreign account raises `ACCOUNT_NOT_FOUND` (404).
- **Integration (`RegisterCorporateAccountIT`, Testcontainers — 6 tests):** RegisterAccount → 201 +
  version 0 + ACTIVE, GetAccount returns it, and the outbox table is queryable and empty (outbox smoke);
  GetAccount is tenant-isolated (other tenant → 404); duplicate company → 409 `DUPLICATE_ACCOUNT`; unknown
  id → 404 `ACCOUNT_NOT_FOUND`; missing admin header → 403 `CORPORATE_ADMIN_REQUIRED`; blank companyName →
  400 `INVALID_REQUEST`. Mirrors the proven `RegisterSellerIT` (same `AbstractPostgresIT` singleton-
  container base, demo-JWT bearer helper, MockMvc patterns). **Runs on CI/Docker** — Docker is unreachable
  from the local Gradle test JVM (Testcontainers probes the `default` context's absent `docker_engine`
  pipe; Docker Desktop is on `desktop-linux`), so all 6 skip cleanly locally via `assumeTrue`, exactly the
  posture every prior milestone records. Full `build (platform)` gate verified on the PR.
- **Fitness — all 9 PASS locally:** `check_tenant_context` now inspects 20 controllers (the new
  `CorporateController` references `TenantContextHolder`); `check_actuator` (starter-actuator present),
  `check_otel` (micrometer-tracing-bridge-otel + otlp present), `check_no_cross_module_db` (JPA confined to
  `pss.corporate`; no foreign module import), plus dashboard-isolation / razorpay / enum-parity / gds /
  interline unaffected.
- **Local build:** `:corporate-service:compileKotlin`/`compileTestKotlin` GREEN; `:corporate-service:test`
  GREEN (4 run, 6 IT skipped pending Docker).

**Deviations:** none from the spec. Decisions recorded (each grounded in the governing docs, none a FLAG):
(1) **`DUPLICATE_ACCOUNT` natural key = (tenantId, companyName)** — companyName is the not-null business
identity; registrationNo is nullable so it cannot anchor a uniqueness constraint. Mirrors distribution's
`uq_seller_tenant_name`. (2) **`version` is `Int`** per the §3 field list (`version: Int = 0`), not the
`Long` distribution uses for `@Version` — the spec governs the field; `@Version Int` is valid Hibernate.
(3) **Admin gate** (§5 "corporate admin APIs require `X-Corporate-Admin: true`") implemented as a 403
guard with code `CORPORATE_ADMIN_REQUIRED`; the demo `SecurityFilterChain` still permits all (full RBAC is
a later slice). (4) **Response omits tenantId/registrationNo** — the §5 RegisterAccountResponse example
omits them; the wire follows the spec. Stop-list respected: no Order/Offer/distribution-service code
touched, no new money paths, no events beyond the (dormant) outbox seam.

**Measured numbers awaiting ratification:** none for C0 (the three open decisions are all RATIFIED in the
build plan; approval-flow / negotiated-fares / SellerProfile registration are C2 concerns). **STOP at C0 —
C1 not started.**

### D1 — Pricing: ancillary catalogue + PriceAncillaries — 2026-06-12 — GATE GREEN

**Milestone (addendum §2 / ancillaries delta §11):** D1 — Pricing: catalogue (tenant config, SEAT +
BAG) + `PriceAncillaries`. Built in `pricing-service` (the catalogue is Pricing-owned priced
reference data, delta §2.1). Branch `tranche1/D1`.

**Scope built (delta §2–3, generated from `ancillary-api.openapi.yaml`):**
- Ancillary catalogue as config-over-code (`pss.pricing.ancillary.*`), versioned: SEAT
  (PER_PAX_PER_SEGMENT, requires `seatNumber`, ₹350.00, GST 5%) and BAG (PER_PAX_PER_DIRECTION,
  ₹500.00, GST 5%). `AncillaryCatalogueProvider` serves it per tenant (one demo catalogue in T1).
- `POST /v1/pricing/ancillaries/quote` → immutable, deterministic `AncillaryQuote`. Pure function of
  catalogue version + selections; the air quote is never re-derived (§3.2). Tenant from the bearer
  token; naturally idempotent (no Idempotency-Key), per the contract.

**Gate checks run — all GREEN (13 tests):**
- **Golden quotes incl. GST** (`AncillaryQuoteServiceTest`): SEAT base 350.00 + GST 17.50 = total
  367.50; BAG base 500.00 + GST 25.00 = total 525.00; SEAT+BAG grandTotal 892.50; 2×SEAT grandTotal
  735.00. GST is an itemised tax line, never folded into the base.
- **Determinism**: same selections + catalogue version → byte-identical priced lines + grandTotal +
  catalogueVersion across runs (data-class equality, scale included).
- **Full 422 matrix** (`ANCILLARY_INVALID_SELECTION`, §3.1): unknown catalogue code · attachment-rule
  violation (SEAT bound to ≠ 1 segment) · missing required attribute (SEAT without `seatNumber`).
- **Contract mapping** (`AncillaryApiTest`): request→domain (code upper-cased, seatNumber→attributes);
  domain→response shape; 422 → RFC 9457 problem+json with stable `code = ANCILLARY_INVALID_SELECTION`.
- Full `:pricing-service:build` green (existing pricing suites unaffected); 4 fitness functions PASS.
  Context-boot ITs (`SchemaValidationIT`) bind the catalogue config and run on CI/Docker.

**Deviations:** none from the authoritative contract. One contract-vs-prose reconciliation, resolved
in favour of the contract (which governs the wire): delta §3.1 prose shows the request as
`{tenantId, currency, selections}`, but `ancillary-api.openapi.yaml`'s `PriceAncillariesRequest` is
`{currency, selections}` and the spec's conventions state "tenant from the verified bearer token, no
tenant parameters" — so tenant comes from the token, not the body. No missing contract shape → not a
FLAG.

**Measured numbers awaiting ratification:** none for D1 (latency/interval measurements belong to
A3–A4 / E3). Golden figures recorded above. **STOP at D1 — D2 not started.**

### B1 — Pricing: multi-passenger (N×ADT) + return journeys — 2026-06-12 — GATE GREEN

**Milestone (build plan §B1 / breadth delta §4):** B1 — grow Pricing from the Phase-1 walking
skeleton (1×ADT, one-way) to N×ADT over return journeys. Branch `tranche1/B1`. Pricing-only — no
Offer/Order wiring (that is B2+), no contract change, no saga change.

**Scope built (breadth delta §4, against `pricing-api.openapi.yaml` — unchanged):**
- `perPassenger[]` is now **real**: one entry per individual passenger (count = 1 each), not one per
  PTC. `grandTotal` = Σ per-passenger totals (was Σ total×count). `PricingPipeline.price()` expands
  each PTC group to N identical priced lines (per-head computed once, replicated — determinism kept).
- **One `FareComponent` per passenger per direction** (delta §4, §BD-5): a 2-adult return yields four
  components, each the passenger's filed base for that O&D (POS-converted). 1:1 with the order's
  per-(passenger × segment) AIR items that B3 will materialise.
- **Scope guards as defence in depth** (delta §4, validated in `price()` so the reprice path is
  covered too): non-ADT PTC → 422 `PTC_NOT_SUPPORTED`; Σ passengers > 9 → 422
  `PRICING_PAX_LIMIT_EXCEEDED`. Both surface as RFC 9457 problem+json with a stable `code`.
- Persisted round-trip fixed for N pax: `PricingMapper.toQuoteResponse` slices each PTC's stored tax
  lines back to each passenger by position, so a row shows its own tax set (never the group ×N).

**Gate checks run — all GREEN:**
- **Golden quotes** (`GoldenQuoteDeterminismTest`): 1×ADT one-way grandTotal 5800.00 (unchanged
  gold); 2×ADT one-way 11600.00 (2 entries, 2 components); 2×ADT return 23200.00 (per-pax base
  10000.00, GST 500.00 once per ticket, YR 800.00 + PSF 300.00 per-segment ×2, total 11600.00; 4
  components BLR-BOM/BOM-BLR ×2); 9×ADT return 104400.00 (9 entries, 18 components).
- **Per-segment vs per-ticket tax bases**: asserted across the multi-segment return — GST is one
  per-ticket line on the full per-passenger base; YR/PSF accrue per segment.
- **Determinism across N**: each multi-pax/return quote is `equals` across two independent runs
  (data-class equality over scaled BigDecimals).
- **PTC rejection / pax cap**: CHD in the set → `PtcNotSupportedException`; 10 ADT →
  `PassengerLimitExceededException`.
- **Persisted round-trip** (`PricingMapperMultiPaxTest`): a 2-adult quote reloads to two passenger
  rows, each carrying exactly its own GST/YR/PSF (250.00/400.00/150.00), grandTotal 11600.00, 2 components.
- Local run: `:pricing-service:test` unit suites GREEN (golden/determinism/tax/reprice/strategy/
  ancillary/mapper). The Testcontainers IT (`PricingPersistenceIT`) and the 4 fitness functions run
  on CI/Docker (Docker unavailable locally) — full `build (platform)` gate verified on the PR.

**Deviations:** none from the authoritative contract. Note: breadth delta §4 describes each
`FareComponent` as carrying a `passengerRef` (P1…Pn); `pricing-api.openapi.yaml`'s `FareComponent`
schema has **no** `passengerRef` field, so components are emitted **positionally** in passenger-major,
direction-minor order (P1-dir1, P1-dir2, P2-dir1, …). The contract governs the wire; positional
order preserves the 1:1 mapping Offer/Order need. No missing contract shape → not a FLAG.

**Measured numbers awaiting ratification:** none for B1. Golden figures recorded above. **STOP at
B1 — B2 not started.**

### E1 — Offer: ShopPerformed telemetry event — 2026-06-12 — GATE GREEN

**Milestone (dashboard delta §3, §9 E1):** E1 — close the funnel's top event gap. Emit
`ShopPerformed` from the Offer module, schema-validated in CI, loss tolerance documented in the
runbook. Branch `tranche1/E1`. Offer-only — no dashboard-service yet (that is E2).

**Scope built (dashboard delta §3, generated against `shop-performed.schema.json`):**
- `ShopPerformedEnvelope` / `ShopPerformedPayload` (searchId-keyed envelope, no offerId/orderId) +
  `ShopPerformedFactory` (eventId/occurredAt/correlation from clock+MDC; tripType = RETURN iff a
  returnDate; paxCount = Σ counts; channel defaults to WEB).
- `ShopTelemetry` port + `ShopPerformedPublisher`: **best-effort, fire-and-forget** publish straight
  to `pss-offer-events` keyed by `searchId` — **NOT** via the OfferCreated outbox (Offer is storeless;
  the metric is telemetry-grade, at-most-once). The send is never awaited; serialization / broker /
  async-reject failures are swallowed and logged at WARN. Disable flag
  `pss.offer.telemetry.publishing-enabled` (off in tests/offline — context needs no broker).
- Wired into `AssemblyService.shop`: emitted **once per shop call on every path, including the
  zero-offer case** (the funnel counts the search). Current single Shop = the `OUTBOUND` stage.
- Runbook `docs/runbooks/offer-service.md` documents the loss tolerance: at-most-once, skews only the
  directional funnel top, never money/funnel-bottom (those stay outbox-grade); do not reconcile the
  two grades; do not "fix" loss by adding an outbox.

**Gate checks run — all GREEN:**
- **Schema in CI** (`ShopPerformedSchemaTest`, networknt Draft 2020-12 against the canonical
  `shop-performed.schema.json` vendored onto the test classpath by the build): one-way OUTBOUND,
  multi-adult RETURN, zero-offer, channel-default, and RETURN/COMBINE stage tags all validate; a
  malformed event (lowercase IATA origin, `stage=MIDDLE`, `paxCount=0`) is **rejected** — the
  validator bites.
- **Stage tags / funnel fields**: `stage=OUTBOUND`, `tripType` ONE_WAY|RETURN, `paxCount`, `channel`,
  `offersReturned` asserted on the wire; envelope carries `searchId`, no `offerId`; nulls omitted.
- **Best-effort behaviour** (`ShopPerformedPublisherTest`): a broker that throws on send is swallowed
  (shop unaffected); disabled flag mints a searchId but never touches the broker; enabled publishes
  once to the topic keyed by the searchId.
- **Emission point** (`AssemblyServiceTest`): exactly one ShopPerformed per shop tagged OUTBOUND with
  the offer count; a zero-offer shop still emits (offersReturned=0); a gating Inventory outage (503)
  emits nothing (no shop happened).
- Local run: `:offer-service:test` unit suites GREEN. The Testcontainers ITs (`OfferPersistenceIT`,
  `SchemaValidationIT`) run on CI/Docker (Docker unavailable locally) with telemetry publishing
  disabled so the context boots without a broker — full `build (platform)` gate verified on the PR.

**Deviations:** none from the authoritative contract/design. Expected sequencing (not a FLAG, per the
work order): **CombineAndPrice does not exist yet (milestone B3)**, and the two-stage return shop is
B2 — so only the `OUTBOUND` shop emission is wired. The `RETURN` (second-leg shop) and `COMBINE`
emission points are present as clearly-marked `TODO(B2)` / `TODO(B3)` in `AssemblyService.shop`, and
both stage tags are already schema-validated. One non-deviating decision recorded: ShopPerformed
shares the `pss-offer-events` topic (keyed by searchId); the delta names no separate topic and the
consumer filters by type.

**Measured numbers awaiting ratification:** none for E1. The ≤ 2s end-to-end latency budget
(dashboard delta §4.3) and the outbox polling interval (Open Decision 4) are measured at E3 on the
demo cluster. **STOP at E1 — E2 not started.**

### E2 — dashboard-service: consumers + projections + dedupe — 2026-06-12 — GATE GREEN

**Milestone (dashboard delta §4, §9 E2):** E2 — build `dashboard-service` as a NEW standalone read-side
projection: event consumers idempotent on `eventId`, per-tenant/day counter projections, the ticker
ring buffer, and the two new CI checks. Branch `tranche1/E2`. First platform consumer — it reads
events and nothing else.

**Scope built (delta §4.1, against the existing event schemas — no contract change):**
- New Gradle module `services/dashboard-service` wired into `platform/settings.gradle.kts`; paved-road
  inherited (JSON logging, OTel, actuator); own Postgres store + Flyway V1; `Dockerfile` for C2. The
  build declares **no module client** (no web client / feign / circuitbreaker) and **no module project
  dependency** beyond `:libs:paved-road`.
- **Consumers** (`DashboardEventConsumer`): ONE consumer group, four subscriptions — Order events and
  Payments events (whole topics), Inventory **AvailabilityChanged only**, Offer **ShopPerformed only**.
  Listeners honour `pss.dashboard.consumers.auto-startup` (off in tests → context boots with no broker).
  `EventEnvelopeParser` reads the common envelope off any module's event; a malformed record is skipped.
- **Idempotency**: a `seen_events` dedupe row (eventId PK) guards every projection mutation in the same
  transaction (`ProjectionService.apply` returns false on a redelivery); TTL-pruned by `SeenEventPruner`.
- **Projections** (delta §4.1 table): `dashboard_daily_counter` per (tenant × display-day) — funnel
  {searches, offers, orders, paid}, bookings, declines, cancellations, revenueGross, refundsTotal — and
  `dashboard_ticker_entry` (per-tenant ring buffer, newest 50). `revenueNet` is derived on read
  (`DashboardQueryService`), never stored.
- **Money discipline (delta §4.1, stop-and-flag)**: `revenueGross` ← `OrderConfirmed.amountPaid` ONLY;
  `refundsTotal` ← `RefundIssued.amount` ONLY. `PaymentSettled` is consumed for the ticker and moves no
  money. One figure, one authoritative source event — proven by tests.
- **Day buckets** on the tenant **display** timezone (delta §4.1): `DayBucket` converts `occurredAt` to
  the tenant zone (IST for the demo carrier `acme-air`); config-over-code (`default-timezone` +
  per-tenant overrides) pending Open Decision 1.

**Gate checks run — all GREEN:**
- **Fitness (a)** — new static check `tools/fitness/check_dashboard_isolation.py` (wired into
  `fitness-functions.yml` as check 5): dashboard-service has no foreign-module import, no HTTP-client
  import, no module-client/feign/webflux/circuitbreaker dependency, no foreign service project dep, and
  no `base-url` in config. Ran locally: PASS (and the other four fitness checks still PASS).
- **Fitness (b) / §7.1 duplicate-delivery** (`DuplicateDeliveryIT`): EVERY projection handler — Shop
  Performed, OrderCreated, OrderConfirmed, RefundIssued, PaymentFailed, OrderCancelled, plus the
  ticker-only PaymentSettled / AvailabilityChanged — delivered twice moves its counter once and tickers
  once (second `apply` returns false).
- **§7.1 projection suite** (`ProjectionIT`): each event type → expected counter movement; OUTBOUND vs
  non-OUTBOUND ShopPerformed (searches vs offers); PaymentSettled moves no money; AvailabilityChanged
  moves no counter (E4 tile deferred); ticker ring buffer capped at 50.
- **§7.1 cross-stream interleaving + §7.2 telemetry-grade** (`InterleavingIT`): a refund consumed
  before its confirmation still nets correctly; an arbitrarily interleaved stream with a redelivery
  yields exact totals; a dropped ShopPerformed skews ONLY the funnel top — every money / funnel-bottom
  figure is provably identical across the with/without runs.
- **Day-bucket** (`DayBucketTest`, no DB) and **envelope parser** (`EventEnvelopeParserTest`, no DB):
  GREEN locally. The Testcontainers ITs (`ProjectionIT`, `DuplicateDeliveryIT`, `InterleavingIT`,
  `SchemaValidationIT`) run on CI/Docker (Docker unavailable locally) — full `build (platform)` gate
  verified on the PR.

**Deviations:** none from the design. Expected sequencing (not a FLAG, per the work order): the
**ancillaryRevenue / attachRate** and **availability tile** projections (delta §4.1) are **deferred to
E4** — they need the D4-enriched ANCILLARY payloads — and are marked as such in `DailyCounter` and the
V1 migration; AvailabilityChanged is still consumed (ticker) so the subscription exists. SSE + SPA are
E3. One non-deviating decision: ShopPerformed is read off `pss-offer-events` (E1 emits it there).

**Measured numbers awaiting ratification:** none for E2. The ≤ 2s end-to-end latency budget (§4.3),
the outbox polling interval (Open Decision 4) and the day-bucket timezone rule (Open Decision 1) are
settled at E3/E4 on the demo cluster. **STOP at E2 — E3 not started.**

### B2 — Offer: shopping context + Stage 1 (two-stage, direction-grouped) — 2026-06-12 — GATE GREEN

**Milestone (build plan §B2 / breadth delta §3):** B2 — grow Offer to a two-stage, direction-grouped
Shop with multi-passenger return journeys and the per-(passenger × segment) item shape. Branch
`tranche1/B2`. Generated against `docs/contracts/offer-breadth-api.openapi.yaml` (FLAG-002 resolved).

**Scope built (breadth delta §3.1–3.3, against offer-breadth-api):**
- **ShoppingContext** gains `tripType {ONE_WAY|RETURN}`; request validation: tripType required, RETURN
  needs a returnDate, **ADT-only** (non-ADT → 422 `PTC_NOT_SUPPORTED`), Σ count ≤ 9 (→ 422
  `PRICING_PAX_LIMIT_EXCEEDED`).
- **Stage-1 direction-grouped Shop**: `ShopResponse {searchId, tripType, directions[]}`. ONE_WAY → one
  direction of `stage=JOURNEY` offers (directly orderable — Phase-1 behaviour preserved); RETURN → two
  directions (outbound + return O&D) of `stage=CANDIDATE` offers. Each direction is shopped
  independently by the existing pipeline (gating Inventory → best-effort parallel Pricing → compose →
  personalise → store).
- **Per-(passenger × segment) item shape** (`OfferItem`: itemRef, directionIndex, segmentRef, paxRef,
  ptc, base, taxLines, amount). The pricing adapter now surfaces the per-passenger breakdown; the
  assembler expands it to one item per passenger, each carrying its quote slice.
- **Money invariant (§3.3) asserted at assembly**: Σ items[].amount = grandTotal; a mismatch throws
  `OfferArithmeticException` and the offer is not published. Property-tested across N = 1, 2, 9.
- **Partial failure degrades a DIRECTION** (delta §3.2): a direction whose gating Inventory is
  unavailable, or that has nothing priceable, yields an **empty** offers list — the shop never errors.
  A genuine provider error (502 `ProviderBadGatewayException`) still propagates. (This generalises the
  Phase-1 pricing-level candidate-drop, which is preserved within each direction.)
- **Telemetry**: one `ShopPerformed` per direction sharing the searchId — OUTBOUND (dir 0) and, for
  RETURN, RETURN (dir 1). This resolves E1's `TODO(B2)` (the RETURN-stage emission). `searchId` is now
  caller-minted and threaded (ShopTelemetry signature change).
- Persistence: V3 migration adds `offer_store.stage/search_id` and the new `offer_items` columns
  (replacing the old `segments` label with `segment_ref`).

**Ratified boundary decision (cross-module compat, Option 1 — approved by the design seat):** the
ratified offer wire (grandTotal/stage/segmentRef, direction-grouped) changes the shape Order's Phase-1
adapter consumes, so the order-chain ITs needed updating to stay green. Per the ratification, this is
**contract compliance, not B4 scope** — strictly parse/mapping, **zero new order business logic**.
Order-side files touched and why each is compat-only:
- `order-service .../saga/OfferHttpAdapter.kt` — its private wire DTOs read `grandTotal` (was
  `totalAmount`) and `segmentRef` (was the `segments` array); the reprice O&D split now reads
  `segmentRef`. No new validation, no N-item materialization, no bridge, no stage-enforcement (full
  `OFFER_NOT_ORDERABLE` is B3/B4). ONE_WAY 1-pax behaviour is byte-for-byte identical.
- `tests/integration OrderFromOfferIT.kt`, `OrderPaymentsIT.kt` — their `shopForOffer()` helpers send
  `tripType` and read `directions[0].offers[0]` (was `offers[0]`); plus a non-functional
  `OFFER_TELEMETRY_PUBLISHING_ENABLED=false` on the offer container (no broker in the IT).
- `tests/offer-integration OfferCrossServiceIT.kt` — flattens the direction-grouped result and asserts
  `item.segmentRef` (was `segments`); telemetry-publish disabled (no broker).

**Gate checks run — all GREEN:**
- **Stage-1 contract / wire shape** (`OfferMapperTest`, Docker-free): direction-grouped response, the
  `stage` discriminator (JOURNEY/CANDIDATE), per-passenger `P1…Pn` expansion, per-(pax × segment)
  items with base/taxLines, and the full request-validation matrix (RETURN-needs-returnDate, non-ADT,
  > 9, bad tripType).
- **Assembly / partial-failure / invariant** (`AssemblyServiceTest`, Docker-free): ONE_WAY → 1 JOURNEY
  direction; RETURN → 2 CANDIDATE directions; un-priceable candidate dropped within a direction; a
  failed direction (inventory down) degrades to empty while the other direction survives and the shop
  returns 200; Σ items = grandTotal across N = 1/2/9; OUTBOUND+RETURN telemetry under one searchId.
- Local run: `:offer-service:test` + `:order-service:test` GREEN (Docker-free suites). The cross-service
  Testcontainers ITs — `OfferCrossServiceIT` (real Inventory+Pricing), `OrderFromOfferIT` /
  `OrderPaymentsIT` (real Order→Offer→{Inventory,Pricing}+Payments chain), `OfferPersistenceIT`,
  `SchemaValidationIT` — run on CI/Docker (unavailable locally); full `build (platform)` gate verified
  on the PR. Full-platform `compileKotlin`/`compileTestKotlin` green locally.

**Deviations:** none from the ratified contract. Stop-list respected: **no saga, no compensation, no
money-invariant weakening** (the invariant is an additive assertion at assembly), and the only
contract adopted is the ratified `offer-breadth-api`. CombineAndPrice (B3) and the Order B4 feature set
are **not** started.

**Measured numbers awaiting ratification:** none for B2. Golden offer figures recorded in the tests.
**STOP at B2 — B3 not started.**

### B3 — Offer: CombineAndPrice + Order stage enforcement — 2026-06-12 — GATE GREEN

**Milestone (build plan §B3 / breadth delta §3.2):** B3 — `POST /v1/offers/combine`: 1–2 candidate
offers → ONE PriceItinerary across all directions → one immutable JOURNEY offer; Order now enforces
the stage. Branch `tranche1/B3`. Generated against `docs/contracts/offer-breadth-api.openapi.yaml`
(the combine shapes were pre-covered when FLAG-002 was resolved).

**Scope built (breadth delta §3.2, against offer-breadth-api):**
- **`POST /v1/offers/combine`** (Idempotency-Key required, 201) → `CombineService`: re-resolves each
  candidate (404 not-found / 410 expired via the lazy-expiry lookup), validates they form one journey,
  runs **ONE combined PriceItinerary** (new `JourneyPricingPort` → pricing `/v1/price` with both
  directions' segments + the full passenger set), and assembles a single **JOURNEY** offer carrying the
  candidates' per-(passenger × segment) items, `combinedFrom`, and the combined quote's grandTotal +
  validUntil. ONE_WAY pass-through: one JOURNEY candidate → a re-issued JOURNEY offer.
- **Idempotency** (offer-breadth-api): a `combine_idempotency` ledger (tenant × key → offerId); a replay
  returns the original JOURNEY offer, never combines twice.
- **Money invariant (§3.3 / BD-5)** asserted on the combined offer: Σ items[].amount = combined
  grandTotal (per-direction filed fares sum to the combined total); mismatch → `OfferArithmeticException`,
  not published.
- **Rejections**: 422 `CANDIDATES_INCOMPATIBLE` (mixed shopping contexts, same direction twice,
  passenger-set mismatch, wrong candidate count for trip type, un-priceable journey); 410 `OFFER_EXPIRED`
  for an expired candidate.
- **Order stage enforcement (B3 gate)**: `OrderHttpAdapter` now reads `stage` and rejects any
  `stage != JOURNEY` with 422 `OFFER_NOT_ORDERABLE` (a null/legacy stage stays orderable — Phase-1
  compatibility). New `OfferNotOrderableException` + handler.
- V4 migration: `offer_store.combined_from` + the `combine_idempotency` table.

**Gate checks run — all GREEN:**
- **Combine happy + pass-through + idempotency + invariant + every rejection** (`CombineServiceTest`,
  Docker-free, in-memory fakes): two CANDIDATES → JOURNEY (2 items, directions 0+1, combinedFrom,
  Σ items = grandTotal, validUntil inherited); one JOURNEY candidate → JOURNEY pass-through; same key →
  original offer (combined once); same-direction-twice / mixed-context / pax-mismatch / wrong-count /
  un-priceable → `CANDIDATES_INCOMPATIBLE`; expired candidate → 410.
- **Order stage enforcement** (`OrderFromOfferIT` scenario e, CI/Docker): a RETURN shop's outbound
  CANDIDATE offer → `CreateOrder` rejected with `OFFER_NOT_ORDERABLE`, nothing reserved (no hold, no
  payment).
- Local run: `:offer-service:test` + `:order-service:test` GREEN (Docker-free); full-platform
  `compileKotlin`/`compileTestKotlin` green. The Testcontainers ITs — `OfferCrossServiceIT`,
  `OrderFromOfferIT` (incl. the new enforcement scenario), `OrderPaymentsIT`, `OfferPersistenceIT`,
  `SchemaValidationIT` (validates V4 + the new entities) — run on CI/Docker; full `build (platform)` gate
  verified on the PR.

**Deviations:** none from the contract. Stop-list respected: **no saga, no compensation, no
money-invariant weakening** (the combine invariant is an additive assertion; the order stage check is a
pre-hold guard, not a saga change). The combine reuses the candidates' per-direction priced items and
reconciles them to the combined PriceItinerary's grandTotal (BD-5: equal for Tranche-1 filed fares;
component proration deferred — delta Open Decision 2). **B4 and D2 not started.** Also cleaned up: the
two stray untracked scratch files `.pr-order-module.md` / `.pr-inventory-module.md` were deleted.

**Measured numbers awaiting ratification:** none for B3. Golden combine figures recorded in the tests.
**STOP at B3 — B4 / D2 not started.**

### A1 — Payments: PaymentProviderPort conformance suite — 2026-06-13 — GATE GREEN

**Milestone (build plan §A1 / payments delta §1, §10):** A1 — promote the simulator's contract tests
into a **provider-agnostic port conformance suite** for `PaymentProviderPort`: the behaviours every
adapter must satisfy. Branch `tranche1/A1`. **No Razorpay symbols** (that is A2).

**Ratified scope boundary (approved by the design seat — Option 1):** this session delivers **only** the
conformance suite plus the **minimal additive port shapes** the six behaviours require per delta §4 —
`getPaymentStatus` (resolves ambiguous/late outcomes) and a **deterministic refund idempotency key**
(idempotent refund). The existing synchronous `settle()` flow is kept intact and non-breaking. The
full initiate/signal reshape, the `AWAITING_CUSTOMER`/`EXPIRED` transitions, the customer clock, and any
application-layer rewiring are **out of scope** here — they land with the A3 webhook and A4 saga, where
they belong. The milestone is self-contained and green on its own.

**Scope built (against the existing port + simulator; additive per delta §4):**
- `PaymentProviderPort` gains, additively: `getPaymentStatus(PspStatusRequest): PspPaymentState`
  (`SETTLED | FAILED | PENDING | NOT_FOUND` — the adapter never fabricates an outcome when the provider
  is mid-flight), and a `PspRefundRequest.idempotencyKey` (deterministic; empty = no dedup, so the
  Phase-1 synchronous caller is unchanged and relies on its own DB-level idempotency). `settle`/`refund`
  signatures are otherwise unchanged — `PaymentInitiationService` / `RefundService` compile and behave
  exactly as before.
- `SimulatedPsp` updated (still the unit-tier double): token-driven `getPaymentStatus`
  (ambiguous→PENDING, late→SETTLED, decline/fraud→FAILED, else SETTLED) and a fetch-and-match
  idempotent `refund` (same non-empty key → original reference).
- **Conformance suite** (`PaymentProviderConformance`, abstract): the six behaviours — settle · decline
  · ambiguous outcome · late settlement · idempotent refund · refund failure — written once,
  provider-agnostic. A new adapter is a **subclass that supplies the port + a `ConformanceScenarios`
  instrument catalogue** (the only per-provider knob) — a parametrization, never a copy-paste. The
  old `SimulatedPspTest` was promoted into it.

**Gate checks run — all GREEN (`SimulatedPspConformanceTest`, Docker-free — the proof):**
- The simulator parametrizes the suite and passes all six provider-agnostic behaviours unmodified, plus
  two simulator-specific assertions (tokenised `psp_`/`pspr_` references, never a PAN).
- Idempotent refund and late-settlement refund both replay to the original reference on the deterministic
  key; ambiguous reports PENDING; refund failure surfaces as `PspTransientException`.
- Local run: `:payments-service:test` GREEN. **Zero Razorpay symbols** in `services/payments-service/src`
  (verified by grep — even the prose says "the real-PSP adapter (A2)"). The Postgres IT runs on CI/Docker.

**Deviations:** none. Stop-list respected — no Razorpay/payment-shaped specifics, no saga/state-machine
change, no application rewiring (all deferred to A3/A4 per the ratified boundary). **A2 not started.**

**Measured numbers awaiting ratification:** none for A1 (customer-clock margin / drainer cadence belong
to A0/A4). **STOP at A1 — A2 not started.**

### D2 — Offer: AugmentOffer + supersession — 2026-06-13 — GATE GREEN

**Milestone (build plan §D2 / ancillaries delta §4):** D2 — `POST /v1/offers/{offerId}/ancillaries`
(AugmentOffer): attach ancillary selections to a JOURNEY offer → a NEW immutable offer that supersedes
the base. Branch `tranche1/D2`. Generated against `docs/contracts/ancillary-api.openapi.yaml`, using the
canonical `OfferItem` shape from `offer-breadth-api.openapi.yaml` (the ancillary fields harmonise onto
it per that contract's merge note).

**Scope built (ancillaries delta §4.1, against ancillary-api):**
- **`POST /v1/offers/{offerId}/ancillaries`** (Idempotency-Key required, 201) → `AugmentService`:
  resolves the base (404 not-found / 410 `OFFER_EXPIRED`), requires `stage=JOURNEY`, validates that
  selections reference the base's passengers (P1…Pn) and segments (else 422 `ANCILLARY_INVALID_SELECTION`),
  calls Pricing **PriceAncillaries** (D1, via the new `AncillaryPricingPort`), and assembles a new offer.
- **New immutable offer**: new `offerId`, `supersedes` = base id, items = base items **+ one ANCILLARY
  OfferItem per selection** (code, paxRef, segmentRef, `attributes.seatNumber` for SEAT, `priceRef` into
  the AncillaryQuote, base/taxLines/amount), `grandTotal` = air total + ancillary total,
  `validUntil` = min(air, ancillary). **The air quote is never re-derived** — `quoteRef` passes through
  unchanged, `ancillaryQuoteRef` is additive.
- **Re-augment** = call again on the BASE journey offer with the full new selection set (offers stay
  append-only; each augmented offer supersedes the BASE, not the prior augmented one).
- **Idempotency**: a replay of the Idempotency-Key returns the original augmented offer (reuses the
  per-offer-creation idempotency ledger).
- **Money invariant** Σ items[].amount = grandTotal asserted at assembly; mismatch →
  `OfferArithmeticException`, not published.
- V5 migration: `offer_store.supersedes` / `ancillary_quote_ref` + `offer_items.seat_number`. The
  canonical wire `OfferItem` gains `attributes.seatNumber` + `priceRef`; `OfferDto` gains `supersedes`
  + `ancillaryQuoteRef`.

**Gate checks run — all GREEN (`AugmentServiceTest`, Docker-free, in-memory fakes — §9.2):**
- **Augment happy path**: SEAT added → one ANCILLARY item (seatNumber 12A, amount 367.50), supersedes
  set, `quoteRef` unchanged, `ancillaryQuoteRef` set, grandTotal = 5800 + 367.50 = 6167.50.
- **Multiple selections** (2 seats + 1 bag on a 2-adult offer): 3 ANCILLARY items, grandTotal 12860.00.
- **validUntil = min(air, ancillary)**; **idempotency** (same key → original offer, augmented once).
- **Re-augment supersession**: re-augmenting the BASE with seat+bag supersedes the BASE (not v1).
- **Rejections**: expired base → 410; unknown passenger / unknown segment / non-JOURNEY base → 422
  `ANCILLARY_INVALID_SELECTION`; a Pricing rejection propagates as `ANCILLARY_INVALID_SELECTION`.
- **Invariant property** Σ items = grandTotal across augmented offers of N = 1, 2, 9.
- Local run: `:offer-service:test` + full-platform `compileTestKotlin` GREEN. The Testcontainers ITs
  (`SchemaValidationIT` validates V5 + the new entities/columns; `OfferPersistenceIT`,
  `OfferCrossServiceIT`, the order ITs) run on CI/Docker — full `build (platform)` gate verified on the PR.

**Deviations:** none from the contract. Stop-list respected: **no saga, no compensation, no
money-invariant weakening, no zone pricing, no payment-shaped logic** — AugmentOffer is purely additive
offer assembly; the air quote is untouched (delta §3.2). PER_PAX_PER_DIRECTION → single segment on the
nonstop demo (Open Decision 3 deferred). **D3 not started.**

**Measured numbers awaiting ratification:** none for D2. Golden ancillary figures recorded in the tests.
**STOP at D2 — D3 not started.**

### E3 — dashboard-service: SSE live push + carrier SPA — 2026-06-13 — GATE GREEN

**Milestone (dashboard delta §4.2, §5 / build plan §E3):** E3 — the live screen: a tenant-scoped SSE
stream that pushes the full snapshot, and the single-page carrier dashboard. Branch `tranche1/E3`.

**Scope built (dashboard delta §4.2, §5):**
- **SSE stream** `GET /v1/dashboard/stream` (`DashboardStreamController` + `DashboardStreamService`):
  tenant from the verified token (Bearer; `EventSource` can't set headers, so the SPA passes the same
  JWT as `?token=`, resolved by the identical claims reader). On connect it sends the **full current
  snapshot**; on every projection change it pushes the **full updated snapshot** — whole snapshot every
  time, **no client-side merge**. Push is driven by a new `ProjectionChangedEvent` published by
  `ProjectionService` and consumed `@TransactionalEventListener(AFTER_COMMIT)`, so the pushed snapshot
  reflects the committed mutation and never fires for a deduplicated redelivery. Emitters are bucketed
  by tenant — a change pushes only to that tenant's streams. Read-only service, no state-changing surface.
- **Carrier SPA** (`resources/static/index.html`, vanilla JS dark ops view, delta §5): KPI row
  (Bookings · Revenue net · Refunds · Declines), the funnel bar (searches → offers → orders → paid)
  **honestly labelled** — searches/offers as telemetry-grade (dashed), orders/paid as exact (solid),
  the two grades never mixed (§3) — and the event ticker (newest 50). Numbers **animate on change**;
  reconnect/F5 re-opens the stream and the server resends the full snapshot (**refresh-proof**).
- **Deferred to E4** (need D4 payloads + demo-flight config): the **ancillaries tile** and the
  **availability tile** are present as clearly-marked **TODO placeholders** in the layout.

**Gate checks run — all GREEN:**
- **Docker-free** (`DashboardStreamServiceTest`): a connect sends the full snapshot; a projection change
  pushes a fresh snapshot to the tenant's streams; **tenant isolation** (one tenant's change never
  reaches another's stream); a change for a tenant with no streams is a no-op. `DashboardStreamControllerTest`:
  tenant from Bearer, the `?token=` EventSource fallback, and reject-when-absent.
- **End-to-end on CI/Docker** (`DashboardStreamIT`, real HTTP SSE on real Postgres): **connect resumes
  with the full snapshot**; **a manual booking moves the screen** (apply `OrderConfirmed` → the stream
  receives bookings=1, revenueNet 5800.00, paid=1); **reconnect** resumes with the full current
  snapshot; **tenant isolation** over the wire (acme sees the booking, beta does not).
- Local: `:dashboard-service:test` GREEN; full-platform `compileTestKotlin` GREEN; fitness
  `check_dashboard_isolation` + `check_tenant_context` PASS (the new controller adds no module client
  and reads the tenant from the verified token).

**Latency (the figure the design seat is waiting on):** the **outbox-poll-to-render** end-to-end latency
**cannot be measured cleanly in this harness** — there is no running demo cluster, no broker, no
producer outbox-poller, and no browser here (and Docker is unavailable locally). What is established:
the **dashboard's own leg** — projection-commit → SSE push — is **synchronous, in-memory** (an
`AFTER_COMMIT` listener fanning out to emitters; sub-millisecond in the unit test), so it consumes a
negligible slice of the §4.3 budget (outbox poll ≤ 800ms + broker ≤ 100ms + projection commit ≤ 100ms +
SSE push & render ≤ 500ms ≈ 1.5s). **The ≤ 2s end-to-end assertion is deferred to the C-gate**, where
the C2 one-command demo cluster + CI smoke measure it against `OrderConfirmed.occurredAt` (delta §7.4) —
as the milestone permits when the harness can't measure it here.

**Deviations:** none. Stop-list respected: no synchronous module call (events only), no foreign
datasource, no money figures from anything but OrderConfirmed/RefundIssued (unchanged from E2). The
`?token=` query param is the standard EventSource accommodation (same JWT claims reader), not a weaker
auth path. **E4 not started.**

**Measured numbers awaiting ratification:** the ≤ 2s outbox-poll-to-render latency — deferred to the
C-gate (see above). **STOP at E3 — E4 not started.**

### A1 — Payments port semantics & state machine (simulator only, no adapter) — 2026-06-14 — GATE GREEN

**Milestone (Payments LLD Delta 0.2 §3, §4, §6 / build plan §A1):** A1 — reshape the provider port from
"a call returns the outcome" to "a call OPENS a window; the outcome arrives as a signal", add the
AWAITING_CUSTOMER/EXPIRED live state machine, and promote the simulator's contract tests into the
provider-agnostic port conformance suite (the swap contract every future adapter must pass unmodified).
Simulator only — **no adapter** (that is A2). Branch `tranche1/A1-statemachine`.

**Scope built:**
- **State machine (Delta 0.2 §3)** — `PaymentStatus` gains **AWAITING_CUSTOMER** (EXPIRED already
  carried). New idempotent transitions on `PaymentIntent`: `awaitCustomer()` (INITIATED →
  AWAITING_CUSTOMER, starts the customer clock), `settle()` extended to AWAITING_CUSTOMER → SETTLED
  (**the only commit path**), `fail()` to AWAITING_CUSTOMER → FAILED (verified decline), `expire()`
  AWAITING_CUSTOMER → EXPIRED (**new terminal**). Every transition is **idempotent on (intentId, target
  state)** — a redelivered signal is a no-op (re-settle does not double-capture; re-expire/​re-fail are
  no-ops). AUTHORIZED/PENDING stay **carried-but-unexercised**; the refund chain is unchanged.
- **Customer clock (§3)** — `CustomerClock.windowFor(holdTTL) = holdTTL − margin` (floored at zero),
  margin **config-driven** via `pss.payments.customer-clock.margin` (default `PT60S`). **The 60s margin
  is NOT ratified** — it is wired provisional, an open decision owned jointly with Inventory (the
  hold-TTL owner). Tests pin the arithmetic, never the value (trap #2 respected).
- **Port reshape (§4, additive)** — `PaymentProviderPort` gains **`initiate`** (opens a window, returns
  a provider ref + opaque checkout params, moves no money) and **`tokenize`** (vaults to an opaque
  token, never a PAN); `getPaymentStatus`/`refund` unchanged. `SimulatedPsp` implements both;
  `getPaymentStatus("…late…")` emits a **post-terminal SETTLED signal** so the late-money test is real.
- **Late money (§6)** — new `PaymentSignalService` applies reconciled signals: a capture on a
  terminal-but-uncaptured intent (EXPIRED/FAILED) does **NOT** resurrect it — it triggers an idempotent
  refund on the deterministic key **`intentId+LATE_SETTLEMENT`** and ticks `payments.late_settlement.total`.
  Pure orchestration over a passed intent + the port — **not yet wired to a webhook or the saga** (A3/A4).
- **Conformance suite** — promoted to the swap contract over **initiate / getPaymentStatus / refund /
  tokenize**; the six canonical outcomes (settle · decline · ambiguous=PENDING · late settlement ·
  idempotent refund · refund failure) plus initiate/tokenize. Runs unmodified against any adapter;
  `SimulatedPspConformanceTest` is the proof. The real-PSP adapter (A2) adds its own subclass.

**Gate checks run — all GREEN (Docker-free, unit tier):**
- `PaymentIntentStateMachineTest` (incl. the new AWAITING_CUSTOMER/EXPIRED transitions, idempotency on
  every target, and "a terminal intent is never resurrected"), `PaymentSignalServiceTest` (commit/
  decline/expire + the **real late-money** path driven through the simulator's post-terminal signal +
  same-key idempotency), `CustomerClockTest`, and the reshaped `SimulatedPspConformanceTest` — all PASS
  (`:payments-service:test`). Fitness `check_*` all PASS (no Razorpay symbol anywhere — that is A2).

**Contract change (additive, delta-sanctioned):** `payments-api.openapi.yaml` `PaymentStatus` enum gains
`AWAITING_CUSTOMER` (Delta 0.2 §3) so the domain/API/events stay in agreement; observable on the wire
only once the async path is wired (A3/A4). Not a FLAG — it is within the delta's sanctioned additions.

**Deviations (trap #1 — declared):** `settle` is **KEPT as a deprecated, transitional Phase-1 sync
path** (doc-marked, not new-callable) so `PaymentInitiationService` and the **order saga remain
UNTOUCHED** — removing `settle` and migrating the live pay-step to `initiate` + capture-signal is **A4**.
This was the ratified path ("additive reshape, keep settle transitionally"). **Zero saga / compensation
touch in A1.** Traps #2 (60s not ratified) and #3 (real late-money) respected as above.

**Measured numbers awaiting ratification:** the customer-clock **margin** (provisional `PT60S`) — to be
set with Inventory at A4. **STOP at A1 — no adapter (A2) started.**
### A0 — Payments: environment & access (Razorpay) — 2026-06-14 — GATE GREEN (harness slice; live step operator-run)

**Milestone (build plan §A0 / payments delta §1, §5, §11):** A0 — real-PSP environment & access only:
secret-store slots + retrieval path, the verify-first webhook ingress (stub), provisional config, and
the runbook. Branch `tranche1/A0`. **No application code** beyond the infra glue — no payment logic,
state machine, or live adapter (A1–A5). All Razorpay-shaped symbols live in `com.pss.payments.psp.razorpay`
(swap-protection, delta §1; the A2 fitness rule's home).

**Scope built:**
- **Secret-store wiring** (`RazorpayProperties` + `RazorpaySecretStore`): per-tenant, per-mode SLOTS for
  Razorpay key id / key secret / webhook secret; values injected **out-of-band** (env from the secret
  store), never committed/logged/fixtured. The retrieval path reads the active mode's slot and throws a
  `MissingPspSecretException` that names only tenant/mode/field — never a value.
- **Co-residence guard** (`RazorpayKeyResidencyGuard`, fail-fast at startup): a deployment holds only its
  declared `mode`'s keys; if any non-active-mode slot is populated the context refuses to start
  (delta §11).
- **Webhook ingress** (`RazorpayWebhookController` + `RazorpaySignatureVerifier`): public
  `POST /v1/psp/razorpay/webhook` behind the gateway. **Verify-first stub** — reads the RAW body,
  HMAC-SHA256 vs `X-Razorpay-Signature` using the stored webhook secret, **constant-time** compare
  (`MessageDigest.isEqual`), `2xx` on pass / `400` on fail, logs the event with its signature header.
  Nothing past verification (parsing / transitions / late-money are A3).
- **Gateway route** (api-gateway): `razorpay-webhook`, `StripPrefix=1`, public (not tenant-token-authed)
  → payments `/v1/psp/razorpay/webhook`.
- **Config entries** (config-over-code): customer-clock margin `PT60S`, refund-drainer cadence `PT5M`
  (provisional; `PaymentsTimingProperties`) — defined now, consumed at A4.
- **Runbook** (`docs/runbooks/payments-razorpay.md`): key/webhook-secret rotation, point-at-fresh-sandbox,
  the ingress URL + gateway route, and the operator steps to register the webhook in the Razorpay
  dashboard + run the live gate.

**Gate checks run:**
- **Verification path — GREEN (deterministic, harness-runnable):** `RazorpaySignatureVerifierTest`
  (correctly-signed raw body verifies; tampered body / wrong secret / missing-blank signature do not;
  blank secret verifies nothing) and `RazorpayWebhookControllerTest` (MockMvc: valid signature → 2xx;
  tampered / missing / wrong-secret → 400). `RazorpayKeyResidencyGuardTest` (co-residence fails fast;
  single-mode starts). All `:payments-service:test` green; fitness checks all PASS (the new webhook
  controller carries `fitness:exempt-tenant-context` — it is signature-authenticated).
- **Live gate — operator-run (deferred to the provisioned environment):** "a test webhook fired from the
  Razorpay dashboard reaches the ingress, raw-body HMAC-SHA256 verifies, the event is logged with its
  signature header" requires a Razorpay test account + the public HTTPS ingress + the cluster — none of
  which exist in this harness (A0 IS environment & access). The steps are documented in the runbook §7;
  the verification algorithm the live step exercises is proven green above.

**Deviations:** none. No secret value is printed/logged/fixtured (test vectors use synthetic
`whsec_test_*` values). Razorpay symbols confined to `psp.razorpay`. No payment logic, saga, or state
machine touched.

**Measured numbers awaiting ratification:** customer-clock margin (60s) and drainer cadence (5m) are
provisional, to be ratified against sandbox latencies (delta §13). **STOP at A0 gate — A1 already
landed; A2 (RazorpayProviderAdapter) not started here.**

### A2 — RazorpayProviderAdapter — 2026-06-14 — GATE GREEN (harness slice; live conformance operator-run)

**Milestone (Payments LLD Delta 0.2 §3, §4, §10 / build plan §A2):** A2 — implement
`RazorpayProviderAdapter` behind the reshaped `PaymentProviderPort`. Adapter only — no webhook handling
(A3), no saga touch (A4). Branch `tranche1/A2`. Pre-flight (P1 enum reconcile, P2 sandbox keys) recorded
under FLAGS above before any adapter code.

**Scope built:**
- **`RazorpayProviderAdapter`** (in `com.pss.payments.psp.razorpay` — the swap-protection home):
  - `initiate` → `POST /v1/orders`, amount in **paise (integer)**, INR, `receipt = paymentIntentId`,
    `payment_capture=1` (auto-capture, §13); returns `rzp_order_id` as the PSP reference + checkout
    session params (public `key`, `order_id`, `amount`, `currency`) for the BFF.
  - `getPaymentStatus` → **live poll, always** (`GET /v1/orders/{id}/payments`); maps
    captured/refunded→SETTLED, failed→FAILED, authorized/created/pending→PENDING, 404→NOT_FOUND. Never
    reads local last-known state (§4).
  - `refund` → **fetch-and-match guard first** (`GET …/refunds`, match the caller's deterministic key
    carried in `notes.pss_refund_key`/`receipt` → replay returns the existing refund), else
    `POST /v1/payments/{id}/refund` carrying that key. The adapter only **carries** the key it is given
    — it never invents the formula. (Razorpay-native idempotency: verify-in-build; the fetch-and-match
    guard stands regardless.)
  - `tokenize` → delegated to Checkout; the opaque handle is the vault token, with a PAN-shape guard.
    Never submits a PAN.
  - `settle` → `UnsupportedOperationException` (Razorpay is async; the deprecated sync path is
    simulator-only, migrated off the live pay-step in A4).
  - **Amount discipline:** `RazorpayAmounts.toPaise/fromPaise` own decimal↔paise at the boundary;
    sub-paise precision is rejected, never rounded. Transient mapping: 5xx/timeout → `PspTransientException`.
- **Bean selection (config-over-code):** `pss.payments.psp.provider` selects the adapter —
  `simulator` (default, `matchIfMissing`) keeps the simulator for CI unit tiers (§10); `razorpay`
  activates the adapter + its `RestClient` (`RazorpayHttpConfig`). Exactly one `PaymentProviderPort`
  bean is ever wired. No saga/domain change.
- **Fitness rule (now ACTIVE in CI):** `tools/fitness/check_no_razorpay_symbols.py` — no Razorpay-shaped
  symbol (the word `razorpay`, `paise`, webhook event names) outside `com.pss.payments.psp.razorpay`;
  wired as check #6 in `fitness-functions.yml`.

**Gate — deterministic (harness-runnable) half: GREEN.**
- `RazorpayPaiseRoundTripTest` — **mandatory** real property test (40k generated inputs, both
  directions, sub-paise rejection). `RazorpayProviderAdapterTest` — adapter logic over a mocked Razorpay
  HTTP layer (`MockRestServiceServer`, no network): request shapes (paise body, receipt, auto-capture,
  Basic auth), status mapping, the **fetch-and-match refund replay (no re-issue)**, transient mapping,
  tokenize PAN-refusal, settle-unsupported. All `:payments-service:test` green; all six fitness checks
  PASS (incl. the new no-Razorpay-symbol rule).

**Gate — live Razorpay-sandbox conformance: OPERATOR-RUN (deferred, P2).** `RazorpaySandboxConformanceTest`
extends the A1 `PaymentProviderConformance` **UNMODIFIED** (supplies only `newProvider()` = a live
adapter + `scenarios()` = the `success@razorpay`/`failure@razorpay` instrument catalogue) and is gated
off (`@EnabledIfEnvironmentVariable RAZORPAY_ACME_TEST_KEY_ID`) — **all 8 cases skipped** in this harness
(no sandbox keys, no egress to api.razorpay.com; P2). It runs from the provisioned environment with the
**rotated** ACME/TEST keys. Per delta §10 the settle/decline/late/refund cases need a real captured
payment, i.e. a customer-in-the-loop Checkout step with the test VPA, after which settle/decline are
observed via `getPaymentStatus` (NOT a webhook — that signal path is A3).

**Conformance-suite shape — recorded for C1 (not a suite edit):** the A1 suite is authored against the
simulator's instrument-token model with fixed fake intent/`psp_ref` ids; on real rails the
settle/decline/late/idempotent-refund cases require **real flow-produced ids + a customer-auth step**,
which the simulator abstracts away. The adapter is correct and the suite is left **unmodified** (no
drift); threading real ids / the customer-auth step through the conformance harness for the live tier is
deferred to the **C1 CI-smoke** milestone (delta §10 "CI smoke (demo script)"). Flagged here so C1 owns it.

**Deviations:** none from the contract or the design. Stop-list respected: Razorpay symbols confined to
the adapter package (fitness-enforced); conformance suite unmodified; **no webhook ingestion (A3), no
saga/compensation change (A4)**; settle proven via `getPaymentStatus`, the webhook never pulled forward.

**Measured numbers awaiting ratification:** none new for A2 (customer-clock margin / drainer cadence
remain A0/A4 items). **STOP at the A2 gate — A3 (webhook ingestion) not started.**

### A2 FIX — PaymentStatus enum regression + enum≡contract fitness check — 2026-06-16 — GATE GREEN

**On `tranche1/A2` (PR #23, not merged).** Corrects the P1(a) regression: the domain `PaymentStatus`
enum had drifted from the design (it lacked the refund-chain terminal-pending states and the carried
two-phase states), and the A1 suite's refund-failure case asserted too weakly to catch it. Authoritative:
PSS_Payments_Module_LLD §3.2 (state machine) / §3.1 (refund states) / §13 ("money still owed") ·
PAYMENTS_LLD_DELTA_0_2_REAL_PSP.md §3 · payments-api.openapi.yaml (contract is source of truth, now
aligned). The deterministic adapter work already in #23 (paise property test, fetch-and-match guard, PAN
guard, Razorpay-symbol confinement) is untouched.

**Changes:**
1. **Domain `PaymentStatus` restored to the design** (and the contract aligned to match it):
   `INITIATED, AWAITING_CUSTOMER, PENDING, AUTHORIZED, CAPTURED, VOIDED, SETTLED, FAILED, EXPIRED,
   REFUND_PENDING, REFUNDED, PARTIALLY_REFUNDED, REFUND_FAILED`. **REFUND_FAILED** is now a first-class
   terminal-PENDING state (money still owed; the A4 drainer queries it). **AUTHORIZED/CAPTURED/VOIDED**
   are the carried two-phase-ready states (LLD §3.2, unexercised this tranche). `PENDING` and
   `PARTIALLY_REFUNDED` were **kept** — the contract defines both (reconciled against the contract, not
   assumed). `isTerminalSuccess()`/`isTerminal()` updated for the new states.
2. **`RefundStatus`: `SUCCEEDED → ISSUED`** (LLD §3.1: `PENDING | ISSUED | FAILED`) in domain + contract;
   all usages updated (`PaymentIntent.refundedAmount/applyRefund`, `RefundService`, fixtures, tests).
   `PaymentIntent.applyRefund` now drives `FAILED → REFUND_FAILED` and `PENDING → REFUND_PENDING`
   (`ISSUED → REFUNDED/PARTIALLY_REFUNDED` unchanged).
3. **Conformance suite "refund failure" case strengthened** (the one allowed suite change): in addition
   to asserting `PspTransientException` from the port, it now asserts a settled intent recording that
   failure reaches **`PaymentStatus.REFUND_FAILED`** — not merely `RefundStatus.FAILED`. This is the
   assertion whose absence let the missing state slip through. Suite otherwise UNMODIFIED.
4. **New fitness rule `check_enum_contract_parity` (CI check #7):** asserts the domain `PaymentStatus`
   and `RefundStatus` enums are **set-equal** to the contract enums (both directions — no extra, no
   missing), reading the OpenAPI as source of truth. Proven to go RED on injected drift and on the
   pre-fix enum (it reports exactly: domain MISSING `CAPTURED/REFUND_FAILED/REFUND_PENDING/VOIDED`, and
   `RefundStatus` MISSING `ISSUED` / EXTRA `SUCCEEDED`), GREEN after the fix. This converts "a faulty
   manual check" into a red CI instead of a waved-through merge.

5. **Cross-module IT assertion updated** (not a saga change): `tests:integration/OrderPaymentsIT` case
   (f) asserted the post-failed-refund intent was `"SETTLED"`; corrected to `"REFUND_FAILED"` to match
   the fixed domain behaviour (the order is still CANCELLED, the refund still FAILED, the metric still
   increments — the saga/compensation code is untouched). Caught by the `build (platform)` matrix.

**Gate — GREEN:** `:payments-service:test` (state-machine REFUND_FAILED transition, the strengthened
conformance refund-failure case, `RefundStatus.ISSUED` accounting; adapter/paise/signature tests
unchanged) + `tests:integration` (the corrected OrderPaymentsIT) + all **seven** fitness checks incl. the
new enum≡contract parity (exercised — so green now means the enum is actually verified) and the
still-active no-Razorpay-symbol rule. No DB migration needed (status columns are width-tolerant VARCHAR,
no CHECK constraints).

**FLAG — repo LLD docx lags the design (design-seat to land):** `PSS_Payments_Module_LLD.docx` §6 still
lists the OLDER state set (`INITIATED → PENDING → AUTHORIZED → SETTLED; FAILED/EXPIRED; REFUNDED/
PARTIALLY_REFUNDED`) with no `REFUND_PENDING/REFUND_FAILED/CAPTURED/VOIDED/ISSUED`, and its section
numbering (§6 state machine, §3.1 entities) differs from the cited §3.2/§3.1. This fix follows the design
authority's instruction and aligns the **contract** + **domain**; the repo docx should be updated to match
so the artifact agrees with the now-locked contract/domain. Not work-stopping (the change was directed).

**Deviations:** none beyond the directed fix. No saga/compensation change; no adapter rewrite; conformance
suite unmodified except the added REFUND_FAILED assertion. **A2's real gate remains
`RazorpaySandboxConformanceTest` against the live sandbox (SKIPPED here) — operator-run with rotated keys,
before C1. STOP — do not merge.**

### A3 — Webhook ingestion & event routing (Razorpay) — 2026-06-16 — GATE GREEN

**Milestone (Payments LLD Delta 0.2 §5 webhook contract, §3 state machine / build plan §A3):** wire
verified Razorpay webhooks into the PaymentIntent state machine — everything from "signature verified"
(A0) to "transition committed + event in the outbox". Branch `tranche1/A3`. No saga change (A4), no BFF
change (A5).

**Scope built:**
- **`RazorpayWebhookTranslator`** (in `psp.razorpay` — the only place, with the A0 ingress controller,
  that Razorpay event/field names appear; fitness #6 enforces it). Maps `payment.authorized` /
  `payment.captured` → SETTLE, `payment.failed` → FAIL, `refund.processed` → REFUND_PROCESSED,
  `refund.failed` → REFUND_FAILED; anything else → UNHANDLED. Decodes to a provider-neutral
  `WebhookSignal` (event id, correlation `intentId` from the entity's `notes.pss_intent_id`, PSP ref,
  refund key); everything downstream is provider-neutral.
- **`PaymentWebhookService`** (application, provider-neutral): exactly-once idempotency, the state guard,
  and the transactional outbox. Loads the intent, applies the transition via the existing domain methods
  (`settle`/`fail`; new `finalizeRefund` for REFUND_PENDING → REFUNDED/REFUND_FAILED), records
  PaymentSettled / PaymentFailed / RefundIssued, all in one tx. Returns APPLIED | DUPLICATE | IGNORED.
- **Idempotency ledger** (`payment_webhook_events`, V2 migration; `ProcessedWebhookEvent` +
  `ProcessedWebhookEventRepository`): PK `psp_event_id` (PSP-agnostic column name). `existsById` is the
  exactly-once guard over the PSP's at-least-once delivery; the PK is the concurrent-duplicate backstop.
- **State guard**: a transition fires only from a valid predecessor (INITIATED/AWAITING_CUSTOMER/
  PENDING/AUTHORIZED → settle/fail; REFUND_PENDING → refund finalize). A late/out-of-order event on an
  already-terminal intent is IGNORED + 200 — never thrown (a non-2xx would make the PSP retry forever).
- **Ingress wired**: the A0 controller now, post-verification, translates + applies; 2xx for
  applied/duplicate/ignored/unhandled, 5xx only on a genuine processing failure (so the at-least-once
  retry re-drives it, absorbed by the ledger).
- **Observability**: `webhook_events_received_total{event_type, outcome}`; every event logged with id,
  intent, type, outcome.

**Gate checks run — all GREEN:**
- Docker-free unit: `RazorpayWebhookTranslatorTest` (each event → correct transition; unknown →
  UNHANDLED; event-id header fallback), `PaymentWebhookServiceTest` (each mapped signal → transition +
  correct outbox event; **duplicate event id → no second transition (DUPLICATE)**; **terminal-state
  event → IGNORED, not thrown**; unknown intent → IGNORED; unhandled → no intent touched; metric tags).
- CI/Docker: `RazorpayWebhookIngestionIT` — a signed `payment.captured` → 200 → intent SETTLED +
  PaymentSettled outbox row in one tx; **redelivery of the same event id → exactly one PaymentSettled**;
  unknown event → 200, no transition.
- `:payments-service:test` green; the **A1 conformance suite passes UNMODIFIED**; fitness **#6**
  (no-Razorpay-symbol) and **#7** (enum≡contract) green, plus all others.

**Deviations / decisions (recorded):**
- **`payment.authorized` AND `payment.captured` both → SETTLE.** The work order lists authorized→SETTLED;
  delta §4/§5 names captured as the canonical commit and authorized as observe-only. Under auto-capture
  (§13) either carries "money is real"; wiring both is safe given the idempotency ledger + state guard
  (whichever lands first settles; the other is a duplicate/ignored). No double-capture.
- **`refund.failed` → REFUND_FAILED carries NO outbox event.** No money moved; `RefundIssued` is
  success-only (matching `RefundService`). A dedicated RefundFailed event would be a contract addition →
  out of scope (would be a stop-and-flag). The transition is recorded in the ledger + metered.
- **§6 late-money (capture on EXPIRED/FAILED → idempotent refund) is NOT wired here.** Per the work
  order, A3 ignores terminal-state events; the §6 refund-on-late-money path is A4 (it needs the customer
  clock / EXPIRED intents and the `PaymentSignalService` A1 already built). Noted for A4.
- **Correlation** is by `notes.pss_intent_id` (the controllable channel the PSP echoes; = receipt =
  paymentIntentId per §5.4); refunds also carry `pss_refund_key` (stamped on the refund by the A2
  adapter). The BFF/Checkout stamps `pss_intent_id` (A5).

**Stop-list respected:** Razorpay symbols confined to the translator + A0 controller (fitness #6);
conformance suite unmodified; **no saga/compensation change**; no AWAITING_CUSTOMER/EXPIRED state-machine
work (used what A1 left). **Measured numbers awaiting ratification:** none new. **STOP — A4 not started.**

### A4a — Customer clock + late-money + REFUND_FAILED drainer (payments-internal) — 2026-06-16 — GATE GREEN

**Milestone (Payments LLD Delta 0.2 §3, §6, §9 / build plan §A4, split per FLAG-003):** the
payments-internal slice of A4 — Pieces 2 (customer clock), 4 (drainer), and 1-partial (signal-driven
simulator). Branch `tranche1/A4`. **The transitional synchronous `settle` shim is RETAINED and the Order
module is UNTOUCHED** (the cross-module inversion — remove the shim + Piece 3 — is **A4b**, per the
design-seat-ratified split). Fitness #6 (no-Razorpay-symbol) and #7 (enum≡contract) stay active and green.

**Piece 2 — customer clock + AWAITING_CUSTOMER → EXPIRED + late money:**
- New async window-open path `PaymentInitiationService.openCustomerWindow`: creates the intent
  AWAITING_CUSTOMER, opens the PSP window via `PaymentProviderPort.initiate`, and arms the clock
  (`customer_clock_deadline = now + (holdTTL − margin)`). The sync `initiate` shim is untouched beside it.
- `CustomerClockSweeper` (`@Scheduled`, config cadence) → `CustomerClockService.expireDue` expires each
  AWAITING_CUSTOMER intent past its deadline → EXPIRED, publishing an internal `CustomerClockExpired`.
- `LateMoneyReconciler` (`@TransactionalEventListener(AFTER_COMMIT)`, §6): live-polls
  `getPaymentStatus`; if SETTLED → money landed late on a dead intent → idempotent refund on key
  `intentId+LATE_SETTLEMENT` + park **REFUND_PENDING** (the refund webhooks, A3, finalize to REFUNDED);
  if not SETTLED → stays EXPIRED. Terminal intent never resurrected; money always returned.
- **Margin validation (Piece-2 gate):** Inventory **default holdTTL = `PT10M`** − margin `PT60S` =
  **540s headroom** — the clock fires well before the hold lapses. `CustomerClock.windowFor` floors a
  sub-margin hold at zero (never negative). **No margin flag.**

**Piece 4 — REFUND_FAILED drainer:** `RefundFailedDrainer` (`@Scheduled`, config cadence) →
`RefundDrainService.drainAll` re-attempts each REFUND_FAILED intent's refund via the port on the
**original deterministic key**; success → REFUND_FAILED → **REFUND_PENDING** (re-enters the async path;
the refund webhook finalizes to REFUNDED — the drainer never transitions straight to REFUNDED). A
**config-over-code retry cap** (`pss.payments.refund-drainer.max-attempts`, default 5 — no magic number)
stops a persistently-failing intent, emits an alert (`payments.refund_drain.exhausted` + ERROR log), and
leaves it REFUND_FAILED for manual handling. Gauge **`refund_failed_queue_depth`** (alert threshold is
config). Domain: `requeueRefund` / `recordDrainAttempt` / `drain_attempts` column.

**Piece 1-partial — signal-driven simulator + conformance:** the **sync `settle` shim is kept** (Order
flow stays green), so the provider-port conformance suite (`PaymentProviderConformance`, six behaviours)
— which tests the PORT and never depended on the shim — **passes UNMODIFIED** (behaviours + assertions
unchanged). The signal-driven path (the production-identical handler) is proven end-to-end: an
AWAITING_CUSTOMER intent settled by a fired capture signal through the same `PaymentWebhookService` A3
built (`CustomerClockIT` "signal-driven settle"; A3's `PaymentWebhookServiceTest` SETTLE/FAIL on
AWAITING_CUSTOMER). **Removing the shim and reworking the conformance suite's settle-delivery to
simulator-fired signals is A4b.**

**Schema:** V3 adds `customer_clock_deadline` + `drain_attempts` to `payment_intents` (nullable/defaulted,
so the retained shim path is unaffected; no CHECK constraints). Config: `customer-clock.hold-ttl` /
`.sweep-interval-ms`, `refund-drainer.cadence-ms` / `.max-attempts` / `.alert-threshold`.

**Gate checks run — all GREEN:**
- Docker-free: domain unit tests (`awaitCustomer`+deadline / `customerClockLapsed`; `requeueRefund` →
  REFUND_PENDING + drain-attempt count) + the A3 webhook/translator suite + the port conformance suite
  (six behaviours, unmodified) + all seven fitness checks.
- CI/Docker: `CustomerClockIT` (AWAITING_CUSTOMER → EXPIRED at the deadline; **late-money** branch:
  PSP SETTLED post-expiry → idempotent refund + REFUND_PENDING; no-late branch: PSP FAILED → stays
  EXPIRED; signal-driven settle of an open window) + `RefundDrainIT` (REFUND_FAILED → REFUND_PENDING on
  a drainable refund; **retry-cap** exhaustion → alert + stays REFUND_FAILED, drain bounded).

**Deviations / decisions (recorded):**
- **Shim retained** (ratified A4a scope); `initiate` sync settle + the Order saga are untouched. A4b
  removes the shim and inverts the create-order flow.
- **Two late-money paths now coexist, intentionally:** A4a's **clock-driven** reconciler (proactive at
  expiry: getPaymentStatus → refund → REFUND_PENDING, §6) and A1's `PaymentSignalService.applyCapture`
  (reactive: a late capture *signal* on a terminal intent → direct idempotent refund). Both honour
  "terminal never resurrected; money always returned." A4b can unify them when the live signal path
  lands; noted.
- **§3 EXPIRED late-money parks REFUND_PENDING** (per the A4 work order) rather than A1's
  direct-refund-and-stay-terminal — chosen because A3's refund webhooks now exist to finalize it.

**Stop-list respected:** Razorpay symbols confined (fitness #6); enum≡contract (fitness #7); conformance
suite six behaviours unmodified; **no Order-module change**; shim NOT removed. **STOP — A4b (cross-module
saga inversion) not started.**

### A4b — Cross-module inversion: bounded-async saga pay-step — 2026-06-17 — GATE GREEN

**Milestone (Payments LLD Delta 0.2 §8 saga reshape, §6 / build plan §A4b):** the cross-module half of
A4 — invert the create-order flow from synchronous to bounded-asynchronous. Branch `tranche1/A4b`,
PR #26. The A4a payments-internal machinery (customer clock, drainer, signal-driven simulator) is
untouched. Conformance suite unmodified; fitness #6/#7 active and green.

**Production changes:**
- **Payments — sync `settle` shim removed** (the transitional A1–A4a path): gone from
  `PaymentProviderPort` (+ `PspSettleRequest`/`Result`), `SimulatedPsp`, `RazorpayProviderAdapter`, and
  `PaymentInitiationService` (the sync `initiate` + `settleWithRetry`). `POST /v1/payments` now →
  `openCustomerWindow` → **202 AWAITING_CUSTOMER**, exposing the customer-clock deadline on
  `PaymentIntentResponse` so the order reads the SAME deadline source (stop-flag #4). The PSP outcome
  arrives only as a webhook signal (A3). The provider-port **conformance suite is unmodified** — it
  tested the port, never the shim.
- **Order — bounded-async pay-step**: `OrderStatus.AWAITING_PAYMENT`; `Order.holdRef`/`paymentDeadline`
  persisted (the saga state needed to resume); the saga split into `book` (hold → open window → PARK,
  no thread held) and `onPaymentSettled` / `onPaymentFailed` / `onTimeout`; `PaymentsPort` reshaped to
  `openWindow` + `getStatus`; a new **Kafka consumer** (`PaymentEventConsumer` on `pss-payments-events`)
  → `PaymentResumeService` (exactly-once via `seen_payment_events`, sets tenant context for the
  background resume) + a `PaymentTimeoutSweeper`; V4 migration; `order-api` OrderStatus += AWAITING_PAYMENT.
- **Late-money seam (Option 2, design-seat ratified, stop-flag #5)**: the order timeout sweeper reads
  payment state via `GET /v1/payments/{id}` and reacts (SETTLED → confirm; FAILED/EXPIRED → compensate);
  `LateMoneyReconciler` stays payments-internal as A4a delivered — one implementation, no new endpoint.
- **Compensation unchanged (stop-flag #6)**: a failed/expired payment releases the inventory hold via
  the existing step; no new compensation steps.

**Gate checks run — all GREEN on the CI Docker matrix:**
- Conformance suite: six behaviours green, **shim removed**, signal path confirmed.
- `BookPayFulfilSagaTest` (unit): park; settle/fail resume; idempotent redelivery; both refund
  compensation paths; both timeout outcomes.
- `OrderPaymentsIT` (real payments container): (a) create → AWAITING_PAYMENT → signed capture webhook
  settles the intent → consumed PaymentSettled → CONFIRMED + seat sold; (b) decline → CANCELLED +
  hold released; (c) refund-on-compensation; (d) idempotent refund; (e) **timeout path** — clock lapses,
  `getPaymentStatus` reads SETTLED → confirm; (f) REFUND_FAILED. Payment outcomes delivered via
  `PaymentResumeService` (the consumer's downstream action — the IT stack has no Kafka broker).
- `OrderFromOfferIT` / `CrossServiceIT`: async create → AWAITING_PAYMENT → settled/failed resume →
  CONFIRMED/CANCELLED; the no-oversell concurrency test parks the 3 winners then settles them.
- `:payments-service:test`, `:order-service:test`, `:tests:integration:test`, all seven fitness checks.

**Deviations / decisions (recorded):**
- **No production change in the IT-rewrite pass** beyond two test-config fixes the matrix surfaced:
  disabling the order payments-events consumer in broker-less IT contexts
  (`pss.order.consumers.auto-startup=false`, mirroring dashboard); and `OrderPaymentsIT(d)` replaying the
  refund's actual stored idempotency key (the async reload makes the key's amount scale the DB column's,
  not an in-memory literal — deterministic within the flow, so a test assumption, not a production bug).
- **Create-order contract inversion**: `POST /v1/orders` returns AWAITING_PAYMENT (was synchronously
  CONFIRMED/CANCELLED); the order IT suite was rewritten to the async flow (sync-settle ITs replaced).
- The IT stack has **no Kafka broker**, so the webhook→consumer→resume chain is exercised by firing the
  signed webhook at the payments container (settles the real intent) + invoking `PaymentResumeService`
  directly (the consumer's downstream resume). The consumer + envelope parsing are covered separately.

**Measured numbers awaiting ratification:** none new. **STOP — A4b complete and green; not merged. A5
(BFF/Checkout) not started.**

---

### A5 — IBE checkout + payments smoke — 2026-06-17 — GATE GREEN (pending CI)

**Milestone (Payments LLD Delta 0.2 §7 BFF/checkout, §8 client-callback semantics; DEMO_SCRIPT Scenes
4/6/7; build plan A5):** the IBE checkout surface + the scripted payments smoke. Branch `tranche1/A5`.
Single-adult one-way only (breadth joins at C1). Conformance suite unmodified; fitness #6/#7 active.
Design-seat-ratified channel stack (**Option 2**): BFF = Kotlin/Spring (paved road); IBE = thin
vanilla-HTML page (dashboard E3 precedent); the polling decision logic lives in the BFF as a pure,
JUnit-tested `ConfirmationStateService` that the IBE polls. No new CI infrastructure.

**Production changes (new `channel-bff` module — mapping only, NO business logic, NO money math):**
- **PIECE 1 — checkout endpoint** `POST /checkout/sessions`: reads the order's decimal total + currency
  + the already-open payment intent via `GET /v1/orders/{id}` (never recalculates), reads the customer
  deadline via `GET /v1/payments/{id}`, and returns the hosted-checkout session — `key` from config,
  `amount` the order's DECIMAL total passed through verbatim, `notes` carrying `{orderId,
  paymentIntentId}` for webhook correlation. **No minor-unit/paise conversion in the BFF** (that stays in
  the PSP adapter — fitness #6); the module is provider-neutral (no `razorpay`/`paise` token in any
  `.kt`). `ConfirmationStateService.decide(orderStatus, deadline, now)` is the pure polling decision
  (CONFIRMED→CONFIRMED; CANCELLED/EXPIRED→CANCELLED; AWAITING_PAYMENT/CREATED before deadline→PENDING,
  at/after→FALLBACK). Tenant from the paved-road servlet filter (auto-configured) via `requireTenantId()`.
- **PIECE 2 — IBE confirmation flow** (`GET /checkout/sessions/{orderId}/state` + `static/checkout.html`):
  the thin IBE opens the widget, then on success OR dismiss POLLs the BFF state endpoint every 2s; the
  BFF reads the order's authoritative status and returns the render decision. Confirmation renders from
  the ORDER, never the client callback (the callback is an optimistic hint only).

**Gate checks run — local (unit + fitness) GREEN; ITs run on the CI Docker matrix:**
- `:channel-bff:test` — 16 green. `CheckoutServiceTest` (MockMVC-free, mocked clients): amount sourced
  from the order as a DECIMAL and asserted NOT to be paise; `notes` carry orderId + paymentIntentId; key
  from config; prefill passed through; missing-window fails closed; confirmation rendered from order
  status. `ConfirmationStateServiceTest`: all four render outcomes incl. the at-deadline boundary.
- All **seven fitness checks GREEN**, including #6 (no Razorpay symbol — the BFF is provider-neutral) and
  #7 (enum≡contract parity, unaffected). Module also passes #2 (no cross-module DB — BFF is stateless),
  actuator, otel, tenant-context.
- `OrderCheckoutSmokeIT` (new, `tests:integration`) — Scenes 4/6/7 through the **real BFF container** over
  HTTP: order in-process (RANDOM_PORT, exposed to the container network via `Testcontainers.exposeHostPorts`
  so the BFF reaches it at `host.testcontainers.internal`), with Offer/Pricing/Inventory/Payments and
  channel-bff all real bootJar containers. Scene 4: AWAITING_PAYMENT → checkout session (decimal amount,
  notes, PENDING state) → capture webhook + resume → CONFIRMED → IBE renders CONFIRMED with the correct
  total + seat sold. Scene 6: decline → CANCELLED → IBE renders CANCELLED, hold released. Scene 7:
  confirm, then voluntary cancel of the CONFIRMED order → existing cancel path issues exactly one real
  refund (intent REFUNDED) → order CANCELLED → IBE renders CANCELLED.

**Deviations / decisions (recorded — all design-seat-approved or faithful readings, none a FLAG):**
- **IBE unit tests = BFF `ConfirmationStateService` tests** (design-seat-approved, Option 2). The
  polling logic is a pure BFF unit rather than client JS; the gate's "IBE unit tests" requirement is met
  by `ConfirmationStateServiceTest`. The IBE page itself is the dashboard-E3-style untested vanilla HTML.
- **BFF reads the order's existing intent, does NOT call `openWindow`** (deviation from Piece 1.1's
  literal "BFF calls `POST /v1/payments`"). A4b moved window-opening to order create-time, so the order
  already holds the intent + deadline; calling `openWindow` from the BFF would be a redundant idempotent
  duplicate. No saga/payments production change (stop-flag honoured).
- **Scene 7 terminal**: cancelling a CONFIRMED order leaves the ORDER at **CANCELLED** with the PAYMENT
  intent at REFUNDED (the order has no distinct REFUNDED status). The IBE renders the CANCELLED view —
  consistent with Piece 2's enumerated render states; no new render state invented, no saga change (the
  CONFIRMED-cancel-with-refund path already exists in `OrderApplicationService.cancelOrder`).
- **SIMULATOR, not sandbox**: the smoke runs against the payments-service **simulated PSP** (signed
  webhook + `PaymentResumeService`, the A4b/OrderPaymentsIT pattern — the IT stack has no Kafka broker
  and the dev sandbox keys are not rotated). The live Razorpay-sandbox CI smoke is C1's responsibility.
- BFF made **provider-neutral** to satisfy fitness #6: config key `pss.bff.checkout.public-key`, no
  Razorpay/paise tokens in `.kt`; the IBE HTML (not a scanned module source) loads the hosted widget.

**Measured numbers awaiting ratification:** none new. **STOP — A5 built; local unit + fitness green;
ITs pending the CI Docker matrix. PR opened; NOT merged.**

---

### B4 — Order: multi-passenger validation, materialization, bridge — 2026-06-18 — GATE GREEN (pending CI)

**Milestone (Breadth Delta 0.2 §5 Order delta, §4 pricing mapping, §6 ticket bridge; build plan B4):**
grow the Order module into multi-passenger return journeys. Branch `tranche1/B4` off master (`9c6e578`,
A5 merged — A5 added only `channel-bff`, no order code, so history stays linear). Merge rule satisfied
(A4b on master). **No saga, compensation or payment changes.** Contracts unchanged (additive consumption
+ two new error codes already coined upstream). B1/B2/B3 on master, so Pricing emits per-direction fare
components and the Offer emits the per-(passenger × segment) item shape the order now consumes.

**Design-seat ruling recorded (AskUserQuestion):** PTC precedence is **PTC-first** — any non-ADT request
PTC → `PTC_NOT_SUPPORTED` (BD-4, N×ADT scope), checked before the offer, regardless of what the offer
carries; count/composition mismatch among ADTs → `PASSENGERS_MISMATCH_OFFER`. The delta-prose matrix
example ("CHD when offer has ADT → PASSENGERS_MISMATCH_OFFER") is superseded by this ruling.

**Production changes (Order module only):**
- **PIECE 1 — validation matrix** (`OrderPassengerValidation`, pure): PTC-first as ruled. `createOrder`
  rejects non-ADT before consulting the offer (`PTC_NOT_SUPPORTED`), then requires the submitted set to
  match the offer's passenger spec exactly (`PASSENGERS_MISMATCH_OFFER`). New exceptions +
  `ApiExceptionHandler` mappings (both 422 with stable codes).
- **PIECE 2 — materialization** (`OrderMaterialization`, pure; `OfferHttpAdapter` rewrite): the order now
  materialises **one AIR item per offer slice** — the offer already enumerates (passenger × segment),
  each slice carrying its `paxRef`, `directionIndex`, routing and its **own** base + taxes (Breadth Delta
  0.2 §3.3/§5.1). A 2×ADT return → exactly 4 items. (Previously the adapter cross-producted flights ×
  passengers and repeated each passenger's full fare across segments — correct for 1×1, wrong for
  multi-segment.) New `OrderItem.direction_index` column (V5 migration, nullable). The re-validation
  reprice now prices the **distinct** segments for the **offer's own** passenger set (request-independent,
  so a passenger mismatch surfaces as `PASSENGERS_MISMATCH_OFFER`, not `OFFER_PRICE_CHANGED`). **Money
  invariant** (Σ item totals = offer grandTotal) asserted in the creation transaction → aborts on
  mismatch (`OrderInvariantViolation`, 422). **No money math in the order** — the offer's decimals pass
  through. Hold model **unchanged**: the existing aggregation (one line per flight+rbd, qty = pax count)
  already yields N lines for a return; extracted to `InventoryHttpAdapter.aggregateHoldLines` (behaviour
  identical) purely to close the test-coverage gap.
- **PIECE 3 — ticket bridge** (`StubTicketingAdapter`): one e-ticket per passenger, one coupon per
  segment, `documentRef = {ticketNumber}/{couponNumber}` (coupons in journey order: outbound /1, return
  /2). PNR bridge = the order (one PNR, N name fields, the journey's segments) — **no model change**.
- **PIECE 4 — event-schema CI check**: N-pax `OrderCreated/OrderConfirmed/OrderCancelled` validated
  against `order-events.schema.json` (networknt, schema vendored onto the test classpath each build, as
  offer-service does). **No schema change** — payload growth only (the arrays the schema already admits).

**Gate checks run — local (unit + fitness) GREEN; ITs run on the CI Docker matrix:**
- `:order-service:test` — all green incl. the new suites: `OrderPassengerValidationTest` (full matrix),
  `OrderMaterializationTest` (4 items + per-item money + invariant-abort), `StubTicketingAdapterTest`
  (ticket/coupon mapping), `InventoryHoldAggregationTest` (2 lines qty 2 for a return; 1 line qty 2 for a
  shared one-way flight), `OrderEventsSchemaTest` (2×ADT and 9×ADT-cap fixtures validate; validator bites).
- All **seven fitness checks GREEN**, incl. #6 (no Razorpay symbol — order module clean) and #7 (enum≡
  contract parity, untouched). Conformance suite unmodified.
- `OrderCreateMultiPaxIT` (new, real Postgres, unit-test profile with a scripted `StubOfferAdapter`):
  2×ADT return → 4 AIR items with correct directionIndex + money, parks AWAITING_PAYMENT; fewer/more
  passengers → `PASSENGERS_MISMATCH_OFFER`; non-ADT → `PTC_NOT_SUPPORTED`; inconsistent total → abort.
  Cross-service `CrossServiceIT`/`OrderFromOfferIT`/`OrderPaymentsIT` keep passing (their offer stub +
  the real offer container both supply the per-item slices the rewritten adapter reads).

**Deviations / decisions (recorded — none a FLAG):**
- **PTC-first precedence** per the design-seat ruling (above); the delta-prose matrix example superseded.
- **Materialize from the offer's own per-(pax × segment) slices** rather than re-deriving money from the
  reprice's aggregated `perPassenger[]` — the offer slices are the 1:1 fare components (BD-5) and repeating
  a per-passenger total across segments would double-count on a return. The reprice stays as the
  request-independent price-drift guard.
- **No saga/compensation/payment change** (stop-flag #1 honoured). **Money invariant strengthened, not
  weakened** (stop-flag #2). **Hold model unchanged** — the existing aggregation already supports N lines
  qty>1, so no code change beyond a behaviour-preserving extraction for testability (stop-flag #4 → no flag).

**Measured numbers awaiting ratification:** none new. **STOP — B4 built; local unit + fitness green; ITs
pending the CI Docker matrix. PR to be opened; NOT merged. B5 not started.**

---

### B5 — Inventory: qty>1 proof — 2026-06-18 — GATE GREEN (pending CI)

**Milestone (Breadth Delta 0.2 §6 Inventory — "explicitly no change"; build plan B5):** close the
multi-passenger test-coverage gap — prove the hold is atomic across lines under concurrency with
multi-seat, multi-line holds. Branch `tranche1/B5` off master (`0938d45`). **No production code change**
(the hold model already supports qty>1 per line and multi-line atomicity from Phase 1). **No saga,
compensation or contract change.**

**What was built — tests only** (added to `InventoryPersistenceIT`, the existing real-Postgres
concurrency suite):
- **Scenario 1 — multi-seat single line**: two competing 2-seat holds against 3 seats → exactly one wins
  both, the loser gets a clean oversell (no partial 1-seat hold); final `held == 2`, `available == 1`.
- **Scenario 2 — multi-line return, cross-line atomicity**: outbound has room for both (4 seats), inbound
  for only one 2-seat hold (3 seats). Two `[outbound 2, inbound 2]` requests race → exactly one wins both
  lines; the loser, having failed inbound, keeps **neither** direction — proven by `outbound.held == 2`
  (not 4): its outbound seats rolled back. Never one-direction-held.
- **Scenario 3 — nine-seat cap under concurrency**: three competing 3-seat holds against 9 seats → total
  `held` never exceeds 9 under any interleaving; each hold is all-or-nothing (`held == succeeded × 3`);
  3×3 fits exactly so all three win the full nine.

**How atomicity is guaranteed (verified, not changed):** `HoldService.createHold` is `@Transactional`
and takes a per-flight pessimistic row lock (`flights.lockForUpdate`) before each `flight.hold(rbd, qty)`;
any line that would oversell throws `OversellException`, rolling back the whole transaction — so no line
is partially applied and concurrent holds serialise on the lock. The new tests exercise this; **no latent
bug surfaced**, so no FLAG.

**Gate checks run — local (compile + unit + fitness) GREEN; the new concurrency ITs run on the CI Docker
matrix:**
- `:inventory-service:test` compiles + existing unit tests green; the three new scenarios are
  real-Postgres ITs (skip locally without Docker, run on CI alongside the existing no-oversell suite).
- All **seven fitness checks GREEN** (no production change; #6/#7 unaffected). Contracts unchanged.

**Deviations / decisions (recorded — none a FLAG):**
- **Error-code name**: the work order calls the loser's response "422 SEATS_UNAVAILABLE"; the actual
  contract code the inventory service returns for an oversell is **`INVENTORY_OVERSELL`** (422,
  `ApiExceptionHandler`). The tests assert the `OversellException` it maps from — a naming note, no code
  change. "No partial hold" is asserted explicitly in every losing scenario via the held-seat counts
  (winner's exact seats held; loser holds nothing on any line).
- **No production code touched** (stop-flag #1 honoured — no latent bug found); no saga/compensation
  change (#2); no contract change (#3); no Razorpay symbols / no enum drift (#4).

**Measured numbers awaiting ratification:** none new. **STOP — B5 built (tests only); local green;
concurrency ITs pending the CI Docker matrix. PR opened; NOT merged. D3/B6 not started.**

---

### B6 — IBE/BFF: two-stage offer flow — 2026-06-18 — GATE GREEN (pending CI)

**Milestone (Breadth Delta 0.2 §8 IBE/BFF; build plan B6):** extend the IBE + BFF to the two-stage
RETURN flow (Stage-1 candidates → Stage-2 combine → order) alongside the one-way path. Branch
`tranche1/B6` off master (`f2f1e7b`). **No payment, saga or inventory change.** The gate accepts the
simulator PSP.

**Production changes (BFF mapping-only + thin IBE):**
- **PIECE 1 — BFF two-stage shop** (`shop/` package): `GET /bff/shop` assembles the Offer shop request
  from the IBE's search params (adults → positional ADT spec, POS defaulted via config) and relays
  Offer's direction-grouped response verbatim; `POST /bff/offers/combine` forwards the selected
  candidate ids (outbound first, deterministic Idempotency-Key) and relays the JOURNEY offer. `OfferClient`
  is a pure proxy returning the offer JSON as a `JsonNode` (no interpretation, no money math); a
  downstream 4xx/5xx is relayed (status + problem+json) so the IBE can re-shop. **No offer selection or
  pricing logic in the BFF** (stop-flag #1 honoured).
- **PIECE 2 — IBE two-stage selection** (`static/shop.html`, thin vanilla per the A5 precedent): search
  form (trip type, dates, 1–9 adults; non-ADT absent); ONE_WAY shows one JOURNEY direction (orderable);
  RETURN shows two CANDIDATE directions, requires an outbound + an inbound selection, then a **mandatory
  Combine** step before a "Proceed to checkout" affordance appears. The "Proceed" path is only ever
  reachable with an orderable JOURNEY offer — a CANDIDATE selection never reaches checkout.
- **PIECE 3 — confirmation-state for a return journey**: no logic change — `CheckoutService.confirmationState`
  reads the order's total straight from the Order service; a 4-AIR-item return order surfaces its full
  total (coverage extension).

**Gate checks run — local (unit + fitness) GREEN; the two-stage IT runs on the CI Docker matrix:**
- `:channel-bff:test` — green incl. the new `ShopServiceTest` (ONE_WAY/RETURN request mapping by
  equality-stubbing — proves the exact assembled request; combine forwards candidates unchanged; relays
  responses verbatim; bad-request guards never call Offer) and `CheckoutServiceTest` (return-journey
  total read straight from the order). **Mapping-only confirmed** by the equality-stub assertions.
- All **seven fitness checks GREEN** (incl. #6 no-Razorpay and #7 enum parity — the BFF is provider-neutral).
- `OrderCheckoutSmokeIT` extended with the **B6 two-stage RETURN path** (real BFF + Offer/Pricing/
  Inventory/Payments containers, order in-process): shop RETURN via `/bff/shop` → two CANDIDATE
  directions → **un-combined checkout blocked** (ordering a CANDIDATE directly → OFFER_NOT_ORDERABLE) →
  `/bff/offers/combine` → JOURNEY offer → create the 2×ADT order (asserts exactly 4 AIR items) →
  AWAITING_PAYMENT → settle → CONFIRMED → BFF confirmation shows the full return total **23200.00**.
  Inbound leg (BOM-BLR) + return fare seeded; runs on the CI Docker matrix.

**Deviations / decisions (recorded — none a FLAG):**
- **The one-way shop proxy did not pre-exist in the BFF** (the A5 BFF was checkout-only; the work order's
  "BFF already proxies the one-way shop" was inaccurate about current code). Created both `/bff/shop` and
  `/bff/offers/combine` as additive mapping endpoints fulfilling §8 — not a flag (mapping, not logic).
- **"Un-combined checkout blocked" is enforced server-side** by the order's existing OFFER_NOT_ORDERABLE
  (B3, on master) and mirrored in the IBE JS (which never offers "Proceed" for a CANDIDATE). The smoke
  asserts the server-side block; the IBE JS guard is presentation (untested vanilla per the A5 ruling
  that IBE unit tests = BFF unit tests).
- **SIMULATOR PSP** (not the Razorpay sandbox), as the B6 gate permits — recorded.
- No payment/saga/inventory change; BFF stays mapping-only; fitness #6/#7 green.

**Measured numbers awaiting ratification:** none new. **STOP — B6 built; local unit + fitness green; the
two-stage IT pends the CI Docker matrix. PR opened; NOT merged. C1 not started.**

---

### D3 — Inventory: seat claims — 2026-06-18 — GATE GREEN (pending CI)

**Milestone (Ancillaries Delta 0.2 §5 Inventory delta; build plan D3):** manage the seat-claim lifecycle
riding the existing flight hold — a seat is never claimed without a hold, and releasing/expiring a hold
releases its seat claims. Branch `tranche1/D3` off master (`df3bee5`). Merge rule D3-after-B5 satisfied.
**No saga, compensation, payment or breaking-contract change.**

**Production changes (Inventory module; additive):**
- **PIECE 1 — model**: `SeatClaim` entity (holdId, flightInventoryId, cabinCode, seatNumber, passengerId,
  status {CLAIMED, RELEASED}) + `SeatClaimStatus`; migration **V3** with a **partial unique index** on
  `(tenant_id, flight_inventory_id, seat_number) WHERE status='CLAIMED'` (a seat is uniquely owned across
  live claims). `SeatClaimRepository`. Inventory does **not** validate seat geometry (D5's concern).
- **PIECE 2 — operations** (`HoldService`): `claimSeats(holdId, selections)` — under the **same
  @Transactional + per-flight lockForUpdate** pattern as createHold, all-or-none; a seat already CLAIMED
  by another hold rejects the whole request (422 SEAT_ALREADY_CLAIMED); re-claiming a seat owned by the
  same hold is idempotent. `getOccupancy(flightId)` (read-only) → the CLAIMED seat numbers (D5's seat-map
  source). New additive endpoints: `POST /v1/holds/{holdId}/seats` (Idempotency-Key, on HoldController)
  and `GET /v1/seats/occupancy/{flightInventoryId}` (SeatController).
- **PIECE 3 — lifecycle subordinate to the hold**: claimSeats requires a HELD, un-expired hold (else 422
  HOLD_NOT_ACTIVE) and seats on flights the hold holds. `releaseHold` and `expireInternal` now cascade the
  hold's live claims to RELEASED **in the same transaction** (no orphaned claims); `confirmHold` leaves
  claims CLAIMED (they become the booked seats).

**Why the saga doesn't change (stop-flag #2 honoured):** the cascade lives **inside** inventory's
`releaseHold`/`expireInternal`. The order saga's existing compensation already calls the inventory release
endpoint — so it now releases seat claims for free, with **zero saga/compensation code change**. The
existing hold-release step covers seat-claim release without modification (no latent gap → no flag).

**Gate checks run — local (compile + unit + fitness) GREEN; the seat ITs run on the CI Docker matrix:**
- `:inventory-service:test` compiles + unit tests green (HoldServiceTest updated for the new repository
  dependency; release/expire still green — the cascade is a no-op when there are no claims).
- `SeatClaimIT` (new, real Postgres) proves: claim + occupancy; **idempotent** same-hold re-claim;
  **HOLD_NOT_ACTIVE** on a released hold; **release cascade** (getOccupancy empties, claim → RELEASED);
  **confirm keeps CLAIMED**; cross-hold **SEAT_ALREADY_CLAIMED**; and the **concurrency race** (two holds
  for the same seat → exactly one wins, no double-claim under any interleaving).
- All **seven fitness checks GREEN** (#6/#7 unaffected — no Razorpay symbol, no enum drift). The new
  `SeatClaim` entity is validated against V3 by `SchemaValidationIT` (ddl-auto validate) on CI.

**Deviations / decisions (recorded — none a FLAG):**
- **Separate `claimSeats` operation** (work-order design) rather than the delta §5's "seatClaims embedded
  in the hold request's lines". Same principle (claim rides the hold, same transaction, inherited
  lifecycle); the work order's separate additive endpoint is the D3 task. Error codes per the work order:
  **SEAT_ALREADY_CLAIMED** + **HOLD_NOT_ACTIVE** (the delta's prose "SEAT_UNAVAILABLE" was for the
  embedded-in-hold variant; not used in the separate-operation design).
- **No seat geometry validation** (cabin layout / valid seat numbers) — deferred to D5 (stop-flag #1).
- **No saga/compensation/payment change**; **additive contract only** (claimSeats + occupancy endpoints);
  fitness #6/#7 green.

**Measured numbers awaiting ratification:** none new. **STOP — D3 built; local unit + fitness green; the
seat ITs pend the CI Docker matrix. PR opened; NOT merged. D4 not started.**

---

### D4 — Order: ANCILLARY materialization — 2026-06-18 — GATE GREEN (pending CI)

**Milestone (Ancillaries Delta 0.2 §6 Order delta; build plan D4):** materialize ancillary selections
(seats + bags) as ANCILLARY line items alongside AIR. Branch `tranche1/D4` off master (`fea3ed3`). Merge
rule D4-after-B4 satisfied. **No payment, saga or pricing change.**

**Design-seat ruling (AskUserQuestion):** **Piece 3 (ANCILLARY_CATALOGUE_CHANGED guard) dropped** — it is
not in the authoritative §6 delta and the plumbing it needs (a catalogueVersion source + a confirm-time
catalogue check) crosses the contract + saga stop-flags, unratified for this milestone. No catalogueVersion
recorded, no confirm-time check. **The create-time money invariant remains the price-drift guard.** Built
Pieces 1, 2 and 4 only.

**Production changes (Order module):**
- **PIECE 1 — materialization** (Ancillaries Delta 0.2 §6.1/§6.2): the order consumes ANCILLARY items from
  the **augmented offer** (§6.1) — `OfferHttpAdapter` now parses ANCILLARY offer items (productRef=code,
  `attributes.seatNumber`) and materializes one ANCILLARY `OrderItem` per slice (new `OrderItem.seatNumber`,
  V6 migration). 2×ADT return + 2 seats + 1 bag → 4 AIR + 3 ANCILLARY items. **Money invariant** now sums
  AIR + ANCILLARY = grandTotal, aborting on mismatch. **§6.1 validation** (`OrderAncillaryValidation`):
  ANCILLARY paxRef ∈ passengers, segmentRef ∈ itinerary, SEAT carries a seat number → else 422
  `ANCILLARY_REF_MISMATCH`. **Seat claim**: a SEAT selection triggers `InventoryPort.claimSeats` (the D3
  operation) against the placed hold; a conflict releases the hold (the existing release — D3 cascades the
  seat release) and aborts with 422 `ANCILLARY_CONFLICT`.
- **PIECE 2 — events**: OrderCreated/OrderConfirmed already carry items generically; ANCILLARY items flow
  through unchanged. No schema change — `OrderEventsSchemaTest` adds mixed AIR+ANCILLARY fixtures (7-item
  OrderCreated, mixed OrderConfirmed) validated against `order-events.schema.json`.
- **PIECE 4 — cancellation**: no change — `cancelOrder` refunds `amountPaid` (= the full grandTotal,
  air + ancillaries; verified). The decline/compensation path releases the hold, which cascades seat-claim
  release (D3, proven). No new compensation step.

**Stop-flags honoured:**
- **Money invariant strengthened, not weakened** (#1) — now spans AIR + ANCILLARY.
- **claimSeats is within order creation, not the saga** (#2/#4): placed in `OrderApplicationService.createOrder`
  after `saga.book` (which placed the hold); `BookPayFulfilSaga` is unchanged. It is a remote call whose
  failure releases the hold via the **existing** release op (stop-flag #2's "existing hold pattern is the
  precedent") — not a literal cross-service transaction.
- **No new compensation step** (#3) — the existing hold release (D3 cascade) covers seat release on
  decline; voluntary cancel of a confirmed order refunds the full total (no seat release — the seats are
  the booked seats).
- **No payment/pricing change** (#4); fitness #6/#7 green.

**Gate checks run — local (compile + unit + fitness) GREEN; the ITs run on the CI Docker matrix:**
- `:order-service:test` green incl. `OrderAncillaryMaterializationTest` (mixed 4 AIR + 3 ANCILLARY = 7
  items; money invariant across both; the §6.1 ref-mismatch matrix; isSeat) and `OrderEventsSchemaTest`
  mixed fixtures.
- `OrderCreateMultiPaxIT` (real Postgres, scripted stubs) extended: 2×ADT return + 2 seats + 1 bag → 7
  items, total 12260.00, seats claimed; **ANCILLARY_CONFLICT** (stub seat conflict → abort + hold
  released); **cancellation refunds the full 12260.00** (air + ancillaries); **decline releases the hold**.
- All **seven fitness checks GREEN**; `SchemaValidationIT` validates `OrderItem.seat_number`/V6 on CI.

**Deviations / decisions (recorded — design-seat-ruled or consistent with prior milestones):**
- **Piece 3 dropped** (ruling above): "ANCILLARY_CATALOGUE_CHANGED guard deferred — not in §6, requires
  additive offer-contract change and a saga touch, parked for a later milestone."
- **Seat claim via the separate D3 `claimSeats` call** (work order + built D3), not §6.3's "fold seatClaims
  into the hold request" — same deviation recorded at D3; the hold request is unchanged.
- **Ancillary source = the augmented offer** (§6.1/§6.2), not a CreateOrder request field. The reprice
  guard now compares to the offer's **AIR subtotal** (Σ FLIGHT amounts), not grandTotal, so an augmented
  offer doesn't falsely trip OFFER_PRICE_CHANGED; ancillary prices come from the offer (not re-validated —
  consistent with dropping the catalogue guard).
- **§6.4 EMD bridge deferred** — not among the D4 work-order pieces or gate (EMD-A/-S typing realism is
  Open Decision 5); ANCILLARY items confirm without a documentRef for now.
- **claimSeats HTTP wire**: order→inventory shapes (`POST /v1/holds/{id}/seats`, `{seats:[{flightInventoryId,
  cabin,seatNumber,passengerId}]}`, Idempotency-Key) match the D3 inventory endpoint by construction
  (both built this pair); the order-level scenarios are proven by `OrderCreateMultiPaxIT` (scripted
  inventory stub), and the real wire is exercised E2E in the demo / C-series.

**Measured numbers awaiting ratification:** none new. **STOP — D4 built; local unit + fitness green; ITs
pend the CI Docker matrix. PR opened; NOT merged. D5 / E4 not started.**

---

### D5 — IBE: seat map + bag step + server-ratified totals — 2026-06-18 — GATE GREEN (pending CI)

**Milestone (Ancillaries Delta 0.2 §7 IBE/BFF, §4.2 seat geometry; build plan D5):** a seat map + bag
step between offer selection and checkout, with seat geometry validation (D3 deferred it) and a
server-ratified total. Branch `tranche1/D5` off master (`88ab6d5`). Merge rule D5-after-D4 satisfied.
**No saga, compensation, payment or inventory code change.** Simulator PSP.

**Production changes (BFF + thin IBE):**
- **PIECE 1 — seat map** (`seatmap/` package): `GET /bff/flights/{flightId}/seatmap` assembles the cabin
  grid from **static config** (`CabinGeometry`: rows × columns × cabin code — one demo aircraft type) +
  live occupancy from Inventory (`InventoryOccupancyClient` → D3 `GET /v1/seats/occupancy/{id}`). Each
  cell carries seatNumber, cabinCode, occupied. Mapping only.
- **PIECE 2 — seat geometry validation** (`AncillaryService` / `CabinGeometry.contains`): a seat must be a
  real seat within the cabin's row/column bounds, and at most one seat per passenger per segment — else
  **422 INVALID_SEAT_SELECTION**, before any offer call. This is the bounds check D3 deferred.
- **PIECE 3 — IBE seat + bag steps** (`static/ancillaries.html`, thin vanilla): Step A renders the grid
  (occupied greyed, one seat per passenger), Step B a 0–2 bag stepper. On proceed the IBE POSTs to
  `/bff/offers/{offerId}/ancillaries`, which validates geometry then calls **AugmentOffer (D2)**; the
  **augmented offer's grandTotal is the server-ratified total** shown at checkout. The IBE never
  self-calculates the checkout total. `shop.html` now routes proceed → `ancillaries.html` → `checkout.html`.

**Gate checks run — local (compile + unit + fitness) GREEN; the Scene-3 IT runs on the CI Docker matrix:**
- `:channel-bff:test` green incl. `SeatmapServiceTest` (grid assembled from config + occupancy, occupied
  seats marked) and `AncillaryServiceTest` (valid seat+bag → AugmentOffer with the exact selections via
  equality-stubbing; out-of-bounds/malformed seat → INVALID_SEAT_SELECTION; one-seat-per-pax-per-segment;
  bag-count bounds; two bags → two BAG selections; never calls Offer on a bad request).
- `OrderCheckoutSmokeIT` extended with **Scene 3** (real BFF + Offer/Pricing/Inventory/Payments containers,
  order in-process): ONE_WAY shop → seat map (12A free) → augment seat 12A + 1 bag → server-ratified
  augmented offer → order (1 AIR + 2 ANCILLARY) → settle → CONFIRMED → BFF confirmation total = the
  augmented grandTotal **and occupancy now shows 12A**. This also exercises the real D4 order→inventory
  `claimSeats` wire end to end.
- All **seven fitness checks GREEN** (BFF provider-neutral — #6/#7).

**Deviations / decisions (recorded — §7-aligned or consistent with prior milestones):**
- **Ratification via AugmentOffer, not a separate quote** — the work order names "`/bff/offers/ancillaries/
  quote` (D1's pricing endpoint)", but §7 ratifies via **AugmentOffer**, and **D4 (merged) materializes
  ANCILLARY items only from the augmented offer** — so AugmentOffer is required regardless, and its
  grandTotal is the server-ratified total; a separate PriceAncillaries quote would be redundant. The BFF
  proxies `POST /v1/offers/{id}/ancillaries` (Idempotency-Key); the augmented offer feeds checkout.
- **Static fleet/cabin geometry** (work-order-sanctioned): **carry-forward — dynamic fleet/cabin lookup
  deferred** to a later milestone (the §4.2 `GET /v1/cabins/{flightId}/seatmap` master-data endpoint does
  not exist; the BFF uses config).
- **§6.4 EMD bridge** still deferred (carried from D4; not a D5 piece).
- **Stop-flags honoured**: BFF mapping-only except the in-scope geometry validation (#2); the IBE uses the
  server-ratified AugmentOffer total, never self-calculated (#3); no saga/compensation/payment/inventory
  code change (#4); no dynamic fleet or zone pricing (#1); fitness #6/#7 green (#5).

**Measured numbers awaiting ratification:** none new. **STOP — D5 built; local unit + fitness green; the
Scene-3 IT pends the CI Docker matrix. PR opened; NOT merged. E4 / C1 not started.**

---

### E4 — Dashboard: availability tile + ancillary metrics — 2026-06-18 — GATE GREEN (pending CI)

**Milestone (Dashboard Delta 0.2 §3 funnel/§4 tiles, §4.1 money discipline, §6 fitness; build plan E4):**
fill the two projection areas E3 left as TODO placeholders — (1) the live **availability tile**
(flightInventoryId → seats remaining), and (2) **ancillary metrics** (attach rate + ancillary revenue) —
read-side only. Branch `tranche1/E4` off master (`8ae52a7`). Merge rule **E4-after-D4** satisfied (D4
merged #32). **No write to any module, no synchronous module call, no new event, no schema change.**

**Production changes (dashboard-service read model only):**
- **PIECE 1 — availability tile.** New `FlightAvailability` projection (`@IdClass` (tenantId,
  flightInventoryId, rbd) → absolute `availableSeats` + `updatedAt`); `AvailabilityChanged` (already on the
  bus, already consumed for the ticker since E2) now upserts the row — newest-snapshot-wins (an
  out-of-order older `occurredAt` is ignored). The snapshot exposes `availability: flightInventoryId →
  Σ seats across its booking classes`. Scene 6 restore-on-decline is just the release-path
  `AvailabilityChanged` raising the count back.
- **PIECE 2 — ancillary metrics.** `OrderConfirmed` does **not** carry items (payload is `{amountPaid,
  documentRefs}`, `additionalProperties:false`); D4 emits ANCILLARY items on **OrderCreated**. So the read
  model **correlates the two events** (design-seat approved — see deviation): a new `OrderAncillaryLedger`
  (keyed by envelope `orderId`) records each order's ANCILLARY count + total on **OrderCreated** (only
  ancillary-bearing orders); on **OrderConfirmed** the ledger row recognises `ordersWithAncillary += 1`
  and `ancillaryRevenue += ledger.total`; on **OrderCancelled** the ancillary revenue is backed out (the
  attach stays). `attachRate = ordersWithAncillary / bookings` is derived on read. Envelope `orderId`
  added to `DashboardEvent` + parser (Order events only; null otherwise).
- **Money discipline preserved (§4.1):** `revenueGross` still comes **only** from `OrderConfirmed.amountPaid`
  (the full AIR+ANCILLARY grand total); the ledger only *breaks out* the ancillary portion for the KPI —
  it is never summed into gross. `refundsTotal` still only from `RefundIssued`.
- **Migration** `V2__e4_ancillary_availability.sql`: two columns on `dashboard_daily_counter`
  (`orders_with_ancillary`, `ancillary_revenue`) + tables `order_ancillary_ledger`, `flight_availability`.

**Gate checks run — local (compile + unit + fitness) GREEN; the Scene-6/Scene-3 ITs run on the CI Docker matrix:**
- `:dashboard-service:test` green. `ProjectionIT` (+E4): ancillary KPIs recognised at confirmation
  (attachRate=1.0000, ancillaryRevenue=892.50, gross=6692.50 full total); confirmed-with-no-ancillaries →
  attachRate/ancillaryRevenue 0; cancel backs out ancillary revenue, attach intact; availability tile
  restore (1→3) + sums booking classes + ignores an out-of-order older snapshot.
- `DuplicateDeliveryIT` (fitness (b) — every handler): `AvailabilityChanged` ×2 → tile set once;
  `OrderConfirmed` of an ancillary order ×2 → KPIs recognised once.
- `DashboardStreamIT` (E2E SSE over real HTTP): **Scene 6** decline→release `AvailabilityChanged` moves the
  on-screen availability tile (1→3); **Scene 3** confirm an order with ANCILLARY → attachRate +
  ancillaryRevenue + full gross move on screen.
- All **seven fitness checks GREEN** — incl. `check_dashboard_isolation` (#3: still no module import, no
  HTTP client, no foreign datasource — the two new tables are the dashboard's own read model) and the
  duplicate-delivery handler coverage (#4).

**Deviations / decisions (recorded):**
- **OrderCreated+OrderConfirmed correlation (design-seat approved):** OrderConfirmed does not carry
  ANCILLARY items (D4 emits them on OrderCreated); ancillary metrics are sourced by correlating both events
  in the read model — revenue recognised at confirmation, sourced from OrderConfirmed's amountPaid and
  broken down by the OrderCreated ledger record. **No synchronous call, no new event, no Order module or
  schema change.** This resolves the work order's §66 assumption that OrderConfirmed carries items.
- **No AvailabilityChanged FLAG needed:** the tile's data (`flightInventoryId, rbd, availableSeats`) is
  already in the existing `AvailabilityChanged` payload (Inventory emits it on hold create + release),
  which the dashboard already subscribes to — no new event, no stop-flag.
- **Stop-flags honoured:** no synchronous module call (#3 isolation green); no new event beyond the six
  consumed types + ShopPerformed; money figures only from OrderConfirmed/RefundIssued (§4.1); fitness #6/#7
  green.

**Carry-forwards (unchanged):** §6.4 EMD bridge (from D4); dynamic fleet/cabin lookup (from D5); D4 Piece-3
catalogue guard (dropped, design-seat); C1 Razorpay sandbox smoke; C2 ≤2s dashboard latency (open decision
awaiting measurement).

**Measured numbers awaiting ratification:** none new (C2 will measure dashboard push latency). **STOP — E4
built; local unit + fitness green; the Scene-6/Scene-3 + duplicate-delivery ITs pend the CI Docker matrix.
PR opened; NOT merged. C1 not started.**

---

### C1 — Breadth × PSP join smoke — 2026-06-19 — GATE GREEN (pending CI)

**Milestone (DEMO_SCRIPT Scenes 0–7; build plan C1 as amended by Addendum-1 §1.2: "Scenes 1–7 with
ancillaries, dashboard assertions per E"). The join milestone — every workstream converges in one scripted
end-to-end smoke.** Branch `tranche1/C1` off master. **No new feature code** — integration proof only
(one IT + build wiring + the test's own demo-data seed).

**SCOPE RECONCILIATION (recorded, not a FLAG):** the *original* build-plan C1 line scopes the smoke as
"Scenes 1–7 minus ancillary add and dashboard," and lists "anything ancillary-/dashboard-shaped" under
stop-and-flag. **Addendum-1 §1.1–1.2 explicitly lifts that exclusion** once D4/D5/E3 are green (all merged):
the amended C1 end-state is Scenes 1–7 with ancillaries + dashboard assertions per E. The work order's
fuller scope (Scenes 0–7) therefore aligns with the *amended* design — no scope stop-flag. Scene 0
(baseline-zeros) is a precondition of the dashboard assertions; the full "both screens, unattended, twice"
remains C2.

**What was built:** `platform/tests/integration/.../C1JoinSmokeIT.kt` — the full platform end-to-end with
the event backbone LIVE, so the dashboard (first read-side consumer) is proven against the actual bookings:
- a real **Redpanda** broker (Testcontainers; host listener for the in-process order, network listener
  `redpanda:29092` for the containers); topics pre-created via AdminClient.
- inventory / pricing / offer / payments / channel-bff / **dashboard-service** as real bootJar containers
  (order in-process, RANDOM_PORT, exposed to the network). The order/payments/inventory **outboxes** and the
  offer **telemetry** publisher are ENABLED (disabled in the lighter ITs) so the dashboard consumes
  ShopPerformed / OrderCreated / OrderConfirmed / OrderCancelled / PaymentFailed / RefundIssued /
  AvailabilityChanged off the bus. Per-container lean-JVM caps keep ~7 JVMs within the runner.
- build wiring: `dashboard.jar.path` + `:dashboard-service:bootJar` dep + `org.testcontainers:redpanda` +
  `org.apache.kafka:kafka-clients` added to `platform/tests/integration/build.gradle.kts`.

**The 7 scenes (asserted) and the PAYMENT PATH used for each:**
- **Scene 0** — dashboard SSE live; KPI row all zeros for acme-air. *(read-side SSE)*
- **Scene 1** — shop BLR→BOM RETURN, 2 ADT → two CANDIDATE directions; ShopPerformed reaches the dashboard
  funnel. *(no payment)*
- **Scene 2** — combine → one JOURNEY offer; 4 AIR components; grandTotal = ₹23200 (4 × ₹5800). *(no payment)*
- **Scene 3** — seat map (no occupied) → augment **2 seats (one per pax, outbound) + 1 bag (pax 1)** via the
  AugmentOffer endpoint → order = **4 AIR + 3 ANCILLARY**, Σ items = server-ratified grandTotal (AIR + ancillary),
  seat claims registered (occupancy shows 12A/12B). *(no payment)*
- **Scene 4** — pay (UPI success): **signed Razorpay-shaped webhook → simulated PSP** settles the intent →
  PaymentResumeService resumes the saga → CONFIRMED; dashboard **bookings +1, revenueGross +grandTotal,
  attachRate > 0, ancillaryRevenue > 0**. *(simulator signal via signed webhook)*
- **Scene 5** — seat map shows 12A/12B occupied=true. *(read-side)*
- **Scene 6** — new 2×ADT return order, **decline** (`failure`-style): signed `payment.failed` webhook →
  simulated PSP → PaymentResumeService → CANCELLED; both flights' holds released, availability restored;
  dashboard **declines +1** and the **availability tile** restores for both flights. *(simulator signal via signed webhook)*
- **Scene 7** — cancel the Scene-4 confirmed booking → saga issues a refund (simulated PSP) → intent
  REFUNDED; refund amount = **full grandTotal (AIR + ANCILLARY)**; dashboard **refundsTotal +grandTotal,
  revenueNet falls by grandTotal**, cancellations +1. *(synchronous refund against the simulated PSP)*

**EGRESS (recorded):** CI has **no egress to api.razorpay.com and no Razorpay sandbox credentials**
(`build-and-test.yml` sets none). Per work-order note 4 + implementation-notes (a), PSP outcomes are driven
the proven simulator-signal way (signed Razorpay-shaped webhook to the payments container + PaymentResumeService);
**no live Razorpay sandbox API call is made in CI.** The live-sandbox keys-valid / adapter-reaches-rails half
is separately evidenced by the 2026-06-19 operator run of `RazorpaySandboxConformanceTest` (see that entry).

**DEMO DATA:** the smoke seeds exactly what it needs in its companion (BLR–BOM out + BOM–BLR in flights,
cabin/bucket/availability, filed fares + GST/YR/PSF taxes + FX, demo tenant acme-air) over JDBC, modelled on
`OrderCheckoutSmokeIT`, and resets inventory per run. **This inline seed is the precursor to C2's one-command
cold-rebuild seed** (build plan C2). The ancillary catalogue (SEAT/BAG) is tenant config in the offer/pricing
containers (D1), present by default — no extra seed.

**DEVIATIONS (design-aligned):**
- **Scene 3 endpoint** — the work order's `POST /bff/offers/ancillaries/quote` does not exist; D5 ratified
  the server-ratified total via **AugmentOffer** (`POST /bff/offers/{offerId}/ancillaries`). Used that.
- **Ancillary count** — DEMO_SCRIPT Scene 3 + the D4 gate ("2×ADT return + 2 seats + 1 bag → 4 AIR + 3
  ANCILLARY") govern: **2 seats + 1 bag = 3 ANCILLARY**. The work order's "2 bags" is a typo (its own
  assertion says 3 ANCILLARY).
- **Order saga resume** — kept deterministic via PaymentResumeService (the order Kafka consumer stays off);
  the bus carries events to the dashboard. No saga / compensation / event change (stop-flags honoured).

**Gate checks — local: `:tests:integration:compileTestKotlin` green; all 7 fitness checks green (incl. #6
no-Razorpay-symbols and #7 enum parity). The C1JoinSmokeIT runs on the CI Docker matrix (self-skips without
Docker).** Reliability note: this is the heaviest IT in the repo (~13 containers); if the runner cannot
sustain it that surfaces the **C2 cluster CPU-ceiling investigation** (build plan C2 / DEMO_SCRIPT §5), a
C2 concern — not a C1 code fix (stop-flag #5).

**Measured numbers awaiting ratification:** dashboard push latency target ≤2s remains a C2 measurement (the
smoke uses generous propagation budgets, not a latency assertion). **STOP — C1 built; local compile + fitness
green; the join smoke pends the CI Docker matrix. PR opened; NOT merged. C2 not started.**

**CI RESULT (2026-06-19):** `C1JoinSmokeIT` ran green on the CI Docker matrix — the full 13-container stack
came up and **all 7 scenes passed end-to-end** (dashboard baseline → shop/combine → 4 AIR + 3 ANCILLARY
order with seats claimed → pay→CONFIRMED with dashboard bookings/revenue/attach-rate moving → occupancy →
decline→CANCELLED with holds released + availability tile restored → cancel→refund of the full grand total
with revenueNet falling). One assertion bug was fixed mid-gate (Scene 6 compared a Boolean to a Pair); the
test itself then passed on every subsequent run.

**Reliability fix (forkEvery=1):** the build-and-test job had been failing ~14 min *after* the tests passed
with a runner shutdown signal. Root cause: each IT class holds its Testcontainers in static (companion)
fields reaped only at JVM exit, and all IT classes share ONE test JVM — so the containers from every heavy
IT (`OrderPaymentsIT`, `OrderCheckoutSmokeIT`, `CrossServiceIT`, and the C1 smoke's ~13) were alive at once
by suite end, exhausting the runner. C1 didn't introduce the flake (it predates C1 — see E4 / refund-drain
gates) but tipped the accumulated peak over the edge. Fix: `forkEvery = 1` on the `tests:integration` test
task (fresh JVM per IT class → each class's containers reaped before the next starts → peak bounded to one
class). Pure test-execution config — no production code, no new tests, no scope change. **Gate met:
build-and-test (full matrix: build-and-test + fitness-functions + dependency-scan) GREEN on two consecutive
runs** (`f31f0ac`, `1508b60`); the suite is also faster (no resource thrash). This actions the demo-grade
slice of the C2 "cluster CPU-ceiling investigation" at the CI level; the cluster-side investigation remains
C2.

**STOP — C1 gate GREEN on CI. PR #37 open; NOT merged. C2 not started.**

---

### C2 — Demo environment — GATE REPORT — 2026-06-23

Deliverable 1 — Idempotency:
- Run 1: exit 0, smoke PASS
- Run 2: exit 0, smoke PASS (dashboard bookings 3→4, clean re-seed)

Deliverable 2 — Dashboard latency:
- Environment: CI Docker matrix (Linux)
- Measurement: C1JoinSmokeIT, 12 bookings, outbox-commit → SSE render
- P50: 1025 ms
- P99: 1095 ms
- SLO (P99 ≤ 2000 ms): MET
- Note: local Testcontainers skipped (Windows JVM). SLO should be
  re-validated on the real demo cluster before the live demo.

Gate result: GREEN
Tag on merge: v0.2.0-tranche1

---

### Operator run — RazorpaySandboxConformanceTest (live Razorpay sandbox) — 2026-06-19

Out-of-band operator run of `RazorpaySandboxConformanceTest` (the env-gated
`@EnabledIfEnvironmentVariable(RAZORPAY_ACME_TEST_KEY_ID)` subclass of `PaymentProviderConformance`)
against the **live Razorpay sandbox**, from a provisioned environment with the rotated ACME/TEST
credentials exported. This is the delta §10 "manual-rehearsal" tier — it never runs in the unit CI
tier (no keys, no egress).

**Result — operator gate satisfied:**
- **2 passed:** `initiate` (opens a customer window, returns a provider handle) and `tokenize` (vaults
  to an opaque token, no PAN). These need no captured payment, so they pass on live rails as-is.
- **6 expected-gap failures:** `settle`, `decline`, `ambiguous outcome`, `late settlement`,
  `idempotent refund`, `refund failure`. These require a **real captured payment** (a customer-in-the-loop
  Razorpay Checkout step before settle/decline can be observed via `getPaymentStatus`). This is the
  documented thread-real-ids-through-the-suite gap recorded in the **A2 gate report** and deferred to the
  **C1 CI-smoke** milestone — the suite is left UNMODIFIED. The failures are EXPECTED, not regressions.
- **Confirmed:** sandbox keys are valid and the `RazorpayProviderAdapter` reaches live Razorpay rails
  (credentials resolve through `RazorpaySecretStore`, residency guard satisfied, HTTP to api.razorpay.com).

**Bearing on C1:** this satisfies the operator prerequisite for the C1 Razorpay-sandbox smoke (carried in
the D5/E4 carry-forwards). C1 must supply the operator Checkout-capture step (thread real PSP ids through
the 6 deferred cases) for them to pass on live rails; the keys-valid / adapter-reaches-rails half is now
verified. **No code change. No design discrepancy. Secrets handled out-of-band (none in repo/log/fixture).**

---

### GATE REPORT — Loyalty L1 (ledger + earn/tier engines + ManualEarn) — 2026-07-01 — `feat/loyalty-l1`

**Milestone (Loyalty Delta 0.3 §3–§8 / BUILD_PLAN_LOYALTY_L1 §1–§11):** the append-only points ledger, the
pure earn-rule and tier engines behind ports with abstract conformance suites, the balance/tier projection,
the ManualEarn admin seam plus GetBalance/GetTransactions, and `MilesAccrued`/`TierChanged` emission via the
L0 outbox — built on the L0 skeleton with **no change to order/pricing/offer/payments** internals.

**Delivered:**
- **V2 migration** `V2__loyalty_ledger_earn_rules.sql` (V1 untouched): `points_transactions`
  (`CONSTRAINT uq_ledger_dedupe UNIQUE (tenant_id, dedupe_key)` — the idempotency guard; append-only, no
  UPDATE/DELETE), `earn_rules` (partial unique index — one active rule per tenant), `tier_change_events`.
- **Entities/repos**: `PointsTransaction` (no `@Version`), `EarnRule`, `TierChangeEvent` + four enums;
  `PointsTransactionRepository` (dedupe lookup, newest-first history, `SUM` projections), `EarnRuleRepository`,
  `TierChangeEventRepository`.
- **Pure engines** `InProcessEarnRuleEngine` (`points = floor(basis × pointsPerCurrencyUnit)`, qualifying
  miles = floor(basis)) and `InProcessTierPolicyEngine` (SILVER ≥ 25 000, GOLD ≥ 50 000; direction-agnostic).
- **`LedgerService.append`** — idempotent on `(tenantId, dedupeKey)` (pre-check + DB unique backstop),
  reprojects balance/miles, evaluates tier, writes `TierChangeEvent` + enqueues `TierChanged` on a crossing,
  enqueues `MilesAccrued` — one transaction. **`ManualEarnService`** for the earn seam + read side.
- **`LedgerController`**: `POST …/earn` (201 `PointsTransaction`; `X-Loyalty-Admin` → 403 if absent,
  `Idempotency-Key` required), `GET …/balance`, `GET …/transactions` — shapes per `loyalty-api_openapi.yaml`,
  tenant from `TenantContextHolder`.

**Gate checks run (this box + CI on merge):**
- **Unit — 18 pass / 0 fail (run locally, not skipped):** `EarnRulePortConformanceSuite` (5),
  `TierPolicyPortConformanceSuite` (8, both directions + boundaries 24999/25000/49999/50000),
  `LoyaltyEventSchemaTest` (5 — a built `MilesAccrued`/`TierChanged` validates against
  `loyalty-events_schema.json`; asserts `version` is the string `"1"` and ULID patterns are enforced).
- **Integration — 14 present, SKIP locally (no Docker in the Gradle JVM), run in CI:** `ManualEarnIT` (8:
  accrual→ledger+balance+MilesAccrued, idempotent repeat, tier crossing→SILVER+TierChanged+row, 422
  NO_ACTIVE_EARN_RULE, 403 missing admin, 422 MEMBER_SUSPENDED, 404 unknown, tenant isolation),
  `SchemaValidationIT` (1, ddl-validate over V1+V2), `EnrolMemberIT` (5, unchanged).
- **`./gradlew -p platform build --max-workers=1` → BUILD SUCCESSFUL.** All 9 fitness functions green
  (incl. `check_tenant_context` for the new controller).

**Deviations (recorded, none blocking; see FLAG-005 resolution):** two plan-vs-contract details resolved in
favour of the authoritative OpenAPI/JSON-Schema — ManualEarn's 201 body is `PointsTransaction` (not the plan
§6 prose's `MemberSummary`), and the earn engine uses the build-plan §4 `EarnInput` signature (not the delta
§5 `OrderSummary` prose). The `LedgerService` concurrent-duplicate guard is the pre-check + `(tenant_id,
dedupe_key)` unique constraint (at-most-once regardless), rather than a same-transaction catch-and-reread,
which would hit Hibernate's rollback-only state — the idempotency invariant is fully preserved.

**Out of scope (L2, deliberately not built):** OrderConfirmed/OrderCancelled consumer + order read-back,
REVERSAL, all redemption, GetStatement, MilesReversed/MilesRedeemed. Master untouched; delivered as PR.

---

### GATE REPORT — Loyalty L2 (accrual/reversal consumer + order read-back) — 2026-07-01 — `feat/loyalty-l2`

**Milestone (Loyalty Delta 0.3 §4 accrual/reversal, §8 events):** the reactive event-driven path that L1's
ManualEarn stood in for — consume `OrderConfirmed`→accrue and `OrderCancelled`→reverse off the Order event
backbone, resolving the member and the earn basis via a **read-only** order read-back. No L2 build-plan doc
exists; built against the delta + contracts (order-events / order-api / loyalty-events). **No change to
order/pricing/offer/payments internals** — the only coupling is the read-only `GET /v1/orders/{orderId}`.

**Ratified open decisions (delta §10; confirmed by the operator this session):**
1. **Accrual trigger** — accrue on `OrderConfirmed`, reverse on `OrderCancelled` (booking-time; L1 already
   assumed this for ManualEarn).
2. **Amount carriage** — `OrderConfirmed` carries only `amountPaid` (total) + `documentRefs`; no pax-ref /
   currency / base-fare. A read-only read-back is required **regardless of earn basis** for member
   resolution → L2 adds an Order client (delta §2 sanctions the read).
3. **BASE_FARE basis** — no first-class `baseFare` field on the Order read-model; derived as **Σ AIR item
   `price.amount`** (tax-exclusive; taxes are a separate array). Recorded derivation.
4. **Member resolution** — match order `Passenger.loyaltyRef` → `Member.externalRef`; no enrolled match →
   `NO_MEMBER` no-op (logged), per delta §4.

**Delivered:** `OrderEventConsumer` (`@KafkaListener` on `pss-order-events`, `autoStartup` gated so ITs boot
without a broker; acts only on `OrderConfirmed`/`OrderCancelled`) · `OrderEventParser` (envelope only;
malformed → skip) · `OrderServiceClient` (read-only RestClient over HTTP/1.1, minted tenant JWT, 404→null) ·
`AccrualService` (accrual: read-back → resolve member → BASE_FARE basis → `EarnRulePort` → ACCRUAL
`sourceType=ORDER_EVENT`, `dedupeKey=eventId`; reversal: find prior accrual by orderId → REVERSAL of negated
amounts) · `LedgerService` extended to emit `MilesReversed` for REVERSAL · `MilesReversedPayload`.
**Idempotent on `eventId`** (ledger dedupe; accrual also skips the read-back on redelivery).

**Gate checks run (this box + CI on merge):**
- **Unit — 19 pass / 0 fail (local):** earn (5) + tier (8) conformance + `LoyaltyEventSchemaTest` (6 — now
  includes a negative-points `MilesReversed` validating against the schema).
- **Integration — 18, run in CI (skip locally, no Docker):** `OrderEventConsumerIT` (4 — accrual on base fare
  not total, idempotent redelivery, reversal→`MilesReversed`+tier step-down, `NO_MEMBER` no-op; order
  read-back stubbed by WireMock) + the L0/L1 ITs (14).
- **`./gradlew -p platform build --max-workers=1` → BUILD SUCCESSFUL.** All 9 fitness functions green.

**Deviations (recorded, none blocking):** a missing active earn rule / missing account / order-not-found in
the consumer is logged-and-skipped (no ledger row) rather than raised — a Kafka record can't return a 422,
and at-least-once redelivery must not poison the partition. The idempotency + append-only + no-core-change
invariants are preserved.

**Out of scope (L3, deliberately not built):** all redemption (`RedemptionOption`/`Redeem`/`AwardRedemption`/
`MilesRedeemed`/`RedemptionFulfilmentPort`), `GetStatement`. Master untouched; delivered as PR.

---

### GATE REPORT — Loyalty L3 (redemption + fulfilment port + statement) — 2026-07-01 — `feat/loyalty-l3`

**Milestone (Loyalty Delta 0.3 §6 redemption, §7 API, §8 events):** the redemption slice — debit points →
mint a loyalty-owned award voucher behind a stub port — plus the redemption catalogue, award retrieval, and
the period statement. Closes the Loyalty tranche (L0–L3). **No money moves** (points are a loyalty-internal
unit; the voucher is loyalty-owned) and **no order/pricing/offer/payments internals change**.

**Delivered:**
- **V3 migration** (V1/V2 untouched): `redemption_options`; `award_redemptions` with
  `UNIQUE (tenant_id, dedupe_key)` idempotency guard + FKs to `loyalty_accounts` / `redemption_options`.
- **Entities/repos**: `RedemptionOption`, `AwardRedemption` + two enums; `RedemptionOptionRepository`
  (active-by-tenant, active-by-id), `AwardRedemptionRepository` (by-id-tenant, by-dedupe-key); statement
  queries on `PointsTransactionRepository` (Σ points before period; lines within period).
- **`RedemptionFulfilmentPort`** — interface + `StubRedemptionFulfilmentPort` (mints `AWD-…` / `STUB-…`),
  wired as a `@Bean`. **Stays a stub** — no wiring into any Pricing/Payments write path (delta §11).
- **`RedemptionService`** (one transaction): idempotency pre-check on the award dedupe key → member/account
  (404) → suspended (422) → active option (422) → **balance check before any debit** (422
  INSUFFICIENT_POINTS, ledger untouched) → REDEMPTION ledger append via the one ledger path (reprojects
  balance; tier unchanged — only accrual/reversal move qualifying miles) → fulfil → persist `AwardRedemption`
  (ISSUED) → publish `MilesRedeemed`. **`StatementService`** projects opening/closing/accrued/redeemed.
- **`RedemptionController`**: `GET /v1/loyalty/redemption-options`, `POST …/{memberId}/redemptions`
  (201, member-facing — no `X-Loyalty-Admin`, requires `Idempotency-Key`), `GET …/redemptions/{id}` (404
  REDEMPTION_NOT_FOUND), `GET …/{memberId}/statement`. Shapes match `loyalty-api_openapi.yaml`.

**Gate checks run (this box + CI on merge):**
- **Unit — 26 pass / 0 fail (local):** `RedemptionServiceTest` (6 — happy path + idempotent replay + every
  error branch, asserting a rejected redeem never debits) + earn (5) + tier (8) + `LoyaltyEventSchemaTest`
  (7 — now includes `MilesRedeemed`).
- **Integration — 25, run in CI (skip locally, no Docker):** `RedemptionIT` (7 — debit + ISSUED award +
  MilesRedeemed + GetRedemption, idempotent, INSUFFICIENT_POINTS leaves the ledger untouched, suspended,
  unknown option, GetStatement opening/closing/lines, ListRedemptionOptions active-only + tenant-isolated) +
  L0/L1/L2 ITs (18).
- **`./gradlew -p platform build --max-workers=1` → BUILD SUCCESSFUL.** All 9 fitness functions green.

**Deviations (recorded, none blocking):** the concurrent-duplicate guard is the award dedupe pre-check + the
`(tenant_id, dedupe_key)` unique constraints (award + ledger) as DB backstops, rather than a same-transaction
catch-and-reread (which would hit Hibernate's rollback-only state) — the idempotency invariant holds. The
`GetStatement` `periodStart`/`periodEnd` query params are additive (the OpenAPI defines none) with a 30-day
default; the response body matches the `Statement` schema exactly.

**Loyalty tranche complete: L0 ✅ · L1 ✅ · L2 ✅ · L3 ✅ (this PR).** Master untouched; delivered as PR.

---

### GATE REPORT — Reporting & BI (thin historical read-side) — 2026-07-01 — `feat/reporting-bi`

**Milestone:** a new `reporting-service` (pkg `pss.reporting`, port 8087) — the standalone event-sourced
projection for the historical/drill-down/export views deferred from Dashboard Delta 0.2 §11. Same one
architectural rule as dashboard: **consumes domain events and nothing else** — no synchronous module call,
no foreign datasource. Every figure derives from published events (FLAG-006 governed the sourcing).

**Ratified sourcing (FLAG-006):** consume `OrderCreated` (currency, pax_count, ANCILLARY footprint) +
`OrderConfirmed` (revenue + ancillary recognition) + `OrderCancelled` (cancellations) + `RefundIssued`
(refunds). `origin`/`destination`/`channel` carried by no correlatable event this slice → nullable, left null.

**Delivered:**
- **V1 migration**: `seen_events` (dedupe), `daily_booking_summary` (unique `(tenant,date)`), `order_fact`
  (unique `order_id`), `order_ancillary_ledger` (OrderCreated→OrderConfirmed handoff), `report_exports`.
- **Consumer**: `ReportingEventConsumer` (`@KafkaListener` on `pss-order-events` + `pss-payments-events`,
  `autoStartup` gated) → `ReportingEventParser` → `ProjectionService.apply` (idempotent on `eventId` via
  `seen_events`; upsert-by-order_id makes it out-of-order safe). Amounts are whole currency units; day
  bucket = `occurredAt` in UTC (dashboard Open Decision §10.1).
- **API** (`ReportingController`, tenant from `TenantContextHolder`): `GET /v1/reporting/summary`,
  `GET /v1/reporting/orders` (paginated + status filter), `POST /v1/reporting/exports` (201 PENDING,
  requires `Idempotency-Key`), `GET …/{id}`, `GET …/{id}/download` (text/csv). Export generation is
  synchronous-in-slice behind a `ReportStoragePort` stub (real blob storage deferred).
- **DemoSecurityConfig** (`@Profile("!prod")` permit-all) mirrors the other services.
- **New fitness `check_reporting_isolation.py`** (wired into `fitness-functions.yml`): no foreign-module
  imports, no HTTP-client imports, only `:libs:paved-road` project dep, no module base-url in config.

**Gate checks run (this box + CI on merge):**
- **Unit — 6 pass / 0 fail (local):** `ProjectionServiceTest` — dedupe guard, each handler's mutation, and
  a duplicate-delivery test per handler (same eventId twice → counted once).
- **Integration — 6, run in CI (skip locally, no Docker):** `ReportingIT` (5 — summary totals + date-range
  filtering, orders pagination + status drill-down, export PENDING→READY→CSV download, tenant isolation,
  redelivery counted once) + `SchemaValidationIT` (1, ddl-validate over V1).
- **`./gradlew -p platform build --max-workers=1` → BUILD SUCCESSFUL.** All 10 fitness functions green
  (incl. the new `check_reporting_isolation` and `check_tenant_context` for the new controller).

**Deviations (recorded, none blocking):** `OrderCreated` added to the consumer set + an `order_ancillary_ledger`
staging table and a `seen_events` dedupe table beyond the spec's three tables — all event-sourced
infrastructure the task's own rules require (idempotency dedupe + ancillary handoff). `origin`/`destination`/
`channel` nullable/unpopulated (FLAG-006). Export `POST` returns PENDING then generates synchronously
(models the async contract without storing from/to columns). Master untouched; delivered as PR.

---

### GATE REPORT — Conversational Commerce (WhatsApp thin channel) — 2026-07-02 — `feat/conversational-commerce`

**Milestone:** a new `conversation-service` (pkg `pss.conversation`, port 8088) — a **thin channel adapter**
(SDD §5.4/§6.2). It receives inbound WhatsApp messages via a webhook, drives a keyword state machine, calls
the SAME order/DCS APIs the IBE uses, and sends replies through a stub WhatsApp gateway. It owns conversation
**session state only** — never order/inventory/payment/loyalty/DCS state. **No module internals changed.**

**Delivered:**
- **V1 migration**: `conversation_sessions` (unique `(tenant, wa_phone_number)`; `context_json` TEXT, not
  jsonb), `outbound_notifications`, `seen_events` (event dedupe).
- **State machine** (`ConversationService`, keyword-only — no NLP): IDLE → greet → AWAITING_PNR → order
  lookup (found → BOOKING_CONFIRMED + summary; not found → stays) → "check in" → DCS check-in → CHECKIN_SENT.
- **Clients** (read-only order lookup + the existing DCS CheckIn path): `OrderServiceClient`
  (`GET /v1/orders?paxRef`, `/{orderId}`), `DcsServiceClient` (`GET /v1/departures`, `POST …/checkins`) —
  RestClient over HTTP/1.1 + minted tenant JWT; WireMocked in tests. No Order/DCS write path beyond the
  existing CheckIn; no store reads.
- **`WhatsAppGatewayPort`** + `StubWhatsAppGateway` (logs + acks, `STUB-…` ids), wired as a `@Bean`. **Stays a
  stub** — no real Meta API calls, credentials or webhook registration (deferred behind the port).
- **Event side** (`ConversationEventConsumer` + `EventNotificationService`): consume `OrderConfirmed`
  (order backbone) + `PassengerCheckedIn` (dcs backbone), idempotent on `eventId` via `seen_events`; correlate
  to a session by the order ref it stored → outbound notification; no session → no-op.
- **API** (`ConversationController`): public `POST /v1/conversation/webhook` (ACKs 200 immediately; tenant from
  the webhook payload; no signature verification this slice), admin `GET /sessions`, `/sessions/{id}`,
  `/notifications` (tenant from `TenantContextHolder`, gated by `X-Conversation-Admin`). DemoSecurityConfig
  mirrors the other services.

**Gate checks run (this box + CI on merge):**
- **Unit — 8 pass / 0 fail (local):** `ConversationServiceTest` (5 — each transition, keyword match,
  order-not-found stays AWAITING_PNR, check-in trigger) + `EventNotificationServiceTest` (3 — OrderConfirmed
  notifies, idempotent twice→once, no-session no-op).
- **Integration — 6, run in CI (skip locally, no Docker):** `ConversationIT` (5 — webhook→BOOKING_CONFIRMED,
  check-in→CHECKIN_SENT, order-not-found, OrderConfirmed event→notification, tenant isolation + admin gate;
  order/DCS WireMocked) + `SchemaValidationIT`.
- **`./gradlew -p platform build --max-workers=1` → BUILD SUCCESSFUL.** All 10 fitness functions green
  (incl. `check_tenant_context` for the combined webhook+admin controller — the webhook is tenant-from-payload,
  the admin endpoints reference `TenantContextHolder`, so the class satisfies the check).

**Deviations (recorded, none blocking):** the webhook payload is a stub shape (not the real Meta envelope) and
carries `tenantId` (the webhook-config tenant) — real Meta format + signature verification are deferred with
the gateway. Event→session correlation is by the order ref stored in `context_json` (a `Containing` query),
since neither `OrderConfirmed` nor `PassengerCheckedIn` carries a phone number. `seen_events` added beyond the
spec's two tables (the §8 idempotency the task requires). Master untouched; delivered as PR.

---

### GATE REPORT — Revenue Management (thin RM seam) — 2026-07-02 — `feat/revenue-management`

**Milestone:** a new `rm-service` (pkg `pss.rm`, port 8089) — Offer/Retailing layer (SDD §5.2, Inventory LLD
§12). It owns forecasting + class-level authorisation units (AUs) and feeds Inventory **one direction** via
the existing AdjustInventory API. It **never holds or reads inventory counts** — it sets AU controls Inventory
enforces. **No inventory/order/pricing internals changed.** (The Inventory LLD is a `.docx`, not machine-
readable by my tools; built from the task's self-contained spec — DDL, endpoints, engine formulas, invariants.)

**Delivered:**
- **V1 migration**: `flight_class_config` (unique natural key), `au_recommendations`, `au_adjustments`,
  `seen_events` (event dedupe).
- **Pure engines behind ports** (loyalty pattern; real ML deferred): `ForecastPort` + `StubForecastEngine`
  (demand signal = max(bookings, capacity×LF) × OB, capped at capacity×OB; confidence 0.75) and
  `OptimisationPort` + `StubOptimisationEngine` (AU = min(forecast, capacity×OB), pulled to the conservative
  capacity×LF baseline when confidence < 0.5), each with an **abstract conformance suite** (bounds, zero-history
  conservative, high-demand near authorised, zero-confidence conservative).
- **`InventoryServiceClient`** (write-only): `POST /v1/inventory/flight-classes/{id}/adjust-au` — RestClient
  over HTTP/1.1 + minted tenant JWT; maps ACCEPTED/REJECTED/PENDING; WireMocked in tests. No store reads.
- **`RmService`**: register (idempotent on natural key) · computeAndApply (forecast → optimise → persist
  recommendation → AdjustInventory → record adjustment → `applied` only on ACCEPTED, no retry) · list/get/history.
- **Reactive recompute** (`RmEventConsumer` + `RmEventService`): consume `OrderConfirmed`, idempotent on
  `eventId` via `seen_events`; match flight class → recompute; no-op otherwise.
- **API** (`RmController`, all `X-Rm-Admin`-gated, tenant from `TenantContextHolder`): register, list, detail,
  compute, recommendation history.

**Gate checks run (this box + CI on merge):**
- **Unit — 12 pass / 0 fail (local):** `ForecastPortConformanceSuite` (3) + `OptimisationPortConformanceSuite`
  (4) + `RmServiceTest` (2 — ACCEPTED→applied, REJECTED→applied=false no-retry) + `RmEventServiceTest` (3 —
  match triggers recompute, no-config no-op, duplicate no-op).
- **Integration — 6, run in CI (skip locally, no Docker):** `RmIT` (5 — compute→ACCEPTED applied, REJECTED
  applied=false, OrderConfirmed→reactive recompute, unmanaged-flight no-op, tenant isolation + admin gate;
  Inventory WireMocked) + `SchemaValidationIT`.
- **`./gradlew -p platform build --max-workers=1` → BUILD SUCCESSFUL.** All 10 fitness functions green
  (incl. `check_tenant_context` for the RM controller).

**Deviations (recorded, none blocking):** the forecast stub incorporates the booking snapshot (rather than
the illustrative `capacity×LF×OB` formula alone) so the conformance requirements (zero-history conservative,
high-demand near authorised) hold; the "small random noise" is fixed at 0 for deterministic tests. The
booking snapshot is stubbed to 0 this slice (event-derived counts deferred). **`OrderConfirmed` carries no
flight key today**, so the reactive recompute reads `flightNumber`/`departureDate`/`rbd` as optional payload
fields and **no-ops when absent** (exercised via enriched events in the IT); the headline path is the manual
`POST …/compute`. No order-service change made. `seen_events` added beyond the spec's three tables (the §6
idempotency). Master untouched; delivered as PR.

---

### GATE REPORT — Personalisation / CDP (thin profile + consent-gated ranking) — 2026-07-02 — `feat/personalisation-cdp`

**Milestone:** a new `cdp-service` (pkg `pss.cdp`, port 8090) — Offer/Retailing layer (SDD §5.2, Offer LLD
§11/§13). It owns the 360° customer profile **built exclusively from published events** (never module-store
reads) and serves a **consent-gated** offer-ranking endpoint behind a pure `RankingPort`. **Completes Phase 2.**

**Delivered:**
- **V1 migration**: `customer_profiles` (unique `(tenant, external_ref)`; only the listed PII), `consent_records`
  (unique `(tenant, external_ref, consent_type)`), `profile_events_seen` (event dedupe).
- **Event-sourced profile** (`CdpEventConsumer` on Order/Loyalty/DCS topics → `ProfileProjectionService`,
  idempotent on `eventId`): OrderConfirmed builds/updates; OrderCancelled decrements (floor 0); MilesAccrued/
  TierChanged set the tier; PassengerCheckedIn touches last-booking — update-only (no profile created from a
  cancellation/tier/check-in alone). Missing `externalRef` → no-op.
- **Consent** (`ConsentService`): upsert on the natural key; GIVEN/WITHDRAWN with timestamps.
- **Pure `RankingPort`** + `StubRankingEngine` (base 0.5 · preferred cabin +0.3 · preferred origin +0.2 · GOLD
  +0.15/SILVER +0.10 · frequent booker +0.10; sorted desc) with an **abstract conformance suite**.
- **`RankingService`** (the PersonalisationPort surface): **consent-first** — profile data used only when a
  PERSONALISATION consent is GIVEN (hard DPDPA invariant). No consent → `NO_CONSENT` neutral; no profile →
  `NO_PROFILE` neutral. Graceful degradation — never blocks shopping.
- **API** (`CdpController`, tenant from `TenantContextHolder`): consent POST/GET (customer-facing),
  `POST /v1/cdp/rank-offers` (s2s, consent-gated), profile GET + list (`X-Cdp-Admin`-gated).
- **New fitness `check_cdp_isolation.py`** (wired into `fitness-functions.yml`): no foreign-module imports,
  no HTTP-client imports, only `:libs:paved-road` project dep, no module base-url in config.

**Gate checks run (this box + CI on merge):**
- **Unit — 13 pass / 0 fail (local):** `RankingPortConformanceSuite` (4) + `ProfileProjectionServiceTest` (5 —
  build, idempotent, cancel floor-0, tier update, no-externalRef no-op) + `RankingServiceTest` (4 — consent
  gate: NO_CONSENT/withdrawn/NO_PROFILE/ranked).
- **Integration — 8, run in CI (skip locally, no Docker):** `CdpIT` (7 — consent record/read, profile build,
  two-bookings accumulate, rank preferred-cabin scoring, no-consent neutral, no-profile neutral, tenant
  isolation) + `SchemaValidationIT`.
- **`./gradlew -p platform build --max-workers=1` → BUILD SUCCESSFUL.** All 11 fitness functions green
  (incl. the new `check_cdp_isolation` and `check_tenant_context`).

**Deviations (recorded, none blocking):** **Offer-service integration deferred — see FLAG-007**: the
PersonalisationPort bean swap is NOT config-only (needs a new HTTP-adapter class + runtime dep in a core
module), so no offer-service file was touched; the `rank-offers` endpoint is the ready integration surface.
Source events don't carry `externalRef`/origin/cabin canonically today, so the parser reads them as optional
payload fields and handlers no-op when absent (task §4; enriched events drive the IT). No source module
changed. Consent invariant enforced (consent-first). Only the listed PII is stored. Master untouched;
delivered as PR.

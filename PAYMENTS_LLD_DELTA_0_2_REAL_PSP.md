# Payments Module — LLD Delta 0.2: Real PSP Integration (Razorpay)

**Status:** Working draft v0.1 — for ratification before build
**Companion to:** PSS_Payments_Module_LLD v0.1 · Order Module LLD (book-pay-fulfil saga) · DEMO_SCRIPT.md (Phase 2.0)
**Scope:** Replaces the Phase-1 simulated PSP with Razorpay (sandbox → production) behind `PaymentProviderPort`. Extends the PaymentIntent state machine for the customer-in-the-loop flow, specifies the webhook contract, changes the Order saga's pay step from synchronous to bounded-asynchronous, and specifies the refund drainer. Resolves four of the LLD v0.1 §13 open decisions; records two new ones.

---

## 1. Decision Record — PSP Selection

**Decision (ratified):** Razorpay as the Phase 2.0 / demo-tranche PSP. **AuroPay (Aurionpro's own PSP) is the intended successor**, gated on the AuroPay Integration Questionnaire (separate document): when its Gating answers come back positive, an `AuroPayProviderAdapter` replaces Razorpay with no change to the domain, state machine, saga, or webhook-handling principles. Cashfree remains the documented third-party fallback.

**Swap protection (build discipline, enforced in CI):**
- All Razorpay specifics (event names, signature header/scheme, paise conversion, API paths) live **only** in `RazorpayProviderAdapter` and its webhook translator. Nothing Razorpay-shaped may appear in the domain, the saga, or the PaymentIntent model — fitness-function check, same pattern as the no-cross-module-DB rule.
- The simulator's contract tests are promoted to a **port conformance suite**: any adapter (Razorpay now, AuroPay later) must pass the identical suite — settle, decline, ambiguous outcome, late settlement, idempotent refund, refund failure. Passing the suite is the definition of "swap done."
- Demo optics note: running the demo on Razorpay rails is fine — real money movement is the point of Scene 4. The roadmap close (Scene 8) may state that the platform's payment rails will include AuroPay, Aurionpro's own RBI-authorised aggregator.

**Criteria (demo-driven, per DEMO_SCRIPT.md §6):**

| Criterion | Razorpay test mode |
| --- | --- |
| Sandbox UPI success/decline | `success@razorpay` / `failure@razorpay` VPAs — Scene 6's decline instrument is a typed string |
| Sandbox cards | Test cards + mock bank page with explicit Success/Failure buttons |
| Sandbox refunds | Refund API fully functional in test mode (Scene 7) |
| Webhooks in sandbox | Deliverable + simulatable; signature scheme identical to live |
| Checkout recognition | Hosted Checkout widely recognized by an Indian demo audience |
| Methods | UPI, cards, netbanking all sandbox-exercisable |

**Consequence:** the adapter (`RazorpayProviderAdapter`) is the deliverable; the simulator is retained as a test double for CI tiers that should not depend on Razorpay availability (see §10).

## 2. The Shape Change — Customer in the Loop

LLD v0.1's `Pay()` was a synchronous server-side call into the simulator. A real PSP inserts the customer between initiate and outcome:

```
Saga                    Payments                Razorpay              Customer (IBE)
 |--- Pay step -------->|                          |                       |
 |                      |-- create order --------->|                       |
 |                      |<- rzp_order_id ----------|                       |
 |   (AWAITING_CUSTOMER)|-- checkout session ------------------------------>|
 |                      |                          |<-- UPI/OTP auth ------|
 |                      |<======= webhook payment.captured (signed) =======|
 |                      |   [client callback = untrusted hint only]        |
 |<-- settled ----------|  (or payment.failed → declined; or TTL expiry)   |
```

Three consequences, each designed below:
1. **PaymentIntent gains an explicit waiting state** with a bounded clock (§3).
2. **The settle signal is asynchronous and webhook-borne**; the client callback is a hint, never a commit (§5).
3. **Money can arrive after we have given up** — Razorpay documents that an apparently failed/abandoned payment can transition to authorized later. The late-money rule (§6) handles this.

## 3. State Machine Delta

One new live state; no removals. Two-phase states (AUTHORIZED/CAPTURED/VOIDED) remain carried-but-unexercised — see §13 (capture-mode decision).

| Transition | Trigger | Notes |
| --- | --- | --- |
| INITIATED → AWAITING_CUSTOMER | Razorpay order created; checkout session issued to IBE | Starts the customer clock |
| AWAITING_CUSTOMER → SETTLED | Verified `payment.captured` webhook (auto-capture on) | The only commit path |
| AWAITING_CUSTOMER → FAILED | Verified `payment.failed` webhook | Saga compensates the hold |
| AWAITING_CUSTOMER → EXPIRED *(new terminal)* | Customer-clock timeout fires with no settle signal | Saga compensates; intent is dead (§6 governs late money) |
| SETTLED → REFUND_PENDING → REFUNDED / REFUND_FAILED | unchanged from v0.1 | Refunds now hit the real refund API |

**Clock coordination (resolves a v0.1 open decision):** the customer clock is `inventoryHoldTTL − safetyMargin` (margin to ratify; proposal 60s). The payment must never outlive the hold that backs it. Checkout's own timeout is configured ≤ the customer clock. One clock owns the truth: ours.

**Idempotent transitions:** every webhook-driven transition is idempotent on (paymentIntentId, target state); duplicate webhook deliveries (Razorpay retries non-2xx for up to 24h) are no-ops.

## 4. PaymentProviderPort → Razorpay Mapping

The port's contract is unchanged; its semantics shift from "call returns outcome" to "call opens a window; outcome arrives as a signal."

| Port operation | Razorpay mapping |
| --- | --- |
| `initiate(intent)` *(was the front half of Pay)* | `POST /v1/orders` — amount (paise, integer), currency INR, `receipt = paymentIntentId`. Returns `rzp_order_id`, stored on the intent. Checkout session params returned to the BFF. |
| settle signal *(was the back half of Pay)* | `payment.captured` webhook (§5). Auto-capture enabled for this tranche (§13). |
| `getPaymentStatus(intent)` | `GET /v1/payments/{id}` or fetch payments-for-order — **live poll of the PSP, always**. Resolves the v0.1 open decision: in the ambiguous case, local last-known state is precisely what cannot be trusted. |
| `refund(intent, key, amount)` | `POST /v1/payments/{id}/refund` with our deterministic refund key carried in `receipt`/`notes`. Before issuing: fetch existing refunds for the payment and match on the carried key — replay returns the existing refund. Razorpay-native idempotency support: verify-in-build; the fetch-and-match guard stands regardless. |
| `tokenize` | Delegated entirely to Checkout; the platform sees method metadata, never a PAN. PCI posture unchanged from v0.1 §7. |

**Amount discipline:** Razorpay amounts are integer minor units (paise). The adapter owns the decimal↔paise conversion at the boundary; the domain remains exact-decimal INR. A round-trip property test is mandatory.

## 5. Webhook Endpoint Specification

`POST /v1/psp/razorpay/webhook` — internet-facing via the gateway, the platform's only inbound PSP surface.

1. **Verify first.** HMAC-SHA256 of the **raw request body** with the webhook secret, compared constant-time against `X-Razorpay-Signature`. Failure → 400, log, count. Raw-body discipline matters: verify before any JSON re-serialization (a known class of integration bug).
2. **Ack fast, process async.** Persist the event (dedup on Razorpay event id) and return 2xx; processing happens off the request thread. Non-2xx triggers Razorpay's exponential retries — useful as transport-level at-least-once, which our idempotent transitions absorb.
3. **Subscribed events:** `payment.captured`, `payment.failed`, `refund.processed`, `refund.failed` (exact refund-event set: verify-in-build). `payment.authorized` subscribed observe-only while auto-capture is on — it is the early-warning signal for the late-money case.
4. **Correlate by `receipt`** (= paymentIntentId) on the Razorpay order, never by amount or timing.
5. **Unknown/unmatched events:** logged, metered, never 5xx'd (we don't want retries of events we will never handle).

**Secret handling:** webhook secret per mode (test/live) per tenant config; key rotation honours Razorpay's old-secret-validates-old-retries rule.

## 6. The Late-Money Rule

The dangerous sequence: customer's window dies mid-UPI → no signal → customer clock expires → intent EXPIRED, saga compensates, hold released, seat resold → **then** `payment.captured` arrives for the dead intent.

**Rule: a terminal intent is never resurrected, and late money is always returned.**
- A settle signal arriving on an EXPIRED (or FAILED) intent does **not** transition the intent and does **not** touch the order. It triggers an immediate idempotent refund through the standard path (deterministic key derived from `paymentIntentId + LATE_SETTLEMENT`), metered and alerted (`late_settlement_total`).
- This reuses the proven refund machinery rather than inventing a new flow, and it is the reason auto-capture is acceptable for this tranche: the worst case is money briefly held and automatically returned — never a charge without a booking that stands.
- The inverse (booking without charge) remains impossible: SETTLED via verified webhook is the only path to a confirmed order.

## 7. Refund Drainer (resolves v0.1 open decision)

**A scheduled job, not an event consumer**, for this tranche: every N minutes (config; proposal 5m), select intents in REFUND_FAILED, re-attempt refund with the **original deterministic key**, bounded attempts per cycle, exponential per-intent backoff, alert past an age SLO (proposal: REFUND_FAILED older than 1h pages). Rationale: a clock-driven sweep is the simplest correct thing and is independently testable; an event-driven drainer adds nothing while volumes are demo-scale. Revisit at production load.

## 8. Order Saga Delta (the only cross-module change)

The pay step becomes **bounded-asynchronous**:
- Saga invokes the pay step → Payments returns `AWAITING_CUSTOMER` + checkout session → saga parks on the intent's outcome with its timeout aligned to the customer clock.
- Resume on: settled (proceed to fulfil) · failed (compensate) · expired (compensate).
- The ambiguous arm survives intact: if the saga's own timer fires and the intent is still AWAITING_CUSTOMER, Payments resolves via live `getPaymentStatus` before declaring EXPIRED — the v0.1 ambiguous-outcome property, now exercised against a PSP that can genuinely be mid-flight.
- No new compensation logic: expiry compensates exactly as decline does.

## 9. IBE / BFF Delta

- BFF receives the checkout session params and renders Razorpay Checkout (hosted widget; no PAN ever transits our origin).
- The client success callback (`payment_id`, `order_id`, `signature`) is **a UX hint**: the BFF may verify the callback signature (HMAC of `order_id|payment_id` with key secret) to show an optimistic "confirming…" state, but order confirmation renders only on the intent reaching SETTLED. Slow webhook → the confirmation page polls order state; it never self-declares success.
- Checkout abandonment needs no IBE logic: the customer clock and saga expiry own it.

## 10. Testing Strategy (delta)

| Tier | PSP | Proves |
| --- | --- | --- |
| Unit/contract | Simulator (retained) | Port semantics, state machine, drainer, late-money rule — deterministic, no network |
| CI smoke (demo script) | Razorpay sandbox | Scenes 4/6/7: `success@razorpay` settle; `failure@razorpay` decline + compensation; refund through real API |
| Manual/rehearsal | Razorpay sandbox | Full DEMO_SCRIPT.md run incl. webhook latency feel |

Mandatory new cases: signature-verification negative tests (tampered body, wrong secret, replayed event id) · late-settlement refund path · customer-clock vs hold-TTL ordering under jitter · paise round-trip property test · webhook 2xx-fast SLA under processing backlog.

## 11. Configuration & Secrets

Per tenant, per mode: Razorpay key id/secret, webhook secret, customer-clock margin, drainer cadence and SLOs. All config-over-code; secrets via the platform secret store; test/live keys never co-resident in one deployment environment.

## 12. Resolved Open Decisions (LLD v0.1 §13)

1. **GetPaymentStatus:** live PSP poll, always (§4).
2. **Refund drainer:** scheduled job (§7).
3. **Phase method scope:** UPI + cards + netbanking, real in sandbox; simulator retained for unit tiers (§1, §10).
4. **Hold-TTL relationship:** customer clock = hold TTL − margin; checkout timeout ≤ customer clock (§3).

## 13. New Open Decisions

1. **Capture mode beyond the demo tranche.** Auto-capture is chosen *for this tranche* (§6 makes it safe). Production posture may prefer manual capture-on-live-check — i.e. genuine two-phase, which the state enum already carries. This is v0.1's "when does two-phase land" question, now with a concrete forcing function. Decide before pilot traffic, not before the demo.
2. **Customer-clock margin and checkout timeout values** — ratify with hold-TTL owner (Inventory) once measured against real sandbox latencies.
3. **AuroPay migration timing.** Triggered when the questionnaire's Gating answers (esp. H2 late-completion signalling, C2 deterministic decline, D2/D3 webhook contract, F2 refund duplicate guard) return positive. Migration = write `AuroPayProviderAdapter`, pass the port conformance suite, re-run the demo-script CI smoke against AuroPay UAT. Target: before pilot traffic, so the production posture launches on house rails.

## 14. Out of Scope (unchanged deferrals)

Fraud scoring · continuous reconciliation engine (hooks live, engine deferred — though against a real PSP a *manual* reconciliation check enters the demo-env runbook) · settlement-with-orders / BSP-ARC (P3) · multi-currency · in-house vault · dunning.

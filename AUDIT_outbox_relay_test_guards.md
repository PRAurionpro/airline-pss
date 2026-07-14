# Audit — scheduled outbox-relay / sweeper test guards

**Session:** 21 · **Date:** 2026-07-02
**Trigger:** `BUG_distribution_outbox_relay_ci.md` — the distribution outbox relay's
`@Scheduled` tick committing against a torn-down Testcontainers Postgres. That bug is
**already fixed on master** (PR #56, merge `2231b8b`, 1 Jul). This audit answers the
open follow-up in that bug's Acceptance note: *"same latent risk exists for any service
whose outbox relay defaults to a short interval and is booted in a cross-service suite —
worth a quick audit."*

## The race, precisely

A `@Scheduled(fixedDelay=…)` method that opens a JPA transaction against Postgres is a
teardown hazard **only when a Spring context whose scheduler is still running outlives the
Postgres it commits to.** Two facts about this harness bound that risk:

1. **`AbstractPostgresIT` uses a singleton container that is started once and *never
   stopped*** — Ryuk reaps it at JVM exit (see the class doc). So within any single Gradle
   test task (one JVM), the container is alive for every tick, start to finish. A scheduled
   commit can only ever hit a *dead* backend at JVM shutdown, after all tests in that JVM
   have already completed — which does not fail a test.
2. **`fixedDelay` still fires one tick at context startup** (initialDelay defaults to 0).
   Pinning the interval to 1 h stops the *repeated* ticks but not that first one.

The distribution flake was the confluence of (2) plus a **cross-test global assertion**
(`RegisterSellerIT` asserted `findTop100ByPublishedFalseOrderByCreatedAtAsc().isEmpty()`
— the very query the relay runs — over global state another tick could mutate). PR #56
resolved it two ways: `@MockBean` the relay in `DistributionPostgresIT` (so the bean is
never created → no startup tick) **and** drop the global outbox assertion in
`RegisterSellerIT` (commit `53f62e7`).

## Guard mechanisms in use

| Mechanism | How it protects | Where |
|---|---|---|
| **A. `@ConditionalOnProperty(…, matchIfMissing=true)` + `…-enabled=false` in IT base** | Bean is **not created** → no `@Scheduled` registered at all (strongest) | all core `OutboxPublisher`s, `HoldExpirySweeper` |
| **B. Internal `-enabled` flag, early-return** | Bean exists, scheduled method no-ops | `RefundFailedDrainer` |
| **C. Interval pinned to `3600000` in IT base** | Only the harmless startup tick can fire | `Dcs`/`Corporate`/`Distribution` relays |
| **D. `@MockBean` the relay in IT base** | Real bean never instantiated → no tick, and no cross-test outbox reads | `DistributionOutboxRelay` (belt-and-suspenders) |
| **E. Implicit — singleton container never stops** | Every tick hits a live backend for the whole JVM | see "implicit-only" rows below |

## Audit table

| Service | Scheduled task | Default interval | Own-IT guard | Cross-suite exposure | Verdict |
|---|---|---|---|---|---|
| order | `OutboxPublisher` | 2000 ms | **A** — `publishing-enabled=false` (`OrderPostgresIT`) | `tests:integration` boots order **in-process**: all ITs set `publishing-enabled=false` **except `C1JoinSmokeIT`** which deliberately runs it `true`@500 ms for the end-to-end join smoke | ✅ guarded |
| order | `PaymentTimeoutSweeper` | 30000 ms | **C** — pinned `3600000` (`OrderPostgresIT`, session 21b) | in-process in every `tests:integration` IT (no disable flag exists) | ✅ guarded |
| offer | `OutboxPublisher` | 2000 ms | **A** — `publishing-enabled=false` (`OfferPostgresIT`) | `tests:offer-integration` boots offer in-process with `publishing-enabled=false` (`OfferCrossServiceIT`) | ✅ guarded |
| inventory | `OutboxPublisher` | 2000 ms | **A** — `publishing-enabled=false` (`InventoryPostgresIT`) | runs as a **bootJar container** in cross-suites (own DB, prod defaults; reaped with the container) | ✅ guarded |
| inventory | `HoldExpirySweeper` | 30000 ms | **A** — `hold.expiry-sweep-enabled=false` | container as above | ✅ guarded |
| payments | `OutboxPublisher` | 2000 ms | **A** — `publishing-enabled=false` (`PaymentsPostgresIT`) | container in `OrderPaymentsIT` (prod defaults, own DB) | ✅ guarded |
| payments | `RefundFailedDrainer` | 300000 ms | **B** — `refund-drainer.scheduled-enabled=false` | container as above | ✅ guarded |
| payments | `CustomerClockSweeper` | 30000 ms | **C** — pinned `3600000` (`PaymentsPostgresIT`, session 21b) | container as above | ✅ guarded |
| pricing | `OutboxPublisher` | 2000 ms | **A** — `publishing-enabled=false` (`PricingPostgresIT`) | container in offer/order cross-suites | ✅ guarded |
| dcs | `DcsOutboxRelay` | 500 ms | **C** — pinned `3600000` (`DcsPostgresIT`) | not booted by any cross-suite | ✅ guarded |
| distribution | `DistributionOutboxRelay` | 500 ms | **C + D** — pinned `3600000` **and** `@MockBean` (`DistributionPostgresIT`) | not booted by any cross-suite | ✅ guarded — **fixed PR #56** |
| corporate | `CorporateOutboxRelay` | 500 ms | **C** — pinned `3600000` (`CorporatePostgresIT`) | not booted by any cross-suite | ✅ guarded |
| dashboard | `SeenEventPruner` | **3600000 ms** | default is already 1 h; read-side, no outbox | runs as a bootJar container in `C1JoinSmokeIT` (own scope) | ✅ safe by default |
| loyalty | *(no scheduled relay yet)* | — | `LoyaltyPostgresIT` pins `pss.loyalty.outbox.poll-interval-ms` defensively, **but there is no scheduled consumer** of the loyalty outbox | — | ✅ N/A |
| *(platform)* | `service-template OutboxPublisher` | 2000 ms | template scaffold — **not a deployed service**, not in the platform build's test matrix | — | ✅ N/A |

## Note on the two former implicit-only cases (pins applied, session 21b)

`order:PaymentTimeoutSweeper` and `payments:CustomerClockSweeper` are plain `@Component`s
with **no** `-enabled` disable flag. Before session 21b their only protection was
mechanism **E** — the singleton container never stops mid-JVM — plus a 30 s cadence that
rarely fires the repeated tick inside a class. That is why they had run without a flake
across all of Phase 1 and Phase 2. But the protection was *implicit* (an undocumented
invariant of `AbstractPostgresIT`), which is precisely the fragile shape the distribution
flake exposed.

**Session 21b applied the explicit pin** (mechanism **C**), test-config only, mirroring the
dcs/corporate/distribution/loyalty guard exactly:

- `OrderPostgresIT` → `registry.add("pss.order.payment-timeout.sweep-interval-ms") { "3600000" }`
- `PaymentsPostgresIT` → `registry.add("pss.payments.customer-clock.sweep-interval-ms") { "3600000" }`

A cleaner disable flag (mechanism **A/B**) would have needed an `@ConditionalOnProperty` /
`-enabled` early-return in `src/main` — out of scope (test-config + docs only) — so the pin,
which requires no production change, was chosen. `fixedDelay` still fires one harmless
startup tick against the live singleton container; the pin removes every subsequent tick, so
no tick can ever race JVM teardown.

## Conclusion

**No teardown-race gap remains.** The distribution relay was the single real case and is
fixed on master (PR #56). Every DB-touching scheduled task now carries an **explicit** guard
(A–D): the two sweepers that were implicit-only (E) were pinned in session 21b. Mechanism
**E** remains as a backstop for all of them, but no scheduled task now depends on it alone.

# RULE — a headline gate must test against the real counterparty

**Standing rule (adopted session 23).** For any tranche whose headline capability is a **cross-service
seam** (service A calls service B), the tranche's headline gate MUST include at least one integration
test that drives the seam against the **real counterparty service** (booted via the shared
Testcontainers harness, same pattern as `tests:integration` / `tests:offer-integration`).

**WireMock / stub-only gates do not close a cross-service seam.** A stub proves A's client speaks the
shape A *believes* B serves — it cannot prove B actually serves it. A green stub-only gate is a false
signal for the one property that matters: does the call work end-to-end.

## Motivating case (why this rule exists)

Revenue Management (PR #64) shipped with a "headline gate" that drove RM's AU feed to inventory-service
**through RM's own WireMock**. It was green. But inventory-service serves no
`/v1/inventory/flight-classes/{id}/adjust-au` endpoint — so in a real deployment the feed 404s. The
defect survived merge, the contract backfill (PR #67, which could only document what the code claimed),
and was only caught when session 23 read *both* sides. Worse, closing it turned out to need a
cross-service identity design decision (see `FLAG_rm_inventory_au_seam.md`) — a gap a real-counterparty
gate would have surfaced at build time, not months later.

## What the rule requires

1. **Real-counterparty IT in the headline gate.** Boot B for real; drive A → B → assert B's state
   actually changed and A handled the real response. Stubs may remain for unit-level breadth.
2. **The counterparty endpoint must exist before the caller's gate is green.** If B doesn't serve it yet,
   that is a blocking dependency to raise — not something to paper over with a stub.
3. **When booting B in a new test context, apply the outbox-relay guard pattern** — see
   `AUDIT_outbox_relay_test_guards.md` (pin/disable B's scheduled relays so a tick can't race teardown).

## Scope

Applies to cross-service *write* seams especially (they mutate the counterparty), and to reads whose
correctness depends on B's real contract. A seam that is genuinely fire-and-forget telemetry
(at-most-once, best-effort) may document that posture instead, but must say so explicitly.

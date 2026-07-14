# PSS Platform — Open Items & Deferred Register

The repo's known-items register. Two kinds of entry:

- **Intentional** — deliberate design or test scaffolding that must **not** be "fixed". Touching it
  silently breaks coverage or behaviour.
- **Deferred** — designed in the module LLDs but not built in Phase 1; scheduled for a later phase.

Keep this scannable: item · status · one-line why · where it lives. Update it whenever a gap moves
from "lives only in a test comment / a design session" to "tracked".

---

## Intentional — do NOT change

### Dev-seed market asymmetry (Inventory ↔ Pricing)

The Phase-1 dev-seeds are **deliberately asymmetric** on the BLR→BOM / cabin-Y market, because that
asymmetry is exactly what makes the Offer best-effort assembly tests real (a uniform seed would make
them pass vacuously). Three RBDs, three different outcomes:

| RBD | Inventory dev-seed | Pricing dev-seed | Net result | What it exercises |
|-----|--------------------|------------------|------------|-------------------|
| **B** | seeded (alloc 4, OPEN) | filed fare `BL7APIN` (₹5000) | available **and** priceable **and** bookable | the **happy path**, end-to-end through pay |
| **H** | seeded (alloc 6, OPEN) | **no fare** | available but **un-priceable** → Pricing returns 422 | the **partial-failure DROP** path (candidate excluded, shop still succeeds) |
| **M** | **not seeded** | filed fare `MFLEXIN` (₹8000) | priceable but **never offered** (no availability) | proves Offer only offers what Inventory actually has |

This three-way split is asserted by:

- `OfferCrossServiceIT` → *"partial failure — the un-priceable H class is dropped by real Pricing, B survives"*
- `AssemblyServiceTest` → *"partial failure — an un-priceable candidate is dropped, the priceable one survives"*

> ⚠️ **Do not "align" the seeds.** Giving H a fare, or seeding M in Inventory, will make the offer
> assemble 2 offers where the tests expect 1 — silently deleting the only end-to-end coverage of the
> best-effort drop path. The asymmetry is the test fixture, not a bug.

Files: `services/inventory-service/src/main/resources/db/seed/V900__seed_dev_inventory.sql`,
`services/pricing-service/src/main/resources/db/seed/V900__seed_dev_pricing.sql`.

---

## Deferred — designed in the LLDs, not built in Phase 1

| Item | Module | Status | Why deferred (Phase 1 ships…) | LLD ref |
|------|--------|--------|-------------------------------|---------|
| **Two-phase auth/capture** | Payments | Phase 2+ | Single-settle only (UPI/net-banking collapse auth+capture to SETTLED). `AUTHORIZED` exists in the enum but no Phase-1 transition reaches it; cards' separate capture is designed-not-wired. | Payments §5.3, §6 |
| **DynamicPricingStrategy** | Pricing | Phase 4 | `FiledFareStrategy` is the real Phase-1 strategy; `DynamicPricingStrategy` ships as a **deterministic stub behind the same port** (D9) so the swap is a config change later — no pipeline change. | Pricing §4.1, §14 |
| **Reconciliation engine + BSP/ARC settlement** | Payments | Phase 3 | Every money-moving op records a `pspRef`/`pspRefundRef` (the reconciliation hook). The scheduled reconciliation job and the settlement / order-accounting (BSP/ARC) feed are Phase 3. | Payments §7, §12 |
| **Offer `ACCEPTED` write-back (Offer ← Order)** | Offer / Order | Phase 2 | The Offer API has no mark-accepted operation; `ACCEPTED` is modelled in the enum but offers expire by TTL. Order does **not** write back to Offer at create time. | Offer §5, §6 |
| **Refund-failed background drainer** | Payments | Phase 2 | A refund that exhausts PSP retries lands `FAILED`, the intent stays `SETTLED`, the `payments.refund.failure` metric increments and a WARN is logged — surfaced, never swallowed. An auto-retry drainer / ops workflow is later. | Payments §8 |
| **Personalisation / ranking** | Offer | Phase 2+ | `PersonalisationPort` ships with a deterministic **pass-through**; real ranking/filtering slots in behind the port with no change to assembly or the API. | Offer §4.3 |

Each module LLD also carries its own *Phase-1 Scope vs Full Design* table (e.g. Pricing §14,
Payments §16, Offer §14) — this register collects only the **cross-module** items worth seeing in
one place, plus the intentional scaffolding above.

---

## Phase-1 status

All five Phase-1 modules — Inventory, Pricing, Offer, Order, Payments — are built, individually
tagged (`v0.1.0-<module>`), and wired into one **shop → offer → order → pay → confirm** path proven
end-to-end on CI against real services (`v0.1.0-phase1-complete`). The items above are the known,
intended boundaries of that milestone — not regressions.

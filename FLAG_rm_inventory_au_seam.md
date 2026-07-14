# FLAG — RM → Inventory AU seam needs a cross-service identity decision

**Raised:** session 23 (2026-07-03), investigating the PR #67 finding that rm-service posts to a
non-existent inventory endpoint. **Status:** stop-and-flag — no `src/main` change made; the RM
WireMock stays in place. This is a design decision for the Phase 2 closure / Phase 3 kickoff review.

## What was investigated

RM's `computeAndApply` calls `InventoryServiceClient.adjustAu(tenantId, configId, auValue, reason)`,
which posts `POST /v1/inventory/flight-classes/{configId}/adjust-au` with `{tenantId, auValue, reason}`.
inventory-service serves no such endpoint (only `/v1/availability`, `/v1/holds`, `/v1/seats`), so the
call 404s against the real service and only ever passed against RM's own WireMock.

## The AU concept is NOT the problem — it maps cleanly

`BookingClassBucket.allocation` is documented as *"Seats authorised for this class (the AU). Phase 1
sets it from setup; **RM sets it later**."* Inventory already models the AU and anticipated RM setting
it. Adjusting `allocation` is additive and does **not** touch the hold/claim lifecycle:
`available() = allocation − sold − held` simply recomputes; existing holds/sold are preserved. Building
a controller + aggregate method to set `allocation` under the existing pessimistic lock would be small.

## The blocker: RM and Inventory share no flight-class identity

| | RM | Inventory |
|---|---|---|
| Flight-class id | `flightClassConfigId` — a ULID **RM mints itself** (`UlidCreator`) over (tenant, flightNumber, departureDate, cabin, rbd) | `flightInventoryId` — an **independently minted** ULID; buckets keyed `(flightInventoryId, rbd)` |
| Write-path key | — | `lockForUpdate(flightInventoryId, tenantId)` |

The two ULIDs are disjoint by construction. The only shared business coordinates are
`(tenant, flightNumber, departureDate, rbd)`, and **inventory's schema declines to make that a key**:
`flight_inventory.flight_number` is **nullable** and there is **no unique index** on
`(tenant, flightNumber, departureDate)` (only a non-unique `idx_flight_inv_search`).

So making RM's feed hit the real service requires a **design decision**, not plumbing:

- **Option A — RM carries inventory's `flightInventoryId`.** Requires RM to learn/store it (a linkage at
  register time, or an inventory lookup API). New cross-service read + RM state. Design.
- **Option B — Inventory resolves by natural key `(tenant, flightNumber, departureDate, rbd)`.** Requires
  inventory to *guarantee* that key (make `flight_number` non-null + a unique constraint + migration +
  backfill). Changes an authoritative-store invariant. Design.
- **Option C — A shared flight-class identity convention** established across RM/Inventory/Schedule setup.
  Broadest. Design.

Each is a real architectural choice about who owns cross-service flight-class identity — beyond this
fix session's "small and well-bounded, do not improvise inventory semantics" mandate. **Deferred to the
kickoff review.** Until then the RM→Inventory feed is documented (README_RM_CONTRACTS.md, RM_DELTA
§Findings) as not wired to the real service; RM's WireMock provides unit-level coverage only.

## Recommendation for the review

Option A (RM stores the inventory flight id, captured when a flight-class is set up) is likely the
smallest honest design — it keeps inventory's identity authoritative and adds no invariant to the
availability store — but confirm against the Schedule/Fleet setup flow that seeds inventory. See also
`RULE_headline_gates_real_counterparty.md`, whose motivating case is this exact defect.

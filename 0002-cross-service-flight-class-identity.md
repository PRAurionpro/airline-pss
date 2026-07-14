# ADR-0002: Cross-service flight-class identity (RM ↔ Inventory)

- **Status:** Proposed
- **Date:** 2026-07-03
- **Deciders:** <to be filled at the closure/kickoff review>
- **Decision ref:** residual (Phase 2 closure) — originates from `docs/FLAG_rm_inventory_au_seam.md`

> This ADR is a **decision paper**: it lays out the options with honest trade-offs and takes
> **no position**. The `## Decision` block is intentionally empty for the deciders. It follows
> the ADR convention established in [ADR-0001](0001-record-architecture-decisions.md) (the first
> ADR authored under it after the framing set).

## Context

Revenue Management (rm-service) computes an Authorised-Units (AU) value per flight-class and
tries to push it to Inventory. `RM.computeAndApply` → `InventoryServiceClient.adjustAu(...)`
posts `POST /v1/inventory/flight-classes/{configId}/adjust-au`. **inventory-service serves no
such endpoint** (only `/v1/availability`, `/v1/holds`, `/v1/seats`), so the call 404s against
the real service and has only ever passed against RM's own WireMock stub
(`FLAG_rm_inventory_au_seam.md`; `RM_DELTA_0_6_RETRO.md` §Findings).

**The AU concept itself is not the problem — it maps cleanly.** `BookingClassBucket.allocation`
is documented as *"Seats authorised for this class (the AU). Phase 1 sets it from setup; RM sets
it later."* Adjusting `allocation` is additive and does not touch the hold/claim lifecycle —
`available() = allocation − sold − held` simply recomputes, preserving existing holds/sold. A
controller + aggregate method to set `allocation` under the existing pessimistic lock would be
small.

**The blocker is identity, not plumbing.** RM and Inventory share no flight-class identifier:

| | RM | Inventory |
|---|---|---|
| Flight-class id | `flightClassConfigId` — a ULID **RM mints itself** over (tenant, flightNumber, departureDate, cabin, rbd) | `flightInventoryId` — an **independently minted** ULID; buckets keyed `(flightInventoryId, rbd)` |
| Write-path key | — | `lockForUpdate(flightInventoryId, tenantId)` |

The two ULIDs are disjoint by construction. The only shared business coordinates are
`(tenant, flightNumber, departureDate, rbd)`, and **inventory's schema declines to make that a
key**: `flight_inventory.flight_number` is **nullable** with **no unique index** on
`(tenant, flightNumber, departureDate)` (only the non-unique `idx_flight_inv_search`). So closing
the seam requires deciding **who owns cross-service flight-class identity**.

New finding surfaced while drafting (not in the inputs pack): the FLAG doc records a preliminary
author lean toward Option A. It is reproduced below as history only — **this ADR does not adopt
it**; the decision is the deciders'.

## Options

Each option's "forecloses" line is what it makes harder or rules out later.

### Option A — RM carries Inventory's `flightInventoryId`
RM learns and stores Inventory's id (a linkage captured when a flight-class is set up, or via a
new inventory lookup call at compute time), then calls an adjust-AU endpoint keyed by
`flightInventoryId`.
- **Migration impact:** RM gains a stored `flightInventoryId` per flight-class config (new column
  + backfill for existing RM rows from the setup/seed flow). Inventory rows unchanged.
- **Event/contract changes:** a new inventory `adjust-AU` write endpoint keyed by
  `flightInventoryId`; a linkage source (either a register-time field on the setup event RM
  already consumes, or a new inventory resolve/read call — see Option A′ below).
- **Keeps authoritative:** Inventory's identity stays the single source of truth; the availability
  store gains no new invariant.
- **Forecloses:** nothing structural; but it makes RM depend on knowing inventory ids, so any
  future producer of AU (e.g. a manual override tool) must also resolve the same id.

### Option A′ — Inventory exposes a natural-key **resolve** endpoint that RM calls
A read-only variant of A: inventory adds `GET/POST …/resolve?carrier&flight&date&class →
flightInventoryId`; RM resolves on demand and then adjusts by id. (Called out separately because
the brief names it explicitly.)
- **Migration impact:** none to stored rows; RM holds no new state (resolves per call).
- **Event/contract changes:** a new inventory resolve endpoint **plus** the adjust-by-id endpoint;
  the resolve endpoint still needs the natural key to be unique enough to return one row — which
  today it is **not guaranteed to be** (nullable `flight_number`, no unique index), so this option
  quietly depends on the same guarantee Option B makes explicit.
- **Forecloses:** low commitment, but two network hops per adjust and a hidden dependency on
  natural-key uniqueness that is not enforced by a constraint.

### Option B — Inventory resolves by the natural key `(tenant, flightNumber, departureDate, rbd)`
Inventory accepts the AU adjust keyed by business coordinates and guarantees that key.
- **Migration impact:** make `flight_number` **non-null**, add a **unique constraint** on
  `(tenant, flightNumber, departureDate)` (or incl. carrier), **migrate + backfill** existing
  `flight_inventory` rows, and reject rows that can't satisfy it. Changes an authoritative-store
  invariant.
- **Event/contract changes:** adjust-AU endpoint keyed by natural coordinates; no RM state change.
- **Forecloses:** commits Inventory to natural-key uniqueness forever (e.g. code-shares / operated-by
  vs marketed-flight scenarios must fit that key), which is a real constraint for interline/GDS
  futures.

### Option C — A shared canonical flight-class identifier minted by one owner
Establish one identifier for a flight-class across Schedule/Fleet setup → Inventory → RM (and any
future consumer), minted by a single owner at setup time and carried by all.
- **Migration impact:** broadest — a new identifier threaded through the setup/seed flow, Inventory,
  and RM, with backfill in each. Requires naming the owner (Schedule/Fleet setup is the natural
  mint point since it seeds Inventory).
- **Event/contract changes:** the identifier added to the setup event and to inventory + RM
  contracts; possibly to `dcs-events`/`distribution` later.
- **Forecloses:** least — it's the most future-proof, at the highest up-front cost; picking A/B now
  may make a later move to C a second migration.

### Option D — Do not wire the feed (accept RM as compute-only for now)
Keep RM's AU output un-applied (WireMock/unit coverage only), revisit when a Phase-3 tranche needs it.
- **Migration impact:** none.
- **Forecloses:** nothing, but leaves a headline capability (RM actually steering inventory)
  undelivered and the `RULE_headline_gates_real_counterparty.md` gap open.

## Interactions

- **This decision probably outlives RM.** A cross-service flight-class identity is consumed by any
  service that must name a specific flight-class: **DCS deepening**, **IROPS**, and **Weight &
  Balance** (Phase-3 candidates, `PHASE2_CLOSURE_INPUTS.md` §4) all operate per departure/flight and
  would reuse whatever identity is chosen here. Choosing a narrow RM-only linkage (A/A′) may need
  redoing when those tranches land; C front-loads that cost.
- **Constrains Paper 3 (deviation remediation):** if Option B/C touches inventory's write contract,
  it is the natural moment to also settle inventory's error-format and header posture.
- **Enforcement hook:** `RULE_headline_gates_real_counterparty.md` — whichever option is chosen, its
  headline gate must include a real RM→Inventory integration test, not WireMock only.
- **Carried item link:** appears in the carried-items triage (Paper 5) as "RM → Inventory feed
  half-built"; the triage call there should match this ADR's outcome.

## Consequences

To be recorded once decided (which invariant/contract changes land, and which fitness function or
integration test enforces the seam).

## Decision

<!-- EMPTY — to be filled by the deciders at the closure/kickoff review.
     State the chosen option, the owner of flight-class identity, the migration plan, and the
     enforcing test/fitness function. Set Status: Accepted and fill Deciders/Date on acceptance. -->

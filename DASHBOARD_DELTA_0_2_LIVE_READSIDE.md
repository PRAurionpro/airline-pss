# Dashboard Delta 0.2 — Live Carrier Dashboard (First Read-Side Consumer)

**Status:** Draft for ratification · Companion to: DEMO_SCRIPT.md (Scenes 0, 4, 6, 7) · ANCILLARIES_DELTA_0.2_SEAT_BAG.md · BREADTH_DELTA_0.2_MULTIPAX_RETURN.md · BUILD_PLAN_PHASE_2_0_TRANCHE_1.md
**Base documents:** PSS_System_Design_Document (event backbone, §8) · Order LLD §6 · Payments LLD §8 · Inventory LLD §7 · Offer LLD §8
**Strategic note:** this is the seed of the Phase-2 Reporting & BI module — the first consumer proving the event backbone was worth building. Scene 0's line ("this dashboard is just one consumer of them") must be literally true.

---

## 1. Scope and Non-Goals

One single-page carrier dashboard, fed exclusively by domain events, showing: **today's funnel** (searches → offers → orders → paid) · **bookings count** · **revenue (net) and refunds** · **declines** · **ancillary revenue and attach rate** · **a live availability tile** for the demo flights (the Scene 6 beat) · **a live event ticker**. Updates reach the screen within the demo-credible latency target (≤ 2s, ratified here as the budget to engineer against).

**Non-goals (deferred without guilt):** historical ranges and date pickers · drill-down to individual orders · multi-tenant admin views · alerting · scheduled reports · the warehouse/lake pipeline · revenue accounting figures (this dashboard shows *commercial* totals, not accounting truth) · mobile layout.

## 2. The One Architectural Rule

**The dashboard service consumes events and nothing else.** No synchronous calls into Offer, Order, Inventory or Payments; no reads of their stores (the standing no-cross-module-DB fitness rule applies and gets a dashboard-specific check: no module API client and no foreign datasource may appear in this service). If a number can't be derived from published events, the answer is to publish a better event — which §3 does once — not to add a query.

This rule is what makes Scene 0's claim honest and what makes the service the legitimate seed of Reporting & BI: anything it can do, any future consumer can do.

## 3. Closing the One Event Gap — ShopPerformed

The funnel's top stage has no event today: Offer's `OfferCreated` is optional in the LLD (per-offer, never built) and Offer — stateless, storeless — has no outbox to publish from.

**New event: `ShopPerformed`** (platform envelope; no orderId — keyed by `searchId`):

```json
{ "type": "ShopPerformed", "payload": {
    "searchId": "01H...", "origin": "BLR", "destination": "BOM",
    "tripType": "RETURN", "paxCount": 2, "channel": "WEB",
    "offersReturned": 12, "stage": "OUTBOUND|RETURN|COMBINE" } }
```

- Emitted once per Shop call and once per CombineAndPrice (stage-tagged, so two-stage shopping counts as one search journey — the funnel counts `stage=OUTBOUND` as "searches" and sums `offersReturned` as "offers").
- **Telemetry-grade, deliberately.** Offer has no transactional store, so this is a best-effort, fire-and-forget publish — at-most-once, may be lost under failure. That is acceptable *and is documented as a property of the metric*: the funnel's top is directional telemetry; the funnel's bottom (orders, paid, revenue) comes from outbox-grade events and is exact. The dashboard never mixes the two grades into one derived figure.
- `OfferCreated` stays in the Offer LLD as optional/unbuilt; this event supersedes its analytics purpose. Additive contract change; schema added to the event-schema CI set.

Everything else the dashboard needs **already exists**: `OrderCreated`, `OrderConfirmed` (amountPaid, documentRefs), `OrderCancelled` (reason, refundRef) from Order; `PaymentSettled`, `PaymentFailed`, `RefundIssued` from Payments; `InventoryHeld/Confirmed/Released` and `AvailabilityChanged` from Inventory. The breadth and ancillaries deltas already enriched the payloads (pax counts, ANCILLARY items) — §8 of each anticipated exactly this consumer.

## 4. The Dashboard Service

A single small service (`dashboard-service`), the platform's first standalone read-side projection:

```
event backbone ──▶ consumers (idempotent on eventId)
                      │
                      ▼
              projection store (Postgres: counters + dedupe + ticker ring)
                      │
                      ▼
              SSE endpoint ──▶ browser SPA (carrier view)
```

### 4.1 Consumers and projections

- One consumer group; subscriptions: Order events, Payments events, Inventory `AvailabilityChanged`, `ShopPerformed`.
- **Idempotent on `eventId`** (the platform consumer contract): a `seen_events` dedupe table (eventId PK, TTL-pruned) guards every projection update; at-least-once delivery yields exactly-once counts.
- Projections are per-tenant, per-day counters and small aggregates — plain rows, updated transactionally with the dedupe insert:

| Projection | Source events | Drives |
| --- | --- | --- |
| funnel{searches, offers, orders, paid} | ShopPerformed · OrderCreated · OrderConfirmed | Scene 0/4 funnel |
| bookings, revenueGross, refundsTotal, revenueNet | OrderConfirmed · RefundIssued | KPIs; Scene 7 reversal |
| declines | PaymentFailed | Scene 6 tick |
| cancellations | OrderCancelled | Scene 7 |
| ancillaryRevenue, attachRate | OrderConfirmed items[type=ANCILLARY] | Ancillaries delta §8 payoff |
| availability{flightId → seatsRemaining} | AvailabilityChanged (demo flights, config-listed) | Scene 6 live tile |
| ticker (ring buffer, last 50) | all consumed events (type + summary) | The "everything is an event" beat |

- "Today" buckets on the **tenant's display timezone** (IST for the demo carrier, from tenant config) — Open Decision 1 ratifies the general rule.
- Revenue figures derive **only from order-grade events** (`OrderConfirmed.amountPaid`, `RefundIssued.amount`), never from the funnel side; `PaymentSettled` is consumed only for the ticker. One number, one authoritative source event — no double counting when both Payments and Order speak about the same money.

### 4.2 Live push

- **SSE** (server-sent events) from the service to the SPA — one-directional, reconnect-friendly, no WebSocket infrastructure needed. On any projection change, push the updated tenant snapshot (small enough to send whole — no client-side merge logic).
- On connect, the SPA receives the full current snapshot, then deltas. Refresh-proof: the demo can F5 at any moment.

### 4.3 Latency budget (the ≤ 2s, made engineerable)

outbox poll/publish (≤ 800ms — **ratify the outbox polling interval against this budget**; if pollers run slower today, this delta is the forcing function to tune them) + broker delivery (≤ 100ms) + projection commit (≤ 100ms) + SSE push & render (≤ 500ms) ≈ 1.5s, leaving headroom. The CI smoke asserts the end-to-end number (§7).

### 4.4 Tenancy, security, lifecycle

- Tenant from the verified token, exactly as every other API; consumers filter on `tenantId`; SSE streams are tenant-scoped. The demo carrier sees only itself.
- Read-only service: no state-changing endpoints exist.
- **Cold rebuild (C2):** projections start at zero on a fresh environment — truthful, since Scene 0 opens on a quiet dashboard. Rebuild-by-replay from the event log is the principled future (and what Reporting & BI will need) but is deferred: Open Decision 2 records the backbone-retention question it depends on.

## 5. The Screen (Scene Choreography)

A single dark "ops" view, IBE-adjacent branding from tenant config (config-over-code, visibly):

- **Top row — KPIs:** Bookings · Revenue (net) · Refunds · Declines. Numbers animate on change (the Scene 4 "tick" is a UI event, not a poll).
- **Funnel bar:** searches → offers → orders → paid, with today's counts. Quietly labelled so the telemetry/exact distinction is honest if the IT lead asks.
- **Ancillaries tile:** ancillary revenue + attach rate (orders with ≥1 ancillary / orders). The Scene 3 sale visibly changes carrier economics — the retailing pitch closing its own loop.
- **Availability tile:** seats remaining on the two demo flights, live. In Scene 6 the decline fires and the numbers *go back up* — the integrity scene's right-screen payoff (DEMO_SCRIPT §6 "Scene 6 staging" can now choose dashboard, IBE re-search, or both).
- **Event ticker:** scrolling feed of envelope type + one-line summary ("OrderConfirmed · ₹13,148 · 2 pax"). This is the single cheapest proof that the architecture is what the narration claims. Collapsible — Open Decision 3 (rehearsal) decides whether it stays on screen for the prospect.

## 6. Contracts and Fitness Rules (delta summary)

- **New:** `ShopPerformed` event schema (telemetry grade, documented as such) — added to the schema CI set.
- **No other contract changes.** Every other input already exists and is already schema-validated.
- **New fitness checks:** (a) dashboard-service contains no module API clients and no foreign datasource config; (b) every projection handler is covered by a duplicate-delivery test (same event twice → counted once).

## 7. Testing Strategy

1. **Projection unit suite:** every event type → expected counter movement; duplicate eventId → no movement; out-of-order arrival (RefundIssued before OrderConfirmed is impossible per-order ordering, but cross-stream interleaving isn't) → totals correct regardless of interleaving.
2. **Telemetry-grade behaviour:** dropped ShopPerformed events skew only the funnel top; money KPIs provably unaffected.
3. **SSE:** reconnect resumes with a full snapshot; refresh mid-demo loses nothing.
4. **Latency assertion in the CI smoke (extends C1/C2):** after the scripted booking, the dashboard snapshot must show the increments **within 2s** of OrderConfirmed's `occurredAt`; after the decline, `declines` +1 and the availability tile back at the prior count; after the cancel, refunds and net revenue move. This makes Exit Criterion 3 of the demo script a green/red check instead of a stopwatch.

## 8. Demo-Scene Traceability

| Scene | Delivered by |
| --- | --- |
| 0 (carrier view, live and quiet) | §5 layout; §4.4 zero-start on fresh env |
| 4 (the tick: order, revenue, funnel) | §4.1 projections, §4.2 push, §4.3 budget |
| 6 (seats return on screen) | availability tile ← AvailabilityChanged |
| 7 (reversal visible) | refunds/net-revenue ← RefundIssued, OrderCancelled |

## 9. Build Milestones (Workstream E — slots into the build plan)

Independent of Workstreams A/B/D until the smoke; can start immediately after the backbone consumer scaffolding exists (it does — Phase 1):

- **E1 — ShopPerformed:** emit from Offer (shop + combine), schema in CI. Gate: events observed on the backbone with correct stage tags; loss-tolerance documented in the runbook.
- **E2 — dashboard-service projections:** consumers, dedupe, counter tables. Gate: §7.1–7.2 green; duplicate-delivery tests green; fitness checks live in CI.
- **E3 — SSE + SPA:** the §5 screen minus the availability tile. Gate: §7.3 green; manual booking moves the screen.
- **E4 — availability tile + ancillary metrics:** consumes AvailabilityChanged; ANCILLARY item aggregation (after D4 lands, since it needs the enriched payloads). Gate: Scene 6/3 movements visible.
- **C2 amendment:** the one-command demo env includes dashboard-service + its store + the demo-flight config for the availability tile; the CI smoke gains the §7.4 latency and movement assertions. With D and E both landed, **C2's smoke is the full demo script, Scenes 0–7, both screens.**

**Stop-and-flag additions:** any synchronous call from dashboard-service into a module · any module publishing a new event *for* the dashboard beyond ShopPerformed · derived money figures sourced from anything but OrderConfirmed/RefundIssued.

## 10. Open Decisions

1. **Day-bucket timezone rule** — tenant display timezone (proposed; IST for demo) vs UTC buckets with display conversion. Decide once; it's a Reporting & BI precedent.
2. **Projection rebuild-by-replay** — depends on backbone retention policy (currently unratified). Defer to the Reporting & BI design; zero-start is sufficient for the demo.
3. **Ticker visibility in the live demo** — impressive to engineers, possibly noise to commercial leads. Decide in rehearsal (it's a toggle).
4. **Outbox polling interval** — ratify against the §4.3 budget during E3, measured on the demo cluster (and feed the result back to the Phase-1 CPU-ceiling investigation in C2 — same cluster, related symptom).
5. **Gross vs net revenue as the headline KPI** — net proposed (Scene 7 reads better when the headline visibly absorbs the refund). Rehearsal call.

## 11. Out of Scope (deferred without guilt)

Historical/date-range views · drill-down · alerting · exports · the lake/warehouse pipeline · multi-dashboard roles · per-flight P&L · load-factor and RM views · anything write-shaped.

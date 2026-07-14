# Dashboard-service — API contract

Contract: [`contracts/dashboard-api_openapi.yaml`](contracts/dashboard-api_openapi.yaml) (OpenAPI 3.0.3).

## Purpose

dashboard-service is the platform's **operational dashboard read-model**. It is a
read-side CQRS projection: it owns its own `dashboard` Postgres store, consumes the
order / payments / inventory / offer backbones, and exposes a single tenant-scoped
**Server-Sent-Events** stream that pushes the current snapshot on connect and re-pushes
the whole snapshot on every committed projection change. It writes no domain state and
calls no module API (enforced by `check_dashboard_isolation`).

## Endpoints

| Method | Path | Purpose | Media type | Auth |
|---|---|---|---|---|
| GET | `/v1/dashboard/stream` | Subscribe to the live snapshot stream | `text/event-stream` (SSE `snapshot` events) | Bearer token, or `?token=` query fallback |

Each SSE message is a `snapshot` event whose `data:` line is a full `DashboardSnapshot`
JSON document (see the schema). The whole snapshot is re-sent on every change — there are
no deltas or merges.

## Events

- **Publishes: none.** dashboard-service has no outbox and no producer — it emits nothing
  on the wire except the SSE stream. `ProjectionChangedEvent` is an in-process Spring event
  only (drives the AFTER_COMMIT SSE fan-out); it never leaves the process. No events schema
  is authored for this service (there is nothing to publish).
- **Consumes** (inbound Kafka, out of band from this HTTP contract): `pss-order-events`
  (all types), `pss-payments-events` (all types), `pss-inventory-events` (`AvailabilityChanged`),
  `pss-offer-events` (`ShopPerformed`), deduplicated on `eventId`.

## Tenant & security

Tenant comes from the verified bearer token (`tenantId` claim, fallback `tid`). Because a
browser `EventSource` cannot set an `Authorization` header, the stream also accepts the
token as a `?token=` query parameter, resolved by the same claims reader. There is no
tenant path/query parameter beyond that fallback, and no session or cookie.

## Deviations

Documented as-merged; carried items, not normalised away in the spec.

1. **SSE-only, no JSON REST.** The entire public surface is one `text/event-stream` GET —
   there is no request/response JSON endpoint and no write surface at all. OpenAPI models
   the per-message `snapshot` data payload; the wire framing is SSE, not a single JSON body.
2. **JWT-in-URL fallback.** `?token=` carries the JWT as a query parameter for EventSource
   clients — a deliberate divergence from header-only auth (mitigated: claims-only reader,
   signature verified upstream).
3. **Controller instantiates `TenantContextResolver()` directly** to resolve the post-filter
   `?token=` path, rather than relying solely on the filter-bound holder.
4. **No typed error contract.** The only error path — unresolved tenant — throws an unmapped
   exception that falls through to Spring's default **500 problem+json**; there is no
   deliberate 401/403. Modelled as a `500` in the spec with that caveat.
5. **Consumer-only** (no outbox/producer) — intentional per the Dashboard delta.
6. **Money derived on read** (`revenueNet`, `attachRate`) — never stored.

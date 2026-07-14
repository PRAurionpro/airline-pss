# Reporting & BI — Retro Design Delta 0.4

> **Nature:** *Retro-documentation of merged code* (**PR #61**, branch `feat/reporting-bi`,
> tag `v0.3.4-reporting-bi-thin`). This is a record of what was built, reconstructed from
> the code + the BUILD_LOG gate report — **not** a ratified-before-build design. The merged
> code is authoritative for behaviour. Contracts: `contracts/reporting-api_openapi.yaml`,
> `README_REPORTING_CONTRACTS.md`.

## Scope & non-goals

**In scope (as built):** a standalone read-side projection over the Order + Payments event
streams serving (a) a daily booking/revenue **summary**, (b) paginated **order drill-down**,
(c) **CSV export** (create / status / download). Deferred from Dashboard Delta 0.2 §11.

**Non-goals:** no synchronous module calls; no write path into any module; no real blob
storage (in-memory `ReportStoragePort` stub); no scheduled jobs; no BI cube / OLAP; no
auth model beyond the platform bearer + tenant claim.

## Module posture

Standalone **read-side projection**. Package `pss.reporting`, **port 8087**. Owns its own
projection tables; consumes events and nothing else. Isolation enforced in CI by
`check_reporting_isolation.py`. Publishes no events; calls no module.

## Entities (V1__reporting.sql)

- `seen_events` — append-only dedupe ledger (PK `event_id`).
- `daily_booking_summary` — rollup, unique `(tenant_id, date)`, mutable counters; money as whole-unit BIGINT.
- `order_fact` — per-order projection, unique `order_id` (upsert). `origin`/`destination`/`channel` nullable + currently unpopulated (FLAG-006).
- `order_ancillary_ledger` — OrderCreated→OrderConfirmed ancillary staging.
- `report_exports` — export handle (`PENDING`/`READY`/`FAILED`; `download_url` nullable).

## Event flows

Two `@KafkaListener`s (group `pss-reporting`) on `pss-order-events` + `pss-payments-events`.
Handled types: `OrderCreated`, `OrderConfirmed`, `OrderCancelled`, `RefundIssued` (all
others ignored). Each apply is `@Transactional` and dedupes on the event `eventId` via
`seen_events` (`existsById` → skip, else project + record). **Idempotency invariant:** a
redelivered event is a no-op.

## Ports & deferral lines

- `ReportStoragePort` (`store`/`retrieve`) — impl `InMemoryReportStorage` (stub download URL). Real object storage deferred. **No conformance suite** (open item).

## API summary

5 endpoints under `/v1/reporting/*` — see `contracts/reporting-api_openapi.yaml`. Tenant
from token; problem+json errors; the single typed code is `EXPORT_NOT_FOUND` (404).

## Decisions made de facto

1. **Read-side isolation over convenience** — figures are served only from the projection; no read-back to order/payments. (Consistent with dashboard.)
2. **Whole-currency-unit integers** for money in summaries (not minor units).
3. **Synchronous export modelled as async** — returns `PENDING` then generates in-request; the async contract is preserved so real off-thread generation can drop in behind the port.
4. **`OrderCreated` added to the consumer set** (beyond confirm/cancel) to capture currency/pax/ancillary footprint early.
5. **Export idempotency deferred** — the `Idempotency-Key` header is required but not yet honoured.

## Findings

- No suspected defects and **no core-module writes**. The service is cleanly isolated.
- Documentation-only gaps (now recorded): `Idempotency-Key` required-but-ignored; `origin/destination/channel` always null (FLAG-006); export `from`/`to` not persisted.

## Open items

- Honour `Idempotency-Key` on export creation (dedupe retries).
- Populate `origin/destination/channel` once a correlatable event carries them (FLAG-006).
- Add a `ReportStoragePort` conformance suite.
- Real blob storage behind the port.

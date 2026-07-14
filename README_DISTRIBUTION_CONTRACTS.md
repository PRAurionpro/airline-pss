# Distribution / NDC Module — API & Event Contracts

Contract artifacts for the **distribution-service** of the Aurionpro PSS platform,
authored for **exact fidelity to the merged code** (NDC Delta 0.3 §3/§4 seller
management, §6 NDC AirShopping / OfferPrice / OrderCreate / OrderRetrieve). Where the
service diverges from platform conventions, the divergence is documented **as-is** —
see `## Deviations` below — not normalised away.

- `distribution-api_openapi.yaml` — OpenAPI 3.0.3 for the seven HTTP endpoints.
- `distribution-events_schema.json` — JSON Schema for the one published domain event
  (`DistributionOrderCreated`). Already present; referenced, not re-derived here.

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `distribution-api_openapi.yaml` | OpenAPI 3.0.3 spec — seller management (register / list / get) + NDC (air-shopping / offer-price / order-create / order-retrieve). Server port 8084. | `openapi-spec-validator` — PASS |
| `distribution-events_schema.json` | JSON Schema for `DistributionOrderCreated` (the only published event; emitted on successful OrderCreate via the transactional outbox). | (pre-existing) |

## Identity

- **Service:** `distribution-service`, package `pss.distribution`.
- **Port:** `8084`.
- **Security:** bearer JWT (prod: OAuth2 resource server; non-prod: permitAll demo).
  Tenant is resolved from the verified token via the paved-road tenant filter
  (`TenantContextHolder.requireTenantId()`) — **never** a header/path/query param.
  No `X-Tenant-Id` anywhere.

## Endpoints

| # | Method + Path | Controller | Purpose | Required headers | Success |
|---|---------------|-----------|---------|------------------|---------|
| 1 | `POST /v1/sellers` | Distribution | Register seller | `Idempotency-Key` (ignored) | 201 + `ETag` |
| 2 | `GET /v1/sellers` | Distribution | List tenant's sellers | — | 200 (bare array) |
| 3 | `GET /v1/sellers/{sellerId}` | Distribution | Get seller | — | 200 + `ETag` |
| 4 | `POST /v1/ndc/air-shopping` | Ndc | AirShopping | `Idempotency-Key`, `X-Seller-Id` | 200 |
| 5 | `POST /v1/ndc/offer-price` | Ndc | OfferPrice | `Idempotency-Key`, `X-Seller-Id` | 200 |
| 6 | `POST /v1/ndc/order-create` | Ndc | OrderCreate (+ event) | `Idempotency-Key`, `X-Seller-Id` | 200 |
| 7 | `GET /v1/ndc/orders/{orderRef}` | Ndc | OrderRetrieve | `X-Seller-Id` (Idempotency-Key optional) | 200 |

**Idempotency semantics differ per endpoint:**

- `order-create` — TRUE replay key: a repeat with the same `(tenantId, sellerId, key)`
  returns the original `OrderCreateRs` with no re-create and no second event.
- `air-shopping` / `offer-price` — key is REQUIRED but audit-correlation-only; repeats
  re-execute (a new session each time).
- `sellers` (register) — key is REQUIRED by the signature but IGNORED; dedup is a
  natural key on `(tenantId, name)` (re-registering the same name returns the original,
  still 201).
- `order-retrieve` — key is OPTIONAL; when absent, `orderRef` is the audit correlationId.

**Seller principal:** the four NDC endpoints identify the seller via the non-standard
`X-Seller-Id` request header (a stand-in for an upstream api-key→seller resolver), NOT a
security scheme. Every NDC read/write is scoped `(tenantId, sellerId)` for seller isolation.

## Error model

The body shape is a **reduced problem object** — plain `application/json`:

```json
{ "status": 404, "code": "NOT_FOUND", "detail": "Seller … not found" }
```

It is **not** RFC 9457 (no `type` / `title` / `instance`) and is **not** served as
`application/problem+json`. `detail` may be null. One `Error` schema covers all cases.

### Typed error codes

| Code | HTTP | When |
|------|------|------|
| `NOT_FOUND` | 404 | Seller not found (get-seller, or seller lookup on NDC endpoints). |
| `INVALID_REQUEST` | 422 | AirShopping with no originDestination (`InvalidState`, caller-supplied code). |
| `SELLER_SUSPENDED` | 403 | Seller is not ACTIVE. |
| `OFFER_EXPIRED` | 410 | Offer past its `validUntil` (offer-price, order-create). |
| `OFFER_NOT_FOUND` | 404 | Session is not this seller's (offer-price). |
| `ORDER_NOT_FOUND` | 404 | order-retrieve seller-isolated miss. |
| `SESSION_NOT_IN_OFFER_SELECTED` | 409 | order-create against a session not in OFFER_SELECTED. |
| `ORDER_CREATE_FAILED` | 422 | order-service returned a 4xx during order-create. |

> The seller controller (`DistributionController`) collapses everything to **404**
> (NotFound) or **422** (generic), ignoring the exception's own `httpStatus`; only
> `NOT_FOUND` (404) is actually reachable there in practice. The NDC controller
> (`NdcController`) honours the exception `httpStatus` exactly (403/404/409/410/422).

## Events

The service publishes exactly one domain event, out-of-band via the transactional outbox
— it is **not** part of the HTTP contract and is defined in
[`distribution-events_schema.json`](contracts/distribution-events_schema.json):

| Event | Published by | Meaning |
|-------|--------------|---------|
| `DistributionOrderCreated` | successful `POST /v1/ndc/order-create` | An NDC order was created; committed atomically with the session `OFFER_SELECTED → ORDER_CREATED` transition. |

Envelope: `eventId` (ULID), `type`, `version` = 1, `tenantId`, `occurredAt`,
`correlationId`, `payload { orderRef, sellerId, sessionId, offerId, totalAmount,
currency, sellerRef }`. `sellerRef` is nullable; the other payload fields are required.
Consumers must be idempotent on `eventId`. No events are emitted by air-shopping,
offer-price or order-retrieve.

**Not exposed:** GDS and interline seams (`GdsAdapterPort` / `InterlineAgreementPort`)
are internal ports (simulator by default), with no HTTP controller — deliberately absent
from the OpenAPI surface.

## Deviations

Documented as-merged. These are **carried items**, not fixes — recorded here so the
contract stays truthful to the code.

1. **`X-Seller-Id` custom principal header.** The seller principal on all four NDC
   endpoints rides in a non-standard `X-Seller-Id` header, out-of-band from the token —
   a documented stand-in for an upstream api-key→seller resolver. Modelled as a required
   header parameter, not a security scheme.
2. **`Idempotency-Key` required-but-ignored on `POST /v1/sellers`.** The header is
   mandatory in the method signature yet never consulted; real deduplication is a natural
   key on `(tenantId, name)`.
3. **Divergent per-controller error handlers.** There is no global `@ControllerAdvice`.
   `NdcController` honours each exception's `httpStatus` (403/404/409/410/422);
   `DistributionController`'s generic handler **ignores `httpStatus`**, collapsing to 404
   (NotFound) or 422.
4. **Reduced problem body, not RFC 9457.** `{status, code, detail}` only — no
   `type`/`title`/`instance` — served as `application/json`, despite a code comment
   claiming "RFC 9457 problem+json".
5. **No `If-Match` although `ETag` is emitted.** Seller resources carry a JPA `@Version`
   surfaced as an `ETag` on `POST /v1/sellers` (201) and `GET /v1/sellers/{sellerId}`
   (200), but no write path reads `If-Match` — the ETag is informational only. The list
   endpoint and all NDC responses carry no ETag.
6. **`TenantContextMissingException` → 500.** When no tenant is bound, the paved-road
   `requireTenantId()` throws, and neither controller catches it → a framework HTTP 500
   (not a problem body, not a 401/403). Represented in the spec as a `500` per operation
   with a note that the body is the framework default.
7. **`InvalidState` is a generic 422 with a caller-supplied code.** Only `INVALID_REQUEST`
   (422) is observed in practice (do not assume a 400).
8. **No dedicated N0–N3 gate reports in `BUILD_LOG.md`.** Distribution/NDC has no
   milestone gate-report sections of its own; the surface is referenced only obliquely
   from the later corporate-booking (C-tranche) entries that consume it.

## Regenerating / validating

```bash
pip install --break-system-packages openapi-spec-validator
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('distribution-api_openapi.yaml')))"
```

*Working draft — documents distribution-service as merged (NDC Delta 0.3).*

# Corporate / SME Module — API & Event Contracts

Contract artifacts for the **corporate-service** of the Aurionpro PSS platform,
documenting the service **as merged** (build plan §C0–C3, Corporate Delta 0.3).
`corporate-api_openapi.yaml` covers the HTTP surface; `corporate-events_schema.json`
covers the single published domain event. These are descriptive of the code as it
stands — where the code diverges from platform conventions, the divergence is
carried faithfully and listed under **Deviations** (never normalised away).

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `corporate-api_openapi.yaml` | OpenAPI 3.0.3 spec for the 14 corporate endpoints (server port 8085). | `openapi-spec-validator` — PASS |
| `corporate-events_schema.json` | JSON Schema (draft 2020-12) for the one published event, `CorporateBookingCreated`. | `jsonschema` Draft202012 `check_schema` + 2 fixtures — PASS |
| `fixtures/corporate/CorporateBookingCreated.sample1.json` | Valid instance — COMPLIANT booking. | validates against the schema |
| `fixtures/corporate/CorporateBookingCreated.sample2.json` | Valid instance — ADVISORY booking, larger amount. | validates against the schema |

## API conventions actually in force

- **Tenant from token** — every handler resolves the tenant via
  `TenantContextHolder.requireTenantId()` (claim `tenantId`, fallback `tid`). There is
  **no** tenant path/query/header, and specifically **no `X-Tenant-Id`**.
- **Admin gate is a demo header** — the account, cost-centre, traveller and policy
  surfaces require `X-Corporate-Admin: true` (case-insensitive); missing or non-`true`
  yields `CORPORATE_ADMIN_REQUIRED` (403). The **booking and statement surfaces are NOT
  gated** (bearer only).
- **No inbound Idempotency-Key** on any corporate endpoint.
- **ETag without If-Match** — account create/get emit a plain `ETag: "<version>"`
  response header; no `If-Match` is ever consumed and there is no account update
  endpoint.
- **Errors** are a plain `{ status, code, detail }` JSON body (`application/json`) —
  **not** RFC-9457 problem+json.
- **Money** fields are BigDecimal on the wire, modelled as JSON `number`. Identifiers
  are ULIDs unless noted.

## Endpoints (grouped by controller)

| # | Method & path | Controller | Admin-gated | Success | Notes |
|---|---------------|------------|-------------|---------|-------|
| 1 | `POST /v1/corporate/accounts` | CorporateController | yes | **201** | + `ETag` header |
| 2 | `GET /v1/corporate/accounts/{accountId}` | CorporateController | yes | 200 | + `ETag` header |
| 3 | `POST /v1/corporate/accounts/{accountId}/cost-centres` | CostCentreController | yes | **201** | |
| 4 | `GET /v1/corporate/accounts/{accountId}/cost-centres/{costCentreId}` | CostCentreController | yes | 200 | |
| 5 | `GET /v1/corporate/accounts/{accountId}/cost-centres` | CostCentreController | yes | 200 | **bare array** |
| 6 | `POST /v1/corporate/accounts/{accountId}/travellers` | TravellerController | yes | **201** | |
| 7 | `GET /v1/corporate/accounts/{accountId}/travellers/{travellerId}` | TravellerController | yes | 200 | |
| 8 | `POST /v1/corporate/accounts/{accountId}/policies` | TravelPolicyController | yes | **201** | supersedes prior active |
| 9 | `GET /v1/corporate/accounts/{accountId}/policies/active` | TravelPolicyController | yes | 200 | |
| 10 | `POST /v1/corporate/bookings` | BookingController | no | **200** | NOT 201; no ETag/Location |
| 11 | `GET /v1/corporate/bookings/{bookingId}` | BookingController | no | 200 | |
| 12 | `GET /v1/corporate/accounts/{accountId}/bookings` | BookingController | no | 200 | **bare array** |
| 13 | `GET /v1/corporate/accounts/{accountId}/statements` | BillingStatementController | no | 200 | **wrapped** `{statements:[...]}` |
| 14 | `GET /v1/corporate/accounts/{accountId}/statements/{statementId}` | BillingStatementController | no | 200 | with booking lines |

## Typed error codes

Body shape: `{ "status": <int>, "code": "<CODE>", "detail": "<message>" }`
(`application/json`; **not** problem+json).

| Code | HTTP | When |
|------|------|------|
| `INVALID_REQUEST` | 400 | Bean-validation failure (`@NotBlank` / `@Valid`). |
| `CORPORATE_ADMIN_REQUIRED` | 403 | Admin gate: missing/non-`true` `X-Corporate-Admin`. |
| `ACCOUNT_SUSPENDED` | 403 | Booking against a non-ACTIVE account. |
| `ACCOUNT_NOT_FOUND` | 404 | Account not visible to the tenant (get / scoping / booking / statement). |
| `COST_CENTRE_NOT_FOUND` | 404 | Cost centre not found (get / booking). |
| `TRAVELLER_NOT_FOUND` | 404 | Traveller not found (get / booking). |
| `NO_ACTIVE_POLICY` | 404 | No active policy (getActivePolicy / booking). |
| `BOOKING_NOT_FOUND` | 404 | Booking not visible to the tenant (getBooking). |
| `STATEMENT_NOT_FOUND` | 404 | Statement not visible to the tenant (getStatement). |
| `DUPLICATE_ACCOUNT` | 409 | registerAccount for an already-existing account. |
| `POLICY_VIOLATION` | 409 | Booking hard breach — blocked. |
| `OFFER_NOT_AVAILABLE` | 422 | No offer / no session, or an unparseable `departureDate`. |

## Events

**Exactly one** event type is emitted by the whole service, from
`PolicyCompliantBookingService.publishBookingCreated` via the transactional outbox
(Kafka topic `pss.corporate.events`, message key = `accountId`).

| Event | Published by | Effect / meaning |
|-------|-------------|------------------|
| `CorporateBookingCreated` | PolicyCompliantBooking flow (C2) | A COMPLIANT / ADVISORY corporate booking was persisted. VIOLATION never emits (it blocks upstream). |

**Wire envelope** (follows the JSON built in code, not the DB columns):
`eventId` (ULID), `type` = `"CorporateBookingCreated"`, `version` = integer `1`,
`tenantId`, `occurredAt` (date-time), `payload{}`. There is **no** `accountId` /
`memberId` / `correlationId` at envelope level — `accountId` lives in the payload.

**Payload:** `bookingId`, `accountId`, `travellerId`, `costCentreCode`, `orderRef`,
`totalAmount` (number), `currency`, `policyStatus` (`COMPLIANT`|`ADVISORY`),
`sellerRef` (pattern `CORP-{accountId}-CC-{costCentreCode}`).

Fixtures:
- `CorporateBookingCreated.sample1.json` — COMPLIANT, INR 12450.00, cost centre `ENG`.
- `CorporateBookingCreated.sample2.json` — ADVISORY, INR 88999.50, cost centre `SALES-EMEA`.

## Deviations

These are documented as-merged (carried items, not fixed). They deviate from the
loyalty/platform contract conventions.

1. **Wire `type` vs DB `event_type`.** The published JSON envelope discriminator key is
   `type`; the outbox table column / entity field is `event_type` / `eventType`. The
   schema follows the wire (`type`).
2. **Integer `version` 1 vs platform string `"1"`.** The corporate envelope emits
   `version` as the JSON integer `1`. Loyalty (and the platform convention) use the
   string `"1"`. The schema uses `{ "type": "integer", "const": 1 }`.
3. **Demo `X-Corporate-Admin` gate; bookings/statements ungated.** The admin surfaces
   are gated only by a demo header (`X-Corporate-Admin: true`, case-insensitive), not
   RBAC. Bookings and statements are not gated at all (bearer only). Full RBAC deferred
   (delta §5).
4. **`createBooking` returns 200, not 201** — despite creating a resource, and unlike
   every other create endpoint (which return 201). No `Location`/`ETag` on booking
   creation.
5. **ETag without If-Match.** Account create/get emit a plain (quoted) `ETag` from the
   JPA `@Version`, but no `If-Match` is ever consumed and there is no update endpoint,
   so the version is read-only on the wire. Only `CorporateAccount` carries `@Version`.
6. **No inbound Idempotency-Key** on any corporate POST. Booking dedup relies on an
   internally-minted `correlationId` sent downstream to distribution-service, not on a
   client-supplied key.
7. **`email` is `@NotBlank`, not `@Email`.** Both `RegisterTravellerRequest.email` and
   `RegisterAccountRequest.billingEmail` accept any non-blank string (modelled as
   `type: string`, no `format: email`).
8. **Bare-array vs wrapped lists.** `listCostCentres` and `listBookings` return bare
   JSON arrays; `listStatements` returns a wrapper object `{ statements: [...] }`.
   Inconsistent list envelope within the same service.
9. **`AccountResponse` omits `tenantId` and `registrationNo`.** Both are intentionally
   not on the wire (documented in the DTO comment), even though they are persisted.
10. **`departureDate` is a free string parsed server-side.** A parse failure surfaces as
    `OFFER_NOT_AVAILABLE` (422), **not** a 400 validation error.
11. **Error body is not RFC-9457.** `{ status, code, detail }` served as
    `application/json` — no `type` / `title` / `instance`, not `application/problem+json`
    (the handler comment even calls it "RFC-9457-ish").

## Regenerating / validating

```bash
pip install --break-system-packages openapi-spec-validator jsonschema pyyaml
# OpenAPI
python -c "from openapi_spec_validator import validate_spec; import yaml; validate_spec(yaml.safe_load(open('corporate-api_openapi.yaml',encoding='utf-8'))); print('OPENAPI PASS')"
# Events schema (meta) + fixtures
python -c "import json; from jsonschema.validators import Draft202012Validator as D; s=json.load(open('corporate-events_schema.json',encoding='utf-8')); D.check_schema(s); v=D(s); [v.validate(json.load(open('fixtures/corporate/CorporateBookingCreated.sample%d.json'%i,encoding='utf-8'))) for i in (1,2)]; print('SCHEMA+FIXTURES PASS')"
```

*Descriptive contract — companion to Corporate Delta 0.3 and the build plan §C0–C3
(tranche tag `v0.3.2-corporate-thin`).*

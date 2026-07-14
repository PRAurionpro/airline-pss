# Conversational Commerce Module — API & Event Contracts

Retro-documented contract-first artifacts for the Conversational Commerce (WhatsApp)
module (merged in **PR #62**, branch `feat/conversational-commerce`). Written *after*
the code to close the contract gap. Companion: `CONVERSATION_DELTA_0_5_RETRO.md`.

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `contracts/conversation-api_openapi.yaml` | OpenAPI 3.0.3 for the Conversation API (webhook + admin reads). | `openapi-spec-validator` — PASS |

**No event schema.** conversation-service **publishes no backbone events** — verified:
no `KafkaTemplate`, no outbox table, no `@Scheduled` relay. Outbound WhatsApp (via the
stub gateway port) is a channel side-effect, **not** a backbone event.

## API conventions (carried from the platform)

- **Tenant from token (admin endpoints)** — verified bearer-token claim (`TenantContextHolder`).
- **Errors** — `application/problem+json` (RFC 9457) with a stable `code`.

## Typed error codes

| Code | HTTP | When |
|------|------|------|
| `SESSION_NOT_FOUND` | 404 | Session id not visible to the tenant. |
| `CONVERSATION_ADMIN_REQUIRED` | 403 | `X-Conversation-Admin` not present/true. |

## Events

**Consumed** (idempotent on `eventId` via `seen_events`) — these drive outbound WhatsApp notifications:

| Event | Topic | Effect |
|-------|-------|--------|
| `OrderConfirmed` | `pss-order-events` | Sends a booking-confirmed WhatsApp message; advances the session. |
| `PassengerCheckedIn` | `pss-dcs-events` | Sends a checked-in / boarding-pass WhatsApp message. |

**Published:** none.

**Downstream reads/writes (not events):** the bot calls the same public APIs the IBE
uses — `order-service` `GET /v1/orders?paxRef=` (read) and `dcs-service`
`GET /v1/departures` (read) + **`POST /v1/departures/{departureId}/checkins`** (write —
see Finding).

## Deviations from platform conventions

Documented as carried open items (code is authoritative):

1. **The webhook is fully public** — no bearer token and **no signature/verification header** (deferred to the real Meta integration).
2. **The webhook resolves tenant from the request body**, not a token claim — two tenant-resolution models coexist in one controller (admin endpoints use the token).
3. **No `Idempotency-Key` on the webhook**, despite it mutating session state and triggering a DCS check-in write; only consumed Kafka events are deduped. *(The downstream DCS check-in call itself now carries a deterministic key — see Finding below, resolved in session 23.)*
4. **Admin authz is a spoofable app-level `X-Conversation-Admin: true` header check**, not a security filter/role (demo posture).
5. **Service-to-service calls use an unsigned `alg:none` tenant JWT** (demo idiom).
6. **Event→session correlation is a `LIKE %needle%` substring match** over `context_json`, because neither consumed event carries a phone number (first-match-wins, brittle).
7. **`WhatsAppGatewayPort` has no conformance suite** (unlike RM/CDP ports). Open item.
8. **`AWAITING_NAME` state, `OrderServiceClient.getOrder`, and `NotificationStatus.PENDING`** are declared but unused (reserved).

## ✅ Finding — RESOLVED in session 23

The bot performs a **state-mutating write into another module** —
`POST /v1/departures/{departureId}/checkins` on dcs-service. In the merged code it sent **no
`Idempotency-Key`**. **Session 23 fixed this:** the client now sends a **deterministic** key
`"${sessionId}:checkin:${departureId}"` (derived from the conversation's own identity), so a
retry of the same user check-in carries the same key — asserted in `ConversationServiceTest`
(retry-stability) and `ConversationIT` (header present + exact value).

This was also **required for correctness**: DCS's check-in endpoint declares
`@RequestHeader("Idempotency-Key")` as **non-optional**, so the previous header-less call would
have `400`ed against the real service (the conversation IT's DCS WireMock stub had hidden this).

**DCS-side finding (recorded, not fixed — out of scope per Task 2):** dcs-service *requires* the
`Idempotency-Key` header but **does not dedupe on it** — `DcsController.checkIn` accepts the
header and never passes it to `DcsService.checkIn`, which instead dedupes on the **natural key**
`(departureId, orderRef, paxRef)` (returns the existing check-in with `created=false`). So
double-submit is already prevented by the natural key; the header value is currently inert on
DCS. A dedicated Idempotency-Key store for this endpoint would be a dcs-service change for its
own session.

## Regenerating / validating

```bash
pip install openapi-spec-validator pyyaml
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('contracts/conversation-api_openapi.yaml')))"
```

*Retro-documentation — companion to CONVERSATION_DELTA_0_5_RETRO.md.*

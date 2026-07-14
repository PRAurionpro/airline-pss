# Conversational Commerce (WhatsApp) — Retro Design Delta 0.5

> **Nature:** *Retro-documentation of merged code* (**PR #62**, branch
> `feat/conversational-commerce`, tag `v0.3.5-conversational-commerce-thin`). A record of
> what was built, not a ratified-before-build design. The merged code is authoritative.
> Contracts: `contracts/conversation-api_openapi.yaml`, `README_CONVERSATION_CONTRACTS.md`.

## Scope & non-goals

**In scope (as built):** a thin WhatsApp channel adapter — an inbound webhook (stub shape)
driving a per-customer session state machine that calls the **same public** order/DCS APIs
the IBE uses (find booking by pax ref → confirm → check in), sends outbound WhatsApp via a
stub gateway, and records sessions + a notification log. Plus admin reads.

**Non-goals:** no real Meta webhook envelope / signature verification; no NLU (keyword state
machine only); no new booking/payment logic; no backbone event publishing; no owned domain
beyond conversation session state.

## Module posture

**Channel adapter.** Package `pss.conversation`, **port 8088**. Owns conversation session
state only; WhatsApp I/O behind `WhatsAppGatewayPort` (stub). Consumes events to trigger
notifications; calls order/DCS public APIs. No module internals changed.

## Entities (V1__conversation.sql)

- `conversation_sessions` — id, tenant, `wa_phone_number`, `state`, `context_json` (TEXT), timestamps; unique `(tenant_id, wa_phone_number)`; updated in place.
- `outbound_notifications` — append-only outbound log (`PENDING`/`SENT`/`FAILED`).
- `seen_events` — dedupe ledger (PK `event_id`).

**State machine** `ConversationState { IDLE, AWAITING_PNR, AWAITING_NAME, BOOKING_CONFIRMED, CHECKIN_SENT }` — real path IDLE→AWAITING_PNR→BOOKING_CONFIRMED→CHECKIN_SENT (`AWAITING_NAME` reserved/unused).

## Event flows

`ConversationEventConsumer` (group `pss-conversation`) on `pss-order-events` +
`pss-dcs-events`. Acts on `OrderConfirmed` (top-level `orderId`) and `PassengerCheckedIn`
(`payload.orderRef`); idempotent on `eventId` via `seen_events`. Correlates an event to a
session by substring `LIKE` over `context_json` (no phone number in the events).

## Ports & deferral lines

- `WhatsAppGatewayPort` (`sendTextMessage` / `sendTemplateMessage`) — impl `StubWhatsAppGateway` (logs + acks `STUB-<ulid>`). Real Meta Cloud API deferred. **No conformance suite** (open item).

## API summary

4 endpoints under `/v1/conversation/*` (public webhook + 3 admin reads) — see
`contracts/conversation-api_openapi.yaml`. Typed codes: `SESSION_NOT_FOUND` (404),
`CONVERSATION_ADMIN_REQUIRED` (403).

## Decisions made de facto

1. **Webhook is public and tenant-from-body** — the stub Meta integration carries tenant in the payload; signature verification deferred. (Deliberate two-tenant-model split vs the admin endpoints.)
2. **Reuse public APIs, change no internals** — the bot is just another client of order/DCS, proving the "same APIs the IBE uses" principle.
3. **Keyword state machine, not NLU** — smallest slice that demonstrates the journey.
4. **Idempotency via consumed-event `eventId` only** — the webhook itself is not idempotent.
5. **Outbound WhatsApp is a channel side-effect, not a backbone event** — nothing published.

## Findings

- **Cross-service write:** `POST /v1/departures/{departureId}/checkins` into **dcs-service**. Originally sent **no `Idempotency-Key`**. **Resolved in session 23** — now sends a deterministic `"${sessionId}:checkin:${departureId}"` key (required anyway: DCS declares the header non-optional, so the header-less call would have 400ed against the real service — hidden by the IT's WireMock). DCS-side finding: DCS requires but *ignores* the key, deduping on the natural key `(departureId, orderRef, paxRef)` instead — recorded, not fixed (dcs-service change is out of scope).
- **Brittle correlation:** event→session via `LIKE` substring is first-match-wins; a real phone-number key on the events would fix it.
- No suspected defects in owned logic.

## Open items

- Real Meta webhook (envelope + `X-Hub-Signature-256` verification); drop tenant-from-body.
- Add `Idempotency-Key` to the webhook and the DCS check-in call.
- Replace substring correlation with an explicit key.
- `WhatsAppGatewayPort` conformance suite; real gateway impl.
- Remove/clarify the reserved `AWAITING_NAME` state and unused members.

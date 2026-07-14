# Order Module — API & Event Contracts

Contract-first artifacts for the Order module of the Aurionpro PSS platform.
These realise the **Order Module Low-Level Design (LLD)** — `order-api.openapi.yaml`
covers LLD §5 (API) and `order-events.schema.json` covers LLD §6 (events).
Drop this folder into the service repository / Claude Code project context so generated
code stays aligned to the contract.

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `order-api.openapi.yaml` | OpenAPI 3.0.3 spec for the Order API (Create / Get / List / Change / Cancel). | `openapi-spec-validator` — PASS |
| `order-events.schema.json` | JSON Schema (draft 2020-12) for the published domain events, one envelope + 7 event types. | `jsonschema` Draft202012 `check_schema` — PASS |

## API conventions (enforced by the spec)

- **Tenant from token** — tenant context comes from a verified claim in the OAuth2/OIDC bearer token; there is no tenant path/query parameter.
- **Idempotency** — every state-changing call (`createOrder`, `changeOrder`, `cancelOrder`) requires an `Idempotency-Key` header.
- **Optimistic concurrency** — `changeOrder` / `cancelOrder` require the current ETag via `If-Match`; a stale version returns `409`.
- **Errors** — `application/problem+json` (RFC 9457) with a stable machine-readable `code`.
- **ONE Order mapping** — operations map to the OrderCreate / OrderRetrieve / OrderChange / OrderCancel message families.

## Events

Single envelope (`eventId`, `type`, `version`, `tenantId`, `orderId`, `occurredAt`, `correlationId`, `causationId`, `payload`); the concrete event is selected by the `type` constant. Consumers must be **idempotent on `eventId`**. Types: `OrderCreated`, `OrderConfirmed`, `OrderItemAdded`, `OrderItemCancelled`, `OrderChanged`, `OrderCancelled`, `OrderFulfilled`.

## Provisional — pending LLD §14 decisions

These are encoded with sensible defaults and marked in the spec; confirm before freezing the contract:

- `orderRef` format (currently `^[A-Z0-9]{6}$`).
- Pinned ONE Order / NDC schema generation.
- Default inventory hold TTL.

## Phase 1 note

Only `ADD_ITEM` and `CANCEL_ITEM` are built for `changeOrder` in Phase 1; `EXCHANGE_ITEM` is in the contract but deferred (LLD §12). Air items only in Phase 1; ancillary/service items follow in Phase 2.

## Regenerating / validating

```bash
pip install --break-system-packages openapi-spec-validator jsonschema
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('order-api.openapi.yaml')))"
python -c "import json; from jsonschema.validators import Draft202012Validator as D; D.check_schema(json.load(open('order-events.schema.json')))"
```

*Working draft — companion to the System Design Document (v0.2), the Phase 0 & Phase 1 Build Plan, the Order Module LLD, and PSS_Landscape_Design_Roadmap_3.docx.*

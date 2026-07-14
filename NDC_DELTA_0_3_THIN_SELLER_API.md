# NDC / B2B Distribution Delta 0.3 — Thin Seller API (AirShopping + OrderCreate)

**Status:** Draft for ratification · Companion to: DEMO_SCRIPT.md (roadmap Scene 8 → NDC distribution) · DCS_DELTA_0_3_CHECKIN_BOARDING.md · BUILD_PLAN_DCS_TRANCHE.md
**Base documents:** PSS_System_Design_Document (§5.1 Distribution/NDC, §9.1 IATA standards) · PSS_Offer_Module_LLD · PSS_Order_Module_LLD
**Phase note:** Distribution/NDC is a **Phase 2** module in the SDD catalogue. This delta builds the *thin walking-skeleton slice only*: seller-facing NDC AirShopping + OrderCreate, backed by the existing Offer and Order modules. GDS/EDIFACT adapters, order-based interline (SRSIA), and the full B2B agent portal are deferred to later slices.
**Recommendation honoured (this delta's premise):** EDIFACT/Type-B adapters, GDS connectivity, and order-based interline are **stubbed behind ports with conformance suites** — the same pattern as DCS's WeightBalancePort.

---

## 1. Scope and Non-Goals

Exactly the smallest seller-facing path that proves a third-party (OTA, TMC, or aggregator) can shop, select an offer, and create an order through an NDC-conformant API — without touching the Offer or Order module's internal logic, and without a real GDS connection.

The slice: **AirShopping → OfferPrice → OrderCreate → OrderRetrieve**, single-pax and multi-pax, translated onto the existing `/v1/offers/shop`, `/v1/offers/{id}`, `/v1/orders` endpoints. A registered seller gets a `sellerId` + API key; their shopping and order requests are tenant-scoped (the airline) and seller-scoped (the intermediary).

**This is the differentiator made distributable:** the order Tranche 1 sold directly is now sellable by an OTA or TMC — the same Offer and Order modules, reached through an NDC adapter layer.

**Non-goals (deferred without guilt):**
EDIFACT/Type-B or GDS connectivity · order-based interline (SRSIA/RP 1780s) · OrderChange / OrderCancel NDC messages (servicing flows) · commission billing and settlement with sellers · agency credit accounts and deposit limits · full B2B agent portal UI · seller SLA dashboards · content differentiation per seller · full NDC schema validation (IATA 21.3 XSD) — conformance suite covers the shapes, strict XSD validation is a later milestone · biometric/admissibility seller flows · fare surcharge rules per seller channel.

**Design invariants carried forward, unchanged:**
- **No changes to Offer or Order module internals.** Distribution is a *translation layer* — it maps NDC message shapes onto the existing REST APIs and maps responses back. If the build appears to require an Offer or Order code change, stop and flag.
- **Tenant + seller dual-scope.** Every request carries both a tenant context (the airline, from the verified token) and a seller context (the OTA/TMC, from the `sellerId` claim). The core modules see only the tenant.
- **Money and inventory invariants unchanged.** NDC OrderCreate ultimately calls `/v1/orders` — the same saga, same holds, same no-oversell invariant. The NDC layer adds no new money paths.
- **Contract changes are additive only.** No existing Offer or Order endpoint is modified; the NDC endpoints are net-new surface.

## 2. Module Posture — New Bounded Context, Translation Layer

`distribution-service` is a new service, tenant+seller-scoped, sitting at the edge between external sellers and the platform's internal Offer/Order APIs. It:

- **Receives** NDC-shaped requests from sellers (JSON-over-REST, NDC-aligned field names).
- **Translates** inbound NDC messages to platform REST calls (AirShopping → `/v1/offers/shop`; OfferPrice → `/v1/offers/{id}`; OrderCreate → `/v1/orders`).
- **Translates** platform REST responses back to NDC-aligned response shapes.
- **Owns** its own entities: `SellerProfile`, `DistributionMessage` (audit log), `SellerSession`.
- **Publishes** `DistributionOrderCreated` via the transactional outbox when an NDC OrderCreate succeeds.
- **Stubs behind ports:** `GdsAdapterPort` (EDIFACT/Type-B) and `InterlineAgreementPort` (SRSIA) — each with a simulator and conformance suite.

**Crucially:** the distribution-service never holds offer or order state — it holds the NDC message log (for audit and replay) and the seller profile. All business state lives in Offer and Order as before.

## 3. Seller Model — Entities

Per-tenant seller store. Entities exactly as SDD §5.1 fixes them.

```
SellerProfile  { sellerId, tenantId, name, kind {OTA│TMC│AGGREGATOR│DIRECT_API},
                 status {ACTIVE│SUSPENDED}, apiKeyRef, createdAt }

SellerSession  { sessionId, sellerId, tenantId, offerId (nullable), offerRef (nullable),
                 state {SHOPPING│OFFER_SELECTED│ORDER_CREATED}, expiresAt }

DistributionMessage { messageId, sellerId, tenantId, direction {INBOUND│OUTBOUND},
                      messageType {AIR_SHOPPING│OFFER_PRICE│ORDER_CREATE│ORDER_RETRIEVE},
                      correlationId, payload (TEXT), statusCode, occurredAt }
```

**SellerSession state machine:**
```
SHOPPING → OFFER_SELECTED → ORDER_CREATED
```
The session is the NDC "shopping basket" — it ties an AirShopping result to the subsequent OfferPrice and OrderCreate, preserving the offer reference across messages.

## 4. API Contract (Distribution / NDC, net-new)

Conventions: seller authenticates with an API key (Bearer token carrying `tenantId` + `sellerId` claims); every state-changing call requires `Idempotency-Key`; errors use RFC 9457 problem+json with a stable `code`.

| Operation | Method / path | NDC message analogue |
|-----------|--------------|----------------------|
| AirShopping | `POST /v1/ndc/air-shopping` | AirShoppingRQ / AirShoppingRS |
| OfferPrice | `POST /v1/ndc/offer-price` | OfferPriceRQ / OfferPriceRS |
| OrderCreate | `POST /v1/ndc/order-create` | OrderCreateRQ / OrderCreateRS |
| OrderRetrieve | `GET /v1/ndc/orders/{orderRef}` | OrderRetrieveRQ / OrderRetrieveRS |
| ListSellers | `GET /v1/sellers` | (management API, airline staff only) |
| RegisterSeller | `POST /v1/sellers` | (management API, airline staff only) |

**Typed error codes:**
- `SELLER_NOT_FOUND` — unknown sellerId.
- `SELLER_SUSPENDED` — seller account not active.
- `OFFER_EXPIRED` — OfferPrice or OrderCreate references an offer past its `validUntil`.
- `OFFER_NOT_FOUND` — offer reference not found or not owned by this seller session.
- `ORDER_CREATE_FAILED` — downstream Order module rejected the create; detail carries the upstream typed code.
- `GDS_UNAVAILABLE` — GdsAdapterPort (stub) negative path.
- `INTERLINE_REFUSED` — InterlineAgreementPort (stub) negative path.

## 5. The Two Ports — Stubbed, with Conformance Suites

Same pattern as DCS's WeightBalancePort and GovDataPort.

### 5.1 GdsAdapterPort
Semantics: `submitAirShopping(request) → AirShoppingResult` and `submitOrderToGds(orderRef, content) → GdsConfirmation │ GdsUnavailable`. Simulator returns synthetic availability for the demo flight and exposes a `GdsUnavailable` instrument. Conformance suite: happy path, unavailable path, idempotent re-submit.

**Fitness rule (CI):** no GDS-vendor-specific symbol outside `GdsAdapter` and its translator.

### 5.2 InterlineAgreementPort
Semantics: `checkAgreement(tenantId, partnerCarrier) → AGREED │ NO_AGREEMENT` and `notifyInterlineOrder(orderRef) → acknowledged`. Simulator returns AGREED for the demo tenant. Conformance suite: AGREED path, NO_AGREEMENT path, notify idempotency.

## 6. What the Existing Modules See — Zero Internal Changes

AirShopping → calls existing `/v1/offers/shop` with the translated request.
OfferPrice → calls existing `/v1/offers/{offerId}` (re-price / re-validate at selection time).
OrderCreate → calls existing `POST /v1/orders` with the translated CreateOrderRequest. The order carries an optional `sellerRef` in its metadata (additive field, Open Decision 1).
OrderRetrieve → calls existing `GET /v1/orders/{orderRef}` and translates to NDC shape.

**If any of these calls require an Order or Offer code change, stop and flag.**

## 7. Demo Surfacing — One New Scene

The thin slice makes a new live demo scene possible: an OTA books a flight through the NDC API, and the order appears in the carrier dashboard in real time. A Postman/curl collection representing an OTA drives the scene — prospect sees the platform is a real distribution hub, not just a direct-channel IBE.

## 8. Milestones and Gates

- **N0 — Service skeleton + seller store + RegisterSeller.** `distribution-service`, seller store, `SellerProfile`, transactional outbox wired. **Gate:** RegisterSeller creates a seller; ListSellers returns it; outbox smoke. *(Parallel with N1.)*
- **N1 — Ports + conformance suites (simulators only).** `GdsAdapterPort` + `InterlineAgreementPort` + simulators + conformance suites + fitness rules. **Gate:** both suites green; negative paths proven. *(Parallel with N0.)*
- **N2 — AirShopping + OfferPrice.** NDC AirShopping translated to `/v1/offers/shop`; OfferPrice re-validates. SellerSession: SHOPPING → OFFER_SELECTED. **Gate:** AirShopping returns NDC-shaped offers; OfferPrice works; `OFFER_EXPIRED` rejects stale.
- **N3 — OrderCreate + OrderRetrieve + DistributionOrderCreated.** NDC OrderCreate → `/v1/orders`; NDC OrderRS back; SellerSession → ORDER_CREATED; `DistributionOrderCreated` published. **Gate:** full AirShopping → OfferPrice → OrderCreate → OrderRetrieve path green; event in outbox; `ORDER_CREATE_FAILED` on inventory gone.
- **N4 — Multi-pax + demo Postman collection + scripted smoke.** 2×ADT NDC booking end-to-end; demo collection drives the scene; CI smoke unattended. **Gate:** 2×ADT NDC booking confirmed; demo collection green; smoke passes unattended.

**Tag:** `v0.3.1-ndc-thin` at N4.

## 9. Stop-and-Flag List

Any Offer or Order module code change · any new saga step or compensation · any Inventory write · any real GDS API call (stubs only) · any real EDIFACT/Type-B message · new events beyond `DistributionOrderCreated` · seller isolation breach (one seller seeing another's data) · money paths outside the existing Order saga.

## 10. Open Decisions (ratify before N2; N0/N1 may start in parallel)

1. ★ **`sellerRef` on order** — additive metadata field on Order. *Recommend: optional metadata, not a core field.*
2. ★ **NDC schema generation** — IATA NDC 21.3 field names; strict XSD later. *Recommend: pin 21.3.*
3. ★ **Seller API key storage** — hashed in `SellerProfile.apiKeyRef`. *Recommend: same pattern as PSP keys.*
4. **AirShopping cache** — no cache in this slice. *Recommend: add in N2+ if latency requires.*
5. **Demo collection format** — *Recommend: Postman.*
6. **`DistributionOrderCreated` dashboard beat** — *Recommend: follow-on.*

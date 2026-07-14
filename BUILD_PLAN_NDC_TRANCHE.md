# Phase 2 — Build Plan, NDC / B2B Distribution Tranche (Thin Seller API)

**Status:** Draft for ratification · Companion to: NDC_DELTA_0_3_THIN_SELLER_API.md
**Base documents:** PSS_System_Design_Document (§5.1 Distribution/NDC) · PSS_Offer_Module_LLD · PSS_Order_Module_LLD
**How to use:** Drop this file and the NDC delta into the repo / Claude Code project context. Work strictly milestone by milestone; a milestone is done only when its gate is green on CI. Do not start a milestone whose predecessor's gate is red.
**What this is:** the thin walking-skeleton slice of the Phase 2 Distribution/NDC module — the smallest end-to-end seller API path (AirShopping → OfferPrice → OrderCreate → OrderRetrieve) that proves an OTA or TMC can reach the platform through an NDC-conformant API.
**Excluded from this plan:** EDIFACT/GDS adapters, OrderChange/OrderCancel NDC messages, order-based interline, commission billing, B2B agent portal UI, seller SLA dashboards.

---

## Standing rules for this tranche

1. **Design governs.** Where code and the NDC delta disagree, stop and flag.
2. **Distribution is a translation layer — not a module rewrite.** No Offer or Order module code change. If one appears necessary, stop and flag.
3. **Two fitness rules (CI, from N1 onward):** no GDS-vendor symbol outside `GdsAdapter`/translator; no interline-vendor symbol outside `InterlineAdapter`/translator.
4. **Conformance suites are the swap contracts.** Both ports get provider-agnostic conformance suites in N1; real adapters (later tranches) must pass them unmodified.
5. **Seller isolation is invariant.** One seller must never see another seller's sessions, messages, or order data. Enforced by `sellerId` scoping on every query.
6. **No new money paths.** NDC OrderCreate calls the existing Order saga unchanged.
7. **Highest availability tier not required here.** Distribution is a commercial edge, not a flight-grounding path; ≥99.9% is the target.

## Milestones and gates

Milestones and gates are verbatim from NDC delta §8.

### N0 — Service skeleton + seller store + RegisterSeller *(start immediately)*
Tenant+seller-scoped `distribution-service`; seller store; `SellerProfile` entity; `DistributionMessage` audit log; transactional outbox wired. `RegisterSeller` and `ListSellers` management APIs.
**Gate:** `RegisterSeller POST /v1/sellers` creates a seller; `ListSellers GET /v1/sellers` returns it; outbox smoke (no-op event round-trips); runbook: service ingress + seller store documented.

### N1 — Ports + conformance suites (simulators only) *(parallel with N0)*
`GdsAdapterPort` (`submitAirShopping`, `submitOrderToGds`) and `InterlineAgreementPort` (`checkAgreement`, `notifyInterlineOrder`) semantics; both simulators; both conformance suites; fitness rules active.
**Gate:** both conformance suites green on simulators; `GdsUnavailable` and `NO_AGREEMENT` negative paths proven; no vendor symbols outside adapters.

### N2 — AirShopping + OfferPrice *(after N0 + N1)*
`POST /v1/ndc/air-shopping` → translates to `/v1/offers/shop` → translates response to NDC-shaped AirShoppingRS. `POST /v1/ndc/offer-price` → re-validates offer at selection time. `SellerSession`: SHOPPING → OFFER_SELECTED. `OFFER_EXPIRED` and `OFFER_NOT_FOUND` typed errors.
**Gate:** AirShopping returns NDC-shaped offers for the demo BLR→BOM flight; OfferPrice re-validates and returns NDC OfferPriceRS; `OFFER_EXPIRED` rejects a stale offer; `SellerSession` advances correctly; seller isolation test: seller A cannot retrieve seller B's session.

### N3 — OrderCreate + OrderRetrieve + DistributionOrderCreated *(after N2) — HEADLINE GATE*
`POST /v1/ndc/order-create` → translates to `POST /v1/orders` → translates OrderRS back to NDC shape. `GET /v1/ndc/orders/{orderRef}` → retrieves. `SellerSession` → ORDER_CREATED. `DistributionOrderCreated` published via transactional outbox.
**Gate:** full AirShopping → OfferPrice → OrderCreate → OrderRetrieve path green on CI against real services (GDS + interline stubbed); `DistributionOrderCreated` in outbox; `ORDER_CREATE_FAILED` typed error when inventory gone; idempotent OrderCreate (repeated call with same Idempotency-Key returns same order); **no Order or Offer code change in this PR** (stop-and-flag enforced by CI diff check).

### N4 — Multi-pax + demo Postman collection + scripted smoke *(after N3)*
2×ADT NDC booking end-to-end (both pax in AirShoppingRQ → both on OrderCreateRS); demo Postman collection drives the full AirShopping → OrderCreate scene; `MultiPaxNdcBookingIT` extends the IT base; scripted CI smoke unattended.
**Gate:** 2×ADT NDC OrderCreate confirmed in CI; demo collection runs green; smoke passes unattended; `v0.3.1-ndc-thin` tagged on master.

## Dependency picture

```
N0 (skeleton + seller store) ─┐
                              ├→ N2 (AirShopping + OfferPrice) → N3 (OrderCreate + headline gate) → N4 (multi-pax + smoke)
N1 (ports + conformance) ─────┘
```
N0 and N1 parallelise. Everything from N2 onward is strictly sequential.

## Integration with the rest of the platform

- **Upstream, read-only:** Offer (`/v1/offers/shop`, `/v1/offers/{id}`), Order (`POST /v1/orders`, `GET /v1/orders/{orderRef}`). No writes to either.
- **Downstream:** `DistributionOrderCreated` event on the existing backbone. The live dashboard *may* add a "NDC orders" beat as a further consumer — **recommended as a follow-on**, not part of this tranche.
- **Demo:** makes the NDC distribution scene real enough to show live — an OTA-simulated Postman collection books a flight, and the order appears in the carrier dashboard in real time.

## Stop-and-flag list

Offer or Order module code change · new saga step or compensation · Inventory write · real GDS API call · real EDIFACT message · new events beyond `DistributionOrderCreated` · seller isolation breach · money paths outside the existing Order saga · NDC schema XSD strict validation (conformance suite covers shapes; XSD is a later milestone).

## Open decisions (ratify before N2; N0/N1 may start in parallel)

From NDC delta §10: `sellerRef` on order (recommend optional metadata) · NDC schema generation (recommend pin 21.3) · seller API key storage (recommend same pattern as PSP keys) · AirShopping cache (recommend defer) · demo collection format (recommend Postman) · `DistributionOrderCreated` dashboard beat (recommend follow-on).

## Pre-build checklist (ratify before N2 starts; N0/N1 may begin in parallel)

1. Confirm the three ★ open decisions: `sellerRef` field, NDC schema generation to pin, seller API key storage pattern.
2. Confirm the demo intent: is the NDC scene for the next prospect demo, or a pure architecture proof? Changes whether N4's Postman collection is polish-grade or skeleton-grade.
3. Get an Offer and Order LLD-owner read of the NDC delta before N0 — the delta claims no changes to those modules; the owners should confirm.

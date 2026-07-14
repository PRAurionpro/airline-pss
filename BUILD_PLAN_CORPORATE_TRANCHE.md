# Phase 2 — Build Plan, Corporate / SME Booking Tranche (Thin Self-Booking)

**Status:** Draft for ratification · Companion to: CORPORATE_DELTA_0_3_THIN_BOOKING.md
**Base documents:** PSS_System_Design_Document (§4.1 Channel & Distribution, §5.4 B2B/Corporate/SME) · PSS_Offer_Module_LLD · PSS_Order_Module_LLD
**How to use:** Drop this file and the corporate delta into the repo / Claude Code project context. Work strictly milestone by milestone. A milestone is done only when its gate is green on CI.
**What this is:** The thin walking-skeleton slice of the Phase 2 Corporate/SME module — account onboarding, cost-centre tagging, travel policy enforcement, and self-booking through the existing NDC distribution surface. No agency portal UI, no BSP billing, no negotiated fares.

---

## Standing rules for this tranche

1. **Design governs.** Where code and the corporate delta disagree, stop and flag.
2. **Corporate is a policy + aggregation layer — not a module rewrite.** No Order, Offer, or distribution-service code change. If one appears necessary, stop and flag.
3. **Policy engine is a pure function.** `TravelPolicyPort.evaluate(offer, policy)` has no side effects; fully unit-testable without any Spring context.
4. **Triple-scope isolation.** Every query is `(tenantId, accountId)` or `(tenantId, accountId, travellerId)` scoped. Isolation is structural.
5. **`sellerRef` convention.** Corporate bookings tag orders via `sellerRef = "CORP-{accountId}-CC-{costCentreCode}"` — the field already exists on NDC OrderCreate (N3).
6. **No new money paths.** Corporate booking calls the existing distribution-service OrderCreate unchanged.
7. **Port 8085.** `corporate-service` runs on port 8085 following the established pattern (offer:8082, order:8081, payments:8080, distribution:8084, dcs:8083).

## Milestones and gates

### C0 — Service skeleton + account store + RegisterAccount *(start immediately)*
`corporate-service` module (pkg `pss.corporate`, port 8085) wired into the platform build. `CorporateAccount` entity + Flyway V1. `RegisterAccount POST /v1/corporate/accounts` and `GetAccount GET /v1/corporate/accounts/{id}`. `CorporateOutboxEvent` table. `DemoSecurityConfig`. OTel tracing dependencies.
**Gate:** RegisterAccount → 201; GetAccount returns it; `version=0`; outbox table exists; `RegisterCorporateAccountIT` passes (Testcontainers).

### C1 — Cost centres + travellers + travel policy + policy conformance *(after C0)*
`CostCentre`, `TravellerProfile`, `TravelPolicy` entities + Flyway V2. CRUD APIs for each. `TravelPolicyPort` interface + in-process implementation. `TravelPolicyConformanceSuite` (abstract, applied to `TravelPolicyConformanceTest`).
**Gate:** All three entity CRUD APIs pass; conformance suite green for all three rule types: fare-limit VIOLATION, cabin-class VIOLATION, advance-booking ADVISORY; `NO_ACTIVE_POLICY` error when no active policy exists.

### C2 — PolicyCompliantBooking (headline gate) *(after C1)*
`POST /v1/corporate/bookings` — full flow: load traveller + cost centre + policy → call distribution-service AirShopping → OfferPrice → run `PolicyEngine.evaluate` → if COMPLIANT/ADVISORY call distribution-service OrderCreate → persist `CorporateBooking` → publish `CorporateBookingCreated` via outbox. `GetBooking`, `ListBookings`. Typed errors: `POLICY_VIOLATION`, `ACCOUNT_SUSPENDED`, `TRAVELLER_NOT_FOUND`, `COST_CENTRE_NOT_FOUND`, `NO_ACTIVE_POLICY`.
**Gate:** COMPLIANT booking creates order tagged with correct cost centre and `sellerRef`; POLICY_VIOLATION blocks (fare over limit); ADVISORY proceeds with warnings (advance booking too short); `CorporateBookingCreated` in outbox; `PolicyCompliantBookingIT` full path passes (Testcontainers + WireMock for distribution-service).

### C3 — Billing statement stub + demo Postman collection + smoke *(after C2)*
`BillingStatement` entity + Flyway V3 + `GET /v1/corporate/accounts/{id}/statements/{id}`. Demo Postman collection: `docs/demo/corporate-booking-demo.postman_collection.json` — 5-step polish-grade collection (register company → set policy → add traveller → book compliant → see billing stub). `CI smoke`: `PolicyCompliantBookingIT` is the unattended smoke. Tag `v0.3.2-corporate-thin` on merge.
**Gate:** BillingStatement API returns aggregate view of bookings for an account+period; demo collection valid JSON and chaining works; smoke green; tag on master.

## Dependency picture

```
C0 (skeleton + account store)
    → C1 (cost centres + travellers + policy conformance)
        → C2 (PolicyCompliantBooking — headline gate)
            → C3 (billing stub + demo collection + smoke → v0.3.2-corporate-thin)
```

All milestones strictly sequential (each depends on the previous).

## Integration with the rest of the platform

- **Upstream, via distribution-service:** `POST /v1/ndc/air-shopping`, `POST /v1/ndc/offer-price`, `POST /v1/ndc/order-create` (WireMocked in tests, live in demo).
- **Corporate account = SellerProfile:** register via `POST /v1/sellers` in distribution-service (kind=DIRECT_API) so the account shares the existing seller auth path.
- **`sellerRef` tagging:** `CORP-{accountId}-CC-{costCentreCode}` passes through distribution-service → order-service as opaque metadata (already supported).
- **`CorporateBookingCreated`** event on the backbone — the carrier dashboard *may* add a "corporate bookings" beat as a follow-on.

## Stop-and-flag list

Any change to `order-service`, `offer-service`, or `distribution-service` · new saga steps · Inventory writes · real HR/ERP integration · BSP/ARC billing settlement · negotiated fare computation · full approval workflow with notifications · new events beyond `CorporateBookingCreated`.

## Open decisions (ratify before C2; C0/C1 can start in parallel)

From corporate delta §10:
1. ★ **`requiresApproval=true` flow** — this slice: ADVISORY with warnings, booking proceeds. Full approval workflow (email + approve/reject endpoint) deferred. Recommend: confirm.
2. ★ **Negotiated corporate fares** — use standard fares in this slice; Pricing module hook deferred. Recommend: confirm defer.
3. ★ **Corporate account = SellerProfile** — register corporate account as `SellerProfile` (kind=DIRECT_API) in distribution-service. Recommend: confirm.

## Pre-build checklist (before C2 starts; C0/C1 can begin immediately)

1. Ratify three ★ open decisions above.
2. Confirm demo intent: is the C3 Postman collection for a next prospect demo (polish-grade) or architecture proof?
3. Confirm port 8085 is not in use in the local dev environment.

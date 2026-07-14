# Corporate / SME Booking Delta 0.3 — Thin Corporate Self-Booking (Policy + Cost Centre + Consolidated Billing)

**Status:** Draft for ratification · Companion to: BUILD_PLAN_CORPORATE_TRANCHE.md
**Base documents:** PSS_System_Design_Document (§4.1 Channel & Distribution layer, §5.4 Sales channels, B2B/Corporate/SME) · PSS_Offer_Module_LLD · PSS_Order_Module_LLD · NDC_DELTA_0_3_THIN_SELLER_API.md
**Phase note:** Corporate/SME is a **Phase 2** module in the SDD catalogue. This delta builds the *thin walking-skeleton slice only*: a corporate account store, a travel policy engine, cost-centre tagging on orders, and self-booking through the existing NDC distribution surface. A full agency portal with agent login, BSP billing, and a dedicated booking UI is deferred to later slices.
**Architecture position:** Sits in the Channel & Distribution layer, above the NDC distribution surface (N0–N4). A corporate booker is a special kind of seller — they book through the NDC API but with policy enforcement and cost-centre tagging applied transparently.

---

## 1. Scope and Non-Goals

The smallest slice that proves a corporate travel manager can onboard their company, define a travel policy, and book a compliant flight that lands in the carrier's system tagged with the correct cost centre — without any changes to the Order or Offer modules.

The slice: **CorporateAccount onboarding → TravelPolicy create → PolicyCompliantBooking (shop → price → policy check → book) → CostCentreTaggedOrder → ConsolidatedBillingStatement (stub)**.

**Non-goals (deferred without guilt):**
Full agency portal UI · agent login and agent-level RBAC · BSP/ARC billing settlement · markup and commission rules · pre-approval workflow (email/notification integration) · negotiated corporate fares (requires Pricing module integration — pricing module hook only) · company credit account and deposit limits · multi-level policy hierarchy (department / division / employee grade) · duty-of-care and traveller tracking · self-service company admin portal · deep reporting by cost centre · integration with corporate HR/ERP systems.

**Design invariants (unchanged from NDC tranche):**
- **No changes to Order or Offer module internals.** Corporate booking goes through the same NDC translation layer; policy enforcement is a gate in `corporate-service`, not a hook inside `order-service`.
- **Tenant + corporate + traveller triple-scope.** Every request carries the airline tenant, the corporate account, and the traveller. The core modules see only the tenant.
- **Money and inventory invariants unchanged.** A corporate order is still a normal order; the corporate layer adds metadata (cost centre, policy ref, approval status) and a billing aggregation view.
- **Contract changes are additive only.** No existing endpoint is modified; corporate endpoints are net-new surface.

## 2. Module Posture — New Bounded Context

`corporate-service` is a new service, tenant+corporate-scoped, sitting alongside `distribution-service`. It:

- **Owns** corporate account data: `CorporateAccount`, `CostCentre`, `TravelPolicy`, `TravellerProfile`, `CorporateBooking`, `BillingStatement`.
- **Enforces** policy at booking time: validates the itinerary against the account's `TravelPolicy` before calling the NDC OrderCreate. Violations are either hard-blocked (`POLICY_VIOLATION`) or soft-flagged (`POLICY_ADVISORY`) depending on the policy rule.
- **Tags** every order with `corporateAccountId` + `costCentreCode` via the `sellerRef` metadata field already on NDC OrderCreate (using the convention established in N3).
- **Aggregates** booking data into a `BillingStatement` per account per period (stub in this slice — the data model is real, the settlement integration is deferred).
- **Publishes** `CorporateBookingCreated` via the transactional outbox.

**Crucially:** `corporate-service` never holds offer or order state — it holds the corporate account/policy/traveller data and a billing view over bookings it brokered.

## 3. Corporate Entities

```
CorporateAccount  { accountId, tenantId, companyName, status {ACTIVE│SUSPENDED},
                    billingEmail, createdAt }

CostCentre        { costCentreId, accountId, tenantId, code, name, budgetLimit (nullable),
                    active }

TravelPolicy      { policyId, accountId, tenantId, name, maxFareAmount, cabinClass
                    {ECONOMY│BUSINESS│ANY}, advanceBookingDays (nullable),
                    requiresApproval, active }

TravellerProfile  { travellerId, accountId, tenantId, employeeId, givenName, surname,
                    email, costCentreId (nullable) }

CorporateBooking  { bookingId, accountId, tenantId, travellerId, costCentreId,
                    policyId, orderRef, offerId, totalAmount, currency, cabinClass,
                    advanceBookingDays, policyStatus {COMPLIANT│ADVISORY│VIOLATION},
                    sellerRef, createdAt }

BillingStatement  { statementId, accountId, tenantId, periodStart, periodEnd,
                    totalAmount, currency, status {OPEN│CLOSED│SETTLED},
                    bookingCount, generatedAt }

CorporateOutboxEvent { eventId, accountId, tenantId, eventType, payload, occurredAt }
```

## 4. Travel Policy Engine

The policy engine is a pure function — given an offer and a policy, it returns a `PolicyDecision`. No side effects, fully unit-testable.

```kotlin
sealed class PolicyDecision {
    data class Compliant(val ref: String) : PolicyDecision()
    data class Advisory(val warnings: List<PolicyWarning>) : PolicyDecision()
    data class Violation(val violations: List<PolicyViolation>) : PolicyDecision()
}

data class PolicyViolation(val rule: String, val actual: String, val allowed: String)
data class PolicyWarning(val rule: String, val message: String)
```

**Rules evaluated (this slice):**
- `maxFareAmount` — total offer price ≤ policy limit (VIOLATION if exceeded and `requiresApproval=false`; ADVISORY if `requiresApproval=true`)
- `cabinClass` — booked cabin within allowed cabins (VIOLATION)
- `advanceBookingDays` — departure date ≥ today + policy minimum days (ADVISORY)

**Extensibility:** the engine is a port — `TravelPolicyPort` — so future rules (preferred carrier, destination restrictions, grade-based limits) slot in behind the same interface without touching the booking flow.

## 5. API Contract (Corporate, net-new)

All tenant-scoped. Corporate admin APIs require an `X-Corporate-Admin: true` header (demo posture — full RBAC in a later slice).

| Operation | Method / path | Purpose |
|-----------|--------------|---------|
| RegisterAccount | `POST /v1/corporate/accounts` | Onboard a corporate account |
| GetAccount | `GET /v1/corporate/accounts/{accountId}` | Retrieve account |
| CreateCostCentre | `POST /v1/corporate/accounts/{accountId}/cost-centres` | Add a cost centre |
| CreatePolicy | `POST /v1/corporate/accounts/{accountId}/policies` | Define a travel policy |
| RegisterTraveller | `POST /v1/corporate/accounts/{accountId}/travellers` | Add a traveller |
| PolicyCompliantBooking | `POST /v1/corporate/bookings` | Shop → policy check → book (main flow) |
| GetBooking | `GET /v1/corporate/bookings/{bookingId}` | Retrieve booking + policy status |
| ListBookings | `GET /v1/corporate/accounts/{accountId}/bookings` | Account booking history |
| GetBillingStatement | `GET /v1/corporate/accounts/{accountId}/statements/{statementId}` | Billing view (stub) |

**`POST /v1/corporate/bookings` — the main flow:**
Request:
```json
{
  "accountId": "...",
  "travellerId": "...",
  "costCentreId": "...",
  "itinerary": {
    "origin": "BLR", "destination": "BOM", "departureDate": "2026-08-15"
  },
  "cabinClass": "ECONOMY",
  "passengerCount": 1
}
```

Internally:
1. Load `TravellerProfile` + `CostCentre` + `TravelPolicy` (active policy for account)
2. Call distribution-service AirShopping → OfferPrice (internal HTTP)
3. Run `PolicyEngine.evaluate(offer, policy)` → `PolicyDecision`
4. If `Violation` and `requiresApproval=false` → return `POLICY_VIOLATION` typed error
5. If `Advisory` or `Compliant` → call distribution-service OrderCreate with `sellerRef = "CORP-{accountId}-CC-{costCentreCode}"`
6. Persist `CorporateBooking` with `policyStatus`
7. Publish `CorporateBookingCreated` via outbox

Response:
```json
{
  "bookingId": "...",
  "orderRef": "ORD-...",
  "policyStatus": "COMPLIANT",
  "costCentreCode": "ENG-001",
  "totalAmount": 5499.00,
  "currency": "INR",
  "warnings": []
}
```

**Typed errors:**
- `ACCOUNT_NOT_FOUND` — unknown `accountId`
- `ACCOUNT_SUSPENDED` — account not ACTIVE
- `TRAVELLER_NOT_FOUND` — traveller not found or not belonging to account
- `COST_CENTRE_NOT_FOUND` — cost centre not found
- `NO_ACTIVE_POLICY` — account has no active `TravelPolicy`
- `POLICY_VIOLATION` — hard policy breach (fare limit exceeded, wrong cabin)
- `OFFER_NOT_AVAILABLE` — no suitable offer found for the itinerary

## 6. The Policy Port

```kotlin
interface TravelPolicyPort {
    fun evaluate(offer: OfferSummary, policy: TravelPolicy): PolicyDecision
}
```

Implemented in-process (not a remote port — policy evaluation is a local computation). But the interface makes it swappable for future rule engines (Drools, OPA, etc.) without changing the booking flow. A `TravelPolicyPortConformanceSuite` tests the three rule types: fare limit, cabin class, advance booking.

## 7. What the Existing Modules See — Zero Internal Changes

Corporate bookings flow through `distribution-service` (NDC layer) to `offer-service` and `order-service`. The only coupling point is:
- `sellerRef` on NDC OrderCreate — already supported (N3)
- The corporate account is registered as a `SellerProfile` in `distribution-service` with `kind=DIRECT_API`

**If any change to `order-service`, `offer-service`, or `distribution-service` internals appears necessary, stop and flag.**

## 8. Demo Surfacing

The thin slice enables a new live demo scene: a travel manager onboards their company, sets a fare cap of ₹6,000, books a BLR→BOM flight that comes in at ₹5,499 (COMPLIANT), and the order appears in the carrier dashboard tagged with cost centre `ENG-001`. Then books again exceeding the cap (POLICY_VIOLATION) — the system blocks it. Clean, legible, prospect-grade story.

## 9. Milestones and Gates

- **C0 — Service skeleton + account store + RegisterAccount.** `corporate-service` module (pkg `pss.corporate`, port 8085), account store, Flyway V1, management APIs. **Gate:** RegisterAccount → 201; account store queryable; outbox smoke.
- **C1 — Cost centres + travellers + policy.** `CostCentre`, `TravellerProfile`, `TravelPolicy` entities + APIs. Policy engine (`TravelPolicyPort`) + conformance suite. **Gate:** all three entities CRUD; policy conformance suite green (fare limit, cabin class, advance booking).
- **C2 — PolicyCompliantBooking (headline gate).** Full booking flow: shop → policy check → book. `CorporateBooking` persisted, `CorporateBookingCreated` published. **Gate:** COMPLIANT booking creates order tagged with cost centre; POLICY_VIOLATION blocks; ADVISORY advisory surface; `CorporateBookingCreated` in outbox.
- **C3 — Billing statement stub + demo Postman collection + smoke.** `BillingStatement` aggregate view; `GET /v1/corporate/accounts/{id}/statements/{id}`; demo Postman collection (5-step: register → policy → book → verify → billing); CI smoke unattended. **Tag:** `v0.3.2-corporate-thin`.

## 10. Open Decisions (ratify before C2)

1. ★ **`requiresApproval=true` flow** — this slice: ADVISORY with warnings returned, booking proceeds. Recommend: full approval workflow (email notification + approve/reject endpoint) is a later slice.
2. ★ **Negotiated corporate fares** — requires `pricing-service` to accept a `corporateAccountId` discount context. Recommend: defer, use standard fares in this slice. Flag if Pricing LLD has a hook.
3. ★ **Corporate account = SellerProfile** — register the corporate account as a `SellerProfile` (kind=DIRECT_API) in distribution-service, so it reuses the existing seller auth path. Recommend: yes.

## 11. Stop-and-Flag List

Any change to `order-service`, `offer-service`, or `distribution-service` internals · any new money paths outside the existing Order saga · any real HR/ERP integration · any BSP/ARC billing settlement · negotiated fare computation · full approval workflow with notifications.

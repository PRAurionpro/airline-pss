# Channel BFF (IBE) — API contract

Contract: [`contracts/bff-api_openapi.yaml`](contracts/bff-api_openapi.yaml) (OpenAPI 3.0.3).

## Purpose & posture

channel-bff is the **browser-facing Backend-for-Frontend for the Internet Booking Engine**
(IBE). It is a thin, stateless, **mapping-only** layer: **no owned domain, no database, no
money math, no saga, no events**. It resolves the tenant from the verified bearer token
(paved road), assembles/relays requests to Offer, Order, Payments and Inventory over
in-cluster HTTP, and returns their responses.

> **Posture note.** This contract documents a BFF surface, not a stable domain-service API.
> Three endpoints relay the Offer response **verbatim** as opaque JSON (`OfferPassthrough`) —
> the authoritative shape for those is Offer's own contract. The only non-mapping logic in
> the whole service is in-scope seat-geometry validation against static cabin config. Money
> is never computed here; decimal totals pass through untouched, and confirmation is rendered
> from the order's authoritative state, never the client's payment callback.

## Endpoints

| Method | Path | Purpose | Response |
|---|---|---|---|
| POST | `/checkout/sessions` | Assemble a hosted-checkout session for an order | `CheckoutSession` |
| GET | `/checkout/sessions/{orderId}/state` | Poll the IBE render/confirmation state | `ConfirmationState` |
| GET | `/bff/shop` | Stage-1 shop (proxy to Offer shop) | Offer response, verbatim |
| POST | `/bff/offers/combine` | Stage-2 combine 1–2 candidate offers | Offer response, verbatim |
| GET | `/bff/flights/{flightId}/seatmap` | Assemble a seat map (typed) | `SeatmapResponse` |
| POST | `/bff/offers/{offerId}/ancillaries` | Validate seat/bag picks, augment the offer | Augmented offer, verbatim |

## Idempotency & writes

The BFF opens **no payment window** and performs **no money-domain writes** — it reads the
order's already-open intent. The only writes are Offer creations (combine / augment), and the
BFF mints a **deterministic** `Idempotency-Key` on those **downstream** calls
(`combine-<ids>`, `augment-<offerId>-<sel>`). The **client-facing** endpoints here take no
Idempotency-Key.

## Events

**None.** No messaging dependency; no producer, consumer, or outbox anywhere in the module.

## Tenant & security

Tenant comes from the verified bearer token (`tenantId` claim, fallback `tid`); the BFF is a
claims reader (signatures verified upstream). No tenant path/query parameter, no session, no
cookie. The BFF does **not** forward the caller token downstream — each downstream client
mints its own minimal tenant token.

## Errors

RFC 9457 problem+json (`spring.mvc.problemdetails`). Two BFF problem codes:
`BFF_BAD_REQUEST` (400 — invalid tripType, missing returnDate for RETURN, adults out of
range, wrong candidate count) and `INVALID_SEAT_SELECTION` (422 — seat not in cabin grid,
>1 seat per pax/segment, bag count outside 0..2, empty selection). Upstream Offer errors are
relayed with their **original status + problem+json body unchanged** so the IBE can re-shop.

## Deviations

Documented as-merged; carried items, not normalised away in the spec.

1. **Not OpenAPI-generated.** The BFF is hand-written; three endpoints return opaque
   `JsonNode` passthroughs with no typed BFF response schema (modelled here as the free-form
   `OfferPassthrough`).
2. **No shared `@ControllerAdvice`.** Error mapping is per-controller `@ExceptionHandler`
   (Shop + Seatmap); the **Checkout controller has none** — a missing payment window / tenant
   surfaces as a framework **500** (modelled as the `default` response).
3. **Self-minted `alg:none` tenant token** for outbound calls, duplicated across four
   downstream clients (a DRY deviation), rather than propagating the caller token.
4. **Direct in-cluster service URLs, bypassing the API gateway.**
5. **Does not open its own payment window** — reads the order's existing intent instead of
   the literal build-plan "BFF calls POST /v1/payments" step (design-approved A5 deviation).
6. **IBE polling logic lives in the BFF** (`ConfirmationStateService`), tested as a BFF unit.
7. **Static cabin geometry as config-over-code**; the BFF owns the seat-bounds check (the one
   in-scope non-mapping validation).
8. **No datasource / persistence / messaging** — intentional (stateless), a deviation from
   the standard service template.
9. **Provider-neutral by mandate** — no PSP-specific symbols (fitness #6).

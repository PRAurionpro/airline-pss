# Tranche-1 Delta Contracts — Ancillaries & Dashboard

Contract-first artifacts realising **ANCILLARIES_DELTA_0.2_SEAT_BAG.md** and
**DASHBOARD_DELTA_0.2_LIVE_READSIDE.md** (per BUILD_PLAN_TRANCHE_1_ADDENDUM_1.md).
Drop alongside `order-api.openapi.yaml` / `order-events.schema.json` in the repo /
Claude Code project context so D- and E-milestone code is generated against a
validated contract, not prose.

## Files

| File | Realises | Validated with |
|------|----------|----------------|
| `ancillary-api.openapi.yaml` | PriceAncillaries (D1) · AugmentOffer (D2) · seat-occupancy read (D3) · `SeatClaim` hold-line extension as a reference component (D3) | `openapi-spec-validator` — PASS |
| `shop-performed.schema.json` | `ShopPerformed` telemetry event (E1) | `jsonschema` Draft 2020-12 `check_schema` + instance tests — PASS |

## Conventions carried from the Order/Payments specs

- **Tenant from token** — no tenant path/query parameters anywhere.
- **Idempotency-Key** required on AugmentOffer (creates a new offer); PriceAncillaries
  is a pure function and naturally idempotent — no key.
- **Errors** — RFC 9457 `application/problem+json` with stable `code`s:
  `ANCILLARY_INVALID_SELECTION`, `OFFER_EXPIRED`, `SEAT_UNAVAILABLE`
  (carries `conflictingSeats`), `ANCILLARY_REF_MISMATCH` (Order-side, listed for
  completeness).
- **Consumers idempotent on `eventId`** — applies to ShopPerformed too, with the
  extra telemetry-grade caveat below.

## Things the build must respect (encoded in the specs, worth restating)

1. **ShopPerformed is telemetry-grade.** Best-effort publish from a storeless module:
   at-most-once. Funnel-top figures are directional; **no money figure may derive from
   this event** (dashboard stop-and-flag list).
2. **SeatClaim is an additive field on the existing HoldInventory request**, not a new
   endpoint or lifecycle. Claims live and die with the hold, in the same transaction.
3. **The air quote is never re-derived by AugmentOffer** — `quoteRef` passes through
   unchanged; `ancillaryQuoteRef` is additive; `grandTotal` = Σ items (property-tested).
4. **Seat-occupancy read is eventually consistent** and says so on the wire (`asOf`
   watermark). Authoritative uniqueness is at hold time only.
5. `order-events.schema.json` already admits `ANCILLARY` in `OrderItemType` —
   **no change to the Order event schema is required** for Workstream D. The B4/D4
   schema CI check just gains ancillary-bearing payload fixtures.

## Merge targets (when module specs are drawn up)

`/v1/pricing/ancillaries/quote` → pricing-api · `/v1/offers/{offerId}/ancillaries` →
offer-api · `/v1/inventory/flights/{flightId}/seats` + `SeatClaim` → inventory-api ·
`shop-performed.schema.json` → offer events (or a platform telemetry schema set, Open
Decision for the Reporting & BI design).

## Regenerating / validating

```bash
pip install --break-system-packages openapi-spec-validator jsonschema
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('ancillary-api.openapi.yaml')))"
python -c "import json; from jsonschema.validators import Draft202012Validator as D; D.check_schema(json.load(open('shop-performed.schema.json')))"
```

*Working draft — companion to the two 0.2 deltas, Build Plan Tranche 1 + Addendum 1, and DEMO_SCRIPT.md.*

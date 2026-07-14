# Loyalty Module — API & Event Contracts

Contract-first artifacts for the Loyalty/FFP module of the Aurionpro PSS platform.
These realise **LOYALTY_DELTA_0_3_EARN_REDEEM.md** — `loyalty-api_openapi.yaml` covers
the delta §7 (API) and `loyalty-events_schema.json` covers the delta §8 (published events).
Drop this folder into the service repository / Claude Code project context so generated
code stays aligned to the contract.

## Files

| File | What it is | Validated with |
|------|------------|----------------|
| `loyalty-api_openapi.yaml` | OpenAPI 3.0.3 spec for the Loyalty API (Enrol / GetMember / GetBalance / GetTransactions / GetStatement / ManualEarn / ListRedemptionOptions / Redeem / GetRedemption). | `openapi-spec-validator` — PASS |
| `loyalty-events_schema.json` | JSON Schema (draft 2020-12) for the four published domain events. | `jsonschema` Draft202012 `check_schema` + 8 instance tests — PASS |

## API conventions (carried from the platform)

- **Tenant from token** — tenant context comes from a verified claim in the OAuth2/OIDC bearer token; there is no tenant path/query parameter.
- **Idempotency** — every state-changing call (`Enrol`, `ManualEarn`, `Redeem`) requires an `Idempotency-Key` header; a repeated key returns the original result rather than acting twice.
- **Errors** — `application/problem+json` (RFC 9457) with a stable machine-readable `code`.
- **Append-only ledger** — `pointsBalance` and `qualifyingMiles` on `BalanceView` are projections over `PointsTransaction`; there is no balance-mutation endpoint.
- **Admin seam** — `ManualEarn` requires `X-Loyalty-Admin: true` (demo posture; full RBAC later). In production, accrual flows from `OrderConfirmed`, not this endpoint.

## Typed error codes

| Code | HTTP | When |
|------|------|------|
| `DUPLICATE_ENROLMENT` | 409 | A member with this `externalRef` already exists for the tenant. |
| `MEMBER_NOT_FOUND` | 404 | Member id not visible to the tenant. |
| `MEMBER_SUSPENDED` | 422 | Member is SUSPENDED; earn and redeem are blocked. |
| `NO_ACTIVE_EARN_RULE` | 422 | No active `EarnRule` for the tenant at accrual time. |
| `INSUFFICIENT_POINTS` | 422 | Redeem requested but balance < option cost. **No debit occurs.** |
| `REDEMPTION_OPTION_NOT_FOUND` | 422 | `optionId` not found or not active. |
| `REDEMPTION_NOT_FOUND` | 404 | Redemption id not visible to the tenant. |

## Events

Four events, each a self-contained object discriminated by the `type` const. Consumers
MUST be idempotent on `eventId`. All carry `tenantId`, `accountId`, `memberId`, `occurredAt`,
`correlationId`, `causationId` in the envelope.

| Event | Published by | Effect / meaning |
|-------|-------------|------------------|
| `MilesAccrued` | accrual consumer (`OrderConfirmed`) / `ManualEarn` | Points + qualifying miles credited; `newBalance` / `newTier` are post-state. |
| `MilesReversed` | accrual consumer (`OrderCancelled`) | Prior accrual removed; `points` is negative; tier may step down. |
| `MilesRedeemed` | `Redeem` | Points debited; `voucherRef` issued via the fulfilment port. |
| `TierChanged` | accrual / reversal | Recognised tier moved (up on accrual, down on reversal). |

**Consumed (not defined here):** `OrderConfirmed` → accrue, `OrderCancelled` → reverse,
from `order-events_schema.json`. Idempotency is on the order event's `eventId`, enforced by
a unique `dedupeKey` on the ledger.

## Things the build must respect (encoded in the specs, worth restating)

1. **Loyalty is a side-ledger + event consumer (delta §2).** Accrual reacts to Order events; it never calls `order-service`, `pricing-service`, `offer-service`, or `payments-service` write paths. Any such call is a stop-and-flag.
2. **`OrderConfirmed` is a thin event (resolved against `order-events_schema.json`).** It carries `amountPaid` + `documentRefs` only — no `passengerRef`, no currency, no base-fare split. Member resolution and `BASE_FARE` basis require a **read-only** `GET /v1/orders/{orderId}` read-back (delta §4). A read, never a write.
3. **The ledger is append-only.** Balance and tier are projections. Accrual dedupes on the order event's `eventId`; a redelivered event is a no-op. This is the L1/L2 idempotency invariant.
4. **Redemption is balance-checked before debit.** `INSUFFICIENT_POINTS` (422) leaves the ledger untouched. The concurrent double-spend guard is a conditional ledger append (balance check + insert in one transaction); `Idempotency-Key` dedups identical retries.
5. **Redemption fulfilment is a stub in this slice.** `RedemptionFulfilmentPort` returns a `voucherRef`; real "pay with miles" wiring into Pricing/Payments is deferred. `fulfilmentRef` is the port's reference.
6. **No new money paths.** Points are a loyalty-internal unit; redemption moves no cash.

## L0/L1 gate contract

These files are the L0 + L1 artefacts:
- **L0 gate** — `Enrol` opens a `LoyaltyAccount` at zero balance, BASE tier; the entity + outbox smoke use the `MemberSummary` / `BalanceView` schemas from this spec; `DUPLICATE_ENROLMENT` proven.
- **L1 gate** — `ManualEarn` + the `EarnRulePort` / `TierPolicyPort` conformance suites reference the typed error codes (`NO_ACTIVE_EARN_RULE`) and the `Tier` / `EarnBasis` enums defined here; the `loyalty-events_schema.json` `MilesAccrued` + `TierChanged` fixtures back the conformance assertions.

## Regenerating / validating

```bash
pip install --break-system-packages openapi-spec-validator jsonschema
python -c "from openapi_spec_validator import validate; import yaml; validate(yaml.safe_load(open('loyalty-api_openapi.yaml')))"
python -c "import json; from jsonschema.validators import Draft202012Validator as D; D.check_schema(json.load(open('loyalty-events_schema.json')))"
```

*Working draft — companion to LOYALTY_DELTA_0_3_EARN_REDEEM.md and BUILD_PLAN_LOYALTY_TRANCHE.md.*

# LATEST_STATUS.md — drop-in update (session 19)

Splice the blocks below into `LATEST_STATUS.md`. Each block says where it goes.
This session **scaffolded** the Loyalty tranche (design + build plan + L0/L1 contracts,
all validated). No service code, no PR, no tag yet.

---

## ① Replace the `## Last updated` block

```markdown
## Last updated
**Date:** 28 June 2026 (session 19)
**Session summary:** **Loyalty / FFP tranche kicked off (scaffolding only — no code yet).** Authored and ratified-for-review the design delta (`LOYALTY_DELTA_0_3_EARN_REDEEM.md`) and build plan (`BUILD_PLAN_LOYALTY_TRANCHE.md`, milestones L0–L4), plus the L0/L1 gate contracts: `loyalty-api_openapi.yaml` (OpenAPI 3.0.3 — validates PASS), `loyalty-events_schema.json` (Draft 2020-12 — validates PASS + 8/8 instance fixtures), `README_LOYALTY_CONTRACTS.md`. Loyalty is designed as a **downstream side-ledger + event consumer** (accrual rides `OrderConfirmed`/`OrderCancelled`; redemption issues a voucher behind `RedemptionFulfilmentPort`) — **zero core-module changes**, same discipline as Corporate/DCS/NDC. Service: `loyalty-service`, pkg `pss.loyalty`, **port 8086**. Target tag: `v0.3.3-loyalty-thin`. **Key finding (resolved):** `OrderConfirmed` carries only `amountPaid` + `documentRefs` — no pax ref, currency, or base-fare split — so accrual requires a read-only `GET /v1/orders/{orderId}` read-back for member resolution + basis; L2 WireMocks that read. No Order contract change requested.

> **Prior session (28 June, session 18):** Corporate tranche complete. `v0.3.2-corporate-thin` tagged (merge `3a1192c`). C3 merged PR #53.
> **Prior session (28 June, session 17):** C2 merged PR #52 — corporate headline gate closed.
> **Prior session (28 June, session 16):** C1 merged PR #51.
> **Prior session (28 June, session 15):** C0 merged PR #50.
> **Prior session (27 June, session 13):** NDC tranche complete. `v0.3.1-ndc-thin` tagged.
> **Prior session (24 June):** Tranche 1 complete. Published `v0.2.0-tranche1`.
```

---

## ② Add to the `## Current phase` section (after the Corporate block)

```markdown
**Phase 2 — Loyalty / FFP Tranche (thin earn + redeem): SCAFFOLDED — build not started**
Target tag: `v0.3.3-loyalty-thin` (not yet tagged — no code)
Authoritative docs: `LOYALTY_DELTA_0_3_EARN_REDEEM.md` · `BUILD_PLAN_LOYALTY_TRANCHE.md`
Contracts (L0/L1 gate artefacts, validated): `loyalty-api_openapi.yaml` · `loyalty-events_schema.json` · `README_LOYALTY_CONTRACTS.md`

### Loyalty tranche milestone status
| Milestone | Description | Status |
|-----------|-------------|--------|
| L0 | Service skeleton + member store + Enrol | ⬜ Not started — contracts ready |
| L1 | Points ledger + earn rule + tier engine + conformance | ⬜ Not started |
| L2 | Accrual consumer — OrderConfirmed → miles (headline gate) | ⬜ Not started |
| L3 | Redemption + fulfilment port (stub voucher) | ⬜ Not started |
| L4 | Statement + tier surfacing + demo Postman + smoke → tag | ⬜ Not started |
```

---

## ③ Replace the `## Next steps` section

```markdown
## Next steps — what to work on next session

**Loyalty tranche scaffolded; ready to build L0.** Four ★ decisions to ratify before L2 (L0/L1 can start in parallel).

1. **Ratify the four ★ loyalty open decisions** (delta §10 / build plan): accrual trigger (booking-time vs flown), earn basis (BASE_FARE vs TOTAL), redemption fulfilment (voucher stub vs real Pricing/Payments — defer), tier qualification window (cumulative vs reset).
2. **Build L0** — `loyalty-service` skeleton (pkg `pss.loyalty`, port 8086), `Member` + `LoyaltyAccount` + Flyway V1, Enrol + GetMember, outbox table. Gate: Enrol → 201, `DUPLICATE_ENROLMENT`, `EnrolMemberIT`.
3. **Then L1 → L2** — ledger + engines + conformance, then the accrual consumer. **L2 WireMocks `GET /v1/orders/{orderId}`** (read-back resolved this session). L2 is the headline gate.
4. **Confirm port 8086 free** and the demo tenant's tier thresholds (SILVER/GOLD qualifying-mile values).

**Two other Phase 2 candidates still open:** WhatsApp / conversational commerce (highest demo impact, most external deps), Reporting & BI (analytics over the live event streams).

**One technical note from the NDC tranche:** payment intent at OrderCreate — currently `payment=null`. Needs a payment step before a live NDC prospect demo.
**One low-priority follow-up from the DCS tranche:** remove `X-Tenant-Id` from `dcs-api_openapi.yaml`.
**Carried from Tranche 1 (verification tasks):** re-validate dashboard P99 on the real demo cluster before any live demo; confirm the #35 drainer SLO alert is correctly wired.
```

---

## ④ Add these rows to the `## Key documents in this project` table

```markdown
| `LOYALTY_DELTA_0_3_EARN_REDEEM.md` | **NEW** — Loyalty/FFP thin-slice design delta (side-ledger + event consumer; ports stubbed) |
| `BUILD_PLAN_LOYALTY_TRANCHE.md` | **NEW** — Loyalty thin-slice milestones + gates (L0–L5… L0–L4) |
| `loyalty-api_openapi.yaml` | **NEW** — Loyalty API contract (OpenAPI 3.0.3, validated) — L0/L1 gate artefact |
| `loyalty-events_schema.json` | **NEW** — Loyalty domain events schema (JSON Schema Draft 2020-12, validated) — L1 gate artefact |
| `README_LOYALTY_CONTRACTS.md` | **NEW** — Loyalty contracts README (typed error codes, event table, validation instructions) |
```

---

## ⑤ Note for the Kotlin implementation section

No `loyalty-service/` skeleton was produced this session — scaffolding is docs + contracts only.
The L0 skeleton (Kotlin/Spring Boot, port 8086) is the next build step, following the same
drop-into-`services/` pattern recorded for `dcs-service/`.

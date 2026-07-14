# Paper 5 — Carried-items triage sheet

**Triage sheet.** Every carried open item from `LATEST_STATUS.md` "Carried open items", one row
each, with the **Triage** column left blank. The deciders put one of two values per row:
`must-close-before-Phase-3` or `rides-along`. Each row carries the single fact that most informs
the call. The three deviation patterns from Paper 3 are folded in as rows.

No cell is pre-filled.

## Decisions (ratify before the relevant milestone)

| Item | Informing fact | Cross-ref | Triage (empty) |
|---|---|---|---|
| Capture mode for production | Auto-capture is the demo default; manual two-phase is the alternative. Only bites once real (pilot) traffic runs. | — | |
| Customer-clock margin | 60 s provisional; needs ratifying against **real** sandbox latencies with Inventory (not yet measured on a real cluster). | — | |
| Drainer cadence | 5 m provisional (`refund-drainer.cadence-ms:300000`); a config value, no code change to confirm. | — | |
| Deterministic refund-key formula | Choice is per-`paymentIntentId`+cause vs per-saga-step-id; belongs in the next payments-tranche spec, not standalone. | — | |
| Ancillaries — return seat rehearsal / occupancy staleness | Seat+bag ancillaries already shipped (D4/D5); this is a hardening rehearsal, not a missing feature. | — | |
| Dashboard — rebuild-by-replay / ticker / gross-vs-net | Dashboard is read-side and rebuildable from the event log by design; these are presentation/ops choices. | — | |

## Verification tasks (not decisions)

| Item | Informing fact | Cross-ref | Triage (empty) |
|---|---|---|---|
| Dashboard P99 on real demo cluster | SLO (≤ 2000 ms) already **met** on CI Docker (P99 = 1,095 ms); only a real-cluster re-measure is outstanding. | Paper 4 (dashboard row) | |
| NDC `payment=null` at OrderCreate | `CreateOrderPayload` carries no payment field; a payment step is needed before a **live NDC prospect demo** (not before internal Phase-3 work). | Paper 4 (NDC row) | |
| DCS `X-Tenant-Id` (external contract) | Code-verified **not load-bearing** (dcs never reads it); nothing to change in-repo — only the external project-copy contract. Zero in-repo risk. | — | |

## Backfill findings (documented; code authoritative)

| Item | Informing fact | Cross-ref | Triage (empty) |
|---|---|---|---|
| RM → Inventory feed half-built | Feed 404s against the real inventory service; closing it is a **cross-service identity design decision**, not plumbing. | **ADR-0002** | |
| Conversation → DCS Idempotency-Key | Caller side **resolved** (deterministic stable key, session 23). Residual = DCS *requires but ignores* the key. | Paper 3 · Pattern 3 | |
| `check_contract_presence` exemption list | **CLOSED** (session 24): all 15 services now ship contracts; exemption list empty. No action — listed for completeness. | Paper 4 | |

## Deviation patterns (folded in from Paper 3)

| Item | Informing fact | Cross-ref | Triage (empty) |
|---|---|---|---|
| Header-asserted identity/authorisation | `X-Seller-Id` (distribution NDC) + `X-*-Admin` (corporate/conversation/loyalty) assert seller/admin out-of-band instead of via token claims. | Paper 3 · Pattern 1 | |
| Error-format split | dcs/distribution/corporate emit plain `{status,code,detail}`; the other 12 services emit RFC 9457 problem+json. | Paper 3 · Pattern 2 | |
| Required-but-ignored Idempotency-Key (DCS) | DCS declares the header required on 8 writes but dedupes on natural key; double-submit already prevented. | Paper 3 · Pattern 3 | |

## Notes

- `docs/OPEN_ITEMS.md` is the maintained Phase-1 closeout register; items there that are already
  CLOSED with evidence (age-SLO drainer alert, the four ★ loyalty decisions, ancillaries
  PER_PAX_PER_DIRECTION/EMD, dashboard day-bucket timezone) are **not** repeated here — see
  `OPEN_DECISIONS_RECONCILIATION_session21.md`.
- A row marked `must-close-before-Phase-3` should trace to either a Paper-1/2/3 decision or a
  concrete pre-Phase-3 task; a `rides-along` row rides with the tranche named in Paper 6.

## DECISION

<!-- EMPTY — fill the Triage cell (must-close-before-Phase-3 | rides-along) for every row above. -->

# Claude-project knowledge sync bundle — updated 2026-07-03 (session 25)

**Session-25 update (closure/kickoff decision papers).** PR #69 merged; the raw facts in
`PHASE2_CLOSURE_INPUTS.md` are now worked up into six **decision papers** — each ends with an
empty `## DECISION` block. **This is the bundle the review runs against:** fill the decision
blocks in the project, then a follow-up session executes them. Upload the refreshed
`LATEST_STATUS.md` and the new `kickoff/` + `adr/` files below.

### New in session 25
| Kind | Files |
|---|---|
| Decision-paper index | `kickoff/README.md` (six papers, what each decides, reading order) |
| Papers 2–6 | `kickoff/paper-2-flag-007-cdp-wiring.md` · `paper-3-deviation-remediation.md` · `paper-4-scope-ratification.md` · `paper-5-carried-items-triage.md` · `paper-6-phase3-candidates.md` |
| Paper 1 = ADR | `adr/0002-cross-service-flight-class-identity.md` (status **Proposed**) |
| ADR context | `adr/0000-template.md` · `adr/0001-record-architecture-decisions.md` (the existing convention Paper 1 follows) |
| Refreshed | `LATEST_STATUS.md` (session-25 block + the "platform paused for closure review" note) |

All six `## DECISION` blocks are intentionally empty — this session made no decisions, ranked no
tranche, and filled no triage cell. Platform is **paused** for the review before any Phase-3 build.

---

# Claude-project knowledge sync bundle — updated 2026-07-03 (session 24)

**Session-24 update (era landed + contracts complete platform-wide).** PR #68 merged and
tagged `v0.4.0-phase2-close`; the last five API contracts authored so the
`check_contract_presence` exemption list is now **empty** (15/15, 0 exempt); the Phase-2
closure / Phase-3 kickoff input pack is ready. Upload the refreshed `LATEST_STATUS.md` and
the new files below.

### New / changed in session 24
| Kind | Files |
|---|---|
| New — API contracts (`contracts/`) | `dcs-api_openapi.yaml` · `distribution-api_openapi.yaml` · `corporate-api_openapi.yaml` · `dashboard-api_openapi.yaml` · `bff-api_openapi.yaml` (all OpenAPI 3.0.3, validator PASS) |
| New — events schema + fixtures (`contracts/`) | `corporate-events_schema.json` (Draft 2020-12) + `fixtures/corporate/CorporateBookingCreated.sample{1,2}.json` |
| New — contract READMEs | `README_DCS_CONTRACTS.md` · `README_DISTRIBUTION_CONTRACTS.md` · `README_CORPORATE_CONTRACTS.md` · `README_DASHBOARD_CONTRACTS.md` · `README_BFF_CONTRACTS.md` (each with a `## Deviations` section) |
| New — kickoff pack | `PHASE2_CLOSURE_INPUTS.md` (fact-only inputs for the human-run closure review) |
| Refreshed | `LATEST_STATUS.md` (session-24 block; exemption item marked CLOSED) |

Every registered service now ships an API contract (contract-first across the board); the
DCS spec is free of `X-Tenant-Id`. The remaining external-doc cleanup (X-Tenant-Id in the
project's own DCS contract copy) is still code-verified-safe to do in the project.

---

# Claude-project knowledge sync bundle — updated 2026-07-03 (session 23)

**Session-23 update (Phase 2 build era closed).** Fix-track changes; upload the refreshed
`LATEST_STATUS.md` and the new/changed docs below.

### New / changed in session 23
| Kind | Files |
|---|---|
| New — flag | `FLAG_rm_inventory_au_seam.md` (RM↔Inventory identity gap; stop-and-flagged for the kickoff review) |
| New — rule | `RULE_headline_gates_real_counterparty.md` (headline gates must test the real counterparty) |
| Changed | `RM_DELTA_0_6_RETRO.md` (seam finding updated), `README_CONVERSATION_CONTRACTS.md` + `CONVERSATION_DELTA_0_5_RETRO.md` (DCS Idempotency-Key deviation resolved) |
| Refreshed | `LATEST_STATUS.md` (session-23 block + carried-items updates) |

Note: the `dcs-api_openapi.yaml` `X-Tenant-Id` cleanup (external contract in the project) is
now **code-verified safe** — dcs-service does not read the header. Do it in the project copy.

---

# Claude-project knowledge sync bundle — updated 2026-07-03 (session 22)

**Session-22 update:** the Reporting/Conversational/RM/CDP contract gap flagged below is now
**CLOSED**. Each of the four services has an OpenAPI 3.0.3 contract (validator PASS), a
contracts README, and a retro design delta — all added to this bundle. Upload the new files
alongside the refreshed `LATEST_STATUS.md`.

### New in session 22
| Kind | Files |
|---|---|
| OpenAPI contracts (`contracts/`) | `reporting-api_openapi.yaml` · `conversation-api_openapi.yaml` · `rm-api_openapi.yaml` · `cdp-api_openapi.yaml` |
| Contract READMEs | `README_REPORTING_CONTRACTS.md` · `README_CONVERSATION_CONTRACTS.md` · `README_RM_CONTRACTS.md` · `README_CDP_CONTRACTS.md` |
| Retro design deltas | `REPORTING_DELTA_0_4_RETRO.md` · `CONVERSATION_DELTA_0_5_RETRO.md` · `RM_DELTA_0_6_RETRO.md` · `CDP_DELTA_0_7_RETRO.md` |
| Refreshed | `LATEST_STATUS.md` (contract-backfill section + session-22 backfill findings) |

The four services publish **no** backbone events, so no event schema was authored (stated in
each README). Two findings surfaced during backfill — the RM→Inventory `adjust-au` endpoint
is unbuilt on the Inventory side, and conversation writes to DCS check-in without an
Idempotency-Key — both documented (not fixed) in the retro deltas and `LATEST_STATUS.md`.

---

# Claude-project knowledge sync bundle — 2026-07-02 (session 21b)

Point-in-time copies of the current authoritative repo docs, assembled so they can be
uploaded to the Claude.ai project whose knowledge is frozen at session 20 (missing
everything from PR #56 onward). **Staging copies only** — the originals in the repo are the
source of truth; this folder may be deleted after upload.

## Upload these (replace the superseded project-knowledge versions)

### Status & this-session records
| File | Replaces / adds |
|---|---|
| `LATEST_STATUS.md` | supersedes the session-20 status + both deleted `LATEST_STATUS_update_session19/20.md` drop-ins |
| `AUDIT_outbox_relay_test_guards.md` | new — platform relay/sweeper test-guard audit |
| `BUG_distribution_outbox_relay_ci.md` | new — resolved (PR #56) |
| `OPEN_DECISIONS_RECONCILIATION_session21.md` | new — carried-debt reconciliation |
| `OPEN_ITEMS.md` | the live Phase-1 open-items/deferred register |
| `BUILD_LOG.md` | authoritative build record — **see gap note below** |

### Loyalty tranche (L1–L3) design docs
| File | |
|---|---|
| `LOYALTY_DELTA_0_3_EARN_REDEEM.md` | design delta (records the four ★ decisions) |
| `BUILD_PLAN_LOYALTY_L1.md` | L1 build plan |
| `README_LOYALTY_CONTRACTS.md` | contracts README |

### Contract artefacts (`contracts/`)
All 18 current contract files, including `loyalty-api_openapi.yaml`,
`loyalty-events_schema.json`, `dcs-events_schema.json`,
`distribution-events_schema.json`, and every Tranche-1 `*-api.openapi.yaml` /
`*-events.schema.json`.

## ~~⚠️ Gap~~ — RESOLVED in session 22

> **Update (session 22):** this gap is now closed — Reporting/Conversational/RM/CDP each have
> a contract + README + retro delta (see "New in session 22" above). The historical note
> below is retained for context.

The brief asked to bundle "every tranche delta / build plan / contracts README added since
session 20 (L1–L3, Reporting, Conversational, RM, CDP)." At session 21b, **only Loyalty
(L1–L3) had such docs in the repo.** Reporting & BI, Conversational Commerce, Revenue Management, and
Personalisation/CDP were built and merged (PRs #61, #62, #64, #65) **without** committing any
standalone delta / build-plan / contracts-README, and with **no** contract schema files under
`docs/contracts/`. Verified against the working tree and full git history — none were ever
committed.

Their only in-repo authoritative record is **`BUILD_LOG.md`** (included here):
- Reporting & BI — GATE REPORT, `BUILD_LOG.md` ~line 2259
- Conversational Commerce — GATE REPORT ~line 2302
- Revenue Management — GATE REPORT ~line 2347
- Personalisation / CDP — GATE REPORT ~line 2393 (+ FLAG-007 ~line 389)

plus the summary row for each in `LATEST_STATUS.md` (Phase-2 tranche table) and the merged
code under `services/{reporting,conversation,rm,cdp}-service/`. If richer design docs for
those four exist, they live **only in the Claude project** (authored there, never
committed) — so they cannot be sourced from this repo. Re-uploading is not needed for them;
the `BUILD_LOG.md` GATE REPORTs are the current record.

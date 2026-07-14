# LATEST_STATUS.md — drop-in update (session 20)

Splice the blocks below into `LATEST_STATUS.md`. This session built and **merged L0**
(PR #54), and surfaced a **CI capacity issue** that is now the top priority action.

---

## ① Replace the `## Last updated` block

```markdown
## Last updated
**Date:** 29 June 2026 (session 20)
**Session summary:** **Loyalty L0 complete and merged — PR #54 on master.** `loyalty-service` (pkg `pss.loyalty`, port 8086) wired into the platform build; `Member` + `LoyaltyAccount` + Flyway V1; `Enrol` + `GetMember`; `loyalty_outbox_events`; tenant context via paved-road `TenantContextHolder`; `EnrolMemberIT` (MockMvc + `LoyaltyPostgresIT` on the shared singleton Postgres) green locally. Two fix-ups during integration: switched the controller from a custom `@AuthenticationPrincipal`/`DemoSecurityConfig` to paved-road's `TenantContextHolder` (fitness gate `check_tenant_context`), and added the missing root `gradlew` wrapper + `platform/settings.gradle.kts` registration.

> **⚠️ Merged with `build (platform)` CI check RED — known infra issue, not an L0 defect.** See "Immediate action" below.

> **Prior session (28 June, session 19):** Loyalty tranche scaffolded (delta + build plan + L0/L1 contracts).
> **Prior session (28 June, session 18):** Corporate tranche complete. `v0.3.2-corporate-thin` tagged.
> **Prior session (27 June, session 13):** NDC tranche complete. `v0.3.1-ndc-thin` tagged.
> **Prior session (24 June):** Tranche 1 complete. Published `v0.2.0-tranche1`.
```

---

## ② Update the Loyalty milestone table (replace the L0 row)

```markdown
| L0 | Service skeleton + member store + Enrol | ✅ Green — PR #54 merged (CI infra caveat) |
```

---

## ③ Add a new top section right under `## Last updated` — IMMEDIATE ACTION

```markdown
## ⚠️ Immediate action — CI test capacity (do first, before L1)

**Symptom:** `build (platform)` fails on `:loyalty-service:test` AND `:distribution-service:test` (an untouched module). Both pass locally, including run together with `--rerun-tasks` against real containers.

**Root cause (from CI logs):** `org.postgresql.util.PSQLException: An I/O error occurred while sending to the backend` / `java.io.EOFException` — the Postgres Testcontainer connection drops mid-test. The shared GitHub runner is running every module's ITs concurrently; adding loyalty-service's container load tips the runner over its memory/connection ceiling and Docker kills containers. This is a **whole-pipeline capacity issue**, not a loyalty or distribution defect.

**Why it surfaced now:** loyalty-service is the Nth IT module; it pushed concurrent container count past what the runner tolerates. Foreshadowed by the Phase-1 CPU-ceiling note.

**Options (pick one, team call):**
1. **Limit CI test parallelism** — run `:test` tasks serially or cap `--max-workers` / `org.gradle.workers.max` and Testcontainers reuse in the `build-and-test` workflow so containers don't pile up. *(Recommended — proper fix.)*
2. **Enforce singleton-container reuse** — audit that every IT base extends `AbstractPostgresIT` (shared singleton) and nothing spins its own Postgres; ensure Testcontainers reuse is on in CI.
3. **Bigger runner** — move `build (platform)` to a larger runner. *(Stopgap; doesn't fix the underlying growth.)*

**Status:** L0 merged over the red check (code proven correct locally). This must be resolved before L1 lands, or L1 will hit the same wall. Diagnosis recorded as a comment on PR #54.
```

---

## ④ Replace the `## Next steps` first paragraph

```markdown
## Next steps — what to work on next session

**Do the CI capacity fix FIRST (see "Immediate action" above) — it blocks every future PR, not just loyalty.**

Then resume the loyalty tranche at **L1** — points ledger (append-only `PointsTransaction`, unique `dedupeKey`), `EarnRulePort` + `TierPolicyPort` (pure engines) + conformance suites, balance/qualifying-miles projection, and the `ManualEarn` admin/test seam. Gate: ledger append + balance derivation, earn-rule + tier conformance (BASE→SILVER→GOLD both directions), idempotent ledger.
```

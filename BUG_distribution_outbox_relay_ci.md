# Bug: `:distribution-service:test` flakes in CI — outbox relay hits torn-down Postgres

**Severity:** Blocks `build (platform)` CI job on every PR (whole-pipeline blocker)
**Module:** `distribution-service` (test harness) — NOT loyalty, NOT a capacity issue
**Repro:** CI only (Linux runner with working Docker). Does not reproduce on local
Windows dev — Docker-on-Windows npipe is unreachable from the Gradle test JVM there,
so the distribution ITs SKIP locally instead of running.
**First observed:** PR #54 (loyalty L0) and PR #55, runs #88–#90, June 2026.

## Symptom
`build (platform)` fails. Gradle:
```
Execution failed for task ':distribution-service:test'.
> There were failing tests.
43 tests completed, 1 failed
```
Stack trace (run #90 raw log):
```
DistributionOutboxRelay$$SpringCGLIB$$0.relay(<generated>)
  ... org.springframework.scheduling.support.ScheduledMethodRunnable ...
Caused by: org.postgresql.util.PSQLException:
  FATAL: terminating connection due to unexpected postmaster exit
  ... PgConnection.commit ... JpaTransactionManager.doCommit ...
```

## Root cause (high confidence, one step unconfirmed)
`DistributionOutboxRelay.relay()` is annotated:
```kotlin
@Scheduled(fixedDelayString = "${pss.distribution.outbox.poll-interval-ms:500}")
```
Default poll interval is **500 ms**. The relay opens a JPA transaction and commits to
Postgres. When the test's Postgres (Testcontainers) is torn down, the next relay tick
(<=500 ms later) commits against a dead backend → `postmaster exit` → test fails.

`DistributionPostgresIT` (distribution's own IT base) **already guards this**:
```kotlin
registry.add("pss.distribution.outbox.poll-interval-ms") { "3600000" } // 1h, never fires in-test
```
So distribution's *own* ITs are safe. **The failing test must run in a context that boots
distribution-service WITHOUT inheriting that override** — most likely a cross-service
integration suite (`tests:integration` / `tests:offer-integration`) that brings up
distribution-service as a dependency with its default 500 ms relay.

**UNCONFIRMED (do first):** identify exactly which of the 43 tests is the 1 failing, and
which context boots distribution-service there. Read the CI test report artifact:
`services/distribution-service/build/reports/tests/test/index.html` (or add `--scan`).

## Why earlier theories were wrong (don't repeat)
- **NOT inter-module parallelism.** PR #55 set `--max-workers=1` in CI; run #90 failed
  identically (same 6m57s, same point). Disproven. PR #55 closed unmerged.
- **NOT a CI capacity / OOM issue.** The error is a scheduled job racing teardown, not
  containers being OOM-killed under load.
- **NOT a loyalty defect.** loyalty L0 (PR #54) is merged and correct; it only changed the
  timing enough to surface this pre-existing flake.

## Proposed fix (confirm in CI, where it reproduces)
Pin or disable the scheduled relay in whatever context boots distribution-service for the
failing suite — mirror the existing guard. Options:
1. Add `pss.distribution.outbox.poll-interval-ms=3600000` to the cross-service suite's test
   config / `@DynamicPropertySource` (smallest, matches existing pattern).
2. Disable scheduling in that test context (`spring.task.scheduling.*` / a test profile that
   excludes `@EnableScheduling`).
3. Make `relay()` resilient to a closed datasource during shutdown (defensive, broader).

Recommended: option 1 (consistent with `DistributionPostgresIT`, `CorporatePostgresIT`,
`LoyaltyPostgresIT`).

## Acceptance
- `build (platform)` green on a CI run with the fix.
- No change to `src/main` business logic.
- Note: same latent risk exists for any service whose outbox relay defaults to a short
  interval and is booted in a cross-service suite — worth a quick audit.

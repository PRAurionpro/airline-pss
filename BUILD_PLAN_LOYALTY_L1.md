# BUILD PLAN — Loyalty L1 (Points Ledger + Earn/Tier Engines + ManualEarn)

**Status:** Ready to build · **Slice:** Loyalty tranche, L1 (follows L0 / PR #54)
**Authoritative specs:** `docs/LOYALTY_DELTA_0_3_EARN_REDEEM.md` (esp. §3–§8, §11) · `docs/contracts/loyalty-api_openapi.yaml` · `docs/contracts/loyalty-events_schema.json` · `docs/README_LOYALTY_CONTRACTS.md` · `docs/BUILD_PLAN_LOYALTY_TRANCHE.md`
**Target service:** `services/loyalty-service` (pkg `pss.loyalty`, port 8086)

> **Prerequisite met:** `build (platform)` is green on `master` (PR #56 fixed the L0/CI defects — RegisterSeller flake, relay teardown race, loyalty 401, loyalty outbox jsonb/String mismatch). L1 was gated on green; that gate is now satisfied. **Branch off current `master`.**

---

## 0. How to build this (read first)

- Build in **Claude Code with the repo cloned** — this is multi-file work (new domain types, ports, engines, an application service, controllers, a migration, and several test suites). The GitHub web editor is not the tool for it.
- Gradle **root is under `platform/`** — there is **no** root `build.gradle.kts`. Spring **Boot 3.3.5**, Kotlin 2.0.21, Java 21 (verified in `platform/settings.gradle.kts`).
- **Read these in-repo before writing code** (do not rely on memory): `services/loyalty-service/build.gradle.kts`, `services/loyalty-service/src/main/kotlin/pss/loyalty/domain/Entities.kt`, `.../adapter/persistence/Repositories.kt`, `.../config/DemoSecurityConfig.kt`, `.../resources/db/migration/V1__loyalty_members_accounts_outbox.sql`, and the two contract files above. Mirror `distribution-service` where a pattern is unclear.
- Deliver on a **feature branch + PR**. **Do not push to `master`.** Drive **both** `build (platform)` and `fitness-functions` to green before marking done.

## 1. Scope

**In (the L1 gate):**
- Append-only **`PointsTransaction` ledger** with a **unique `dedupeKey`** (idempotency).
- **`EarnRulePort`** and **`TierPolicyPort`** as **pure engines**, each with an **abstract conformance suite**.
- **Balance / qualifying-miles / tier projection** over the ledger (updates the cached `LoyaltyAccount` columns from L0).
- **`ManualEarn`** admin/test seam (`POST /v1/loyalty/members/{memberId}/earn`) — the way the engines/ledger are exercised before the Order consumer lands.
- Read endpoints **`GetBalance`** and **`GetTransactions`**.
- Emit **`MilesAccrued`** (and **`TierChanged`** on a tier crossing) via the existing L0 transactional outbox, conforming to `loyalty-events_schema.json`.

**Out — this is L2, do NOT build now** (keep the PR tight):
- The `OrderConfirmed` / `OrderCancelled` **event consumer** and the read-only `GET /v1/orders/{orderId}` read-back.
- The **`REVERSAL`** path (tied to `OrderCancelled`).
- **Redemption** entirely: `RedemptionOption` catalogue, `Redeem`, `RedemptionFulfilmentPort`, `AwardRedemption`, `MilesRedeemed`.
- `GetStatement`, `MilesReversed`, and RBAC beyond the `X-Loyalty-Admin: true` demo header.

## 2. Data model — new Flyway migration `V2` (NEVER edit `V1`)

New file `services/loyalty-service/src/main/resources/db/migration/V2__loyalty_ledger_earn_rules.sql`. Follow V1's style (plural snake_case table names, explicit `@Column` mapping on entities). All ids are ULIDs stored as `VARCHAR(26)`.

**`points_transactions`** (append-only — no UPDATE/DELETE, ever):
- `id` VARCHAR(26) PK · `account_id` FK → `loyalty_accounts(id)` · `tenant_id` · `type` VARCHAR(16) (ACCRUAL│REVERSAL│REDEMPTION│ADJUSTMENT) · `points` BIGINT (signed) · `qualifying_miles` BIGINT NULL (signed) · `source_type` VARCHAR(16) (ORDER_EVENT│MANUAL│REDEMPTION) · `source_ref` VARCHAR NULL · `dedupe_key` VARCHAR NOT NULL · `occurred_at` TIMESTAMPTZ NOT NULL.
- **`CONSTRAINT uq_ledger_dedupe UNIQUE (tenant_id, dedupe_key)`** — this is the idempotency guard. Index `(tenant_id, account_id, occurred_at)` for history/projection reads.
- No `@Version` (append-only, never updated).

**`earn_rules`**:
- `id` VARCHAR(26) PK · `tenant_id` · `name` · `earn_basis` VARCHAR(16) (BASE_FARE│TOTAL) · `points_per_currency_unit` NUMERIC(12,4) · `qualifying_miles_basis` VARCHAR(16) (BASE_FARE│DISTANCE_STUB) · `active` BOOLEAN NOT NULL.
- Partial unique index on `(tenant_id)` WHERE `active` (at most one active rule per tenant) is recommended.

**`tier_change_events`** (history behind `TierChanged`):
- `id` VARCHAR(26) PK · `account_id` · `tenant_id` · `from_tier` VARCHAR(8) · `to_tier` VARCHAR(8) · `qualifying_miles_at_change` BIGINT · `changed_at` TIMESTAMPTZ.

> If any new column needs to hold JSON, store it as **TEXT + a Kotlin `String`** — **not** `jsonb`. See landmine #1. (There is no JSON column in this migration; the only JSON is the outbox `payload`, already TEXT from L0.)

## 3. Entities + repositories

Add to `domain/Entities.kt` (mirror the existing `@Entity`/`@Table`/explicit-`@Column` style, and the `protected constructor()` JPA no-arg pattern):
- `PointsTransaction` → `@Table(name = "points_transactions")`.
- `EarnRule` → `@Table(name = "earn_rules")` (`pointsPerCurrencyUnit: BigDecimal`).
- `TierChangeEvent` → `@Table(name = "tier_change_events")`.
- Enums (mirror the `Tier` enum already present; persist with `@Enumerated(EnumType.STRING)`): `PointsTransactionType`, `TransactionSourceType`, `EarnBasis`, `QualifyingMilesBasis`.

Add to `adapter/persistence/Repositories.kt` (all `JpaRepository<T, String>`):
- `PointsTransactionRepository`: `findByTenantIdAndDedupeKey(...): Optional<PointsTransaction>`; `findByAccountIdAndTenantIdOrderByOccurredAtDesc(...)`; sum projections for balance/qualifying-miles (or compute in the service).
- `EarnRuleRepository`: `findFirstByTenantIdAndActiveTrue(...): Optional<EarnRule>`.
- `TierChangeEventRepository`.

## 4. Pure engines behind ports (no Spring)

Signatures per delta §5. Keep the input a small value type so the **same engine serves L1 ManualEarn and L2 OrderConfirmed unchanged**:

```kotlin
data class EarnInput(val baseFareAmount: BigDecimal, val totalAmount: BigDecimal, val currency: String)
data class EarnResult(val points: Long, val qualifyingMiles: Long, val basisAmount: BigDecimal)
interface EarnRulePort { fun evaluate(input: EarnInput, rule: EarnRule): EarnResult }

data class TierThresholds(val silver: Long = 25_000, val gold: Long = 50_000)
data class TierDecision(val newTier: Tier, val crossed: Boolean)
interface TierPolicyPort { fun evaluate(qualifyingMiles: Long, current: Tier): TierDecision }
```

- **Earn rule:** `basisAmount = earnBasis == BASE_FARE ? baseFareAmount : totalAmount`; `points = floor(basisAmount × pointsPerCurrencyUnit)` (use `BigDecimal`, `RoundingMode.FLOOR`, then `.toLong()`); `qualifyingMiles` = same basis, floored (distance-based is a stub). No FX — `currency` is recorded, not converted (note this assumption).
- **Tier policy:** map cumulative `qualifyingMiles` → tier (`>= gold` → GOLD, `>= silver` → SILVER, else BASE); `crossed = newTier != current`. Monotonic up on accrual, steps down when a reversal lowers the cumulative total (L2), so the engine must handle both directions.
- Concrete impls live in an `engine` package; thresholds default to (25 000 / 50 000) and should be tenant-configurable later.

## 5. Conformance suites (abstract; the L1 gate's core assertions)

```kotlin
abstract class EarnRulePortConformanceSuite { abstract fun earnRulePort(): EarnRulePort; /* @Test cases */ }
abstract class TierPolicyPortConformanceSuite { abstract fun tierPolicyPort(): TierPolicyPort; /* @Test cases */ }
```

- **EarnRule cases:** floor rounding (e.g. 1234.56 × 1.0 → 1234); basis selection (BASE_FARE vs TOTAL picks the right amount); zero basis → 0 points; qualifying-miles tracks the basis.
- **TierPolicy cases — BOTH directions, on the boundaries:** 24 999 → BASE (no cross); **25 000 → SILVER (crossed)**; 49 999 → SILVER; **50 000 → GOLD (crossed)**; upward BASE→SILVER→GOLD. Downward (drop cumulative): GOLD→SILVER at < 50 000 (crossed, step down), SILVER→BASE at < 25 000. Assert the `crossed` flag each time.
- Concrete test classes extend the suites and supply the impls. Per `README_LOYALTY_CONTRACTS.md` §L1 gate, the `MilesAccrued` / `TierChanged` fixtures in `loyalty-events_schema.json` back these assertions.

## 6. Application service — idempotent ledger + projection + ManualEarn

**`LedgerService.append(...)`** — the one place rows enter the ledger:
1. Look up `findByTenantIdAndDedupeKey`. If present → **return it unchanged** (idempotent no-op).
2. Else insert the `PointsTransaction`. Catch `DataIntegrityViolationException` from the unique constraint (concurrent duplicate) → re-read by `(tenantId, dedupeKey)` and return the existing row. This makes append idempotent-on-`dedupeKey` even under races.
3. After a real insert: **recompute the projection** — `pointsBalance = Σ points`, `qualifyingMiles = Σ qualifyingMiles` over the account's ledger — and update the cached `LoyaltyAccount` columns. Run `TierPolicyPort`; if `crossed`, set `currentTier`, insert a `TierChangeEvent`, and enqueue a `TierChanged` outbox row. Enqueue a `MilesAccrued` outbox row. All in **one transaction** with the ledger insert.

**ManualEarn flow** (`POST /v1/loyalty/members/{memberId}/earn`):
1. Require `X-Loyalty-Admin: true` (else **403**) and `Idempotency-Key` (else **400**).
2. Load member+account, tenant-scoped → **404 `MEMBER_NOT_FOUND`**; if `SUSPENDED` → **422 `MEMBER_SUSPENDED`**.
3. Load active `EarnRule` for the tenant → **422 `NO_ACTIVE_EARN_RULE`** if none.
4. `EarnInput` from the request (`baseFareAmount = totalAmount = basisAmount`, `currency`) → `EarnRulePort.evaluate`.
5. `LedgerService.append`: type `ACCRUAL`, `sourceType = MANUAL`, `sourceRef = request.sourceRef`, **`dedupeKey = Idempotency-Key`**, `occurredAt = now`.
6. Return **201** with `MemberSummary` (post-state). A repeated `Idempotency-Key` returns the same result with no second credit.

## 7. API layer (controllers, `adapter`)

Match `loyalty-api_openapi.yaml` exactly (paths, schema field names, ULID/enum shapes). Tenant from `TenantContextHolder` (never a path/query param).
- `GET  /v1/loyalty/members/{memberId}/balance` → `BalanceView` (accountId, pointsBalance, qualifyingMiles, currentTier). 404 if not in tenant.
- `GET  /v1/loyalty/members/{memberId}/transactions` → `PointsTransaction[]`, newest first.
- `POST /v1/loyalty/members/{memberId}/earn` → ManualEarn (above).
- Errors are `application/problem+json` (RFC 9457) with the stable `code` (`MEMBER_NOT_FOUND`, `MEMBER_SUSPENDED`, `NO_ACTIVE_EARN_RULE`). Reuse L0's exception→problem mapping (`domain/Exceptions.kt` + handler).

## 8. Events (emit; conform to the schema — read it, don't guess)

Publish via the L0 transactional outbox (`LoyaltyOutboxEvent`; `payload` is a JSON `String` in the **TEXT** column, Jackson-serialized).
- **`MilesAccrued`** on every ManualEarn accrual; **`TierChanged`** additionally when the tier crossed.
- **Read `docs/contracts/loyalty-events_schema.json` in-repo** and match the envelope + payload field names exactly. Envelope carries `eventId, type, version, tenantId, accountId, memberId, occurredAt, correlationId, causationId`; each event is discriminated by a `type` const; `MilesAccrued` payload includes post-state (`newBalance`, `newTier`) per the README.
- The build already **vendors `loyalty-events_schema.json` onto the test classpath** (see `build.gradle.kts` `copyEventSchemas`); add a **schema-conformance test** that validates a built `MilesAccrued` and `TierChanged` against it using the `com.networknt:json-schema-validator` dep already declared.

## 9. Tests

- **Unit (no Spring):** `EarnRulePortConformanceSuite` + impl test; `TierPolicyPortConformanceSuite` + impl test (§5 matrix).
- **Schema conformance:** validate emitted `MilesAccrued` / `TierChanged` against `loyalty-events_schema.json`.
- **Integration (MockMvc + `LoyaltyPostgresIT` on the shared singleton Postgres — mirror `EnrolMemberIT`):**
  - `ManualEarnIT`: seed an active `EarnRule` for the tenant in `@BeforeEach`; POST earn → 201, ledger has one ACCRUAL, balance/qualifying-miles updated, `MilesAccrued` enqueued.
  - **Idempotency:** same `Idempotency-Key` twice → exactly one ledger row, balance credited once, identical response.
  - **Tier crossing:** earn past 25 000 → `currentTier = SILVER`, `TierChanged` enqueued, `tier_change_events` row written.
  - **Errors:** no active rule → 422 `NO_ACTIVE_EARN_RULE`; missing `X-Loyalty-Admin` → 403; suspended member → 422 `MEMBER_SUSPENDED`; unknown member → 404; tenant isolation on `GetBalance`/`GetTransactions`.
  - **`SchemaValidationIT` (NEW — the guard L0 lacked):** boot the context with Hibernate `ddl-auto=validate` against the migrated schema (copy the sibling services' `SchemaValidationIT`) so an entity/DDL mismatch fails at context load, not as a runtime 500.

## 10. L0 landmines — MUST follow (these are exactly what broke L0 in CI)

1. **Outbox / JSON columns are TEXT + `String`, never `jsonb`.** `columnDefinition = "jsonb"` only affects DDL generation, not JDBC binding, so a `String`→`jsonb` insert throws `SQLGrammarException` (SQLState 42804). Distribution stores outbox payload as TEXT; do the same.
2. **Mock with classic `@MockBean`** (`org.springframework.boot.test.mock.mockito.MockBean`). `@MockitoBean` (Boot 3.4+) and springmockk `@MockkBean` do **not** resolve on Boot **3.3.5**.
3. **Do not delete / weaken `DemoSecurityConfig`.** Its `@Profile("!prod")` permit-all `SecurityFilterChain` is what lets the demo bearer through; tenant comes from paved-road's `TenantContextFilter`/`TenantContextHolder`. Removing it → 401 on every request (the L0 bug). Any new admin endpoint stays under the same permit-all posture (`X-Loyalty-Admin` is an app-level check, not a security filter).
4. **ITs SKIP on the Windows/local dev box** (Testcontainers/Docker) — "green locally" for an IT means "skipped." **Verify on a CI run**, never assume from local.
5. **ktlint import order is ASCII** (uppercase before lowercase): e.g. `org.springframework.boot.*` before `org.springframework.test.*`.
6. **New migration is `V2`; never edit `V1`** (Flyway checksum). The ledger is **append-only** — no `UPDATE`/`DELETE` on `points_transactions`.

## 11. Definition of done / gate

- Ledger append + balance derivation working; `EarnRulePort`/`TierPolicyPort` conformance suites green (both directions); **idempotent ledger** (unique `dedupeKey`, repeated `Idempotency-Key` is a no-op); ManualEarn end-to-end; `MilesAccrued`/`TierChanged` validate against the schema.
- **`build (platform)` green AND `fitness-functions` green** (Architectural fitness incl. `check_tenant_context`; Contract verification).
- New loyalty `SchemaValidationIT` present and green.
- Delivered as a **feature branch + PR**; **not** pushed to `master`. Update the Loyalty L1 row in `docs/LATEST_STATUS.md`.

## 12. Stop-and-flag (from delta §11 — halt and ask if any occur)

Any change to `order-service`/`pricing-service`/`offer-service`/`payments-service` internals · any new field requested on `OrderConfirmed`/`OrderCancelled` · any real money path or "pay with miles" wiring · any new saga step · partner/coalition earn · points expiry/lifecycle jobs · any consumed event beyond `OrderConfirmed`/`OrderCancelled` · any published event beyond `MilesAccrued`/`MilesReversed`/`MilesRedeemed`/`TierChanged`.

# Fix: drainer age SLO alert (missing from #35)

Three file changes. Apply in order, then run `RefundDrainIT` to confirm green.

---

## 1. `PaymentsTimingProperties.kt` — add `ageSlo` to `RefundDrainer`

Add one field to the `RefundDrainer` inner class. Slot it after `maxAttempts`.

```kotlin
/** The scheduled REFUND_FAILED drainer (delta §9). All config-over-code — */
class RefundDrainer {
    var cadence: Duration = Duration.ofMinutes(5)

    /** Retry cap: after this many drain attempts an intent is left REFUND_ */
    var maxAttempts: Int = 5

    // ── ADD THIS ──────────────────────────────────────────────────────────
    /**
     * Age SLO: a REFUND_FAILED intent older than this duration has not been
     * resolved within the acceptable window and must page ops immediately.
     * Provisional 1 h — ratify against real sandbox refund latencies (§13).
     */
    var ageSlo: Duration = Duration.ofHours(1)
    // ─────────────────────────────────────────────────────────────────────

    /** refund_failed_queue_depth gauge value at/above which ops should be */
    var alertThreshold: Int = 20
}
```

Config-file override (application.yml / environment):
```yaml
pss:
  payments:
    refund-drainer:
      age-slo: PT1H   # ISO-8601; override to tune without rebuild
```

---

## 2. `Repositories.kt` — add `findByStatusAndUpdatedAtBefore`

The `PaymentIntent` entity has no `refundFailedAt` timestamp. The existing
`updatedAt` audit field is the correct proxy: it is set by `@LastModifiedDate`
(or equivalent) on every `intents.save(intent)` call, and the last save on a
`REFUND_FAILED` intent is exactly when it entered that state.

Add one query method to `PaymentIntentRepository`:

```kotlin
/** Age-SLO sweep (Delta 0.2 §9): REFUND_FAILED intents whose last update
 *  pre-dates the SLO window, meaning they have been stuck too long and must
 *  be alerted. `updatedAt` is set by the ORM on every save; its value on a
 *  REFUND_FAILED intent is the timestamp the intent entered that state
 *  (or the last drain attempt, which is still within the SLO window if
 *  recent — intentional: we alert only when silence exceeds the SLO). */
fun findByStatusAndUpdatedAtBefore(
    status: PaymentStatus,
    cutoff: Instant
): List<PaymentIntent>
```

> **Note:** if the entity uses `@CreatedDate`/`@LastModifiedDate` via
> `@EntityListeners(AuditingEntityListener::class)`, the field is named
> `updatedAt` and is already mapped. If the column is named differently in
> your schema, substitute the correct field name — Spring Data derives the
> query from the method name.

---

## 3. `RefundDrainService.kt` — emit the age SLO alert

Add one private counter and one private method; call the method at the end
of `drainAll()`.

### 3a. Add the counter (alongside `exhausted` and `requeued`)

```kotlin
private val ageSloBreached = meterRegistry.counter("payments.refund_drain.age_slo_breached")
```

### 3b. Add the private alert method

```kotlin
/**
 * Age-SLO check: any REFUND_FAILED intent that has been stuck longer than
 * timing.refundDrainer.ageSlo is beyond the acceptable recovery window.
 * Each such intent increments the age_slo_breached counter and emits a
 * log.error — both are wired to alert rules in the observability stack.
 *
 * This runs AFTER the drain loop so that intents re-queued in this cycle
 * are not counted (their updatedAt just moved to now).
 */
private fun checkAgeSlo(now: Instant) {
    val cutoff = now.minus(timing.refundDrainer.ageSlo)
    val stale = intents.findByStatusAndUpdatedAtBefore(PaymentStatus.REFUND_FAILED, cutoff)
    if (stale.isNotEmpty()) {
        ageSloBreached.increment(stale.size.toDouble())
        log.error(
            "AGE-SLO BREACH: {} intent(s) have been REFUND_FAILED for longer than {}. " +
            "Intent IDs: {}",
            stale.size,
            timing.refundDrainer.ageSlo,
            stale.map { it.intentId }
        )
    }
}
```

### 3c. Call it at the end of `drainAll()`

The method currently ends:
```kotlin
        intents.save(intent)
    }
    return requeuedCount
}
```

Change to:
```kotlin
        intents.save(intent)
    }
    checkAgeSlo(now)   // ← add this line before the return
    return requeuedCount
}
```

---

## 4. `RefundDrainIT.kt` — add the age SLO test

Add a third `@Test` after the existing two. The pattern follows the existing
`refundFailedIntent()` helper; the only difference is back-dating the intent's
`updatedAt` to simulate age.

```kotlin
@Test
fun `age SLO alert fires when a REFUND_FAILED intent is older than the SLO window`() {
    // Arrange: create a REFUND_FAILED intent and artificially back-date it
    // so it appears older than the ageSlo window (default 1 h).
    // We use a small override via @DynamicPropertySource so the test does not
    // wait a real hour: set ageSlo to 1 s, then back-date by 2 s.
    val id = refundFailedIntent("tok_agebreach").intentId

    // Back-date updatedAt directly via JDBC so the ORM audit does not
    // overwrite it on the next save.
    val cutoff = java.time.Instant.now().minusSeconds(2)
    intents.findByIntentIdAndTenantId(id, tenant)!!.let { intent ->
        // Use a native query or a test-only helper to set updatedAt < cutoff.
        // The simplest approach that does not require a new repository method:
        // update directly via JdbcTemplate (autowired in PaymentsPostgresIT base).
        jdbc.update(
            "UPDATE payment_intents SET updated_at = ? WHERE intent_id = ?",
            java.sql.Timestamp.from(cutoff), id
        )
    }

    val before = meters.counter("payments.refund_drain.age_slo_breached").count()

    drainer.drainAll()

    assertThat(meters.counter("payments.refund_drain.age_slo_breached").count())
        .isGreaterThan(before)
}

companion object {
    // Add alongside the existing smallCap source:
    @DynamicPropertySource
    @JvmStatic
    fun ageSloOverride(registry: DynamicPropertyRegistry) {
        registry.add("pss.payments.refund-drainer.age-slo") { "PT1S" } // 1 second for tests
    }
}
```

> **Prerequisite:** `PaymentsPostgresIT` (the base class) must expose a
> `JdbcTemplate jdbc` field — check if it already does. If not, add
> `@Autowired private lateinit var jdbc: JdbcTemplate` to `RefundDrainIT`
> directly.

---

## Checklist before raising the PR

- [ ] `updatedAt` field exists on `PaymentIntent` entity (verify column name in schema)
- [ ] `PaymentsPostgresIT` exposes `jdbc: JdbcTemplate` (or add it to `RefundDrainIT`)
- [ ] `RefundDrainIT` passes all three tests locally against Testcontainers Postgres
- [ ] `payments.refund_drain.age_slo_breached` counter is wired to an alert rule in the observability stack (Prometheus/Grafana or equivalent) — this is the **operational** half of the fix; without it the counter increments silently
- [ ] `ageSlo: PT1H` is confirmed (or adjusted) after real sandbox latency measurements in step 2 of the stability plan
- [ ] Open decision "drainer retry SLO alert" closed in `LATEST_STATUS.md`

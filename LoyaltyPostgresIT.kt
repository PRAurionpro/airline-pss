package pss.loyalty.it

import com.pss.platform.testsupport.AbstractPostgresIT
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * loyalty-service Postgres IT base: the shared singleton-container harness ([AbstractPostgresIT])
 * plus loyalty-service test properties. The @Scheduled outbox relay publishes to Kafka (no broker
 * in the IT), so its poll interval is pushed far out so it never fires during a test. Security
 * autoconfigures normally — the demo SecurityFilterChain permits requests; tenant comes from
 * paved-road's TenantContextFilter reading the demo bearer token.
 */
abstract class LoyaltyPostgresIT : AbstractPostgresIT() {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun loyaltyProperties(registry: DynamicPropertyRegistry) {
            registry.add("pss.loyalty.outbox.poll-interval-ms") { "3600000" }
        }
    }
}

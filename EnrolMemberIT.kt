package pss.loyalty.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.ulid.UlidCreator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pss.loyalty.adapter.persistence.LoyaltyAccountRepository
import pss.loyalty.adapter.persistence.LoyaltyOutboxEventRepository
import pss.loyalty.adapter.persistence.MemberRepository
import java.util.Base64

/**
 * L0 gate on a real Postgres (Testcontainers): Enrol creates a member + account and returns 201;
 * GetMember returns it; the optimistic-lock version starts at 0; the account is ACTIVE at BASE tier
 * with zero balance; the loyalty_outbox_events table exists and is queryable (outbox smoke);
 * GetMember is tenant-isolated; a duplicate externalRef is rejected 409 DUPLICATE_ENROLMENT;
 * an unknown id is 404 MEMBER_NOT_FOUND.
 * The token is the platform's unsigned demo JWT — the demo SecurityFilterChain permits it and
 * paved-road's TenantContextFilter reads the tenant claim.
 */
@AutoConfigureMockMvc
class EnrolMemberIT @Autowired constructor(
    private val mockMvc: MockMvc,
    private val members: MemberRepository,
    private val accounts: LoyaltyAccountRepository,
    private val outbox: LoyaltyOutboxEventRepository,
) : LoyaltyPostgresIT() {

    private val mapper = ObjectMapper()

    private fun bearer(tenant: String): String {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        return "Bearer " + b64("""{"alg":"none","typ":"JWT"}""") + "." +
               b64("""{"tenantId":"$tenant","sub":"staff"}""") + "."
    }

    private fun enrolBody(externalRef: String) = """
        {"externalRef":"$externalRef","givenName":"Jamie","surname":"Doe","email":"jamie.doe@example.com"}
    """.trimIndent()

    // ── Gate 1: Enrol → 201, account at zero balance, BASE tier, version 0 ──

    @Test
    fun `Enrol returns 201 - account at zero balance BASE tier version 0 outbox queryable`() {
        val tenant = "acme-air"
        val ref = "pax-jdoe-${UlidCreator.getUlid()}"

        val created = mockMvc.perform(
            post("/v1/loyalty/members")
                .header("Authorization", bearer(tenant))
                .header("Idempotency-Key", UlidCreator.getUlid().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(enrolBody(ref)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.externalRef").value(ref))
            .andExpect(jsonPath("$.account.pointsBalance").value(0))
            .andExpect(jsonPath("$.account.qualifyingMiles").value(0))
            .andExpect(jsonPath("$.account.currentTier").value("BASE"))
            .andReturn().response.contentAsString

        val memberId = mapper.readTree(created).get("memberId").asText()
        val accountId = mapper.readTree(created).at("/account/accountId").asText()

        // version 0 in the store
        assertThat(members.findByIdAndTenantId(memberId, tenant).get().version).isEqualTo(0)
        // outbox table queryable — Enrol writes a MemberEnrolled entry
        assertThat(outbox.findAll().any { it.accountId == accountId }).isTrue()
    }

    // ── Gate 2: GetMember returns the enrolled member ─────────────────────

    @Test
    fun `GetMember returns the enrolled member`() {
        val tenant = "acme-air"
        val ref = "pax-get-${UlidCreator.getUlid()}"

        val created = mockMvc.perform(
            post("/v1/loyalty/members")
                .header("Authorization", bearer(tenant))
                .header("Idempotency-Key", UlidCreator.getUlid().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(enrolBody(ref)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        val memberId = mapper.readTree(created).get("memberId").asText()

        mockMvc.perform(
            get("/v1/loyalty/members/$memberId")
                .header("Authorization", bearer(tenant)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memberId").value(memberId))
            .andExpect(jsonPath("$.externalRef").value(ref))
            .andExpect(jsonPath("$.account.currentTier").value("BASE"))
    }

    // ── Gate 3: Tenant isolation ──────────────────────────────────────────

    @Test
    fun `GetMember is tenant-isolated - another tenant never sees the member`() {
        val ref = "pax-iso-${UlidCreator.getUlid()}"
        val created = mockMvc.perform(
            post("/v1/loyalty/members")
                .header("Authorization", bearer("acme-air"))
                .header("Idempotency-Key", UlidCreator.getUlid().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(enrolBody(ref)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        val memberId = mapper.readTree(created).get("memberId").asText()

        mockMvc.perform(
            get("/v1/loyalty/members/$memberId")
                .header("Authorization", bearer("other-air")),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))
    }

    // ── Gate 4: DUPLICATE_ENROLMENT on repeat externalRef ────────────────

    @Test
    fun `duplicate externalRef returns 409 DUPLICATE_ENROLMENT`() {
        val ref = "pax-dup-${UlidCreator.getUlid()}"
        val body = enrolBody(ref)
        val tenant = "acme-air"

        mockMvc.perform(
            post("/v1/loyalty/members")
                .header("Authorization", bearer(tenant))
                .header("Idempotency-Key", UlidCreator.getUlid().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/v1/loyalty/members")
                .header("Authorization", bearer(tenant))
                .header("Idempotency-Key", UlidCreator.getUlid().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_ENROLMENT"))
    }

    // ── Gate 5: Unknown member → 404 ─────────────────────────────────────

    @Test
    fun `unknown memberId returns 404 MEMBER_NOT_FOUND`() {
        mockMvc.perform(
            get("/v1/loyalty/members/01ZZZZZZZZZZZZZZZZZZZZZZZ0")
                .header("Authorization", bearer("acme-air")),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))
    }
}

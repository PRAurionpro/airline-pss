# Paper 3 — Deviation-pattern remediation

**Decision paper.** Three cross-cutting patterns harvested from the session-24 contract
`## Deviations` sections. Each has options (fix-now / fix-with-a-Phase-3-tranche /
accept-and-document); no recommendation. Decision blocks are empty.

Sources: `README_DCS_CONTRACTS.md`, `README_DISTRIBUTION_CONTRACTS.md`,
`README_CORPORATE_CONTRACTS.md`, `README_DASHBOARD_CONTRACTS.md`, `README_BFF_CONTRACTS.md`;
verified against source (`grep` over `services/*/src/main`).

---

## Pattern 1 — Header-asserted identity/authorisation vs token-derived

**Facts.** Two app-level header-trust patterns exist instead of token claims:

- **Principal identity — `X-Seller-Id`** (required) on **all four** distribution NDC endpoints
  (`NdcController` `/v1/ndc/air-shopping|offer-price|order-create|orders/{ref}`). It names *which
  seller* the request acts as; the README calls it a "documented stand-in for an upstream
  api-key→seller resolver."
- **Admin authorisation — `X-*-Admin`** (optional boolean/string; missing ⇒ 403) on admin ops in
  **corporate** (`X-Corporate-Admin` — accounts/cost-centres/travellers/policies), **conversation**
  (`X-Conversation-Admin` — session/notification listing), and **loyalty** (`X-Loyalty-Admin` —
  manual earn). These are demo-posture gates, "not security filters."

Tenant itself is already token-derived everywhere (`TenantContextHolder.requireTenantId()`); these
headers are the exceptions that assert *seller* or *admin* out-of-band.

**What a token-claims migration touches.** Seller: an upstream api-key→seller resolver (gateway/IAM)
that mints a `sellerId` claim, plus removing the `X-Seller-Id` param from distribution's 4 endpoints
+ contract. Admin: an RBAC role claim checked by a filter, replacing the per-controller header check
in corporate/conversation/loyalty + their contracts. Both are contract changes (out of scope this
session) and would ripple to any client/postman/demo that sets the headers today.

**Phase-3 note.** Phase 3 deepens **DCS and distribution** (`PHASE2_CLOSURE_INPUTS.md` §4), i.e. the
tranches that own `X-Seller-Id`; a seller-claim migration naturally rides with distribution deepening.

**Options**
- **1a Fix now** — introduce the seller claim + RBAC role claim and drop the headers platform-wide.
  *Cost:* multi-service contract + gateway/IAM change; touches core (loyalty) and all three admin
  services. *Forecloses:* nothing; aligns everything to token-derived trust.
- **1b Fix with the Phase-3 tranche(s)** — migrate `X-Seller-Id` when distribution deepens; migrate
  `X-*-Admin` when an auth/RBAC tranche lands. *Cost:* deferred, scoped per tranche. *Forecloses:*
  nothing; leaves headers in place meanwhile.
- **1c Accept-and-document** — ratify header-asserted seller/admin as the demo posture; keep the
  README deviations as the record. *Cost:* none. *Forecloses:* a production security posture until
  revisited.

---

## Pattern 2 — Error-format split (plain `{status,code,detail}` vs RFC 9457)

**Facts (verified in code).** Two error envelopes are in production:

- **Plain `application/json` `{status, code, detail}`** (no `type`/`title`/`instance`): **dcs,
  distribution, corporate** (local per-controller handlers; no `@ControllerAdvice`).
- **RFC 9457 `application/problem+json`**: everyone else — core (order, pricing, offer, payments,
  inventory), loyalty, reporting, conversation, rm, cdp, dashboard, bff.

So three of fifteen services diverge from the platform's problem+json norm. All carry a stable
machine-readable `code`, so the *code* vocabulary is consistent; only the *envelope* differs.

**Client impact.** A client hitting dcs/distribution/corporate must parse a different shape than for
every other service — two branches in error handling, and problem+json tooling (content negotiation,
`type` URIs) doesn't apply to the trio.

**Cost of converging.**
- *Trio → RFC 9457:* replace the three local handlers with the platform problem+json handler; map
  each existing `code`/`status` into `ProblemDetail` (+ set the `code` property as the others do);
  update the three contracts' error responses. No new codes.
- *Everyone → plain:* larger blast radius (12 services + core), and a step *away* from the platform
  convention and RFC 9457 tooling — noted only for completeness.

**Options**
- **2a Fix now** — converge the trio to RFC 9457. *Cost:* three services + three contracts.
  *Forecloses:* nothing; single error shape platform-wide.
- **2b Fix with a Phase-3 tranche** — convert each of dcs/distribution/corporate when its deepening
  tranche opens it anyway. *Cost:* deferred, scoped. *Forecloses:* nothing.
- **2c Accept-and-document** — ratify the split; keep the code vocabulary as the contract and the
  README deviations as the record. *Cost:* none. *Forecloses:* uniform client error handling.

---

## Pattern 3 — Required-but-ignored `Idempotency-Key` (DCS)

**Facts.** dcs-service declares `Idempotency-Key` **required** (non-optional `@RequestHeader`) on its
8 write endpoints, but never stores or dedupes on it — dedup is purely natural-key / terminal-state
(`DcsController`/`DcsService`). distribution's `POST /v1/sellers` has the same shape (required,
natural-key dedup on `(tenant,name)`); distribution's `order-create` **does** honour the key as a
true replay key. So the "required-but-ignored" pattern is specifically DCS's 8 writes (+ distribution
sellers).

**Caller state (fact).** The conversation-service caller now sends a **correct, deterministic, stable**
`Idempotency-Key` on the DCS check-in (session 23: `"${sessionId}:checkin:${departureId}"`). So the
client side is already right regardless of what DCS chooses — DCS just doesn't use the value yet.

**Options**
- **3a Honour it** — add an idempotency store to dcs-service keyed on the header (return the stored
  result on replay), superseding/《augmenting》 natural-key dedup. *Cost:* dcs-service change + store +
  tests. *Forecloses:* nothing; makes retries exactly-once on the key.
- **3b Relax it** — make the header **optional** in code + contract (keep natural-key dedup as the
  real guard). *Cost:* small dcs-service + contract change. *Forecloses:* a future move to key-based
  idempotency would re-tighten it.
- **3c Accept-and-document** — keep required-but-natural-key-deduped; the README deviation is the
  record. *Cost:* none. *Forecloses:* nothing functional (double-submit already prevented by natural
  key); leaves a contract that promises more than the code does.

**Phase-3 note.** DCS deepening is a Phase-3 candidate; 3a/3b ride naturally with it.

---

## Interactions

- **All three ride the same tranches.** DCS deepening opens Patterns 2 and 3 in dcs; distribution
  deepening opens Patterns 1 (`X-Seller-Id`) and 2 in distribution. Deciding "fix-with-tranche"
  bundles them per service.
- **ADR-0002 link:** if the flight-class identity decision reopens inventory's/​distribution's write
  contracts, that is the low-marginal-cost moment to also settle Patterns 1–2 there.
- **Feeds Paper 5:** these three patterns appear as carried-item rows cross-referencing this paper.

## DECISION

### Pattern 1 — header-asserted identity/authorisation
<!-- EMPTY — fix-now / fix-with-tranche / accept-and-document, per header family -->

### Pattern 2 — error-format split
<!-- EMPTY -->

### Pattern 3 — required-but-ignored Idempotency-Key
<!-- EMPTY -->

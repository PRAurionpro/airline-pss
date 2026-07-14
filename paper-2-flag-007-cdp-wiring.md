# Paper 2 — FLAG-007: wire offer-service PersonalisationPort to CDP

**Decision paper.** Options with trade-offs; no recommendation. `## DECISION` is empty.
Source: `BUILD_LOG.md` FLAG-007 (2026-07-02); `CDP_DELTA_0_7_RETRO.md` §Findings;
`README_CDP_CONTRACTS.md` deviation 4; `PHASE2_CLOSURE_INPUTS.md` §2 row 2.

## Context (facts)

CDP (cdp-service) shipped as a thin slice: event-sourced profile + consent-gated ranking, with
`POST /v1/cdp/rank-offers` ready and waiting. The intended consumer is offer-service, which owns
`com.pss.offer.personalisation.PersonalisationPort`
(`fun rank(tenantId, context: ShoppingContext, offers: List<Offer>): List<Offer>`). Today the
bound implementation is `PassThroughPersonalisation` — a `@Component` that returns offers
unchanged — injected into `AssemblyService`. **Direction is offer → cdp; cdp calls nobody.**

The CDP tranche invariant permitted exactly one change in offer-service — swapping the
PersonalisationPort bean to a CDP HTTP adapter — **only if config-only**. It is not config-only.
Per the FLAG-007 record, swapping it requires, in offer-service (a Tranche-1 core module):
(a) a new adapter class implementing `PersonalisationPort` with an outbound `RestClient` to
`POST /v1/cdp/rank-offers`; (b) `Offer` ↔ `OfferSummary`/`RankedOffer` mapping; (c) conditional
bean wiring to displace the `@Component` stub; (d) graceful-degradation handling when cdp is down
(so a CDP outage cannot break shopping). That is new code + a new runtime dependency in a core
module, with offer-service's own ITs at risk — so the tranche stopped at the boundary and flagged.

**Assessment of "trivial?":** this is a **real but low-complexity decision**, not a trivial
close. The *code* is small and well-understood, but it introduces a new runtime dependency edge
(offer → cdp on the hot shopping path) whose **resilience posture** is a genuine choice. It should
not be closed by fiat; it needs an owner to accept the dependency + degradation behaviour.

## Options

### Option 1 — Wire it now, in a scoped offer-service PR (with graceful degradation)
Add the adapter, mapping, conditional wiring, and fail-open-to-passthrough on CDP error/timeout.
- **Consequences:** personalised ranking goes live; offer-service gains a runtime dependency on
  cdp (mitigated by fail-open). Offer-service ITs must cover the CDP-down path.
- **Cost:** one scoped PR in a core module + IT updates; must not weaken offer-service gates.
- **Forecloses:** nothing; fail-open keeps shopping resilient.

### Option 2 — Defer to a Phase-3 tranche that owns offer-service changes
Leave the stub; schedule the wiring alongside the next offer/personalisation tranche.
- **Consequences:** CDP stays dormant (built, unused) until then; no core-module churn now.
- **Cost:** near-zero now; the integration + its resilience design move into that tranche's scope.
- **Forecloses:** nothing; CDP endpoint remains ready.

### Option 3 — Accept-and-document as a standing gap (no scheduled work)
Ratify the pass-through as the intended steady state for now; record CDP as an available-but-unwired
capability.
- **Consequences:** no personalisation in production; CDP remains a tested island.
- **Cost:** none.
- **Forecloses:** nothing technically, but leaves a shipped capability with no consumer — revisit
  cost recurs each planning cycle.

## Interactions

- **Only touches offer-service** on the write side; independent of ADR-0002 (different domain).
- **Feeds Paper 5 (carried-items triage):** FLAG-007 is a carried item; the triage call there should
  match this paper's outcome.
- **Fitness note:** whichever option, the offer↔cdp edge is provider-neutral and does not affect the
  isolation checks (`check_cdp_isolation` governs cdp's own boundaries, not offer's outbound call).

## DECISION

<!-- EMPTY — to be filled by the deciders. Choose wire-now / defer-to-Phase-N / accept-and-document,
     name the owner, and state the required resilience behaviour if wiring. -->

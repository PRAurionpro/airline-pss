# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-06-09
- **Deciders:** Architecture board
- **Decision ref:** —

## Context

The Build Plan executes against the SDD decision log. Phase 0 requires that all framing
decisions (D1–D11 + residuals) are recorded as ADRs, with fitness functions enforcing
them in CI. We need a lightweight, durable way to capture the *why* behind decisions as
the platform grows.

## Decision

We will record architecturally significant decisions as Architecture Decision Records in
`docs/adr/`, one Markdown file per decision, numbered sequentially from the template in
[`0000-template.md`](0000-template.md). ADRs are immutable once accepted; a reversal is a
new ADR that supersedes the old one.

The framing decisions that shape Phase 0 / Phase 1 (each to be expanded into its own ADR):

| Ref | Decision |
|-----|----------|
| D3  | India first |
| D5  | Cloud-neutral; AWS + Azure (AWS Mumbai first) |
| D6  | Java 21 / Kotlin + Spring Boot; React; Python |
| D7  | Default pooled tenancy |
| D8  | Web IBE first; LCC/hybrid config |
| D9  | Filed fares in Phase 1 |
| D10 | Strong consistency: order / inventory / payment |

## Consequences

Decisions are discoverable and reviewable; CI fitness functions can reference the ADR
they enforce. The cost is the discipline of writing an ADR for each significant change.

## Alternatives considered

Capturing decisions only in the SDD — rejected because the SDD is authoritative on
architecture but not a running log of *why*, and is not co-located with the code that
must obey the decisions.

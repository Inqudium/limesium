# ADR-0004: The default correlation id is a counting id, not a UUID

- **Status:** accepted
- **Date:** 2026-08-30 (records the decision shipped in commit `7c1365d`;
  written after the fact per finding 2 of
  `docs/assessment/CODE_ANALYSIS-2026-08-30T21-52-43.md`, which flagged
  the change's missing decision trail)
- **Context:** The previous default, `CorrelationIdGenerator.RANDOM_UUID`,
  drew 16 bytes from the process-wide, statically shared `SecureRandom`
  on every traceless request. That is the wrong shape for this library's
  hot path twice over: the native provider's reseeding reads a system
  entropy source behind a monitor - blocking work on a reactive event
  loop and a pinning point under virtual threads. The latency is
  unlikely to be visible in a logging pipeline; the structural argument
  (no shared lock, no I/O per request) motivated the change.

## Decision

**`CorrelationIdGenerator.DEFAULT` is a `CountingCorrelationIdGenerator`:
a random per-instance base-36 prefix (13 chars, seeded once from
`SecureRandom` at construction) followed by a monotonically increasing
counter (8 chars) - 21 lowercase alphanumeric characters, fixed width,
lexicographically ordered per instance.** Uniqueness within an instance
is guaranteed (the counter never repeats); across instances it is
probabilistic with 64 bits of prefix entropy. The full rationale
(entropy source, widths, ordering, failure modes) lives on the class.

The public constant `RANDOM_UUID` was REMOVED, not deprecated: with the
ADR-0003 package move already source-breaking for hosts that import the
generator types, the same release is the cheapest moment to drop the
old name instead of carrying it as dead API.

## Consequences

- **Breaking at the boundary, to be release-noted together with the
  ADR-0002/0003 changes:** hosts referencing `RANDOM_UUID` fail to
  compile (replace with `DEFAULT`, or pin a UUID generator bean of
  their own); consumers that parse or validate the echoed
  `X-Correlation-Id` see 21-char base-36 ids instead of 36-char UUIDs.
- Ids from one instance sort in allocation order - usable as a
  tiebreaker for same-timestamp log entries; not a global sort key.
- `DEFAULT` is a JVM-global singleton: every context in a JVM shares
  one prefix and one counter, which preserves uniqueness (a fresh
  context does not restart the sequence).
- A host that requires UUIDs (compliance, tooling) overrides the
  `CorrelationIdGenerator` bean - the extension point is unchanged.

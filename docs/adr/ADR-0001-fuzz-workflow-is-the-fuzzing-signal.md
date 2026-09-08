# ADR-0001: The Fuzz workflow, not the Scorecard score, is the fuzzing signal

**Status:** Accepted  
**Date:** 2026-08-30  
**Deciders:** Dirk Haase (maintainer)

## Context

The OpenSSF Scorecard **Fuzzing** check dropped to 0 ("project is not
fuzzed") although the nightly Fuzz workflow runs the Jazzer `@FuzzTest`
targets green.

The cause was verified against the Scorecard v5.5.0 source
(`checks/raw/fuzzing.go`, commit `c395761d`). Scorecard does support
Jazzer: it greps `*.java` files for
`com.code_intelligence.jazzer.api.FuzzedDataProvider;`, which the fuzz
tests import verbatim. But the language-specific scan only runs for
"prominent" languages, defined as a byte share of at least
(total ÷ languages) ÷ 4 per GitHub's linguist statistics. With exactly
two detected languages (Kotlin + Java) that means Java needs ≥ 12.5 %
of the repository's bytes; it sat at ~5.4 % (39,955 of 744,018 bytes
on 2026-08-30). The Java fuzz tests are therefore never scanned, and
the score flips 10↔0 whenever the Kotlin:Java byte ratio crosses 7:1,
with ordinary commits.

This ADR also establishes the ADR series itself as a stable reference
target for future decisions (`ADR-NNNN`), following the convention of
the sibling project tabellarium. The record format is described in
[ADR-FORMAT.md](ADR-FORMAT.md); the number is never reused or changed
because it is cited from code comments, the CHANGELOG and review
reports.

## Decision

**The Fuzz workflow's run history is the authoritative fuzzing signal.
The Scorecard Fuzzing score is accepted as 0 (or flapping) and is not
acted on.**

The reader-facing consequence lives in `SECURITY.md` (Scorecard scope
note, PR #15): the badge's Fuzzing line tracks the language ratio, not
the fuzzing coverage.

**Revisit when:** Scorecard drops the prominent-language gate for fuzz
detection, adds jazzer-junit `@FuzzTest` or Kotlin detection, or the
project joins OSS-Fuzz (which is detected independently of language).

## Consequences

**Positive:**

- The fuzzing evidence stays where it is produced: the Fuzz workflow's
  run history, with the targets and their invariants in the test
  bodies.
- "Fuzzing is 0 again" is answered by this ADR; neither direction of a
  flip warrants action.

**Negative:**

- The overall Scorecard score carries a standing deduction of medium
  weight; this is accepted alongside the other single-maintainer
  deductions already documented in `SECURITY.md`.
- The Fuzzing score may flip back to 10 (or to 0 again) without any
  change in fuzzing coverage, so the badge misrepresents the project in
  both directions.

**Neutral:**

- The fuzz tests remain Java classes under `src/test/java`, which is
  what jazzer-junit requires; the language ratio is a by-product of the
  codebase, not a lever this project pulls.

## Considered and rejected

- **Introducing ClusterFuzzLite** to satisfy the detector (it is
  detected by file presence, `.clusterfuzzlite/Dockerfile`). The
  Inqudium projects deliberately avoid it: its OSS-Fuzz base images are
  pinned to JDK 17, while this project builds on a newer JDK.
- **Gaming the linguist statistics** (`.gitattributes` overrides, or
  inflating the Java share) so that Java crosses the 12.5 % line. The
  language statistics would then misrepresent the codebase to fix a
  number that misrepresents the fuzzing.
- **Converting the fuzz tests to Kotlin** would not help either way:
  Scorecard has no Kotlin fuzzer spec at all.

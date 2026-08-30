# ADR-0001: The Fuzz workflow, not the Scorecard score, is the fuzzing signal

- **Status:** accepted
- **Date:** 2026-08-30
- **Context:** The OpenSSF Scorecard **Fuzzing** check dropped to 0
  ("project is not fuzzed") although the nightly Fuzz workflow runs the
  Jazzer `@FuzzTest` targets green. The cause was verified against the
  Scorecard v5.5.0 source (`checks/raw/fuzzing.go`, commit `c395761d`):
  Scorecard does support Jazzer — it greps `*.java` files for
  `com.code_intelligence.jazzer.api.FuzzedDataProvider;`, which the
  fuzz tests in both modules import verbatim — but the
  language-specific scan only runs for "prominent" languages, defined
  as a byte share of at least (total ÷ languages) ÷ 4 per GitHub's
  linguist statistics. With exactly two detected languages
  (Kotlin + Java) that means Java needs ≥ 12.5 % of the repository's
  bytes; it sits at ~5.4 % (39,955 of 744,018 on 2026-08-30). The Java
  fuzz tests are therefore never scanned, and the score flips 10↔0
  whenever the Kotlin:Java byte ratio crosses 7:1, with ordinary
  commits. This ADR also establishes the ADR series itself as a stable
  reference target for future decisions (`ADR-nnnn`), following the
  convention of the sibling project tabellarium.

## Decision

**The Fuzz workflow's run history is the authoritative fuzzing signal;
the Scorecard Fuzzing score is accepted as 0 (or flapping) and is not
acted on.**

Rejected alternatives:

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

The reader-facing consequence lives in `SECURITY.md` (Scorecard scope
note, PR #15): the badge's Fuzzing line tracks the language ratio, not
the fuzzing coverage.

## Consequences

- The Fuzzing score may flip back to 10 (or to 0 again) without any
  change in fuzzing coverage; neither direction warrants action, and
  "Fuzzing is 0 again" is answered by this ADR.
- The overall Scorecard score carries a standing deduction of medium
  weight; this is accepted alongside the other single-maintainer
  deductions already documented in `SECURITY.md`.
- Revisit if Scorecard drops the prominent-language gate for fuzz
  detection, adds jazzer-junit `@FuzzTest` or Kotlin detection, or if
  the project ever joins OSS-Fuzz (detected independently of language).

# Architecture Decision Records

This directory holds the Architecture Decision Records for
**limesium**. Every record follows the shape in
[ADR-FORMAT.md](ADR-FORMAT.md). ADRs are numbered
`ADR-NNNN-short-kebab-slug.md`; the number is never reused or changed,
because it is cited from code comments, the POMs, the CHANGELOG, and
review reports. The **code is authoritative**: where an ADR and the
code disagree, the code wins and the ADR is corrected.

New to the project? Start with
[ADR-0003](ADR-0003-limesium-common-inlined-by-shade.md), which
explains why there are two twin modules plus an unpublished common
module and where a given piece of code lives, then read
[ADR-0002](ADR-0002-trace-id-is-the-request-id.md) for the identity
each exchange line carries, and finally the cluster you are touching.

## Index by topic

### Exchange identity & wire contract

| ADR | Title | Status |
|-----|-------|--------|
| [0002](ADR-0002-trace-id-is-the-request-id.md) | The trace id is the request id; the correlation echo is the traceless fallback | Accepted |
| [0004](ADR-0004-counting-correlation-id-default.md) | The default correlation id is a counting id, not a UUID | Accepted |
| [0007](ADR-0007-a-4xx-response-is-rejected.md) | A 4xx response is `rejected`, at INFO on every status | Accepted |

### Module structure & build

| ADR | Title | Status |
|-----|-------|--------|
| [0003](ADR-0003-limesium-common-inlined-by-shade.md) | Byte-identical twin code moves to limesium-common, inlined by Shade | Accepted; last updated 2026-09-05 |

### Configuration & logged content

| ADR | Title | Status |
|-----|-------|--------|
| [0005](ADR-0005-headers-masked-by-default.md) | Logged header values are masked by default; plaintext is an explicit allowlist | Accepted |
| [0006](ADR-0006-bodies-logged-by-outcome.md) | Body logging is a mode per direction, gated by the outcome | Accepted; last updated 2026-09-19 |

### Operator surface & observability

| ADR | Title | Status |
|-----|-------|--------|
| [0008](ADR-0008-startup-wiring-report-is-a-bounded-diagnostic.md) | The startup wiring report is a bounded diagnostic: DEBUG says what is in effect, TRACE where it came from | Accepted |

### Conventions & project process

| ADR | Title | Status |
|-----|-------|--------|
| [0001](ADR-0001-fuzz-workflow-is-the-fuzzing-signal.md) | The Fuzz workflow, not the Scorecard score, is the fuzzing signal | Accepted; also establishes the ADR series as reference target |

## Superseded

None yet. A fully superseded record keeps its number, gets the status
`Superseded by ADR-NNNN (YYYY-MM-DD)`, and moves to this table; it
must not be treated as current guidance.

## Notes

- **Format & conventions:** [ADR-FORMAT.md](ADR-FORMAT.md).
- **Evidence:** the review reports that ADRs cite live in
  [`../assessment/`](../assessment/) (repository-wide) and in
  `<module>/docs/assessment/` (module-scoped); they are dated
  snapshots and are not updated when an ADR changes.
- **Siblings:** several decisions were taken first in the outbound
  sibling legatium or follow the convention of tabellarium; the ADR
  says so where it applies, but each project keeps its own record.

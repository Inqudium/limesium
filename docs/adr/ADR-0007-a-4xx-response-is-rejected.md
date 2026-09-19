# ADR-0007: A 4xx response is `rejected`, at INFO on every status

**Status:** Accepted  
**Date:** 2026-09-19  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0006 (the body gate, whose 4xx special case this
ADR removes), ADR-0003 (`StatusClassification` lives in
`limesium-common` by its criterion); legatium's ADR-0012 (the
outbound sibling's decision of the same day, which this one mirrors)

## Context

The exchange line carries two independent things: the SLF4J level
and `endpoint_outcome`. The level is the severity an operator's
alerting keys on; the outcome is the semantic a dashboard splits by
(Common guide §5.3). Until this decision, both twins resolved them
so: a thrown chain is ERROR `failure`, the stack's own disposition
is WARN `timeout` (servlet) or WARN `cancelled` (reactive), a 5xx
without an exception is WARN `failure`, and everything else, every
4xx included, is INFO `success`. Slowness escalates INFO to WARN
without changing the outcome.

The question came up on the outbound sibling legatium: should a 4xx
not be a WARN? There the caller of the line is the application
itself, so a refused request can be its own defect. Legatium's
ADR-0012 records the answer: a blanket WARN fails on the 4xx that
are regular answer paths (a 404 on a lookup, a 409 of optimistic
locking, a 422 for an end user's forwarded input), the module sees
the status and not the cause, and what the old classification
lacked was not severity but semantics. `success` covered a 200 and
a 404 alike, so a dashboard that wanted the 4xx share had to query
the status field, and ADR-0006 had to widen its body gate beyond
the vocabulary by hand (`outcome != success || status in 400..499`)
for exactly that gap.

The family's vocabulary is one contract across the inbound and the
outbound line: `success`, `failure`, and a fourth value per stack.
A value the outbound line gains must mean the same on the inbound
line, or the pair stops being a pair.

Inbound, the case for the level is clearer than outbound. A 4xx
here says the foreign caller made a mistake and the application
answered as designed. Scanners, expired tokens, stale links and
rate-limited clients produce 401, 403, 404 and 429 all day on an
exposed API; at WARN they would drown the 5xx and the stack's own
dispositions that WARN exists for. Nothing in a refused inbound
request is the operator's to act on.

## Decision

**A 4xx response is a `rejected` exchange, at INFO for every status
of the class.** The outcome vocabulary is the responsibility axis
of an exchange, the level its severity; the two stay decoupled.

### The outcome names who is responsible

| `endpoint_outcome` | Meaning                                                    | Responsible                     |
|--------------------|------------------------------------------------------------|---------------------------------|
| `success`          | the application answered below 400                         | nobody                          |
| `rejected`         | the application answered 4xx: the caller's request refused | the caller, the foreign party   |
| `failure`          | the application answered 5xx, or the chain threw           | the application                 |
| `timeout`          | the container's async cycle timed out (servlet stack)      | the clock                       |
| `cancelled`        | the subscription was cancelled (reactive stack)            | the caller, by disconnecting    |

`rejected` is one value for the whole 4xx class, as `failure` is one
value for the whole 5xx class: the status is on the line as a
number for the finer split.

### The classification

Resolved in this order in each twin's `ExchangeLogEmitter`; the
status half is `StatusClassification.levelAndOutcome` in
`limesium-common`, one function for both twins (ADR-0003):

| Condition                                              | Level          | `endpoint_outcome`      |
|--------------------------------------------------------|----------------|-------------------------|
| the chain threw or signalled an error                  | `ERROR`        | `failure`               |
| the stack's own disposition                            | see §5.3       | `timeout` / `cancelled` |
| status ≥ 500 without any of the above                  | `WARN`         | `failure`               |
| status 4xx without any of the above                    | `INFO`         | `rejected`              |
| otherwise                                              | `INFO`         | `success`               |
| … and the duration reached the slow threshold          | `INFO → WARN`  | unchanged               |

### No escalation on the inbound line

Legatium lifts four rejections to WARN (401, 403, 408, 429): there
they are about the application's own standing with the peer, which
only an operator can resolve. Inbound, the same statuses are about
the caller's standing with the application, which the caller
resolves. They stay INFO here, and the twins' suites pin exactly
those four at INFO so the asymmetry is a decision, not a drift. A
408 is not a `timeout` on either line: `timeout` means the
container's async cycle ended without an answer, a 408 is an answer
the application chose to send.

### The body gate follows

`on-failure` body logging writes the bodies when the outcome is not
`success`. A `rejected` exchange is not a success, so the 4xx
bodies stay logged (the case a body explains best, ADR-0006), and
the hand-written widening of that gate by the status range is gone:
the gate reads `outcome != success` again, in both twins.

**Verification.** `StatusClassificationTest` in `limesium-common`
pins the table, including the four statuses the sibling escalates,
at INFO. Each twin's suite pins that its emitter calls the shared
function (a 404 at INFO `rejected`, the four statuses at INFO), the
`on-failure` body test that a rejected exchange logs its bodies, and
the contract tests that `rejected` is pre-registered on the events
counter of both stacks.

## Consequences

**Positive:**

- The outcome vocabulary is complete: every exchange is attributed
  to a side, and a dashboard splits the 4xx share off without a
  status query.
- The vocabulary stays one contract with the outbound sibling: a
  `rejected` line means "the caller's request was refused" on both.
- WARN keeps its meaning on an exposed API: a broken endpoint, a
  timed-out or abandoned exchange, a slow one, never a scanner's 404.
- ADR-0006's gate is the plain rule again: bodies for every outcome
  but `success`.

**Negative:**

- **Log and meter contract change:** `success` no longer includes a
  4xx. A host's dashboard or alert that keyed on `success` as "the
  application answered" sees the 4xx share move to `rejected`; the
  `outcome` tag of `endpoint.logging.events` gains the value,
  pre-registered at zero like the others. Recorded in the CHANGELOG.
- With the exchange logger set to WARN as the volume control, every
  `rejected` line is gone together with the healthy ones; the status
  distribution of `http.server.requests` remains for them.

**Neutral:**

- The level of a 4xx is what it was since the first release: INFO.
- Should an inbound escalation ever be wanted (a 429 the application
  itself imposes to protect a dependency, say), it is a change of
  this ADR, not a property.

# ADR-0002: The trace id is the request id; the correlation echo is the traceless fallback

**Status:** Accepted  
**Date:** 2026-08-30  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0003 (the `Traceparent` parser and `Mdc.kt` this ADR
made byte-identical across the twins were extracted into
`limesium-common`), ADR-0004 (the generator that produces the id on
traceless exchanges)

## Context

A request logger must be observationally neutral: whether exchange
logging is enabled or disabled must not change the visible HTTP
communication. At the time of this decision the module violated that
on every response. It unconditionally wrote the `X-Correlation-Id`
response header (echoing the caller's id, or a freshly generated
UUID), so switching the logger on altered the wire contract for every
client.

The two identities on the exchange line were also sourced
asymmetrically: the reactive twin parsed the incoming W3C
`traceparent` header itself (`Traceparent.kt`), while the servlet twin
captured `traceId`/`spanId` from the MDC that the host's Micrometer
tracing bridge maintains. And the correlation id
(`endpoint_request_id`) was an entirely separate,
always-generated-or-accepted value, even when the caller had already
supplied a perfectly good distributed identity in `traceparent`.

## Decision

**Both twins source the trace id from the incoming `traceparent`
header, the trace id doubles as the request id, and the
`X-Correlation-Id` echo happens only on traceless exchanges.**

1. **Trace id from the header, in both twins.** The servlet twin
   adopts the reactive twin's `traceparent` parsing (lockstep twin
   code, no cross-module dependency; the strict W3C validation and its
   fuzz target come along). Its Micrometer-bridge MDC capture is
   retired; like the reactive twin it publishes the header's trace id
   as `traceId` and the caller's span as `parentSpanId`, never as
   `spanId`. A `traceparent` that fails W3C validation counts as
   absent.
2. **An available trace id is the request id.** When the incoming
   `traceparent` is conformant, `endpoint_request_id` carries its
   trace id. A caller-supplied `X-Correlation-Id` is ignored on such
   exchanges: the distributed identity outranks the private one.
3. **A correlation id is generated only on traceless exchanges.** When
   no (valid) `traceparent` is present, the previous behaviour is
   unchanged: an incoming `X-Correlation-Id` is accepted, otherwise a
   new id is generated. The directive constrains *generation*;
   accepting a caller-supplied id in the traceless case remains. This
   interpretation is deliberate and recorded here.
4. **The echo is conditional.** Only a traceless exchange gets the
   `X-Correlation-Id` response header (same name, same always-echo
   semantics as before, so a client without tracing still learns the
   id it can quote). When a `traceparent` header is present, the module
   writes no `X-Correlation-Id` response header: the exchange passes
   through observationally untouched.
5. **The MDC always carries a request id.** In every case, trace-derived
   or generated, the winning id is set as the `endpoint_request_id`
   MDC value (`MdcKeys.REQUEST_ID`) in both twins, exactly where each
   twin maintains its MDC scope. Neutrality is a wire property; inside
   the process the identity is unconditional, so application log lines
   and the exchange line always join on it.

**Implementation order:** this ADR records the contract first; the
code follows it, not the other way around. The change lands in
lockstep across both twins: filter wiring, `Traceparent` twin code,
MDC scopes, metrics (`correlationId(fromHeader=...)` gains the
trace-sourced case), the GUIDEs, `endpoint-logging-reference.yml`, the
READMEs, and the test and fuzz suites.

## Consequences

**Positive:**

- **Traced exchanges become neutral.** With a conformant `traceparent`
  the module adds no header and invents no identity; enabling or
  disabling the logger is invisible on the wire.
- **Application log lines always join the exchange line.** The MDC
  request id is unconditional, whichever source won.

**Negative:**

- **`endpoint_request_id` changes cardinality on traced exchanges.**
  All exchanges under one trace (at this service and every other
  limesium-instrumented service) share the request id, because it IS
  the trace id. Per-exchange uniqueness is only guaranteed for
  self-generated ids; per-exchange log lines remain distinguishable by
  their remaining fields.
- **Locally rooted traces are no longer joined by the servlet twin.**
  Without an incoming `traceparent`, a trace the host's bridge started
  locally is not captured any more, matching what the reactive twin
  always did. Such exchanges carry a generated request id and no trace
  fields.
- **Breaking change at the boundary.** Clients that send `traceparent`
  and rely on the `X-Correlation-Id` response header lose it, and
  callers' `X-Correlation-Id` values no longer win over `traceparent`.
  The change ships with a version bump and release-note callout.

**Neutral:**

- The traceless echo remains a deliberate, documented service to
  clients that have no tracing infrastructure, and is the one remaining
  visible effect of enabling the logger.

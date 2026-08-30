# limesium-servlet-logging

Auto-configured servlet filter for Spring Boot (Tomcat) applications that logs **one structured line per
HTTP exchange** and carries the exchange identity in the MDC while the request is handled.

Design in brief:

- One final `RequestLoggingFilter`; sync and async share a single, exactly-once emission path.
- Injectable `NanoTimeSource` — deterministic tests, no sleeps.
- Identity per ADR-0002: a conformant `traceparent`'s trace id **is** the request id (no echo, the
  wire stays untouched); only traceless exchanges adopt/echo `X-Correlation-Id` via the injectable
  `CorrelationIdGenerator`.
- Passive bounded **tee** (`BoundedBodyCapture`) — nothing buffered, replayed, or withheld;
  async-safe by construction.
- SLF4J fluent API with `addKeyValue` — structured encoders pick the fields up directly.
- Boot auto-configuration + `endpoint-logging.*` properties; the functional beans (filter, time source, id generator) overridable.
- `endpoint_request_id`, `endpoint_method`, `endpoint_route` in the MDC for the whole chain,
  previous values restored.

Deliberately **out of scope**: body masking transformers and per-key response sampling. (Header masking
exists as the `masked` list per header section; an optional arrival line can announce the request
before the handler runs.)

The long-form guide — introduction, architecture, integration into a foreign project, configuration,
metrics and the stack-specific behaviours — is [`docs/GUIDE.md`](docs/GUIDE.md).

## Usage

Add the module to a servlet-stack Spring Boot application — the filter registers itself:

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-servlet-logging</artifactId>
</dependency>
```

Example line (on the `http-exchange` logger):

```
Endpoint http exchange GET /api/things -> 200 [endpoint_request_id=0f7c...]
```

plus the structured `endpoint_*` key-values: the wire names are a contract with
the log index, each field owns its JSON shape (`EndpointLogFields.kt`), a badly typed value drops that
field with a warning but never the event, and the request id rides the MDC (plus the message suffix
for plain-text appenders) rather than a key-value. The index-side mapping ships as a component template
in [`/docs/elk/`](../docs/elk/README.md), kept in lockstep with the enum by `EndpointLogFieldTest`.

| Field | Shape | When |
|---|---|---|
| `endpoint_outcome` | keyword | always — `success` / `failure` / `timeout`; decoupled from the level |
| `endpoint_duration_ms` | long | always |
| `endpoint_request_method` | keyword | always |
| `endpoint_response_status_code` | short | always |
| `endpoint_url_path` | keyword | always — expanded path, high cardinality |
| `endpoint_url_template` | keyword | when Spring MVC recorded a handler pattern — the aggregation half |
| `endpoint_url_query` | keyword | when the request carried one and query logging is on |
| `endpoint_async` | boolean | always |
| `endpoint_slow` | boolean | only when the slow threshold was reached |
| `endpoint_request_headers` / `endpoint_response_headers` | keyword, display-only | when selected headers are present |
| `endpoint_request_body` / `endpoint_response_body` | keyword, display-only | when body capture is on and bytes actually flowed |

## Configuration (`endpoint-logging.*`)

A complete, commented reference configuration with every property at its default lives in
[`/docs/endpoint-logging-reference.yml`](../docs/endpoint-logging-reference.yml) — copy the block and change
only what you need. `EndpointLoggingReferenceConfigTest` keeps it in lockstep with the code: every key
must exist, every value must be the built-in default.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` removes the filter (auto-configuration backs off entirely) |
| `logger-name` | `http-exchange` | Logger of the exchange lines (dedicated name, so routing/levels can target exactly these lines) |
| `correlation-id-header` | `X-Correlation-Id` | Header the id is read from and echoed to on traceless exchanges (ADR-0002) |
| `include-query-string` | `true` | Append the query string to the logged path |
| `log-request-start` | `false` | Additionally log an arrival line before the chain runs — it carries no outcome/status/duration, so outcome-keyed dashboards still count one line per exchange |
| `include-path-patterns` | *(empty)* | URL patterns (Spring `PathPattern`, e.g. `/api/**`) the filter is active for at all; empty = every endpoint. A request is logged when it matches any include and no exclude — the exclude wins |
| `exclude-path-prefixes` | *(empty)* | Request-URI prefixes that are not logged at all |
| `slow-request-threshold` | `5s` | At/above this duration the line escalates to WARN and is flagged `slow` |
| `request-headers.*` / `response-headers.*` | *(empty)* | Per-direction sections with `includes` (names or `*`), `excludes`, and `masked` — masked values become a stable `length:hash` fingerprint (equal values, equal fingerprint) |
| `log-request-body` / `log-response-body` | `false` | Capture bodies as they flow (tee, never a pre-read) |
| `max-body-bytes` | `16384` | Capture limit per body; beyond it the log truncates (and says so), the exchange is untouched |
| `measure-request-body-size` / `measure-response-body-size` | `false` | Count body bytes for the size meters (`endpoint.request/response.body.size`) without logging content |

Levels carry severity only (`endpoint_outcome` carries the semantic): ERROR when the chain threw (the
exception is rethrown unchanged) or the async lifecycle reported an error, WARN for a 5xx, a container timeout, or a slow-but-successful exchange,
INFO otherwise.

## Emission point

The event is emitted at **request destruction** (`ServletRequestListener.requestDestroyed`, registered by
the auto-configuration) — after the container's error dispatch and, for async exchanges (`suspend`
controllers, `DeferredResult`, `Callable`), after completion. So the logged status is the one the client
actually received (a crashed exchange logs the rendered `500`, not the pre-dispatch `200`), and
`endpoint_duration_ms` measures until processing truly ended — request occupancy including error
rendering, not bare chain time. A burst of terminal events still yields exactly one line.

When the chain throws, a short WARN breadcrumb is additionally logged immediately (on the module's own
logger, not the exchange logger — its one-event-per-exchange contract holds), so the failure is visible
the moment it happens while the full ERROR event follows at request destruction.

**Trace integration:** the incoming W3C `traceparent` header is parsed at filter entry (strict W3C
validation, lockstep with the reactive twin) and restored around the emission — the destruction
callback's thread carries no per-request state — so the exchange event stays joinable with its trace:
as MDC fields for structured encoders (`traceId`, and the caller's span as `parentSpanId`, never as
the local `spanId`), and inline in the message (`… [endpoint_request_id=… traceId=… parentSpanId=…]`)
for plain-text appenders. Without a conformant header, nothing is decorated and the request id is the
accepted or generated correlation id (ADR-0002).

## Metrics

Six meters, all fed from the host's `MeterRegistry` when one exists (actuator); without one a private
registry absorbs the values and the module works unchanged. Rates, latencies and status distributions are
deliberately left to Boot's own `http.server.requests` and to the structured log fields.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `endpoint.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed: `emission` = an exchange event was **lost**, `arrival` = a start line was lost, `wiring` = wiring or bookkeeping around the chain failed - a pre-chain wiring failure degrades the filter to an unlogged pass-through, a post-chain one usually still emits the event. A lost log line cannot reliably report itself through the same pipeline — this counter is the independent channel. |
| `endpoint.logging.events` | counter | `outcome` | Exchange events actually **emitted** (after the level gate; arrival lines excluded). The reconciliation ground truth: compare its sum against the count of indexed events — any difference is loss in the log pipeline itself (appender overflow, broker loss, index rejection). |
| `endpoint.request.body.size` / `endpoint.response.body.size` | distribution summary (bytes) | `uri` (handler pattern, `UNKNOWN` without one) | Bytes that **actually flowed**, opt-in via `measure-request-body-size` / `measure-response-body-size` and independent of body logging and log level. Exact beyond `max-body-bytes` (the tee counts past the capture cap); zero-byte bodies record no sample. |
| `endpoint.request.body.read` | counter | `uri` (handler pattern), `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the request body, opt-in via `measure-request-body-size`. The tee mirrors consumption, not transmission, so neither the logged body nor the size sample can tell a body the client sent but the application ignored from one that was never sent — this counter can. `partial` = consumption started but the end of the stream was never observed (an early-exiting parser, an exception mid-read). |
| `endpoint.logging.exchanges.open` | gauge | — | Exchanges between filter entry and request destruction. Hovers near the active-request count in health; a **monotonically growing baseline** means `requestDestroyed` is not firing and events are lost silently — the one failure mode neither the fail-open counter (nothing throws) nor the events counter (no baseline) can see. |
| `endpoint.logging.correlation.id` | counter | `source` = `trace` \| `header` \| `generated` | Origin of each exchange's request id (ADR-0002). A rising `generated` share means the upstream (gateway, sidecar) stopped propagating traceparent or the correlation header. |

## The reactive twin and the shared layer

[`limesium-reactive-logging`](../limesium-reactive-logging/README.md) is the WebFlux twin of this module:
identical message format, field family, `endpoint-logging.*` configuration and meters, so that a
dashboard or index mapping never cares which stack produced an event. This module is the **reference
implementation** — the configuration reference (`/docs/endpoint-logging-reference.yml`) and the ELK
component template (`/docs/elk/`) live in the repository-shared `/docs` and are bound by both
modules' lockstep tests.

The **byte-identical** part of the shared layer (the `traceparent` parser with its fuzz target, the
injectable time/id interfaces, `reportQuietly`, the MDC keys and scope) lives in the internal
`limesium-common` module and is **inlined into this jar** by the Maven Shade plugin
([ADR-0003](../docs/adr/ADR-0003-limesium-common-inlined-by-shade.md)): consumers add exactly one
artifact, the published POM carries no extra dependency, and `limesium-common` itself is never
published.

Everything whose twin copies genuinely differ (field enum and metrics with their per-stack outcome
vocabulary, emitters, exchanges, properties, body capture) stays **deliberately duplicated**, per the
original architecture-review decision: one twin per host, standalone jars, contract-level code that
changes rarely. For that remainder every change is still a conscious port in *both* directions; the
pins in `TwinContractTest` and the cross-module tests catch *named* contract drift, not behavioural
drift — a change there must be ported consciously and verified in both modules.

## Overriding

Define your own bean to replace a default: `NanoTimeSource`, `CorrelationIdGenerator`, or a complete
`RequestLoggingFilter`. A custom filter bean takes over the *filter*, not the wiring: the auto-configured
`FilterRegistrationBean` (order, URL mapping) and the request-destruction listener are still registered
around it, so the emission point stays intact. Set `endpoint-logging.enabled=false` to remove the
registration entirely.

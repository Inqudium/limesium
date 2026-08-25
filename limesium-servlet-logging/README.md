# limesium-servlet-logging

Auto-configured servlet filter for Spring Boot (Tomcat) applications that logs **one structured line per
HTTP exchange** and carries the exchange identity in the MDC while the request is handled.

Design in brief:

- One final `RequestLoggingFilter`; sync and async share a single, exactly-once emission path.
- Injectable `NanoTimeSource` — deterministic tests, no sleeps.
- Injectable `CorrelationIdGenerator`, header adoption + response echo.
- Passive bounded **tee** (`BoundedBodyCapture`) — nothing buffered, replayed, or withheld;
  async-safe by construction.
- SLF4J fluent API with `addKeyValue` — structured encoders pick the fields up directly.
- Boot auto-configuration + `endpoint-logging.*` properties; every bean overridable.
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
field with a warning but never the event, and the correlation id rides the MDC (plus the message suffix
for plain-text appenders) rather than a key-value. The index-side mapping ships as a component template
in [`docs/elk/`](docs/elk/README.md), kept in lockstep with the enum by `EndpointLogFieldTest`.

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
[`docs/endpoint-logging-reference.yml`](docs/endpoint-logging-reference.yml) — copy the block and change
only what you need. `EndpointLoggingReferenceConfigTest` keeps it in lockstep with the code: every key
must exist, every value must be the built-in default.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` removes the filter (auto-configuration backs off entirely) |
| `logger-name` | `http-exchange` | Logger of the exchange lines (dedicated name, so routing/levels can target exactly these lines) |
| `correlation-id-header` | `X-Correlation-Id` | Header the id is read from and echoed to |
| `include-query-string` | `true` | Append the query string to the logged path |
| `log-request-start` | `false` | Additionally log an arrival line before the chain runs — it carries no outcome/status/duration, so outcome-keyed dashboards still count one line per exchange |
| `include-path-patterns` | *(empty)* | URL patterns (Spring `PathPattern`, e.g. `/api/**`) the filter is active for at all; empty = every endpoint. A request is logged when it matches any include and no exclude — the exclude wins |
| `exclude-path-prefixes` | *(empty)* | Request-URI prefixes that are not logged at all |
| `slow-request-threshold` | `5s` | At/above this duration the line escalates to WARN and is flagged `slow` |
| `request-headers.*` / `response-headers.*` | *(empty)* | Per-direction sections with `includes` (names or `*`), `excludes`, and `masked` — masked values become a stable `length:hash` fingerprint (equal values, equal fingerprint) |
| `log-request-body` / `log-response-body` | `false` | Capture bodies as they flow (tee, never a pre-read) |
| `max-body-bytes` | `4096` | Capture limit per body; beyond it the log truncates (and says so), the exchange is untouched |

Levels carry severity only (`endpoint_outcome` carries the semantic): ERROR when the chain threw (the
exception is rethrown unchanged), WARN for a 5xx, a container timeout, or a slow-but-successful exchange,
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

**Trace integration:** when a Micrometer tracing bridge is present, the bridge's `traceId`/`spanId` MDC
entries are captured at filter entry and restored around the emission — the destruction callback's thread
has lost them — so the exchange event stays joinable with its trace: as MDC fields for structured
encoders, and inline in the message (`… [endpoint_request_id=… traceId=… spanId=…]`) for plain-text
appenders. Without a bridge, nothing is captured and nothing is decorated.

## Metrics

Six meters, all fed from the host's `MeterRegistry` when one exists (actuator); without one a private
registry absorbs the values and the module works unchanged. Rates, latencies and status distributions are
deliberately left to Boot's own `http.server.requests` and to the structured log fields.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `endpoint.logging.failopen` | counter | `stage` = `emission` \| `arrival` \| `wiring` | Logging failures the fail-open path swallowed: `emission` = an exchange event was **lost**, `arrival` = a start line was lost, `wiring` = post-chain bookkeeping failed (the event usually still follows). A lost log line cannot reliably report itself through the same pipeline — this counter is the independent channel. |
| `endpoint.logging.events` | counter | `outcome` | Exchange events actually **emitted** (after the level gate; arrival lines excluded). The reconciliation ground truth: compare its sum against the count of indexed events — any difference is loss in the log pipeline itself (appender overflow, broker loss, index rejection). |
| `endpoint.request.body.size` / `endpoint.response.body.size` | distribution summary (bytes) | `uri` (handler pattern, `UNKNOWN` without one) | Bytes that **actually flowed**, opt-in via `measure-request-body-size` / `measure-response-body-size` and independent of body logging and log level. Exact beyond `max-body-bytes` (the tee counts past the capture cap); zero-byte bodies record no sample. |
| `endpoint.request.body.read` | counter | `uri` (handler pattern), `state` = `unread` \| `partial` \| `complete` | How far the application **consumed** the request body, opt-in via `measure-request-body-size`. The tee mirrors consumption, not transmission, so neither the logged body nor the size sample can tell a body the client sent but the application ignored from one that was never sent — this counter can. `partial` = consumption started but the end of the stream was never observed (an early-exiting parser, an exception mid-read). |
| `endpoint.logging.exchanges.open` | gauge | — | Exchanges between filter entry and request destruction. Hovers near the active-request count in health; a **monotonically growing baseline** means `requestDestroyed` is not firing and events are lost silently — the one failure mode neither the fail-open counter (nothing throws) nor the events counter (no baseline) can see. |
| `endpoint.logging.correlation.id` | counter | `source` = `header` \| `generated` | Origin of each exchange's correlation id. A rising `generated` share means the upstream (gateway, sidecar) stopped propagating the correlation header. |

## The reactive twin — deliberate duplication, no shared base module

[`limesium-reactive-logging`](../limesium-reactive-logging/README.md) is the WebFlux twin of this module:
identical message format, field family, `endpoint-logging.*` configuration and meters, so that a
dashboard or index mapping never cares which stack produced an event. This module is the **reference
implementation** — the configuration reference (`docs/endpoint-logging-reference.yml`) and the ELK
component template (`docs/elk/`) are shipped here and bound by the twin's lockstep tests.

Eleven production files exist in both modules, roughly a thousand lines identical (field enum, properties
and header masking, meters, MDC keys, event rendering, the injectable time/id interfaces). This is a
**deliberate decision**, reviewed in an internal architecture review and kept:

- **One twin per host, never both.** An application is either a servlet or a reactive application, so
  the two copies never share a classpath — there is no runtime drift to guard against, only an
  organisational contract (dashboards, alerts, index mapping), which the lockstep tests pin.
- **Standalone by design.** Each twin is one jar with no dependency on the other, and no third artifact
  to version, release, and keep from becoming a dumping ground. With exactly two consumers, a base
  module sits at — not beyond — the rule-of-three threshold.
- **The shared layer changes rarely.** It is contract-level code (wire names, configuration keys, meter
  names, rendering) that stabilises after the initial remediation rounds; porting an occasional change
  by hand is cheaper than carrying a module boundary for it.

**Accepted residual cost:** every change to the shared layer is a port — in *both* directions, since
fixes originate in whichever twin an analysis hit first. The pins in `TwinContractTest` and the twin's
cross-module tests catch *named* contract drift (meter names, field names, configuration keys), not
behavioural drift inside the identical emitter or metrics code; a change there must be ported
consciously and verified in both modules. Revisit the decision if a third stack appears or the port
frequency stops being occasional.

## Overriding

Define your own bean to replace a default: `NanoTimeSource`, `CorrelationIdGenerator`, or a complete
`RequestLoggingFilter`. A custom filter bean takes over the *filter*, not the wiring: the auto-configured
`FilterRegistrationBean` (order, URL mapping) and the request-destruction listener are still registered
around it, so the emission point stays intact. Set `endpoint-logging.enabled=false` to remove the
registration entirely.

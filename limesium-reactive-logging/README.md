# limesium-reactive-logging

The **WebFlux twin of [`limesium-servlet-logging`](../limesium-servlet-logging/README.md)**: an
auto-configured `WebFilter` that logs one structured `endpoint_*` line per HTTP exchange — with the
**identical message format, identical field family, identical `endpoint-logging.*` configuration and the
identical meters**. A dashboard, alert, or index mapping must not care which stack produced an event.

The long-form guide — introduction, architecture, integration into a foreign project, configuration,
metrics and the stack-specific behaviours — is [`docs/GUIDE.md`](docs/GUIDE.md).

The servlet module is the reference implementation; its documentation applies here too:

- **Configuration:** the complete commented reference for THIS module is
  [`docs/endpoint-logging-reference.yml`](docs/endpoint-logging-reference.yml) (the shared
  namespace plus the one reactive-only `variant` key) — this module's
  `EndpointLoggingReferenceConfigTest` **binds both files against this module's properties class** and
  pins the key parity, so neither reference can drift from the code or from its twin.
- **Index mapping:** the one component template for both stacks is the repository-shared
  [`/docs/elk/`](../docs/elk/README.md) — this module's `EndpointLogFieldTest`
  locks this module's field enum against that same template across the reactor.
- **Metrics:** the same six meters (`endpoint.logging.failopen`, `endpoint.logging.events`,
  `endpoint.logging.exchanges.open`, `endpoint.logging.correlation.id`, `endpoint.request/response.body.size`,
  `endpoint.request.body.read`),
  consumed from the host's `MeterRegistry`, never exported.

## Deliberate stack differences

| Concern | Servlet twin | This module |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / `timeout` | `success` / `failure` / **`cancelled`** (client disconnect — the reactive reality; there is no container async timeout) |
| `endpoint_async` field | emitted | **never emitted** — everything is asynchronous here, the flag would carry no information |
| Final-status emission | at `requestDestroyed`, after the error dispatch | at the terminal signal; for an error on an **uncommitted** response deferred to the commit callback, which sees the upstream handler's **rendered 500**. A commit that never happens leaves the exchange open on the gauge (the liveness signal) instead of logging a wrong status; a never-committed cancellation logs `-> -` and omits the status field |
| Chain-wide MDC | `endpoint_request_id`/`endpoint_method`/`endpoint_route` during the chain | Reactor **context** under the same keys; with `io.micrometer:context-propagation` on the classpath (an optional dependency — its presence is the opt-in) the auto-configuration registers matching `ThreadLocalAccessor`s and automatic propagation restores the identity into handler-side MDC, restoring parity. Both the accessors and the startup warning about the propagation mode are installed only while the Reactor variant owns the filter slot. Without the library: emission-scope MDC and the message inline only |
| Body tee | servlet stream/writer wrappers; `reset()`/`resetBuffer()` clears the capture | `DataBuffer` map-tee in request/response decorators (a non-advancing bounded copy out of each buffer; the original flows on untouched — pooled-buffer safe); no reset analog exists: emitted buffers are on their way to the client |

Everything else — fail-open including the wiring (`stage=wiring` degrades to pass-through), the
level/outcome decoupling, slow escalation, header sections with `includes`/`excludes`/`masked` and the
stable masking fingerprint, the arrival line (`log-request-start`), count-only body measuring — behaves
exactly as documented in the servlet twin's README.

## The shared layer

The **byte-identical** part of the twins' shared layer (the `traceparent` parser with its fuzz target,
the injectable time/id interfaces, `reportQuietly`, the MDC keys and scope) lives in the internal
`limesium-common` module and is **inlined into this jar** by the Maven Shade plugin
([ADR-0003](../docs/adr/ADR-0003-limesium-common-inlined-by-shade.md)): consumers add exactly one
artifact, the published POM carries no extra dependency, and `limesium-common` itself is never
published.

Everything whose twin copies genuinely differ (field enum and metrics with their per-stack outcome
vocabulary, emitters, exchanges, properties with the reactive-only `variant`, body capture with its
own concurrency design) stays **deliberately duplicated**, per the original architecture-review
decision: one twin per host, standalone jars, contract-level code that changes rarely. For that
remainder every change is still a conscious port in both directions; the pins in `TwinContractTest` /
`EndpointLogFieldTest` / `EndpointLoggingReferenceConfigTest` catch *named* contract drift (meter
names, field names, configuration keys) — not behavioural drift inside near-identical code. A change
there must be ported consciously and verified in both modules.

## Usage

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-reactive-logging</artifactId>
</dependency>
```

Auto-configures in a REACTIVE web application only (`@ConditionalOnWebApplication(type = REACTIVE)`), so
it can never clash with the servlet twin — an application may even carry both jars, and the matching one
activates.

## Kotlin coroutines

For coroutine WebFlux applications (`suspend fun` handlers) the module ships a second, coroutine-idiomatic
variant: **`CoRequestLoggingWebFilter`** (a `CoWebFilter`). Both variants delegate to the same internal
`ExchangeLifecycle`, so logging, configuration and metrics are identical by construction — the variant
choice is invisible to dashboards. The coroutine variant adds one thing natively: the chain runs inside
`MDCContext` with the `endpoint_*` identity, `CoWebFilter` publishes that coroutine context to the
handler invocation, and **every log line inside a suspend handler carries
`endpoint_request_id`/`endpoint_method`/`endpoint_route`** — the coroutine equivalent of the servlet
twin's chain-wide MDC scope, with no `context-propagation` dependency.

Selection is classpath-based by default: with `kotlinx-coroutines-reactor` **and**
`kotlinx-coroutines-slf4j` present — both optional dependencies of this module — the coroutine variant
registers and the Reactor variant backs off; exactly one `EndpointLoggingFilter` is ever active, and a
host-defined bean of either variant backs off both. The one reactive-only configuration key makes the
choice explicit when the classpath should not decide: `endpoint-logging.variant` — `auto` (default, as
above), `reactor` (force the Reactor variant even with the coroutine libraries present, e.g. pulled in
transitively by a Reactor-only host), or `coroutine` (require the coroutine variant; the context fails
to start with a message naming the missing libraries instead of silently falling back). The servlet
twin does not bind this key; everything else in the namespace stays identical. One
boundary note: across the coroutine-to-Reactor bridge, kotlinx's stacktrace recovery may surface a
COPY of a handler exception (original as its cause); type, message and the reachable original — what
error handling classifies on — are preserved.

# limesium-servlet-logging

The **servlet twin of [`limesium-reactive-logging`](../limesium-reactive-logging/README.md)** and the
**reference implementation** of Limesium: an auto-configured servlet filter for Spring Boot applications
on embedded Tomcat 11+ or Jetty 12.1+ that logs one structured `endpoint_*` line per inbound HTTP
exchange — the record of a foreign party crossing the service's own frontier — and carries the exchange
identity in the MDC while the request is handled. Message format, field family, `endpoint-logging.*`
configuration and meters are **identical** in both twins: a dashboard, alert, or index mapping must not
care which stack produced an event.

The long-form documentation is split in two. The [common guide](../docs/GUIDE.md) holds everything
both twins share — the exchange line, the shared architecture, dependency and encoder setup, the
configuration namespace, the field family, the meters and the trace contract; what the modules do
and deliberately do not do (no body masking transformers, no per-key response sampling) is its
[§1.1](../docs/GUIDE.md#11-what-the-modules-do) and [§1.2](../docs/GUIDE.md#12-what-the-modules-deliberately-do-not-do).
[`docs/GUIDE.md`](docs/GUIDE.md) holds what the **servlet stack decides** — the filter and its two
registrations, request destruction as the emission point, async exchanges, the chain-wide MDC, the
stream and writer tees, and the servlet-only edge cases; its
[§1.1](docs/GUIDE.md#11-what-is-specific-to-the-servlet-stack) is the summary.

This module is the reference implementation; the documentation shared by both twins is bound to the
code it inlines:

- **Configuration:** the complete commented reference for both twins is the repository-shared
  [`/docs/endpoint-logging-reference.yml`](../docs/endpoint-logging-reference.yml) — bound by
  `EndpointLoggingReferenceConfigTest` against this module's properties class (the reactive twin binds
  the same file plus its one `variant` key against its own), so the namespace cannot drift from the
  code, and the twins cannot drift from each other by construction. The properties are explained in
  the common guide's [§4](../docs/GUIDE.md#4-configuration).
- **Index mapping:** the one component template for both stacks is the repository-shared
  [`/docs/elk/`](../docs/elk/README.md) — bound by `EndpointLogFieldTest` in `limesium-common` against
  the one field enum both twins inline. The field table is the common guide's
  [§5.1](../docs/GUIDE.md#51-log-fields).
- **Metrics:** the same six meters (`endpoint.logging.failopen`, `endpoint.logging.events`,
  `endpoint.logging.exchanges.open`, `endpoint.logging.correlation.id`, `endpoint.request/response.body.size`,
  `endpoint.request.body.read`), consumed from the host's `MeterRegistry`, never exported. The meter
  table is the common guide's [§5.4](../docs/GUIDE.md#54-meters).

## Deliberate stack differences

| Concern | This module | Reactive twin |
|---|---|---|
| Disposition vocabulary | `success` / `failure` / **`timeout`** — the container's async timeout | `success` / `failure` / **`cancelled`** — a client disconnect, the reactive reality; there is no container async timeout |
| `endpoint_async` | emitted, always | never emitted — everything is asynchronous there |
| `endpoint_response_status_code` | always present | absent for a never-committed cancellation |
| Emission point | **request destruction** (`ServletRequestListener.requestDestroyed`) — after the container's error dispatch and, for `suspend` controllers, `DeferredResult` and `Callable`, after async completion; so a crashed exchange logs the rendered `500`, not the pre-dispatch `200`, and `endpoint_duration_ms` is **request occupancy** including error rendering, not bare chain time. A burst of terminal events still yields exactly one line | the terminal signal; an error on an uncommitted response is deferred to the commit callback, a commit that never happens leaves the exchange open on the gauge |
| Chain-wide MDC | thread-local for the **whole chain** — `endpoint_request_id`, `endpoint_method`, `endpoint_route`, previous values restored — plus the Spring MVC async worker thread | Reactor context under the same keys, opt-in `ThreadLocalAccessor`s with `context-propagation`, or `MDCContext` in the coroutine variant |
| Body tee | stream/reader and stream/writer wrappers; `reset()`, `resetBuffer()` and `sendError` clear the capture, container error rendering bypasses it | `DataBuffer` map-tee; no reset analog, emitted buffers are on their way to the client |
| Body capture concurrency | single writer, late reader; a volatile total as the happens-before edge | lock-guarded, frozen at emission |
| Variant selection | one filter | `endpoint-logging.variant`: Reactor or coroutine |
| Handler template attribute | Spring MVC's `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`, pinned by `HandlerMappingAttributeTest` | WebFlux's |

Everything else — fail-open including the wiring (`stage=wiring` degrades to pass-through), the
level/outcome decoupling, slow escalation, header sections with `includes`/`excludes`/`masked`/`unmasked`
(masked by default, ADR-0005) and the injectable `HeaderValueMasker` (default: the stable fingerprint),
the arrival line (`log-request-start`), count-only body measuring, path activation, the identity contract
of ADR-0002 (a conformant `traceparent`'s trace id **is** the request id, the wire stays untouched; only
a traceless exchange adopts or generates an `X-Correlation-Id` and echoes it back) — is the one contract
both twins ship, documented once in the [common guide](../docs/GUIDE.md).

## The shared layer

The **stack-neutral** part of the twins' shared layer - the `traceparent` parser with its fuzz target,
the injectable time/id/masker interfaces, the header selection and masking, the fail-open helpers, the
MDC keys and scope, and since the architecture review of 2026-09-05 also the field enum, the meters
(parameterized with the stack's own outcome) and the core of the exchange line (`ExchangeLine`) - lives
in the internal `limesium-common` module and is **inlined into this jar** by the Maven Shade plugin
([ADR-0003](../docs/adr/ADR-0003-limesium-common-inlined-by-shade.md)): consumers add exactly one
artifact, the published POM carries no extra dependency, and `limesium-common` itself is never
published.

Everything whose twin copies genuinely differ - the filters and lifecycles, the exchange state, the
per-stack classification in the emitters, the properties, the body capture with its own concurrency
design - stays **deliberately duplicated**, per the original architecture-review decision: one twin per
host, standalone jars, contract-level code that changes rarely. For that remainder every change is a
conscious port in both directions; the pins in `TwinContractTest` / `EndpointLoggingReferenceConfigTest`
(and `EndpointLogFieldTest` in `limesium-common`) catch *named* contract drift (meter names, field names,
configuration keys, message text) — not behavioural drift inside near-identical code.

## Usage

The host must be a **Spring Boot 4.x servlet web application** on Java 21 — embedded Tomcat 11+ or
Jetty 12.1+, which supply the Jakarta Servlet API the module declares as `provided` — with an SLF4J 2.x
binding. Spring MVC is optional (without it there is no `endpoint_url_template`, no async pass and no
worker-thread MDC). Undertow, and therefore WildFly, is unsupported on this stack: see
[container support](#container-support). The stack-specific list with the reasons is the guide's
[prerequisites table](docs/GUIDE.md#31-prerequisites), the shared requirements are the common guide's
[§3.1](../docs/GUIDE.md#31-prerequisites); how the `endpoint_*` fields become visible in the log output
is the common guide's [§3.5](../docs/GUIDE.md#35-logging-backend-and-structured-output).

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-servlet-logging</artifactId>
    <version><!-- current release: see the badge below --></version>
</dependency>
```

[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/limesium-servlet-logging.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.inqudium/limesium-servlet-logging)
— there is no BOM; the version is declared on the dependency itself. An application may carry both
twins: each auto-configures for its own web application type only, so the matching one activates and
the other stays inert; keep them at the same version, both jars inline the same shared classes.
`endpoint-logging.enabled=false` removes the module again without touching the classpath.

### Automatic wiring

The long form is the guide's [§3.2](docs/GUIDE.md#32-automatic-wiring).

In a servlet web application (`@ConditionalOnWebApplication(type = SERVLET)`) the auto-configuration
registers the filter bean **and** the two registrations that make it work: a `FilterRegistrationBean`
that puts the filter into the container's chain at `Ordered.HIGHEST_PRECEDENCE + 10` for every request,
and a `ServletListenerRegistrationBean` for the filter's completion listener — the **emission point**,
fired by the container at request destruction. Both go through Boot's `ServletContextInitializer`
mechanism, so an embedded container and a WAR on an external Tomcat or Jetty are wired alike.

There is nothing to inject and nothing to build: every request the container dispatches passes the
filter, and path activation (`include-path-patterns`, `exclude-path-prefixes`) is evaluated inside it,
not through the registration's URL mapping, so the semantics are byte-identical with the reactive twin.
Referencing the filter bean from the registration keeps Boot from also auto-registering the bare
`Filter` bean.

```kotlin
@RestController
class ThingsController(private val things: Things) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/things/{id}")
    fun thing(@PathVariable id: Long): Thing {
        log.info("loading thing")        // carries endpoint_request_id, endpoint_method, endpoint_route
        return things.load(id)
    }
}
```

The order is early but not first: Boot's `ServerHttpObservationFilter` at `HIGHEST_PRECEDENCE + 1`
wraps this filter, so the exchange event stays within the server span's timing, and everything ordered
after `+ 10` — Spring Security, the application's own filters, the `DispatcherServlet` — runs inside
the chain-wide MDC scope and sees `endpoint_request_id`
([§6.8](docs/GUIDE.md#68-the--10-order-is-load-bearing)).

### Manual wiring

The long form — including the Boot-context variant with the auto-configuration switched off — is the
guide's [§3.3](docs/GUIDE.md#33-manual-wiring).

The filter bean `RequestLoggingFilter` exists in every enabled servlet context; only its *registration*
depends on Boot's servlet auto-configuration. Register it yourself when that auto-configuration is not
in charge:

- **Spring MVC without Boot's servlet auto-configuration** — an application bootstrapped without Boot,
  or with the auto-configuration excluded. No `FilterRegistrationBean` runs, so neither the filter nor
  the completion listener reaches the container.
- **A different order or mapping** — the auto-configured registration is fixed at
  `HIGHEST_PRECEDENCE + 10` for every path. A host that must place the filter elsewhere switches the
  auto-configuration off (`endpoint-logging.enabled=false`) and registers by hand — mindful that
  [§6.8](docs/GUIDE.md#68-the--10-order-is-load-bearing) explains why the `+ 10` is load-bearing.
- **Outside a Spring context** — a bare embedded container in an integration test, a servlet
  application without Spring. The filter is constructed directly; every default is public.

In each case register **both** pieces: the filter for every dispatcher type (as Boot does for an
`OncePerRequestFilter`, so the async and error dispatches pass it too), and the completion listener from
`exchangeCompletionListener()` — without the listener no exchange is ever emitted, and the open-exchanges
gauge grows with every request:

```kotlin
class EndpointLoggingInitializer : WebApplicationInitializer {
    override fun onStartup(servletContext: ServletContext) {
        val filter = RequestLoggingFilter(
            RequestLoggingProperties(),            // every default; or a copy(...) with the fields to change
            NanoTimeSource.SYSTEM,
            CorrelationIdGenerator.DEFAULT,
            SimpleMeterRegistry(),                 // or the registry the surrounding code owns
        )
        servletContext.addFilter("requestLoggingFilter", filter)
            .addMappingForUrlPatterns(EnumSet.allOf(DispatcherType::class.java), false, "/*")
        servletContext.addListener(filter.exchangeCompletionListener())
    }
}
```

Reuse one filter per `MeterRegistry` rather than constructing several: the meters are identified by
name, so all filters on one registry share one metrics owner and the `endpoint.logging.exchanges.open`
gauge reports the total across them (common guide
[§6.2](../docs/GUIDE.md#62-one-metrics-instance-per-registry)).
Replacing the filter itself (a host-defined `RequestLoggingFilter` bean) is a different thing: the
automatic wiring still registers the replacement and its listener around it, so the emission point stays
intact ([§3.5](docs/GUIDE.md#35-replacing-the-filter-bean)) — as it does for the other overridable beans,
`NanoTimeSource`, `CorrelationIdGenerator` and `HeaderValueMasker` (how masked header values render — a
keyed HMAC, a fixed `***`).

### The exchange line

On the `endpoint-http-exchange` logger a completed exchange is one event. In a plain-text appender only the
message shows; it repeats the gist inline for exactly that case:

```
Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]
```

With Spring Boot's structured logging (`logging.structured.format.console=ecs`) the same event is one
JSON document: the `endpoint_*` key-values and the MDC-carried identity become flat, typed top-level
fields next to the encoder's own envelope:

```json
{
  "@timestamp": "2026-09-04T13:54:58.534Z",
  "log": { "level": "INFO", "logger": "endpoint-http-exchange" },
  "process": { "pid": 4711, "thread": { "name": "http-nio-8080-exec-3" } },
  "service": { "name": "things-service" },
  "message": "Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]",
  "endpoint_request_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "endpoint_method": "GET",
  "endpoint_route": "/api/things/42",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "parentSpanId": "00f067aa0ba902b7",
  "endpoint_outcome": "success",
  "endpoint_duration_ms": 17,
  "endpoint_request_method": "GET",
  "endpoint_response_status_code": 200,
  "endpoint_url_path": "/api/things/42",
  "endpoint_url_template": "/api/things/{id}",
  "endpoint_async": false,
  "ecs": { "version": "8.11" }
}
```

The trace keys and the trace suffix in the message appear only on a traced exchange — the caller's span
as `parentSpanId`, never as the local `spanId`; a traceless exchange carries the request id alone and has
echoed it to the client as `X-Correlation-Id`. When the chain throws, a short WARN breadcrumb goes to the
module's own logger the moment it happens, and the ERROR event with the rendered status follows at
request destruction. Optional fields (`endpoint_url_query`, `endpoint_slow`, the header and body
sections) are present only when they apply. Which encoder produces which shape — and why the default
console pattern shows none of the fields — is the common guide's
[§3.5](../docs/GUIDE.md#35-logging-backend-and-structured-output); the field family itself is documented
once, in the common guide's [§5.1](../docs/GUIDE.md#51-log-fields), and mapped by the component template in
[`/docs/elk/`](../docs/elk/README.md).

## Configuration (`endpoint-logging.*`)

Every property lives under the `endpoint-logging.*` namespace, identical in both twins by construction
(the reactive twin adds its one `variant` key). The complete, commented reference with every key at its
default is the repository-shared
[`/docs/endpoint-logging-reference.yml`](../docs/endpoint-logging-reference.yml) — copy the block and
change only what you need; `EndpointLoggingReferenceConfigTest` fails the build on any drift between
that file and the properties class. The properties are explained in the common guide's
[§4](../docs/GUIDE.md#4-configuration): the property reference, header sections, body logging and
measuring, path activation, logger levels, validation at startup, and example configurations; what
the servlet stack adds to individual properties is this module's guide's
[§4](docs/GUIDE.md#4-configuration-on-the-servlet-stack).
`endpoint-logging.enabled=false` removes the module without touching the classpath.

## Metrics

The module's meters exist for one reason: a log line that was lost cannot report its own loss through
the same pipeline. Six meters, consumed from the host's `MeterRegistry` when one exists (actuator) and
never exported, form that independent channel — they answer whether exchange events are being lost
loudly (a fail-open counter by stage), lost silently (an open-exchange gauge whose baseline must return
towards zero when `requestDestroyed` stops firing), or lost downstream (an events counter to reconcile
against the index), where each exchange's identity came from (trace, header, or generated — a rising
`generated` share means the gateway or sidecar stopped propagating), and — opt-in — how large the bodies
were and how far the application actually read the request body. Rates, latencies and status
distributions are deliberately left to Boot's own `http.server.requests` and to the structured log
fields.

Every meter with its type, tags and meaning is the common guide's [§5.4](../docs/GUIDE.md#54-meters);
how to read them together, with a suggested alert set, is its
[§5.5](../docs/GUIDE.md#55-reading-the-meters-together). The names are identical in both twins and pinned
by `TwinContractTest`; the `outcome` tag of the events counter carries this stack's `timeout`
([§5.4](docs/GUIDE.md#54-meters) of this module's guide).

## Container support

Tomcat 11+ and Jetty 12.1+ are supported, each pinned by its own integration suite (capture boundaries
and tracing) and documented per engine in [`docs/CONTAINERS.md`](docs/CONTAINERS.md). **Undertow — and
therefore WildFly, whose servlet engine it is — is unsupported on this stack.** That is a platform
boundary, not a module limitation: Spring Framework 7's baseline is Jakarta Servlet 6.1, which Undertow
2.3.x does not implement, and Spring Boot 4 removed the Undertow starter accordingly; the module's own
servlet-API surface is Servlet 3.1-level. An unsupported-territory integration suite on a hand-rolled
embedded-Undertow factory pins the empirical state — the module runs and the capture boundaries hold,
with two pinned engine deviations — and is the tripwire for a Spring patch release adopting 6.1 API.
The boundary lifts properly when Undertow ships Servlet 6.1.

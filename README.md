<p align="center">
  <img src="docs/logo/limesium-banner.svg" alt="Limesium — one structured endpoint_* line per HTTP exchange" width="640">
</p>

[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/limesium.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/eu.inqudium)
[![CI](https://github.com/Inqudium/limesium/actions/workflows/ci.yml/badge.svg)](https://github.com/Inqudium/limesium/actions/workflows/ci.yml)
[![Coverage](https://inqudium.github.io/limesium/coverage/badge.svg)](https://inqudium.github.io/limesium/coverage/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Last commit](https://img.shields.io/github/last-commit/Inqudium/limesium)](https://github.com/Inqudium/limesium/commits/main)
[![Issues](https://img.shields.io/github/issues/Inqudium/limesium)](https://github.com/Inqudium/limesium/issues)
[![Docs](https://img.shields.io/badge/docs-inqudium.github.io-8E2C21)](https://inqudium.github.io/limesium/)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Inqudium/limesium/badge)](https://scorecard.dev/viewer/?uri=github.com/Inqudium/limesium)

Limesium logs one structured endpoint_* line per HTTP exchange at the service's own boundary — 
named after the Roman Limes, the watched frontier where every crossing was recorded. 
Two auto-configured Spring Boot twins with identical fields and configuration: a servlet filter 
and a WebFlux/coroutines web filter. No starter, no forced transitives.

## What sets it apart

### One line per exchange

- **Emitted when the request is truly over.** The servlet twin emits at request destruction, after
  the container's error dispatch and after async completion; the reactive twin at the terminal signal,
  deferred to the commit when an error leaves the response uncommitted. Status, headers and bodies are
  final on the line, and `endpoint_duration_ms` is request occupancy including error rendering, not
  bare handler time.
- **An outcome that names who is responsible.** `success`, `rejected` (a 4xx), `failure`, and
  `timeout` or `cancelled` say which side the disposition belongs to; the level carries severity
  separately, so the meaning of a line never depends on how loud it was logged.

### Two stacks, one contract

- **Two paradigm twins, identical fields.** The servlet filter and the WebFlux filter, in a Reactor and
  a coroutines variant, emit the same fields under the same names with the same shapes, bound by the
  same `endpoint-logging.*` keys, and lockstep tests pin every literal: a field, a message format or a
  meter that drifts between the twins fails the build.

### Correlation

- **Identity across every thread of the exchange.** The trace id is the request id, and the
  `endpoint_*` identity is in the MDC for the whole chain, on the async worker of a `Callable` or
  `DeferredResult`, in the async re-dispatch, and in the Reactor context of the reactive stack, so every
  application log line of a request carries it. A client line the sibling Legatium emits during the
  request inherits it, and the two join in one document.

### Headers and bodies

- **Header values masked by default.** A logged header value is a stable keyed fingerprint unless it
  is on an explicit plaintext allowlist; the same `masking-key` on both sides of the family makes a
  masked token read identically on the inbound and the outbound line.
- **Bodies teed as they flow.** Bodies are never pre-read or replayed: they are teed as the
  application reads and writes them, bounded by `max-body-bytes`, `on-failure` logs them only for the
  exchanges that went wrong, and a read-state meter shows whether an endpoint left a payload unread
  or half read.

### Operating it

- **Fail-open, and the loss reports itself.** A logging failure never reaches the handler and never
  changes the response. It is swallowed, counted in `endpoint.logging.failopen` by stage, and the
  events counter is the ground truth to reconcile against the log index, so a lost line is visible
  through a channel that does not depend on the line.
- **Meters that are consumed, not exported.** Six meters are fed into the host's own registry,
  pre-registered at zero so a `rate()` alert sees the baseline before the first occurrence; rates,
  latencies and status distributions are left to `http.server.requests` on purpose.
- **The logger level is the volume control, at runtime.** Because the level carries severity only, the
  level of the `endpoint-http-exchange` logger decides how much is logged without changing what a line
  means: `INFO` every exchange, `WARN` failures the application handled, the stack's own disposition and
  slow exchanges, `ERROR` only chains that threw, `OFF` nothing. Level and outcome are resolved before
  the event is built, so a disabled level costs no assembly, no header selection, no body decoding, and
  the meters are recorded before the gate. Turn it up during an incident through the host's logging
  backend (Boot's loggers endpoint included) and down again, no restart, no redeploy; the module's own
  logger under `eu.inqudium.limesium` reports at `DEBUG` how it is wired and at `TRACE` where every
  property value came from.

## Quickstart

1. **Add the twin for the host's stack.** There is no BOM; the version is declared on the dependency
   (the current release is in the compatibility table below and on the Maven Central badge).

   Servlet (Spring MVC on Tomcat 11+ or Jetty 12.1+):

   ```xml
   <dependency>
       <groupId>eu.inqudium</groupId>
       <artifactId>limesium-servlet-logging</artifactId>
       <version>3.0.1</version>
   </dependency>
   ```

   Reactive (Spring WebFlux, Reactor or coroutines):

   ```xml
   <dependency>
       <groupId>eu.inqudium</groupId>
       <artifactId>limesium-reactive-logging</artifactId>
       <version>3.0.1</version>
   </dependency>
   ```

2. **Start the application.** Nothing to inject, nothing to configure: the auto-configuration registers
   the filter for its own web application type, and every exchange is one `INFO` event on the
   `endpoint-http-exchange` logger:

   ```
   Endpoint http exchange GET /api/things/42 -> 200 [endpoint_request_id=4bf92f3577b34da6a3ce929d0e0e4736 traceId=4bf92f3577b34da6a3ce929d0e0e4736 parentSpanId=00f067aa0ba902b7]
   ```

   Every application log line written while the request is served carries `endpoint_request_id` in
   its MDC. With Boot's structured logging (`logging.structured.format.console=ecs`) the same event is
   one JSON document with the `endpoint_*` fields as flat, typed top-level fields.

3. **Tune it, if the defaults are not yours.** Every key lives under `endpoint-logging.*` and is the
   same for both twins; the [configuration reference](docs/endpoint-logging-reference.yml) lists them
   all with their defaults. The usual first adjustments:

   ```yaml
   endpoint-logging:
     exclude-path-prefixes: [/actuator/]       # skip health probes and metrics scrapes
     log-request-body: on-failure              # bodies only for exchanges that went wrong
     log-response-body: on-failure
     masking-key: ${ENDPOINT_MASKING_KEY}      # key the header fingerprint; a secret, share it with Legatium
   logging:
     level:
       endpoint-http-exchange: WARN            # or INFO for every exchange; change it at runtime
   ```

The module READMEs carry the details: prerequisites, the automatic wiring and when to wire by hand,
and what one exchange looks like as text and as JSON:
[servlet](limesium-servlet-logging/README.md#usage), [reactive](limesium-reactive-logging/README.md#usage).

## The name

*Limesium* is named after the **Limes**, the fortified frontier of the Roman Empire —
not a wall meant to seal the border shut, but a controlled line of crossing points and
watchtowers. Traffic was allowed through; what the Limes added was *observation*: every
crossing passed a manned post where it could be seen and accounted for.

That is precisely this project's job, transposed to HTTP. The service's own request
boundary is its Limes: exchanges pass through unhindered, and the filter is the
watchtower that records each one — exactly one structured line per crossing, whether it
came through a servlet or a reactive stack. The name deliberately does *not* refer to
the "edge" in the infrastructure sense (CDN, gateway, mesh): the Limes here is the
service's own perimeter, inside the application, behind whatever sits in front of it.

The form follows the naming of chemical elements. Real elements are often named after
places — rhenium after the Rhine, germanium, polonium — and *Limes* + the element
suffix *-ium* yields a plausible entry in that series. This places Limesium in the same
fictional periodic table as **Inqudium** (the `eu.inqudium` group it is published
under): an element-style name for one well-defined capability, here the element of the
observed boundary.

The frontier has two directions, and Limesium watches one of them. Its sibling
[**Legatium**](https://github.com/Inqudium/legatium) — named after the *legatus*, the envoy a
service sends to a foreign party — logs the *outbound* crossings: the `RestClient`, `RestTemplate`
and `WebClient` calls the service makes to others, with the same design (one structured line per
exchange, fail-open, identical across two paradigm twins). Limesium's fields carry the `endpoint_`
prefix and Legatium's the `adapter_` prefix, so a log document may hold both — a client line emitted
while a request is being served inherits the server line's identity from the MDC — and no field ever
means two things.

Two paradigm twins with identical fields and identical configuration:

| Module | Stack | Root package |
|---|---|---|
| [`limesium-servlet-logging`](limesium-servlet-logging/README.md) | Spring MVC / servlet filter | `eu.inqudium.limesium.servlet.logging` |
| [`limesium-reactive-logging`](limesium-reactive-logging/README.md) | Spring WebFlux (Reactor and coroutines) | `eu.inqudium.limesium.reactive.logging` |

Both are auto-configured Spring Boot libraries — no starter, no forced logging transitives;
the host application brings the runtime (Tomcat 11+/Jetty 12.1+ resp. Netty) and the Logback binding.
Undertow/WildFly is unsupported on this stack (no Jakarta Servlet 6.1 implementation; no linkage
blocker was found on the servlet-MVC path, but Spring gives no downward guarantee — see the servlet
module's README).

## Documentation

**Documentation site:** [inqudium.github.io/limesium](https://inqudium.github.io/limesium/) —
guides, Elasticsearch mapping, generated [test evidence](https://inqudium.github.io/limesium/tests/test-evidence/),
[coverage reports](https://inqudium.github.io/limesium/coverage/), and the Dokka
[API](https://inqudium.github.io/limesium/api/limesium-servlet-logging/)
[references](https://inqudium.github.io/limesium/api/limesium-reactive-logging/).

- [Common guide](docs/GUIDE.md) — everything both modules share: the exchange line, the shared
  architecture, dependency and encoder setup, the configuration namespace, the field family, the
  meters, the trace contract, and the table of deliberate stack differences.
- [Servlet guide](limesium-servlet-logging/docs/GUIDE.md) — what the servlet stack decides in the
  reference implementation: the filter and its two registrations, request destruction as the
  emission point, async exchanges, the chain-wide MDC, the servlet-only edge cases.
- [Container guide](limesium-servlet-logging/docs/CONTAINERS.md) — Tomcat, Jetty and Undertow
  documented individually: destruction models, error paths, pinned per-engine deviations.
- [Reactive guide](limesium-reactive-logging/docs/GUIDE.md) — what the reactive stack decides:
  the two filter variants, the commit-deferred emission, the Reactor context and handler-side
  MDC, the reactive-only edge cases.
- [Configuration reference](docs/endpoint-logging-reference.yml) —
  every `endpoint-logging.*` key with its default, contract-tested against both twins.
- [Elasticsearch mapping](docs/elk/README.md) — the ready-made
  component template for the `endpoint_*` fields.
- [Decision records](docs/adr/) — why the trace id is the request id, why the shared code is
  inlined, why the default id counts instead of rolling dice.
- [**Legatium**](https://github.com/Inqudium/legatium) — the sibling project for the *outbound*
  side: one structured `adapter_*` line per call the service makes, on the logger
  `adapter-http-exchange`, built to the same design. Run both and a log document holds the
  server line and the client lines of the calls it made, joined by the shared request id - and
  because both mask header values with the same stable fingerprint (the same `masking-key` on
  both sides keeps it so), a masked token reads identically on the inbound and the outbound line.

### Compatibility

Each Limesium release is built and tested against one Spring Boot line, one Kotlin line and one Java
target; the table is the history of those lines, newest first. The Java column is the bytecode target the
artifacts run on - the build itself needs JDK 24+.

| Limesium | Spring Boot | Kotlin | Java |
|---|---|---|---|
| 3.0.1 | 4.1.x | 2.4.x | 21 |
| 3.0.0 | 4.1.x | 2.4.x | 21 |
| 2.0.0 | 4.1.x | 2.4.x | 21 |
| 1.1.0 | 4.1.x | 2.4.x | 21 |
| 1.0.0 | 4.1.x | 2.4.x | 21 |

Pick the module for the host's stack and follow the **Usage** section of its README — prerequisites,
the dependency with the current version, how the filter is wired automatically, when and how to wire
it by hand, and what one logged exchange looks like as text and as JSON:

- **Servlet** (Spring MVC on Tomcat or Jetty):
  [`limesium-servlet-logging` → Usage](limesium-servlet-logging/README.md#usage) —
  [automatic wiring](limesium-servlet-logging/README.md#automatic-wiring),
  [manual wiring](limesium-servlet-logging/README.md#manual-wiring).
- **Reactive** (Spring WebFlux, Reactor or coroutines):
  [`limesium-reactive-logging` → Usage](limesium-reactive-logging/README.md#usage) —
  [automatic wiring](limesium-reactive-logging/README.md#automatic-wiring),
  [manual wiring](limesium-reactive-logging/README.md#manual-wiring).

An application may carry both jars; each activates for its own web application type only.

## Build

```
mvn verify
```

Maven multi-module build (group `eu.inqudium`), Java 21, Kotlin, Spring Boot parent.

### Reproducible builds

The same source and version produce the same bytes on any machine: the root
POM sets `project.build.outputTimestamp` (bumped in every release commit), so
the jar, source and shade archivers write that instant as every zip entry's
timestamp, sort the entries and normalize their permissions, and the manifests
carry no build user, build JDK or Maven version (`Build-Jdk-Spec` is omitted on
purpose - CI builds on JDK 25, a maintainer on whatever 24+ is installed). This
holds for all published jars of both twins: the javadoc jar is rendered by
Dokka but packaged by the jar plugin, because Dokka's own `javadocJar` goal
writes the build time and JDK into the archive. Consequently the jars the
Release workflow attaches to the GitHub release (built on the tag by GitHub
Actions, attested with SLSA provenance) and the jars deployed to Maven Central
from a local checkout of the same tag are byte-identical. To check a build, run
`mvn -DskipTests package` twice, or once on the tag, and compare
`sha256sum <twin>/target/<twin>-<version>.jar` with the release asset.

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) first.
The [Code of Conduct](CODE_OF_CONDUCT.md) applies to all project spaces, and
security issues should be reported privately as described in [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

<p align="center">
  <img src="docs/logo/limesium-banner.svg" alt="Limesium — one structured endpoint_* line per HTTP exchange" width="640">
</p>

[![Maven Central](https://img.shields.io/maven-central/v/eu.inqudium/limesium.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/eu.inqudium)
[![CI](https://github.com/Inqudium/limesium/actions/workflows/ci.yml/badge.svg)](https://github.com/Inqudium/limesium/actions/workflows/ci.yml)
[![Coverage](https://inqudium.github.io/limesium/coverage/badge.svg)](https://inqudium.github.io/limesium/coverage/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Last commit](https://img.shields.io/github/last-commit/Inqudium/limesium)](https://github.com/Inqudium/limesium/commits/main)
[![Issues](https://img.shields.io/github/issues/Inqudium/limesium)](https://github.com/Inqudium/limesium/issues)
[![Docs](https://img.shields.io/badge/docs-inqudium.github.io-8E2C21)](https://inqudium.github.io/limesium/)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Inqudium/limesium/badge)](https://scorecard.dev/viewer/?uri=github.com/Inqudium/limesium)

Limesium logs one structured endpoint_* line per HTTP exchange at the service's own boundary — 
named after the Roman Limes, the watched frontier where every crossing was recorded. 
Two auto-configured Spring Boot twins with identical fields and configuration: a servlet filter 
and a WebFlux/coroutines web filter. No starter, no forced transitives.

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

### Quick start

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

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) first.
The [Code of Conduct](CODE_OF_CONDUCT.md) applies to all project spaces, and
security issues should be reported privately as described in [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

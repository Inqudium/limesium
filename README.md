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

- [Servlet guide](limesium-servlet-logging/docs/GUIDE.md) — the long-form guide of the
  reference implementation: architecture, integration, configuration, metrics.
- [Container guide](limesium-servlet-logging/docs/CONTAINERS.md) — Tomcat, Jetty and Undertow
  documented individually: destruction models, error paths, pinned per-engine deviations.
- [Reactive guide](limesium-reactive-logging/docs/GUIDE.md) — the twin's guide, including
  the deliberate stack differences and the coroutine variant.
- [Module READMEs](limesium-servlet-logging/README.md) ([reactive](limesium-reactive-logging/README.md)) —
  summary, field family, property table.
- [Configuration reference](docs/endpoint-logging-reference.yml) —
  every `endpoint-logging.*` key with its default, contract-tested against both twins.
- [Elasticsearch mapping](docs/elk/README.md) — the ready-made
  component template for the `endpoint_*` fields.

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

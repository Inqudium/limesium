# Limesium

[![CI](https://github.com/dirkjink/limesium/actions/workflows/ci.yml/badge.svg)](https://github.com/dirkjink/limesium/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

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
| `limesium-servlet-logging` | Spring MVC / servlet filter | `eu.inqudium.limesium.servlet.logging` |
| `limesium-reactive-logging` | Spring WebFlux (Reactor and coroutines) | `eu.inqudium.limesium.reactive.logging` |

Both are auto-configured Spring Boot libraries — no starter, no forced logging transitives;
the host application brings the runtime (Tomcat resp. Netty) and the Logback binding.
See each module's `README.md` and `docs/GUIDE.md` for usage, and
`limesium-servlet-logging/docs/endpoint-logging-reference.yml` for the shared
configuration reference that both twins are contract-tested against.

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

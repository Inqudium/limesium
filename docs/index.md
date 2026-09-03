![Limesium banner](logo/limesium-banner.svg)

# Limesium

Limesium logs **one structured `endpoint_*` line per HTTP exchange** at the
service's own boundary — named after the Roman Limes, the watched frontier
where every crossing was recorded. Two auto-configured Spring Boot twins
with identical fields and identical configuration: a servlet filter and a
WebFlux/coroutines web filter. No starter, no forced transitives.

| Module | Stack | Root package |
|---|---|---|
| `limesium-servlet-logging` | Spring MVC / servlet filter | `eu.inqudium.limesium.servlet.logging` |
| `limesium-reactive-logging` | Spring WebFlux (Reactor and coroutines) | `eu.inqudium.limesium.reactive.logging` |

## Features

- **Exactly one line per exchange.** Sync and async share a single,
  exactly-once emission path; the outcome (`success` / `failure` /
  `timeout`) is decoupled from the log level.
- **A stable field contract.** The `endpoint_*` wire names are a contract
  with the log index: each field owns its JSON shape, a badly typed value
  drops that field with a warning but never the event, and the
  Elasticsearch component template ships with the project — kept in
  lockstep with the code by contract tests.
- **Correlation built in.** The correlation id is adopted from the
  configured header (or generated), echoed on the response, and rides the
  MDC for the whole chain — previous MDC values are restored afterwards.
- **Passive body capture.** Bodies are captured by a bounded tee as they
  flow — nothing is buffered, replayed, or withheld from the application;
  logged header values are masked by default to a stable `length:hash`
  fingerprint (keyed on request, or whatever a host-provided `HeaderValueMasker`
  bean renders), plaintext being an explicit allowlist.
- **Twin symmetry as an invariant.** Both modules expose the same fields
  and the same `endpoint-logging.*` properties; the shared reference
  configuration is contract-tested against both twins.
- **A library, not a platform.** Auto-configured Spring Boot modules with
  no starter and no forced logging transitives; the host application
  brings the runtime (Tomcat resp. Netty) and the Logback binding.

## Quick start

Add the module matching your stack — the filter registers itself:

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-servlet-logging</artifactId>
    <version>...</version>
</dependency>
```

or, for a WebFlux application:

```xml
<dependency>
    <groupId>eu.inqudium</groupId>
    <artifactId>limesium-reactive-logging</artifactId>
    <version>...</version>
</dependency>
```

Every `endpoint-logging.*` key, with its default, is documented in the
[configuration reference](https://github.com/Inqudium/limesium/blob/main/docs/endpoint-logging-reference.yml) —
copy the block and change only what you need.

## Documentation

- **[Servlet guide](https://github.com/Inqudium/limesium/blob/main/limesium-servlet-logging/docs/GUIDE.md)** —
  the long-form guide of the reference implementation: architecture,
  integration, configuration, metrics.
- **[Reactive guide](https://github.com/Inqudium/limesium/blob/main/limesium-reactive-logging/docs/GUIDE.md)** —
  the twin's guide, including the deliberate stack differences and the
  coroutine variant.
- **[Elasticsearch mapping](elk/README.md)** — the ready-made component
  template for the `endpoint_*` fields.
- **[Test evidence](https://inqudium.github.io/limesium/tests/test-evidence/)** —
  the generated inventory of the test suite: every test sentence plus its
  rationale, grouped by module and component.
- **[Coverage report](https://inqudium.github.io/limesium/coverage/)** —
  the JaCoCo reports of the run that built this site.
- **API reference** —
  [servlet](https://inqudium.github.io/limesium/api/limesium-servlet-logging/) and
  [reactive](https://inqudium.github.io/limesium/api/limesium-reactive-logging/),
  generated with Dokka.

## Project

- [README](https://github.com/Inqudium/limesium#readme) — the full project
  story and the naming.
- [Contributing](https://github.com/Inqudium/limesium/blob/main/CONTRIBUTING.md)
- [Changelog](https://github.com/Inqudium/limesium/blob/main/CHANGELOG.md)
- [License (Apache 2.0)](https://github.com/Inqudium/limesium/blob/main/LICENSE)

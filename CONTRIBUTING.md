# Contributing to Limesium

Thank you for considering a contribution! This document explains how to get set up,
what the project expects from changes, and how to submit them.

## Ground rules

- Be respectful; the [Code of Conduct](CODE_OF_CONDUCT.md) applies to all project spaces.
- For anything larger than a trivial fix, please **open an issue first** and discuss the
  change before writing code. This avoids wasted work if the change doesn't fit the
  project's scope.
- Security issues must **not** be reported as public issues — see [SECURITY.md](SECURITY.md).

## Project scope

Limesium does one thing: emit exactly one structured `endpoint_*` log line per HTTP
exchange at the service's own boundary, via two paradigm twins (servlet and reactive)
with **identical fields and identical configuration**. Contributions that widen this
scope (metrics frameworks, tracing backends, other protocols) are likely out of scope —
ask first.

The twin symmetry is a hard invariant: a new field or configuration property must land
in **both** modules, with the shared contract in
`docs/endpoint-logging-reference.yml` updated and the
contract tests passing in both.

## Development setup

Prerequisites:

- JDK 21
- Maven 3.9+ (or use your IDE's bundled Maven)

Build and test everything:

```
mvn verify
```

This compiles both modules, runs all tests, and runs the `ktlint` style check.
A PR must pass `mvn verify` cleanly.

### Code style

- Kotlin code is checked with **ktlint** (via `ktlint-maven-plugin`, bound to `verify`).
  Run `mvn ktlint:format` to auto-format before committing.
- Match the surrounding code's comment density and naming; comments should state
  constraints the code can't express, not narrate the code.

### Tests

- Every behavior change needs a test in the module it touches.
- Changes to the shared field/configuration contract need the reference file and the
  contract tests in **both** modules updated.
- Test classes follow the existing `*Test.kt` naming (Surefire picks up `**/*Test.kt`).

## Submitting changes

1. Fork the repository and create a topic branch from `main`.
2. Make your change with tests; keep commits focused and messages descriptive.
3. Ensure `mvn verify` passes.
4. Open a pull request against `main` describing **what** changed and **why**.
   Link the issue it addresses, if any.

By submitting a contribution you agree that it is licensed under the
[Apache License 2.0](LICENSE), the project's license, and you certify the
[Developer Certificate of Origin](https://developercertificate.org/) — i.e. you have
the right to submit the work under that license.

## Reporting bugs

Please include:

- Limesium version and module (`limesium-servlet-logging` or `limesium-reactive-logging`)
- Spring Boot version and stack (Tomcat / Netty, Reactor / coroutines)
- Relevant configuration (`endpoint-logging.*` properties)
- What you expected, what happened, and a minimal reproduction if possible

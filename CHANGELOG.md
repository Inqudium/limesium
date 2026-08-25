# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial open-source release of Limesium: one structured `endpoint_*` log line
  per HTTP exchange at the service's own boundary.
- `limesium-servlet-logging` — auto-configured servlet filter (Spring MVC).
- `limesium-reactive-logging` — auto-configured WebFlux web filter (Reactor and
  coroutines), field- and configuration-identical twin of the servlet module.

[Unreleased]: https://github.com/dirkjink/limesium/commits/main

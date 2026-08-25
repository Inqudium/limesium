# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-08-26

### Changed

- Default `endpoint-logging.max-body-bytes` raised from `4096` to `16384` in both
  modules. The limit still bounds only the logged/captured prefix per body — bytes
  beyond it flow to the application and client unchanged.
- Documentation accuracy fixes from a full code cross-check (meter count, the
  `stage=wiring` fail-open description, level rules for async errors, body-tee
  description in the reactive README).

## [1.0.0] - 2026-08-25

### Added

- Initial open-source release of Limesium: one structured `endpoint_*` log line
  per HTTP exchange at the service's own boundary.
- `limesium-servlet-logging` — auto-configured servlet filter (Spring MVC).
- `limesium-reactive-logging` — auto-configured WebFlux web filter (Reactor and
  coroutines), field- and configuration-identical twin of the servlet module.

[Unreleased]: https://github.com/Inqudium/limesium/compare/1.1.0...HEAD
[1.1.0]: https://github.com/Inqudium/limesium/releases/tag/1.1.0
[1.0.0]: https://github.com/Inqudium/limesium/releases/tag/1.0.0

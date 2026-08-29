# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Documentation site (MkDocs Material) at
  [inqudium.github.io/limesium](https://inqudium.github.io/limesium/), published
  by a `Docs` workflow that also generates the test-evidence page, the JaCoCo
  coverage reports and the coverage badge from the actual test run, plus the
  Dokka API reference per module.
- Test coverage (JaCoCo) in every `mvn verify` run; per-module reports under
  `target/site/jacoco/`.
- CI dependency vulnerability scan: CycloneDX SBOM of the resolved graph,
  checked against the OSV database on every push/PR and weekly.
- CodeQL static analysis (library code and workflow definitions) and OpenSSF
  Scorecard workflows.
- Dependabot for Maven, GitHub Actions, and the hash-pinned docs toolchain.
- Issue forms (YAML) replacing the Markdown issue templates; `.editorconfig`.
- Release workflow: on a published GitHub release it rebuilds the module jars
  and the aggregate SBOM from the tag, uploads them as release assets, and
  attaches Sigstore-signed SLSA build provenance
  (slsa-github-generator); a `workflow_dispatch` variant backfills existing
  releases.

### Changed

- CI workflow hardened: actions pinned to commit SHAs, explicit least-privilege
  token permissions, weekly scheduled run, test summary on every run.
- `ktlint-maven-plugin` 3.0.0 → 3.7.1 (sources reformatted accordingly) and
  `flatten-maven-plugin` 1.6.0 → 1.8.0.

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

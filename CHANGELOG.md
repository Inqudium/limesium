# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Heads-up: this cycle is **breaking on three axes** - wire behavior (ADR-0002),
API surface (ADR-0003 package moves, ADR-0004 removal), and the observable
default id format (ADR-0004). Each break was decided in a numbered ADR under
`docs/adr/` and carries its migration note below.

### Added

- Counting correlation id generator
  ([ADR-0004](docs/adr/ADR-0004-counting-correlation-id-default.md)): a random
  per-instance base-36 prefix plus a monotonic counter - 21 lowercase
  characters, lexicographically ordered per instance, uniqueness guaranteed
  within an instance. The per-request path is one atomic increment: no shared
  `SecureRandom` lock, no I/O on an event loop.
- Container-support matrix and per-container guide
  (`limesium-servlet-logging/docs/CONTAINERS.md`): Tomcat and Jetty supported
  and integration-tested per engine (capture boundaries and tracing pinned
  beside a live bridge); Undertow/WildFly documented as unsupported on
  Boot 4 (no Jakarta Servlet 6.1 implementation), the boundary pinned by
  tests rather than assumed.
- ADR series under `docs/adr/` (ADR-0001 fuzzing signal, ADR-0002 trace
  identity, ADR-0003 shared twin core, ADR-0004 id generator): every
  consequential decision recorded with context, rejected alternatives, and
  consequences.
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
- The Release workflow additionally mirrors each release into the GitHub
  Packages Maven registry (new `distributionManagement` in `pom.xml`),
  authenticated with the workflow token; Maven Central remains the primary,
  deliberately manual release path.
- Daily fuzzing via Jazzer's JUnit integration (`@FuzzTest` classes under
  `src/test/java`, nightly `Fuzz` workflow with `JAZZER_FUZZ=1`): three
  targets assert the invariants of the caller-facing parsers - bounded body
  capture, header masking/fingerprinting, and the W3C `traceparent` parser -
  and replay their checked-in findings as regression tests in every build.

### Changed

- **BREAKING (wire,
  [ADR-0002](docs/adr/ADR-0002-trace-id-is-the-request-id.md)):** the trace id
  is the request id; the `X-Correlation-Id` echo is traceless-only. Both twins
  parse the incoming W3C `traceparent` themselves; on a conformant header its
  trace id IS `endpoint_request_id`, a caller-supplied `X-Correlation-Id` is
  ignored, and no `X-Correlation-Id` response header is written - a traced
  exchange passes through observationally untouched. The servlet twin's
  Micrometer-bridge MDC capture is retired (the header's parent id is
  published as `parentSpanId`, never as the local `spanId`; locally rooted
  traces are no longer joined). *Migrate:* clients sending `traceparent` and
  reading the echo header use their own trace id; dashboards keying on
  `endpoint_request_id` see trace-id cardinality on traced exchanges. The
  `endpoint.logging.correlation.id` counter gains `source=trace` (meter name
  unchanged).
- **BREAKING (source,
  [ADR-0003](docs/adr/ADR-0003-limesium-common-inlined-by-shade.md)):** the
  byte-identical twin code - `Traceparent`, `NanoTimeSource`,
  `CorrelationIdGenerator`, the MDC keys/scope, and (amendment 2026-08-31)
  `HeaderLogProperties` with the masking fingerprint - moved to the internal
  `limesium-common` module, inlined into each twin jar by Shade. Consumers
  still add exactly one artifact and gain no transitive dependency.
  *Migrate:* update imports of `NanoTimeSource`, `CorrelationIdGenerator`,
  `MdcKeys` or `HeaderLogProperties` to `eu.inqudium.limesium.common`.
- **BREAKING (observable default,
  [ADR-0004](docs/adr/ADR-0004-counting-correlation-id-default.md)):**
  generated correlation ids are 21-character base-36 values instead of
  36-character UUIDs. *Migrate:* consumers that parse or validate UUID shape
  pin a UUID-producing `CorrelationIdGenerator` bean.
- Measured performance cleanups, each confirmed by a JMH benchmark before
  adoption (`benchmarks/`, PERF_ANALYSIS trail in the module assessments):
  header selection precomputes its lowercased sets at construction, masking
  renders via `HexFormat`, path activation short-circuits before parsing when
  nothing is configured, and the reactive body tee copies at most the capture
  limit instead of cloning every buffer.
- Metrics ownership per registry: all filters constructed against the same
  `MeterRegistry` share one metrics owner, so the
  `endpoint.logging.exchanges.open` gauge reports the true total under manual
  multi-filter wiring (auto-configured single-filter contexts are unaffected).
- The servlet arrival line owns its trace keys exactly like the completion
  event, so a stale bridge `spanId` on the container thread cannot ride along.
- CI workflow hardened: actions pinned to commit SHAs, explicit least-privilege
  token permissions, weekly scheduled run, test summary on every run.
- `ktlint-maven-plugin` 3.0.0 → 3.7.1 (sources reformatted accordingly) and
  `flatten-maven-plugin` 1.6.0 → 1.8.0.

### Removed

- **BREAKING
  ([ADR-0004](docs/adr/ADR-0004-counting-correlation-id-default.md)):** the
  public constant `CorrelationIdGenerator.RANDOM_UUID` - removed, not
  deprecated. *Migrate:* replace with `CorrelationIdGenerator.DEFAULT`, or
  define your own generator bean.

### Fixed

- Path activation matches the path WITHIN the application, exactly as the
  router does: a non-root `server.servlet.context-path` (or WebFlux base
  path) no longer silently deactivates configured include patterns, and
  percent-encoded request targets can no longer slip past an exclude prefix.
  Behavior change on such deployments: endpoints the patterns always meant to
  cover are now logged.
- Jetty async exchanges emit exactly once with their FINAL status: Jetty
  destroys requests per dispatch, and the emission choreography now defers
  past the async cycle instead of logging a pre-completion 200 (found and
  pinned by the Jetty capture-boundary integration test).
- Findings of the four recorded analysis passes under `docs/assessment/`
  (defect analyses of 2026-08-30/31, comment audit, architecture review) -
  each fixed and ticked in its report, from test-discovery and test-resource
  lifecycle gaps to benchmark drift and the shared fail-open guard helper.

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

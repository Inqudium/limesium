# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Removed

- Benchmarks whose measured code no longer exists (comment audit round 2 of 2026-09-05,
  CA-10): `ServletTwinSpotCheckBenchmark` measured the servlet copies of the header
  selection and masking that ADR-0003's amendment of 2026-08-31 replaced by one shared
  class, and `CorrelationIdBenchmark` measured the retired UUID default against an
  alternative that never shipped. The recorded results stay under `benchmarks/results/`
  as the evidence the `BENCH_REPORT`s cite; the remaining benchmarks' Javadocs name the
  classes they measure today.

### Changed

- Comment audit round 2 of 2026-09-05 (`docs/assessment/COMMENT_AUDIT-2026-08-31T01-03-25.R2.md`),
  findings CA-11 to CA-18 - documentation and one test, no behaviour change. CONTRIBUTING
  draws the boundary of the "one normative source" rule: the KDoc of the shared public types
  `HeaderLogProperties`, `BodyLogMode` and `MaskingKey` is normative for the type's contract,
  the reference YAML names keys and defaults and points to the type (its header and body
  sections are condensed accordingly). The servlet destruction model has one in-code canon
  (`CompletionState`); `EndpointLogFieldTest` pins type, `index` and `doc_values` of every
  field, so the `ELK:` KDoc lines are tested as claimed; test references in KDoc name the
  test classes; chronicle half-sentences without a failure mode are gone.
- Code-style audit of 2026-09-05 (`docs/assessment/CODE_STYLE-2026-09-05T17-08-39.md`),
  all findings fixed. Host-visible: the servlet tee classes `BoundedBodyCapture`,
  `CapturingRequestWrapper` and `CapturingResponseWrapper` are `internal` like their
  reactive counterparts (finding 1 - they were never documented as API; a host that
  constructed them directly must stop, the filter wires them); the `masking-key`
  property binds to the new `MaskingKey` value (`RequestLoggingProperties.maskingKey`,
  finding 5) whose own `toString` redacts the secret, so both properties classes keep
  their generated `toString` - the YAML/environment binding is unchanged, hand-written
  `RequestLoggingProperties(maskingKey = "...")` calls become `MaskingKey("...")`; the
  companion defaults `HeaderValueMasker.DEFAULT`/`keyed`/`forKey`, `NanoTimeSource.SYSTEM`
  and `CorrelationIdGenerator.DEFAULT` carry `@JvmField`/`@JvmStatic`, so Java hosts call
  them without `Companion` (finding 2). Internal: `Traceparent.parse` returns a named
  `TraceContext` instead of a `Pair` (finding 6), the reactive `Exchange` and the servlet
  emission guard encapsulate their transitions like the servlet `CompletionState`
  (finding 15), the shared `reportFailOpen` replaces fifteen copies of the fail-open
  reporting block (pattern S1), the loggers, `require`, catch parameters, member order
  and file names follow the house pattern (findings 3, 4, 7-11), and the tests share the
  `CapturedLogger` fixture and `keyValues()` extension from the common test-jar instead
  of 24 Logback fixture copies (pattern S2, findings 12-14).
- Shared core widened (ADR-0003 amendment of 2026-09-05, architecture review findings 1
  and 3): the field enum `EndpointLogField`, the meters `EndpointLoggingMetrics`
  (parameterized with the stack's third outcome) and the stack-neutral core of the
  exchange line (`ExchangeLine`) now live in `limesium-common` and are inlined into
  both twins - one class where two near-identical copies drifted before; the emitters
  keep only what differs per stack. The test helpers `AwaitingAppender` and
  `installMdcAdapter` ship to the twins as `limesium-common`'s unpublished `test-jar`
  instead of per-module copies. No host-visible package changes (all moved classes are
  internal); the registration-conflict warnings now come from
  `eu.inqudium.limesium.common.EndpointLoggingMetrics`.
- The servlet twin's completion lifecycle is one atomic `CompletionState` (`OPEN`,
  `ASYNC_ARMED`, `DESTROYED_DURING_ASYNC`, `ASYNC_COMPLETED`, `COMPLETED`) with CAS
  transitions - the mirror of the reactive twin's `ExchangeState` - instead of four
  volatile flags plus two atomic booleans and a re-check protocol (architecture review
  finding 4). Behaviour under Tomcat's once-late and Jetty's per-dispatch destruction is
  unchanged and pinned by the same suites.
- Documentation: one normative source per contract statement (architecture review
  finding 2). The reactive module's `docs/endpoint-logging-reference.yml` carries only
  its `variant` key - the complete reference for both twins is the repository-shared
  file; the twins' property KDocs name the key and point to that reference instead of
  restating it; the filters' wiring comments point to ADR-0002 instead of paraphrasing
  it; CONTRIBUTING states the rule.

- A caller-supplied correlation id is adopted only when it is 1-128 visible-ASCII
  characters (`CorrelationHeaderValue`, shared by both twins); anything else -
  whitespace, control or non-ASCII characters, more than 128 characters - counts as an
  absent header: a fresh id is generated and echoed, counted as `generated`. Before, any
  non-blank value up to the server's header limit was echoed and written into every log
  line and MDC entry of the exchange (code analysis of 2026-09-05, finding 11).

### Fixed

- The reactive emission scope now OWNS the trace MDC keys, like the servlet twin's: the
  parsed `traceId`/`parentSpanId` pair is installed, an unparsed one and the bridge's
  local `spanId` are removed for the duration of the exchange line and the arrival line,
  and the previous values are restored afterwards. Under
  `spring.reactor.context-propagation=auto` with a tracing bridge - the mode the
  handler-MDC parity asks for - the bridge's live `traceId`/`spanId` used to ride along:
  a local `spanId` on traced exchanges, a `traceId` that was not the request id on
  traceless ones, both against ADR-0002 and the guide. Pinned beside a real Brave bridge
  under `auto` by `RequestLoggingWebFilterTracingAutoPropagationIntegrationTest`
  (finding 1).
- The reactive propagation initializer resolves the filter slot over all
  `EndpointLoggingFilter` beans instead of `ObjectProvider.getIfAvailable()`, which threw
  `NoUniqueBeanDefinitionException` and failed the context start when a host defined two
  filters (finding 4).
- The servlet request tee delegates `available()` to the container's stream instead of
  inheriting `InputStream`'s constant 0 (finding 5).
- Fuzzing: every `@FuzzTest` target ships a seed corpus under
  `src/test/resources/**/<Class>Inputs/`; regression mode used to replay exactly one
  empty input per target although CONTRIBUTING promised checked-in inputs. The nightly
  `Fuzz` workflow now also uploads the corpus it grew (finding 3).
- Framework-parsed bodies - a form POST read through `@RequestParam`/`getParameter*` (servlet)
  or `@ModelAttribute`/`getFormData()` (reactive), a multipart request - bypass the request tee on both stacks by
  construction; the boundary is now documented in both module guides (servlet §6.9,
  reactive §6.6), in the common guide's body rules and meter notes, and pinned by
  form-POST integration tests on Tomcat and on every reactive server. The `unread` share
  of `endpoint.request.body.read` must be read per `uri` with that in mind (finding 2).
- Test infrastructure: the servlet module reads the shared contract files from the test
  classpath like the reactive twin (finding 7); the container suites' `AwaitingAppender`
  settles briefly after the awaited count so a late duplicate emission fails the
  exactly-once assertion (finding 10); the fail-open branches of
  `EndpointMdcCallableInterceptor`, the async MDC registration and
  `MdcEntryThreadLocalAccessor` have direct tests (finding 6); the reactive Netty
  integration test names the coroutine variant it actually runs (finding 8); the seven
  test methods without Given/When/Then stage comments carry them (finding 9).

- The published POMs name the repository itself as homepage and SCM. Maven appends the
  module name to an inherited `url` and `scm`, so Maven Central showed the twins with a
  homepage `.../limesium/limesium-servlet-logging` that does not exist; the root POM now
  switches that inheritance off and each twin states its `url` explicitly.

### Added

- Server-agnostic reactive twin, pinned: one `ServerContract` runs the Reactor variant
  against every reactive server Boot 4 ships - Reactor Netty, Tomcat and Jetty (the
  latter two through Spring's `HttpHandler` adapters) - for the single active filter, a
  real round trip with both bodies teed on the server's own buffers, the
  commit-deferred emission behind the server's error rendering, a later commit
  action's status and header, and the handler pattern of a real dispatch. Undertow
  left Boot with 4.0 and is not part of the matrix.
- Injectable `HeaderValueMasker`: the rendering of masked header values is a
  `@ConditionalOnMissingBean` bean (`eu.inqudium.limesium.common.HeaderValueMasker`)
  shared by both twins and both reactive variants - the built-in default is the
  stable `length:hash` fingerprint, a host pins a keyed or fixed masker instead;
  the properties decide which values are masked, the bean decides how.
  `endpoint-logging.masking-key` keys the built-in fingerprint (HMAC-SHA256)
  without a bean: same shape and stability, guess-proof without the key. Both
  ported from the outbound sibling Legatium, where they were designed in first.
- `on-failure` body logging
  ([ADR-0006](docs/adr/ADR-0006-bodies-logged-by-outcome.md)): `log-request-body`
  / `log-response-body` are now a mode per direction - `never` (the default),
  `on-failure` or `always`. `on-failure` writes a body only when
  `endpoint_outcome` is not `success` or the status is a 4xx - the emitter decides
  when the outcome is
  final, the request body is teed before the outcome is known and discarded on
  success - which keeps body logging affordable outside a debug session. Ported
  from Legatium.

### Changed

- **BREAKING (default logger name):** the exchange logger's default name is
  `endpoint-http-exchange` instead of `http-exchange`, so the logger starts with
  the vocabulary word like every field, MDC key, meter and property of the
  `endpoint` family - and mirrors the outbound sibling Legatium's
  `adapter-http-exchange`, so an operator sees both families as two prefixed
  blocks. *Migrate:* logback/Log4j level rules, appender routing and index
  queries that name `http-exchange` move to `endpoint-http-exchange`; a host
  that must keep the old name sets `endpoint-logging.logger-name: http-exchange`
  and nothing else changes.
- **BREAKING (configuration and source,
  [ADR-0006](docs/adr/ADR-0006-bodies-logged-by-outcome.md)):**
  `log-request-body` / `log-response-body` take `never` | `on-failure` |
  `always` instead of `true` / `false`; the former booleans no longer bind, so
  a leftover `true` fails the context start instead of silently switching
  bodies off. *Migrate:* `true` becomes `always` (or, better, `on-failure`),
  `false` becomes `never` or is dropped. In code, the properties' type is
  `BodyLogMode` instead of `Boolean`.
- **BREAKING (behaviour,
  [ADR-0005](docs/adr/ADR-0005-headers-masked-by-default.md)):** logged header
  values are masked by default. `masked` now defaults to `["*"]`, and the new
  `unmasked` list per section names the headers that may appear in plaintext
  (no wildcard) - so `includes: ["*"]` costs readability, not confidentiality.
  *Migrate:* name the harmless headers in `unmasked`, or set `masked: []` to
  restore the old rendering knowingly. The fingerprint is documented as what it
  is - a stable pseudonym, not anonymisation.
- **BREAKING (source):** `HeaderLogProperties.mask(value)` is gone, and
  `HeaderLogProperties.select(names, valueOf)` takes the masker as its second
  argument. *Migrate:* call `HeaderValueMasker.DEFAULT.mask(value)`, and pass a
  `HeaderValueMasker` to `select`. The filter constructors gain an optional
  trailing `HeaderValueMasker` parameter (the default when omitted) - existing
  host-built filter beans compile unchanged.
- Documentation: the two module guides are split into a common guide
  (`docs/GUIDE.md` - everything both twins share: the exchange line, the
  shared architecture, dependency and encoder setup, the configuration
  namespace, the field family, the meters, the trace contract, and the one
  table of deliberate stack differences) and two stack-specific guides
  (`<module>/docs/GUIDE.md` - only what the stack decides). Section numbers
  of the module guides changed; the READMEs, the container guide and the
  docs site link the new sections.

## [2.0.0] - 2026-08-31

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

[Unreleased]: https://github.com/Inqudium/limesium/compare/2.0.0...HEAD
[2.0.0]: https://github.com/Inqudium/limesium/releases/tag/2.0.0
[1.1.0]: https://github.com/Inqudium/limesium/releases/tag/1.1.0
[1.0.0]: https://github.com/Inqudium/limesium/releases/tag/1.0.0

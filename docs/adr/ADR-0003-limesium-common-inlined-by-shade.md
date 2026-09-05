# ADR-0003: Byte-identical twin code moves to limesium-common, inlined by Shade

- **Status:** accepted
- **Date:** 2026-08-30
- **Context:** The twins deliberately duplicated their shared layer -
  "no shared base module", decided in an internal architecture review
  and documented in both READMEs and GUIDEs: one twin per host, one
  standalone jar each, contract-level code that changes rarely.
  ADR-0002 tilted that balance: it grew the BYTE-identical set
  (`Traceparent` with its unit, conformance and Jazzer fuzz tests now
  exists twice; `Mdc.kt` differs only by a servlet-side superset) and
  demonstrated that every shared-layer change is now a synchronized
  multi-file port in both directions. The maintainer decided to extract
  the identical set - under the constraint that consumers keep adding
  exactly ONE artifact to their build.

## Decision

**The byte-identical shared code lives in a new `limesium-common`
module; each twin inlines it into its own jar with the Maven Shade
plugin; `limesium-common` itself is never published.**

- **What moved:** `Traceparent` (with unit, conformance-fixture and
  Jazzer fuzz tests plus seed inputs), `NanoTimeSource`,
  `CorrelationIdGenerator`, `reportQuietly`, and `Mdc.kt`
  (`MdcKeys`/`TraceMdcKeys`/`MdcScope`) as the superset both twins use
  (`ownsTraceKeys` stays default-off in the reactive twin). Package:
  `eu.inqudium.limesium.common`.
- **What deliberately stays duplicated:** everything whose twin copies
  genuinely differ - the field enum and metrics (per-stack outcome
  vocabulary and meter descriptions), the emitters, exchanges, filters,
  properties (`variant` is reactive-only), and `BoundedBodyCapture`
  (two different concurrency designs). The prior duplication rationale
  still holds for those; this ADR narrows it, it does not revoke it.
- **Shading:** an `artifactSet` restricted to `eu.inqudium:
  limesium-common`, NO relocation (relocating rewrites bytecode but not
  Kotlin metadata), `keepDependenciesWithProvidedScope=false` so the
  dependency-reduced POM drops the dependency entirely, and the
  module's `META-INF/maven` filtered out of the shaded jar.
  spring-boot-starter-parent pre-configures an unnamed uber-jar shade
  execution; it is unbound (`phase=none`) so declaring the plugin does
  not swallow the compile classpath.
- **Visibility:** the twins compile with
  `-Xfriend-paths` (own output dir, common's classes dir AND jar - the
  reactor resolves the dependency as a directory before packaging and
  as a jar afterwards), so the shared classes stay `internal`.
- **Not published:** `maven.deploy.skip=true` plus
  `skipPublishing=true` for the Central Portal bundle. The published
  twin POMs mention no `limesium-common`.
- **Documentation:** each twin's Dokka run includes the common sources
  as an additional source root - the API reference documents what the
  shaded jar actually contains, and cross-module KDoc links resolve
  under `failOnWarning`. The Docs workflow installs (not merely
  verifies) before the per-module Dokka runs, so the dependency
  resolves.

## Consequences

- A shared-layer change in the extracted set is made ONCE; the
  both-directions port and its drift risk disappear for exactly the
  code where drift was invisible (byte-identical files).
- Consumers are unaffected in shape: one artifact, no new transitive
  dependency, internals stay internal. The classes' PACKAGE changed
  (`eu.inqudium.limesium.common`), which is source-breaking for hosts
  that import `NanoTimeSource`/`CorrelationIdGenerator` for bean
  overrides - to be called out in the same release notes as ADR-0002's
  boundary change.
- Both twin jars carry byte-identical copies of the common classes. An
  application with BOTH twins on the classpath (not a supported
  deployment) would see benign duplication at equal versions and
  classpath-order-dependent classes at skewed versions.
- `-Xfriend-paths` is a `-X` compiler flag: stable in practice and used
  widely for test friendship, but not a documented contract; a Kotlin
  upgrade that changes it surfaces as a loud compile error
  ("internal in file"), never as silent misbehaviour.
- The fuzz matrix keys on class names and finds `TraceparentFuzzTest`
  in its new module without a workflow change; the coverage, SBOM and
  test-evidence tooling glob `*/target/...` and pick the module up
  automatically.

## Amendment (2026-08-30)

Finding 6 of `docs/assessment/CODE_ANALYSIS-2026-08-30T21-52-43.md`
identified byte-identical residue the extraction had missed:
`decodeTruncated` and the `BodyReadState` enum, identical in both
twins' `BoundedBodyCapture.kt`, now live in `limesium-common`
(`BodyReadState.kt`) - the captures themselves stay deliberately
duplicated as decided above. The TEST helper `MdcAdapterSwap.kt`
remains duplicated on purpose: test classes are not shared across
modules (no test-jar dependency), and a copy of sixteen lines is
cheaper than publishing one; the copies carry a comment saying so.

## Amendment (2026-08-31)

Finding 1 of `docs/assessment/ARCHITECTURE_REVIEW-2026-08-31T10-51-58.md`
identified a second byte-identical residue, hidden inside a file that
legitimately stays duplicated: `HeaderLogProperties` (selection
semantics plus the `mask()` fingerprint - a cross-twin contract) was
byte-identical in both twins' `RequestLoggingProperties.kt`, although
the enumeration above counted "the properties" as genuinely differing.
The class now lives in `limesium-common`; the twins' property files
keep only what actually differs (the reactive-only `variant` key and
stack-specific wording). Its unit test and the `HeaderMaskingFuzzTest`
target moved along, as the Traceparent suite did in the original
extraction. NOTE - source-breaking for hosts that import the class
(bean-less, but referenced in configuration code): same break class as
the original ADR-0003 package moves, shipped in the same release.

## Amendment (2026-09-03)

The masking fingerprint - `HeaderLogProperties.mask`, a static companion
function - became the injectable `HeaderValueMasker` (`fun interface`, with
the fingerprint as `DEFAULT`), a `@ConditionalOnMissingBean` bean in both
twins' auto-configurations and handed to `HeaderLogProperties.select` by the
filters: the properties decide WHICH values are masked, the host may decide
HOW (a keyed HMAC where an unkeyed hash is not acceptable, a fixed `***`
where no correlation is wanted). The interface lives in `limesium-common`
beside `HeaderLogProperties`, as the shared-layer criterion demands, and was
ported from the outbound sibling Legatium, whose design settled it first.
Source-breaking for hosts that called `mask` or `select` directly; the
filter constructors take the masker as an optional trailing parameter, so
host-built filter beans compile unchanged.

## Amendment (2026-09-05)

Findings 1 and 3 of `docs/assessment/ARCHITECTURE_REVIEW-2026-09-05T15-28-48.md`
moved the "genuinely differ" line once more, on the evidence the previous
amendments predicted: the field enum and the metrics differed by one
constant and by prose (29 of 325 and 48 of 168 lines), three emitter
functions were byte-identical, and the defect analysis of the same day found
a behavioural drift (trace-key ownership) exactly inside that near-identical
remainder - the drift no literal pin can see. Now in `limesium-common`:
`EndpointLogField` with its builder extensions (one enum, one
`EndpointLogFieldTest`), `EndpointLoggingMetrics` parameterized with the
stack's third outcome (`forRegistry(registry, OUTCOME_TIMEOUT |
OUTCOME_CANCELLED)`; `micrometer-core` becomes a dependency of the common
module - both twins declared it already, the shaded jars add nothing), and
`ExchangeLine` - the stack-neutral core of the emitters (message texts,
header rendering, the arrival line, the body measurements) over the two
small interfaces `LoggedExchange` and `MeasuredBody` that both twins'
`Exchange` and `BoundedBodyCapture` implement. The emitters keep what
differs: the classification (async disposition vs. cancellation, an
always-present vs. a nullable status) and the exactly-once guard shape. All
moved classes are `internal`; no host-visible package changes.

The TEST-helper exception of the 2026-08-30 amendment is revoked: the "one
16-line copy" had become five copies of two helpers. `AwaitingAppender` and
`installMdcAdapter` now ship to the twins as `limesium-common`'s
`test-jar` (unpublished like the module itself, test scope only, never
shaded). What deliberately stays duplicated: the filters and lifecycles,
the exchange state, the per-stack classification, the properties, the body
captures - and the ENGINE-specific test infrastructure (`ServerContract`,
`EndpointAccessorRegistryGuard`, `UndertowTestServer`).

The code-style audit of the same day (`CODE_STYLE-2026-09-05T17-08-39.md`)
added two more residents by the same routes: `MaskingKey`, the secret-bearing
value the `masking-key` property binds to (finding 5), and the JUnit 5 fixture
`CapturedLogger` with the `ILoggingEvent.keyValues()` extension in the test-jar
(pattern S2 - the per-class Logback fixture had been copied into 24 test
classes). It also closed the twins' visibility gap: the servlet tee classes
(`BoundedBodyCapture`, both wrappers) are `internal` like their reactive
counterparts (finding 1).

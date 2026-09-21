# ADR-0003: Byte-identical twin code moves to limesium-common, inlined by Shade

**Status:** Accepted  
**Date:** 2026-08-30  
**Last updated:** 2026-09-21  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0002 (the change that grew the byte-identical set and
triggered the extraction), ADR-0004 (`CorrelationIdGenerator` is one
of the moved types; its package change ships with this ADR's),
ADR-0005 (`HeaderLogProperties`, `HeaderValueMasker` and `MaskingKey`
live in the common module by this ADR's criterion), ADR-0006
(`BodyLogMode` likewise)

## Context

The twins deliberately duplicated their shared layer: "no shared base
module", decided in an internal architecture review and documented in
both READMEs and GUIDEs. One twin per host, one standalone jar each,
contract-level code that changes rarely.

[ADR-0002](ADR-0002-trace-id-is-the-request-id.md) tilted that
balance. It grew the byte-identical set (`Traceparent` with its unit,
conformance and Jazzer fuzz tests then existed twice; `Mdc.kt`
differed only by a servlet-side superset) and demonstrated that every
shared-layer change had become a synchronized multi-file port in both
directions. The maintainer decided to extract the identical set, under
the constraint that consumers keep adding exactly ONE artifact to
their build.

## Decision

**The byte-identical shared code lives in a `limesium-common` module;
each twin inlines it into its own jar with the Maven Shade plugin;
`limesium-common` itself is never published.**

### The criterion

Code whose twin copies are byte-identical, or differ only by a
superset that the other twin can use with defaults, moves to
`limesium-common` (package `eu.inqudium.limesium.common`). Code whose
twin copies genuinely differ stays duplicated; the prior duplication
rationale still holds for it. This ADR narrows that rationale, it does
not revoke it. The line between the two sets is moved on evidence
(see [History](#history)): each review that finds byte-identical
residue moves it, and the drift found on 2026-09-05 showed that a
near-identical remainder hides behavioural drift no literal pin can
see.

### What lives in `limesium-common`

| Resident                                                                                                       | Since      | Route                                                                        |
|----------------------------------------------------------------------------------------------------------------|------------|------------------------------------------------------------------------------|
| `Traceparent` (with unit test, conformance fixture, Jazzer fuzz target and seed inputs)                         | 2026-08-30 | original extraction                                                          |
| `NanoTimeSource`, `CorrelationIdGenerator`, `reportQuietly`                                                    | 2026-08-30 | original extraction                                                          |
| `Mdc.kt` (`MdcKeys`/`TraceMdcKeys`/`MdcScope`) as the superset both twins use (`ownsTraceKeys` default-off in the reactive twin) | 2026-08-30 | original extraction                                            |
| `BodyReadState` (the enum; `decodeTruncated` moved beneath `BoundedByteBuffer` on 2026-09-17)                  | 2026-08-30 | `CODE_ANALYSIS-2026-08-30T21-52-43.md`, finding 6                            |
| `HeaderLogProperties` (with unit test and `HeaderMaskingFuzzTest`)                                             | 2026-08-31 | `ARCHITECTURE_REVIEW-2026-08-31T10-51-58.md`, finding 1                      |
| `HeaderValueMasker` (`fun interface`, fingerprint as `DEFAULT`)                                                | 2026-09-03 | ported from the outbound sibling legatium; see ADR-0005                      |
| `BodyLogMode`                                                                                                  | 2026-09-03 | arrived with ADR-0006                                                        |
| `EndpointLogField` with its builder extensions, `EndpointLoggingMetrics`, `ExchangeLine` over `LoggedExchange`/`MeasuredBody` | 2026-09-05 | `ARCHITECTURE_REVIEW-2026-09-05T15-28-48.md`, findings 1 and 3 |
| `MaskingKey` (the secret-bearing value the `masking-key` property binds to)                                    | 2026-09-05 | `CODE_STYLE-2026-09-05T17-08-39.md`, finding 5                               |
| test-jar: `AwaitingAppender`, `installMdcAdapter`, `CapturedLogger` with `ILoggingEvent.keyValues()`           | 2026-09-05 | test-helper exception revoked; `CODE_STYLE-2026-09-05T17-08-39.md`, pattern S2 |
| `BoundedByteBuffer` (the byte-bounded buffer beneath both `BoundedBodyCapture`s, with unit test and fuzz target) | 2026-09-17 | ported from the outbound sibling legatium                                    |
| `EndpointLoggingPropertyOrigins` (the TRACE half of the wiring report)                                          | 2026-09-18 | ported from the outbound sibling legatium                                    |
| `RequestLoggingProperties` (the `endpoint-logging.*` binding) with `RequestLoggingPropertiesTest` and the shared reference's `EndpointLoggingReferenceConfigTest` | 2026-09-18 | maintainer decision; the reactive-only `variant` key split off into the reactive module's `RequestLoggingVariantProperties` |
| `StatusClassification` (one `rejected` and one status table on both stacks) with `StatusClassificationTest`      | 2026-09-19 | ADR-0007                                                                     |

Later residents that arrive with ordinary changes follow the same
criterion; the module's source tree is the authoritative list. Moved
classes are `internal` where the twins' copies were `internal`; the
host-visible types (`CorrelationIdGenerator`, `NanoTimeSource`,
`HeaderLogProperties`, `HeaderValueMasker`, `MaskingKey`,
`RequestLoggingProperties`) keep their visibility and are the ones
whose package move is source-breaking.

### What deliberately stays duplicated

Everything whose twin copies genuinely differ:

- the filters and lifecycles, and the exchange state;
- the per-stack classification in the emitters (async disposition vs.
  cancellation, an always-present vs. a nullable status) and the
  exactly-once guard shape; `ExchangeLine` carries only the
  stack-neutral core (message texts, header rendering, the arrival
  line, the body measurements);
- the reactive-only `variant` key, as the reactive module's own
  `RequestLoggingVariantProperties` beside the shared binding;
- `BoundedBodyCapture` and the wrappers: two different concurrency
  designs - the shells; the bounded buffer beneath the captures (the
  bytes, the cap, the truncated rendering) is one `BoundedByteBuffer`
  in `limesium-common` since 2026-09-17;
- the ENGINE-specific test infrastructure (`ServerContract`,
  `EndpointAccessorRegistryGuard`, `UndertowTestServer`).

### Shading

An `artifactSet` restricted to `eu.inqudium:limesium-common`, NO
relocation (relocating rewrites bytecode but not Kotlin metadata),
`keepDependenciesWithProvidedScope=false` so the dependency-reduced POM
drops the dependency entirely, and the module's `META-INF/maven`
filtered out of the shaded jar. spring-boot-starter-parent
pre-configures an unnamed uber-jar shade execution; it is unbound
(`phase=none`) so declaring the plugin does not swallow the compile
classpath.

### Visibility

The twins compile with `-Xfriend-paths` (own output dir, common's
classes dir AND jar; the reactor resolves the dependency as a directory
before packaging and as a jar afterwards), so the shared classes stay
`internal`. Since 2026-09-21 the twins' TEST compilation adds the
test-classes directory and the tests jar to the same list, so the
test-jar's helpers (`AwaitingAppender`, `CapturedLogger` with
`keyValues`, `installMdcAdapter`) are `internal` as well - the shape
the outbound sibling legatium chose when it adopted the test-jar the
same day; a `@RegisterExtension` property holding one is `internal`
in its test class, which JUnit reads as it read the public one.

### Not published

`maven.deploy.skip=true` plus `skipPublishing=true` for the Central
Portal bundle; both cover the test-jar as well. The published twin
POMs carry no compile dependency on `limesium-common` (Shade removes
the inlined one); what remains, since the test-jar of 2026-09-05, is
the TEST-scoped dependency on it, which Shade neither inlines nor
removes. A test-scoped dependency of a dependency is never resolved by
Maven or Gradle, so a consumer's build does not look for the
unpublished artifact - the consumer-smoke job proves that with the
module deleted from the local repository.

### Verification on the consumer's side

The standalone project `consumer-smoke/` (no reactor child, like
`benchmarks/`) holds one consumer per twin - a host carries exactly one
limesium module, the servlet or the reactive one, so unlike the outbound
sibling legatium there is no both-twins context to start. Each consumer
depends on its twin exactly as an application does and starts a Boot
context with an embedded server on the installed jar: the inlined common
classes must resolve from exactly that jar and from no `limesium-common`
artifact, the auto-configuration must wire up through the jar's own
imports file, and one request must end in one exchange line. The CI job
`consumer-smoke` installs the reactor, DELETES `limesium-common` from
the local repository and only then builds the consumers: a
dependency-reduced POM that still named the unpublished module fails
there, not at the first consumer.

### Documentation

Each twin's Dokka run includes the common sources as an additional
source root: the API reference documents what the shaded jar actually
contains, and cross-module KDoc links resolve under `failOnWarning`.
The Docs workflow installs (not merely verifies) before the per-module
Dokka runs, so the dependency resolves.

## Consequences

**Positive:**

- A shared-layer change in the extracted set is made ONCE; the
  both-directions port and its drift risk disappear for exactly the
  code where drift was invisible (byte-identical files).
- Consumers are unaffected in shape: one artifact, no new transitive
  dependency, internals stay internal.
- The fuzz matrix keys on class names and finds `TraceparentFuzzTest`
  and `HeaderMaskingFuzzTest` in their new module without a workflow
  change; the coverage, SBOM and test-evidence tooling glob
  `*/target/...` and pick the module up automatically.

**Negative:**

- The host-visible classes' PACKAGE changed
  (`eu.inqudium.limesium.common`), which is source-breaking for hosts
  that import `NanoTimeSource`/`CorrelationIdGenerator` for bean
  overrides or reference `HeaderLogProperties` in configuration code.
  Called out in the same release notes as ADR-0002's boundary change.
- Both twin jars carry byte-identical copies of the common classes. An
  application with BOTH twins on the classpath (not a supported
  deployment) would see benign duplication at equal versions and
  classpath-order-dependent classes at skewed versions.
- `-Xfriend-paths` is a `-X` compiler flag: stable in practice and used
  widely for test friendship, but not a documented contract. A Kotlin
  upgrade that changes it surfaces as a loud compile error ("internal
  in file"), never as silent misbehaviour.

**Neutral:**

- `micrometer-core` is a dependency of the common module since
  `EndpointLoggingMetrics` moved; both twins declared it already, so
  the shaded jars add nothing.
- The "genuinely differ" line is expected to keep moving; every move is
  recorded below rather than re-argued.

## History

- **2026-08-30:** original extraction (`Traceparent`, `NanoTimeSource`,
  `CorrelationIdGenerator`, `reportQuietly`, `Mdc.kt`).
- **2026-08-30:** finding 6 of
  `docs/assessment/CODE_ANALYSIS-2026-08-30T21-52-43.md` identified
  byte-identical residue the extraction had missed: `decodeTruncated`
  and the `BodyReadState` enum, identical in both twins'
  `BoundedBodyCapture.kt`, moved to `limesium-common`
  (`BodyReadState.kt`); the captures themselves stay duplicated. The
  TEST helper `MdcAdapterSwap.kt` was left duplicated on purpose: test
  classes are not shared across modules (no test-jar dependency), and a
  copy of sixteen lines was cheaper than publishing one; the copies
  carried a comment saying so.
- **2026-08-31:** finding 1 of
  `docs/assessment/ARCHITECTURE_REVIEW-2026-08-31T10-51-58.md`
  identified a second byte-identical residue, hidden inside a file that
  legitimately stays duplicated: `HeaderLogProperties` (selection
  semantics plus the `mask()` fingerprint, a cross-twin contract) was
  byte-identical in both twins' `RequestLoggingProperties.kt`, although
  the original enumeration counted "the properties" as genuinely
  differing. The class moved to `limesium-common`; the twins' property
  files keep only what actually differs. Its unit test and the
  `HeaderMaskingFuzzTest` target moved along, as the Traceparent suite
  did in the original extraction. Source-breaking for hosts that import
  the class (bean-less, but referenced in configuration code): same
  break class as the original package moves, shipped in the same
  release.
- **2026-09-03:** the masking fingerprint (`HeaderLogProperties.mask`,
  a static companion function) became the injectable
  `HeaderValueMasker` (`fun interface`, with the fingerprint as
  `DEFAULT`), a `@ConditionalOnMissingBean` bean in both twins'
  auto-configurations and handed to `HeaderLogProperties.select` by
  the filters: the properties decide WHICH values are masked, the host
  may decide HOW (a keyed HMAC where an unkeyed hash is not acceptable,
  a fixed `***` where no correlation is wanted). The interface lives in
  `limesium-common` beside `HeaderLogProperties`, as the shared-layer
  criterion demands, and was ported from the outbound sibling legatium,
  whose design settled it first. Source-breaking for hosts that called
  `mask` or `select` directly; the filter constructors take the masker
  as an optional trailing parameter, so host-built filter beans compile
  unchanged.
- **2026-09-05:** findings 1 and 3 of
  `docs/assessment/ARCHITECTURE_REVIEW-2026-09-05T15-28-48.md` moved
  the "genuinely differ" line once more, on the evidence the previous
  amendments predicted: the field enum and the metrics differed by one
  constant and by prose (29 of 325 and 48 of 168 lines), three emitter
  functions were byte-identical, and the defect analysis of the same
  day found a behavioural drift (trace-key ownership) exactly inside
  that near-identical remainder. Now in `limesium-common`:
  `EndpointLogField` with its builder extensions (one enum, one
  `EndpointLogFieldTest`), `EndpointLoggingMetrics` parameterized with
  the stack's fourth outcome (`forRegistry(registry, OUTCOME_TIMEOUT |
  OUTCOME_CANCELLED)`), and `ExchangeLine`, the stack-neutral core of
  the emitters, over the two small interfaces `LoggedExchange` and
  `MeasuredBody` that both twins' `Exchange` and `BoundedBodyCapture`
  implement. All moved classes are `internal`; no host-visible package
  changes. The TEST-helper exception of 2026-08-30 was revoked: the
  "one 16-line copy" had become five copies of two helpers.
  `AwaitingAppender` and `installMdcAdapter` now ship to the twins as
  `limesium-common`'s `test-jar`. The code-style audit of the same day
  (`CODE_STYLE-2026-09-05T17-08-39.md`) added two more residents by the
  same routes: `MaskingKey` (finding 5) and the JUnit 5 fixture
  `CapturedLogger` with the `ILoggingEvent.keyValues()` extension in
  the test-jar (pattern S2: the per-class Logback fixture had been
  copied into 24 test classes). It also closed the twins' visibility
  gap: the servlet tee classes (`BoundedBodyCapture`, both wrappers)
  are `internal` like their reactive counterparts (finding 1).
- **2026-09-17:** the two `BoundedBodyCapture`s stay duplicated as
  concurrency shells, but the buffer beneath them was the same
  `ByteArrayOutputStream` twice - growing from 32 bytes by doubling,
  synchronized under a lock or a volatile handoff that already guards
  it, and copied once more for the truncated rendering. Ported from
  legatium (which needed an array a `reset` can cut back):
  `BoundedByteBuffer` moved to `limesium-common` with its unit test and
  fuzz target - a cap-bounded array, allocated on the first buffered
  byte and sized once by the declared `Content-Length` the wrappers and
  decorators hand it, cut back for the servlet twin's response reset.
  `decodeTruncated` takes a length, guards a decoder whose declared
  maximum undershoots, and is private to the buffer's file - its only
  caller. Each twin keeps its count, read state and
  locking; the truncation-boundary tests stay in the twins as tests of
  the twin API.
- **2026-09-18:** `EndpointLoggingPropertyOrigins`, the TRACE half of
  the auto-configurations' wiring report (every bound `endpoint-logging.*`
  value with Boot's origin, shadowed values of lower-precedence sources,
  masking key redacted), ported from legatium's `ClientLoggingPropertyOrigins`
  with its test. One rendering for both twins - the prefix is the same,
  only the bound classes differ, and the class takes the bound map, not
  the class. `limesium-common` gains `spring-boot` as a dependency for
  Boot's property-origin API; both twins bring it transitively already,
  so the shaded jars add nothing to a host.
- **2026-09-18 (second):** `RequestLoggingProperties` moved to
  `limesium-common`, by maintainer decision and without a deprecation
  period. The two copies had differed by the reactive-only `variant`
  key and by KDoc wording only - the same situation in which legatium
  shared its `ClientLoggingProperties` from the start, minus the one
  key. That key now binds beside the shared class, under the same
  prefix, as the reactive module's own `RequestLoggingVariantProperties`
  (two `@ConfigurationProperties` beans on one prefix: each binds the
  keys it knows and ignores the rest), so the servlet namespace carries
  no key it cannot honour. `RequestLoggingPropertiesTest` and the
  shared reference's `EndpointLoggingReferenceConfigTest` moved along
  and are pinned once; the reactive module keeps a test of the same
  name for its own `variant` reference file and the single-source rule.
  The shared reference YAML is a test resource of `limesium-common`
  now, the servlet module no longer declares it. Source-breaking for
  hosts that construct or import the class for a hand-wired filter:
  the package is `eu.inqudium.limesium.common`, like
  `HeaderLogProperties` before it - a major version.
- **2026-09-19:** `StatusClassification` joined common (ADR-0007): the
  status half of both twins' level/outcome resolution is one function,
  so a 4xx is `rejected` on the servlet line and the reactive line by
  construction, and the `on-failure` gate of both emitters reads
  `outcome != success` again.
- **2026-09-21:** the evidence for this decision sat on the wrong side of
  it, as legatium's review of 2026-09-05 had found there: Surefire tests
  the twins in `test` against the module, Shade inlines and writes the
  dependency-reduced POM in `package`, and nothing loaded the jars a
  consumer receives. `consumer-smoke/` with the CI job `consumer-smoke`
  closes that - one consumer per twin, since a host never carries both.
- **2026-09-21 (second):** the test-jar's helpers were public while
  every production class of the module is `internal` - the one place
  the two twin projects differed in the shape of their shared layer,
  legatium having adopted the test-jar with `internal` helpers the same
  day. The helpers are `internal` now; the twins' test compilation adds
  common's test-classes and tests jar to the friend paths. The "Not
  published" section also states precisely what the published POMs
  carry: no compile dependency on the module, but the test-scoped
  test-jar dependency, which no consumer resolves.

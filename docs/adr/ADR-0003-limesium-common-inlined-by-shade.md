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

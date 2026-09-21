# ADR-0008: The startup wiring report is a bounded diagnostic: DEBUG says what is in effect, TRACE where it came from

**Status:** Accepted  
**Date:** 2026-09-21  
**Deciders:** Dirk Haase (maintainer)  
**Related:** ADR-0002 (the observation line reports what sits around
the exchange identity, which it does not change), ADR-0003
(`EndpointObservationWiring` and `EndpointLoggingPropertyOrigins`
live in `limesium-common` by its criterion, ported from legatium),
ADR-0005 (the masking key is redacted on every line of the report);
legatium's ADR-0014 (the outbound sibling's decision of the same
day, which this one mirrors, and whose admission rule follows its
ADR-0008 for meters)

## Context

Both auto-configurations write a report about themselves at context
start, on their own logger (`RequestLoggingAutoConfiguration` of each
twin under `eu.inqudium.limesium`; the coroutine auto-configuration
reports on the reactive twin's), never on the exchange logger: at
DEBUG whether the module is on, which properties the filter bean
runs with, what was wired around it - on the servlet stack the filter
registration with its order and the completion listener, on the
reactive stack the variant that claimed the slot and, in the Reactor
variant, the `endpoint_*` MDC accessors - and whether Boot's server
observation and Micrometer Tracing sit around the filter; at TRACE,
for every `endpoint-logging.*` key some source sets, where the value
came from and which lower-precedence values it shadows. The Common
guide §4.6 documents the lines and how to read them.

The report was ported from legatium in one development cycle: both
`Added` entries under `[Unreleased]` of the CHANGELOG are report
lines, two shared classes came to `limesium-common` for it (and with
them the module's dependency on `spring-boot`), the bean method of
each filter took on `Environment` and
`ObjectProvider<BoundConfigurationProperties>`, and every line came
with a test. On the outbound side the architecture review of
2026-09-21 (legatium,
`docs/assessment/ARCHITECTURE_REVIEW-2026-09-21T08-44-36.md`,
finding 3) measured the same feature at about 230 production and 250
test lines, noted that its rationale stood only in the CHANGELOG,
that Boot's `env` actuator endpoint shows a property's value per
source with its origin, and asked for the decision and a rule before
a release turns the lines into something operators grep for -
warning that without a rule the next cycle adds the next line.
Limesium carries the same code and the same risk; this record is the
inbound side's answer, taken with legatium's ADR-0014.

The forces:

**The questions are real and have no property.** Whether the filter
is actually in the chain and at which position, whether the exchange
runs inside Boot's server observation (which decides whether the
host's handler lines carry a `traceId` and whether the exchange time
is part of the server span), and whether the Reactor variant's
handler-side MDC is wired are decided by the host's classpath, bean
set and filter registrations, not by any `endpoint-logging.*` key.
Before the report, the only way to learn the answer was to send a
request and read the lines.

**A web host is not necessarily an actuator host.** Every limesium
host is a web application of the twin's type, but the actuator is
optional (Common guide, prerequisites), and where it exists, its
`env` endpoint - which shows every property of the application - is
exposed deliberately rarely, a security decision an operator does not
make for a logging library. The startup log is the one channel every
host has, and the wiring report is written before the first request
arrives, which is when an operator looks for it.

**Boot's own startup output already answers part of it.** The
condition evaluation report (DEBUG on
`org.springframework.boot.autoconfigure`) names why an
auto-configuration did or did not run - the switch, the wrong web
application type, a missing class. Boot's servlet initializer logs
the filter mapping at DEBUG, but neither the order nor the dispatcher
types. A line the module writes about something Boot already says at
startup is ceremony.

**A diagnostic that grows without a rule becomes a subsystem.** The
meter family has such a rule in legatium (its ADR-0008: a meter needs
a blind spot none of the others covers); the report had none, on
either side.

## Decision

**The wiring report stays, with its two stages and with an admission
rule for every line: a line answers a question about the module's
wiring that no `endpoint-logging.*` key states and that Boot's own
startup log does not answer. An actuator endpoint does not count as
an answer.**

### The two stages

| Level | Question                                                | Lines                                                                                                     |
|-------|---------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| DEBUG | **what** is in effect                                   | the enabled line, the bean line with the bound properties (on the reactive stack naming the variant that claimed the slot), the registration lines of the stack, the observation line |
| TRACE | **where** each value came from, and what it shadows     | one line per set `endpoint-logging.*` key with Boot's origin, shadowed values indented beneath it; one line saying so when no key is set; one line saying so when the origins are unavailable |

DEBUG is for the operator asking "is it on and is it in the chain";
TRACE is for the one asking "why is my value not in effect". The
stages are switched by the host's logging backend, like the exchange
logger's level (Common guide §4.5); there is no `endpoint-logging.*`
key for the report, because a key would be one more value whose
origin the report would then have to explain.

### The admission rule, applied to the lines that exist

| Line                                        | Wiring question                                                                     | Why Boot's startup log does not answer it                                                                       |
|---------------------------------------------|-------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| enabled                                     | is the auto-configuration active for this web application type?                     | the condition report lists it among hundreds of positive matches; this is the anchor of the family, and a grep for `Endpoint logging` needs a first line |
| bean, with the properties                   | which values does the filter run with, and on the reactive stack which variant won? | Boot logs no bound `@ConfigurationProperties` bean; `configprops` is an endpoint; the variant is a classpath decision unless `variant` forces it |
| filter registration (servlet)               | is the filter in the chain, at which order, for which dispatcher types and mapping? | Boot's servlet initializer names the mapping at DEBUG, not the order - and the order against Boot's observation filter is the fact that matters |
| completion listener (servlet)               | is the emission point registered?                                                   | no line of Boot's names the listener's role                                                                     |
| MDC accessors (Reactor variant)             | are the handler-side `endpoint_*` accessors registered with Micrometer's `ContextRegistry`? | a classpath detection (`context-propagation`) with no property and no Boot output                          |
| observation (once, all singletons up)       | does the exchange run inside the server observation, and will the handler lines carry a `traceId`? | decided by two optional bean sets and, on the servlet stack, by a filter order; Boot reports neither in relation to this module; the fourth rendering names a host's re-registration behind the filter |
| origins, shadowed values (TRACE)            | where did each value come from, and which value lost?                               | Boot tracks the origin but prints it nowhere; `env` is an endpoint, and the masking key must stay redacted      |

The origins lines are the ones legatium's review weighed against
Boot's `env` endpoint. They stay, for the two forces above: the
endpoint is optional and rarely exposed, and its exposure is not a
decision a logging library should require. The report renders the
same facts, redacts the masking key on every line (ADR-0005), and
costs nothing unless TRACE is on.

### The bounds

1. **Context start only.** The report describes the state of the
   context when the singletons exist. It never describes the fate of
   a request: a host can filter the observation with an
   `ObservationPredicate` or exclude a path. The exchange line is the
   per-request truth; a per-request diagnostic is not a report line
   and is out of scope here.
2. **Computed only when its level is enabled, never per request.**
   Each stage is guarded by its level check before any work; the
   origin rendering walks the property sources once.
3. **Nothing when the auto-configuration does not run** -
   `endpoint-logging.enabled=false`, the wrong web application type,
   the module not on the classpath. No line appears, and Boot's
   condition report names the reason. The module does not repeat it.
4. **Diagnostic, not a contract.** The facts a line carries are
   pinned by the auto-configuration tests (the level, the presence of
   each line, the redaction, the renderings of the observation line,
   the silence when switched off); the wording is not frozen the way
   the meter names and the `endpoint_*` field names are. A wording
   change is a `Changed` entry in the CHANGELOG, not a migration
   note. An operator's grep should key on the `Endpoint logging`
   prefix every line of the family carries.
5. **One implementation for both twins where the fact is the same.**
   The observation rendering and the origin rendering live in
   `limesium-common` (ADR-0003); each twin contributes only its
   placement clause (the filter order on the servlet stack, the
   `HttpWebHandlerAdapter` on the reactive stack) and its own
   stack-specific registration lines.

### Adding a line

A new line needs a row in the table above, with the wiring question
it answers and the reason Boot's startup log does not, the level per
the two stages, an entry in the Common guide §4.6, and a test that
pins its presence. A line that would answer a per-request question,
or that an `endpoint-logging.*` key already states (the bean line
shows every key), does not qualify. The size of the auto-
configuration is the running cost of this decision: the filter bean
method takes seven parameters on the servlet stack and in the
coroutine variant, eight in the Reactor variant, two of them for the
report alone; a line that needs a further collaborator injected there
is a sign to stop and revisit.

**Revisit when:** Boot prints bound property origins in its own
startup output; a Boot release makes the observation placement or the
filter order readable from the condition report; an operator reports
that a stage is never read; or a cycle adds a third `Added` entry for
the report, which is the growth this ADR exists to bound. A change of
the rule is taken together with legatium's ADR-0014, so the two
reports keep one shape for an operator running both.

## Consequences

**Positive:**

- The report has a reason and a rule in one place; the CHANGELOG
  entries that introduced it point here instead of carrying the
  rationale alone.
- An operator of a host without the actuator, or without `env`
  exposed, gets the answer to "is it on, is it in the chain, does the
  exchange run in the server span, where does this value come from"
  from the startup log, before the first request and without a
  redeploy.
- The admission rule keeps the report a report: the next cycle
  cannot add a line without naming the question and Boot's silence
  on it.
- The bounds keep the per-request path untouched: nothing of the
  report runs per exchange.
- Both sides of the Inqudium pairing carry the same rule, so an
  operator reads one shape of report for the inbound and the outbound
  side.

**Negative:**

- The auto-configurations, the thinnest layer of the module, carry
  the widest bean signatures of the project (seven resp. eight
  parameters) and about 184 shared production lines plus the twins'
  report code, with 230 dedicated test lines in `limesium-common` and
  the report assertions of both auto-configuration tests, for a
  feature that is off by default. This is accepted with this ADR.
- `limesium-common` depends on `spring-boot` for the origin
  rendering; both twins bring it, so no consumer sees a new
  transitive, but the shared module is no longer Spring-Boot-free.
- The origins stage duplicates what the `env` endpoint shows on a
  host that has and exposes it. The duplication is the price of
  serving the hosts that do not.
- The lines are prose that operators will copy into runbooks; a
  wording change after the release is a CHANGELOG entry and possibly
  a broken grep on the operator's side.

**Neutral:**

- Whether the report becomes the model for a further diagnostic (a
  line per excluded path pattern, for one) is not decided here; such
  a line would have to pass the same rule.
- The outbound sibling legatium records the same decision as its
  ADR-0014, with `adapter` in place of `endpoint` and the builder
  attach lines its customizers produce in place of the registration
  lines here.

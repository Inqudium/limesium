# Architecture & Appropriateness Analysis: limesium (Multi-Modul: limesium-common, limesium-servlet-logging, limesium-reactive-logging, benchmarks)

1. Identification of the Codebase
   - **Repository:** `https://github.com/Inqudium/limesium.git`
   - **Commit-Hash:** `a6673375d411e8f1f1ba7bfc35f29a0358731f35` (Full)
   - **Reference (Branch/Tag):** `refs/heads/main` (kein Release-Tag am Commit; `git describe`: `1.1.0-97-ga667337`)
   - **Erstellt am:** 2026-08-31T10:51:58+02:00
2. Scope of the Analysis
   - **Included:** `./limesium-common/src/main/`, `./limesium-servlet-logging/src/main/`, `./limesium-reactive-logging/src/main/` (alle 31 Produktionsdateien vollständig gelesen); Testcode aller drei Module als **eigenständiger Angemessenheits-Gegenstand in voller Tiefe** (Teil B der Testarchitektur-Prüfung angewendet, zusätzlich zu Teil A als Messinstrument); `./benchmarks/` als Verifikationsapparat; Build-/CI-Struktur, ADRs, GUIDEs und Referenzkonfiguration als dokumentierte Architekturabsicht
   - **Excluded:** Build-Ausgaben (`./target/`, `./*/target/`), generierte Site-Inhalte (`./docs/tests/`), Fremdabhängigkeiten
3. Analysis Environment & Tools
   - **Target Environment:** Artefaktziel Java 21; Build erfordert JDK 24+ (CI: JDK 25); Analyse-JVM Oracle Java 26.0.1; Kotlin 2.4.10; Spring Boot 4.1.1
   - **Build system:** Apache Maven 3.9.15 (Multi-Modul-Reaktor; `./benchmarks` eigenständig)
   - **Analysis tools used:** rein statisch — Git, `rg`, `tokei`, `diff`-basierte Twin-Vergleiche, Quelltext-/POM-/ADR-Inspektion; keine Build- oder Testausführung für dieses Review
4. Placement & Output
   - **Working directory (workdir):** `/home/dirk/IdeaProjects/limesium` (absoluter Referenzpunkt; alle relativen Pfade beziehen sich darauf)
   - **Report output path:** `./docs/assessment/ARCHITECTURE_REVIEW-2026-08-31T10-51-58.md` (relativ zum workdir; Präfix + ISO-8601-Zeitstempel gemäß Benennungsregel)
   - **Scope root (relative to the workdir):** `./` (Reaktorwurzel)
   - **Path convention for findings:** `<Pfad relativ zum workdir>:<Zeile>` (z. B. `./limesium-servlet-logging/src/main/kotlin/.../RequestLoggingProperties.kt:136`)
   - **Frühere Analysedokumente (read-only, gegen den aktuellen Stand verifiziert einbezogen):** `./docs/assessment/CODE_ANALYSIS-2026-08-30T21-52-43.md`, `./docs/assessment/CODE_ANALYSIS-2026-08-31T09-30-53.md`, `./docs/assessment/COMMENT_AUDIT-2026-08-31T01-03-25.md` sowie die Modul-`PERF_ANALYSIS`-Berichte

# 1. Executive Summary

Die Architektur passt zum Problem — und das ist bei diesem Projekt keine triviale Aussage, denn das sichtbare Mittelaufgebot (zwei Paradigmen-Twins, drei Filtervarianten, sieben Meter-Familien, vier ADRs, JMH-Apparat, Fuzz-Ziele, 40 % Kommentaranteil) ist für eine 2.700-Code-Zeilen-Bibliothek ungewöhnlich hoch. Die Prüfung jeder einzelnen Abstraktion gegen die fünf Angemessenheitsfragen ergibt jedoch fast durchweg **tragende Komplexität**: Die harten Probleme sind real (Container-Destruktionsvarianz Tomcat/Jetty, reaktive Cancel-/Late-onNext-Rennen, Exactly-once-Emission, Fail-open-Garantie einer Bibliothek, die fremde Requests niemals stören darf), und die dokumentierten Kräfte — vier explizite ADRs, benchmark-bestätigte Performance-Entscheidungen, ein hartes Twin-Symmetrie-Invariant in CONTRIBUTING — decken die Struktur. Spekulative Generalität wurde gezielt gesucht und nicht gefunden: Jedes Interface hat entweder eine zweite reale Implementierung (`EndpointLoggingFilter`: Reactor- und Coroutine-Variante) oder ist eine echte, von den Tests tatsächlich genutzte Naht (`NanoTimeSource`, `CorrelationIdGenerator` — injizierte Zeit/Zufälligkeit statt Mock-Bibliothek).

Die Tendenz geht damit weder zu Over- noch zu Under-Engineering, sondern zu **bewusst teurem, aber bezahltem Aufwand**: Die laufenden Kosten konzentrieren sich auf das Twin-Lockstep (jede Kontraktänderung ist ein Zwei-Modul-Port) und die Prosa-Masse (Kommentare ≈ 75 % des Code-Volumens), beides dokumentiert entschieden und durch Kontrolltests bzw. generierte Evidenz diszipliniert. Der eine echte Befund ist eine **Inkonsistenz zwischen ADR-0003 und dem Code**: Die Klasse `HeaderLogProperties` samt der sicherheitsrelevanten `mask()`-Funktion ist byte-identisch in beiden Twins dupliziert — exakt das Kriterium, nach dem das ADR Code nach `limesium-common` zieht; die im ADR notierte Begründung („genuinely differ") trifft auf diese Klasse faktisch nicht zu. Daneben bleiben zwei kleine Beobachtungen (Fail-open-Guard-Boilerplate als milde Unter-Abstraktion, messbare Drift-Kosten der Dokumentationsmasse). Kritische oder hohe Befunde gibt es keine.

**Testurteil (Architekturperspektive):**

1. **Testbarkeit der Architektur:** Ausgezeichnet. Die Kernlogik ist ohne Spring-Kontext, Container oder Broker verifizierbar; die Nähte (Zeitquelle, Id-Generator, MeterRegistry, SLF4J-Logger, handgeschriebene Fakes) sind real und werden real genutzt — keine einzige Naht existiert nur „for mockability", es gibt keine Mock-Bibliothek im Projekt.
2. **Nutzung & Pyramidenform:** Gesund und passend zur Testbarkeit. Von ~330 Tests laufen nur 10 Klassen als `@SpringBootTest` (plus eine eigene Embedded-Undertow-Fabrik); die Last tragen schnelle, deterministische Unit-Tests gegen Springs Mock-Server-Typen. Die Container-Matrix (Netty, Tomcat, Jetty, Undertow × Capture/Tracing) prüft genau das, was nur Container zeigen können — kein Integrationstest-Reflex, sondern gezielte Grenzverifikation.
3. **Gravierendste Lücken/Anomalien:**
   - JVM-globale Zustände (Reactor-Hooks, Micrometer `ContextRegistry`, MDC-Adapter, Logger-Level) erzwingen serielle Testausführung — Ursache ist die Domäne (globale Registries von SLF4J/Micrometer/Reactor), nicht das Moduldesign; als Parallelisierungs-Hypothek dokumentiert und akzeptiert.
   - Die Rationale-Kommentar-Konvention (3-Zeilen-Block + Given/When/Then, generator-erzwungen, 199 Blöcke) ist schwere Zeremonie — aber konsumierte: die generierte Test-Evidence-Seite ist ihr Abnehmer, die Konvention damit gerechtfertigt.
   - Zwei Integrationsklassen + Testserver-Fabrik prüfen Undertow, einen ausdrücklich nicht unterstützten Container — dokumentiertes Boundary-Pinning („unsupported, not impossible"), kein Befund, aber ein bewusst gepflegter Zusatzaufwand.

# 2. Problem-Baseline und Methodik

**Kerndomäne:** Eine publizierte Spring-Boot-Bibliothek (Maven Central, `eu.inqudium`) mit exakt einer Aufgabe: eine strukturierte `endpoint_*`-Logzeile pro HTTP-Exchange an der Servicegrenze, plus MDC-Identität während der Verarbeitung und eine kleine Familie flankierender Metriken. Zwei Paradigmen-Twins (Servlet/MVC und WebFlux mit optionaler Coroutine-Variante) mit hartem Symmetrie-Invariant: identische Felder, identische Konfiguration, gemeinsame Referenz-YAML.

**Reale Anforderungen und Maßstab:** Keine Last-SLAs im klassischen Sinn — aber als Bibliothek im Request-Pfad fremder Anwendungen gelten drei harte operative Anforderungen, die die Komplexität kalibrieren: (a) **Fail-open** — ein Logging-Fehler darf nie einen Request stören; (b) **beobachtungsneutrale Wire-Semantik** (ADR-0002); (c) **Exactly-once-Emission mit finalem Status** über Container- und Reaktor-Lebenszyklen hinweg, deren Varianz (Tomcat vs. Jetty Destruktionszeitpunkte, Reactor-Cancel vs. late onNext) extern vorgegeben und nicht wegabstrahierbar ist. Team: Einzelmaintainer; Betriebsreife: hoch (CI, CodeQL, OSV, Scorecard, SLSA, nightly Fuzzing — Ökosystem-Vorlage tabellarium).

**Dokumentierte Architekturabsicht (rechtfertigende Kräfte):** ADR-0001 (Fuzzing-Signal), ADR-0002 (Trace-Id als Request-Id, Neutralität), ADR-0003 (byte-identischer Code nach `limesium-common`, per Shade inlined; bewusst duplizierter Rest), ADR-0004 (Counting-Generator als Default) — alle mit Kontext, verworfenen Alternativen und Konsequenzen. Dazu: CONTRIBUTING-Scope-Grenze („does one thing"), Performance-Entscheidungen nur nach Messung (PERF_ANALYSIS → JMH-Bestätigung → Übernahme, im Code mit Fundstellen verankert). Der Code wurde gegen diese Absicht geprüft, nicht gegen ein eigenes Ideal.

**Technologie-Kohärenz:** Servlet-Twin blockierend (MVC), Reactive-Twin reaktiv (Reactor) mit koroutinen-idiomatischer Variante — die Bibliothek folgt jeweils dem Stack des Hosts, statt einen zu erzwingen; kein blockierender Treiber hinter reaktivem Code, keine erzwungenen Transitiven (Coroutines/context-propagation optional). Kohärent.

**Testtopologie als Baseline-Signal:** 51 Testdateien, ~330 Tests; 10 `@SpringBootTest`-Klassen (die realen HTTP-/Container-Läufe), 3 `ApplicationContextRunner`-Klassen, 3 Jazzer-Fuzz-Ziele, 7 kleine Testhelfer (Fakes/Guards/Fixture — keine Basisklassen-Hierarchie, kein Test-DSL). Kernlogik überall isoliert testbar; die Pyramide steht auf der Spitze der Unit-Tests, nicht auf dem Kopf.

**Analysierte vs. nicht analysierte Einheiten:** Alle 31 Produktionsdateien (5.010 Zeilen, davon 2.658 Code) vollständig gelesen; Testcode strukturell und stichprobenartig vollständig (Topologie, Helfer, Konventionen, Isolation); `./benchmarks` auf Angemessenheit des Apparats. Nicht vertieft: die MkDocs-/CI-Skripte (`.github/scripts/`) über ihre Rolle als Evidenz-Generatoren hinaus. Blinder Fleck: reale Nutzerzahlen/Deployments der Bibliothek sind nicht erkennbar; die Angemessenheit der publizierten API-Stabilitätskosten wurde zugunsten des dokumentierten Publikationsanspruchs (Maven Central, SemVer-Release-Prozess) unterstellt.

# 3. Statistiken

| Merkmal | Wert |
|---|---:|
| Findings Critical | 0 |
| Findings High | 0 |
| Findings Medium | 1 |
| Findings Low | 2 |
| Systemische Muster | 2 |
| Geprüfte und als tragend/gerechtfertigt eingestufte Verdachtsflächen | 10 (siehe Rangliste) |

# 4. Rangliste (Phase 1)

Skala: 5 = hohe Wahrscheinlichkeit/Reichweite eines Mittel-Zweck-Mismatches, 1 = triviale Fläche. Die Spalte „Ergebnis" nimmt das Phase-2/3-Urteil vorweg: die meisten hoch gerankten Einheiten wurden geprüft und **entlastet**.

| Einheit | Score | Begründung des Rankings | Ergebnis |
|---|---:|---|---|
| Twin-Grenze aus ADR-0003 (was dupliziert bleibt vs. `limesium-common`) | 4 | Architektur-Naht mit Ausstrahlung: jede Fehlziehung kostet dauerhaft Zwei-Modul-Ports | **Finding 1**: byte-identische `HeaderLogProperties`/`mask()` widerspricht dem ADR-Kriterium |
| Properties-Twins (`RequestLoggingProperties.kt` beider Module) | 4 | 90 % identisch; Bindungs-API, Host-sichtbar | Teil von Finding 1; `variant`-Schlüssel als echte Differenz bestätigt |
| Fail-open-Guard-Gewebe (querschnittlich, 37 try/catch, 30 `reportQuietly`) | 3 | Hohe Wiederholungsdichte, Kandidat für Unter-Abstraktion | **Muster P1** (mild, Low) |
| `RequestLoggingFilter` (Servlet, 567 Zeilen) | 3 | Größte, dichteste Einheit; Async-/Destruktions-Choreographie | Entlastet: Container-Varianz ist inhärent; Kollaborateure sauber herausgelöst |
| `ExchangeLifecycle` + zwei Filtervarianten (reaktiv) | 3 | Geteilte Choreographie + Variantenmechanik + `variant`-Selektor | Entlastet: Coroutine-Variante liefert real andere MDC-Semantik (`MDCContext`); Selektor löst echtes Classpath-Fehlschluss-Problem |
| `EndpointLoggingMetrics`-Twins + `forRegistry`-WeakMap | 3 | 7 Meter-Familien, Registry-Lifecycle-Feinheiten | Entlastet: jeder Meter mit belastbarer Beobachtbarkeits-Begründung; WeakMap antwortet auf realen Gauge-Defekt (CA-03), Residual dokumentiert |
| Dokumentationsmasse (2.006 Kommentarzeilen auf 2.658 Codezeilen) | 3 | Messbare Drift-Historie (Comment-Audit, Provenance-Anchoring) | **Finding 3** (Low) |
| `EndpointLogFields`-Twins (Enum + Formatgarantie) | 2 | Doku-schweres Enum, totes `ASYNC` im reaktiven Twin | Entlastet: Enum verhindert real Feldnamen-/Typ-Drift; `ASYNC`-Konstante hält beide Enums template-deckungsgleich (dokumentiert) |
| `BoundedBodyCapture`-Twins | 2 | Zwei Implementierungen desselben Namens | Entlastet: genuin verschiedene Nebenläufigkeitsmodelle (volatile Handoff vs. Freeze-Lock) — exakt der ADR-0003-Restbestand |
| Capturing-Wrapper/Dekoratoren (Servlet + reaktiv) | 2 | Viel Fläche für Stream-/Charset-/Reset-Treue | Entlastet: jede Verzweigung bedient einen Servlet-/Reactor-Vertrag; passive Tees statt Replay-Puffer sind die einfachere Lösung |
| `limesium-common` (Nähte, `Traceparent`, Generator) | 2 | Kleine Klassen, `fun interface`-Nähte | Entlastet: Nähte werden von Tests real bedient (kein Mockability-Fall); ADR-0004 deckt den Generator |
| Auto-Konfigurationen (3) inkl. `NotForcedToReactor` | 2 | Bedingungs-Plumbing | Entlastet: Back-off-Kette ist der Spring-idiomatische Minimalweg für „genau ein Filter" |
| Testarchitektur (7 Helfer, Container-Matrix, Rationale-Konvention) | 2 | Zeremonie-Verdacht; Undertow-Tests für Unsupported-Territorium | Entlastet mit Anmerkungen (siehe Testurteil); Helfer klein und mehrfach genutzt |
| `benchmarks`-Modul + PERF-Apparat | 2 | Eigener Modul-Apparat für eine Logging-Bibliothek | Entlastet: Messen-vor-Optimieren ist gelebt (Decision Rules, Übernahmen); Drift seit CODE_ANALYSIS-2026-08-31 CI-gesichert |
| DTO-/Zustandsklassen (`Exchange` beide Twins), `Mdc.kt`, Interceptor | 1 | Wenig Abstraktionsdichte | Nur überflogen; unauffällig |

# 5. Findings-Checkliste

## 🔴 Critical

Keine Findings. Ausdrücklich geprüft und verneint: falsche Abhängigkeitsrichtungen (die Twins hängen von `common` ab, nie umgekehrt; keine Framework-Typen in `common` außer SLF4J, dem Domänengegenstand), Paradigmen-Mismatch (kein Blocking im reaktiven Pfad), Gott-Module.

## 🟠 High

Keine Findings. Die beiden teuersten Strukturen — Twin-Duplikation und Fail-open-Dichte — haben dokumentierte bzw. reale Kräfte und bleiben unterhalb der High-Schwelle (siehe Finding 1 und Muster P1).

## 🟡 Medium

- [x] 1. [UNIT — `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingProperties.kt:136` und `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/RequestLoggingProperties.kt:152`] {Medium} {Confidence: high} {Consistency} Byte-identische `HeaderLogProperties` (inkl. `mask()`) in beiden Twins widerspricht dem Extraktionskriterium von ADR-0003
  - **Status: FIXED (2026-08-31)** — `HeaderLogProperties` lebt jetzt in `limesium-common` (Shade-inlined wie der übrige ADR-0003-Bestand); Unit-Test und `HeaderMaskingFuzzTest` sind mitgezogen, die Twin-Properties behalten nur die echten Differenzen. ADR-0003 um ein Amendment (2026-08-31) ergänzt, das den Fund und den Source-Breaking-Hinweis festhält; GUIDEs und Benchmarks nachgezogen.
  - Actual structure: Die Klasse `HeaderLogProperties` — Include/Exclude/Mask-Auswahl samt der `mask()`-Fingerprint-Funktion (~75 Zeilen) — ist in beiden Modulen **byte-identisch** dupliziert (per `diff` verifiziert); sie lebt jeweils in der Properties-Datei, deren übriger Inhalt sich nur um den reaktiven `variant`-Schlüssel und Dokumentationsformulierungen unterscheidet (22 von 210 Zeilen Differenz).
  - Solved problem / justifying force: ADR-0003 zieht „byte-identischen" Code nach `limesium-common` und lässt duplizieren, „whose twin copies genuinely differ" — es zählt „the properties" pauschal zum duplizierten Rest. Für diese Klasse trifft die Begründung faktisch nicht zu: Sie differiert nicht. Die Kraft ist also nur teilweise vorhanden — das ADR deckt die Datei, nicht diese Klasse; genau der Fall „Code weicht vom ADR ab bzw. das ADR ist an dieser Stelle veraltet" ist per Methodik ein Befund.
  - Cost: Jede Änderung an Header-Auswahl oder Maskenformat ist ein synchroner Zwei-Modul-Port; das Maskenformat ist ein sicherheitsrelevanter Twin-Kontrakt (stabiler Fingerprint, cross-modulare Korrelierbarkeit), dessen Drift stille Kontraktspaltung wäre. Kompensierende Kontrollen existieren und greifen (Literal-Pin in beiden `TwinContractTest`s, `HeaderMaskingFuzzTest`, Referenz-YAML-Tests) — sie sind aber genau die laufenden Kosten, die die Extraktion überflüssig machen würde.
  - Simpler alternative: `HeaderLogProperties` (mit `mask()`) in `limesium-common` aufnehmen — der Shade-Mechanismus von ADR-0003 trägt das bereits (inlined, unpubliziert, `-Xfriend-paths`); alternativ ADR-0003 um eine explizite, begründete Ausnahme ergänzen, damit Kriterium und Enumeration wieder übereinstimmen. Eines von beiden — Code oder ADR — sollte nachziehen.
  - Reversibility: Moderat. Der Paketumzug ist für Hosts source-breaking (die Klasse ist Teil der öffentlichen Bindungs-API); dieselbe Bruchklasse hat ADR-0003/0004 bereits einmal gebündelt ausgeliefert — der nächste Major-/Breaking-Release ist der billige Moment. Die reine ADR-Korrektur ist jederzeit kostenlos.

## 🟢 Low

- [ ] 2. [UNIT — querschnittlich; repräsentativ `./limesium-reactive-logging/src/main/kotlin/eu/inqudium/limesium/reactive/logging/ExchangeLifecycle.kt:128-175` und `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:176-291`] {Low} {Confidence: medium} {Under-Engineering / Coupling & Cohesion} Fail-open-Guard-Boilerplate wiederholt sich weit jenseits der Dreierregel (siehe Muster P1)
  - Actual structure: 37 gleichförmige try/catch-Guards, 30 `reportQuietly`-Stellen, davon 7 wörtlich identische InterruptedException-Restore-Blöcke — jeweils inline mit individuellem Warntext, verteilt über Filter, Lifecycle, Emitter und Interceptor beider Twins.
  - Solved problem / justifying force: Der Fail-open-Vertrag selbst ist tragend und dokumentiert; teilweise rechtfertigt sich die Inline-Form durch standortspezifische Degradationssemantik (Maskierungsverbot für Anwendungs-Exceptions, unterschiedliche Stage-Zählung). Für die identischen Interrupt-Restore- und Report-Blöcke existiert jedoch keine Kraft — `reportQuietly` beweist, dass das Projekt solche Helfer extrahiert, es hat nur beim größeren Muster aufgehört.
  - Cost: Kognitive Last — `doFilterInternal` und `onTerminal` verstecken ihre eigentliche Choreographie hinter Guard-Lagen (bis vier Ebenen try/finally/try/catch); jeder neue Kollaborateur-Aufruf erzeugt einen weiteren Block, pro Twin. Grob 100-150 Zeilen wären durch einen kleinen Guard-Helfer je Degradationsklasse einsparbar.
  - Simpler alternative: Ein bis zwei `inline`-Helfer in `limesium-common` (z. B. „guard mit Stage-Zählung + Interrupt-Restore, Original-Exception niemals ersetzen"), von den Standorten mit abweichender Semantik weiterhin nicht benutzt.
  - Reversibility: Billig und risikoarm (rein mechanische Extraktion, vollständig testgedeckt); ebenso vertretbar ist bewusstes Stehenlassen — als Einzelposten unterhalb jeder Dringlichkeit.

- [ ] 3. [UNIT — Dokumentationsgewebe der Produktionsmodule, repräsentativ `./limesium-servlet-logging/src/main/kotlin/eu/inqudium/limesium/servlet/logging/RequestLoggingFilter.kt:23-107`] {Low} {Confidence: medium} {Consistency / Maintainability} Die Prosa-Masse hat messbare, wiederkehrende Drift-Kosten
  - Actual structure: 2.006 Kommentarzeilen auf 2.658 Produktions-Codezeilen (~75 % des Code-Volumens), großteils Verhaltens- und Begründungsprosa mit Querverweisen auf Berichte, ADRs und Tests.
  - Solved problem / justifying force: Der Audit-Anspruch des Projekts ist dokumentierte Absicht, und ein erheblicher Teil der Prosa trägt echte, nicht code-ausdrückbare Verträge (Container-Verhaltensvarianz, Residuals, Grenzen). Die Kraft ist real — der Befund richtet sich nicht gegen die Dichte an sich.
  - Cost: Empirisch belegt statt vermutet: Innerhalb von zwei Tagen erzwang die Prosa ein eigenes Comment-Audit (CA-01…CA-09), eine ~150-Referenzen-Provenance-Bereinigung (PR #41) und mehrere Stale-Doc-Findings zweier Defektanalysen — wiederkehrender Remediation-Aufwand ist der laufende Preis des Mediums Prosa.
  - Simpler alternative: Keine Reduktion, sondern konsequente Fortsetzung der bereits begonnenen Härtung: datierte/auflösbare Provenance (etabliert), generierte statt gepflegte Evidenz (etabliert), und wo möglich ausführbare Verträge (Literal-Pins, Referenz-YAML) an Stelle beschreibender Wiederholung — die Prosa dann auf das Warum beschränken.
  - Reversibility: Laufende Praxis, kein Umbau; Kosten fallen ohnehin nur bei Änderungen an.

# 6. Systemische Muster

**P1 — Inline-Fail-open-Guards statt eines gemeinsamen Helfers (mild, Low; Basis von Finding 2).** ~37 try/catch-Guards und 30 `reportQuietly`-Stellen über 8 Produktionsdateien beider Twins (Zählung per `rg` über `src/main`), darunter 7 wörtlich identische Interrupt-Restore-Blöcke. Reichweite: das gesamte Fehlerisolations-Gewebe; Sanierungsaufwand: klein (mechanische Helfer-Extraktion nach `limesium-common`, geschätzt 100-150 Zeilen Ersparnis). Kein Drama — aber das eine Muster, bei dem das Projekt seine eigene, sonst konsequent angewandte Extraktionsdisziplin (Dreierregel, `reportQuietly`) nicht zu Ende führt.

**P2 — Twin-Lockstep als bewusst getragene Prozesslast (Anmerkung mit einer konkreten Abweichung; Basis von Finding 1).** Jenseits des byte-identischen Falls aus Finding 1 bleiben die Twins in weiten Teilen nur *nahezu* identisch: `EndpointLoggingMetrics` 91 % gleiche Zeilen (Differenz: ein Outcome-Vokabel + Gauge-Semantikprosa), `EndpointLogFields` 71 % (Differenz: Doku und drei stack-spezifische Felder), Properties 90 % (Differenz: `variant`) — zusammen ~600-700 nahezu identische Zeilen (Zählung per normalisiertem `diff`). Das ist durch ADR-0003 gedeckt („genuinely differ" trifft hier, anders als in Finding 1, tatsächlich zu — die Differenzen sind semantisch, nicht kosmetisch) und durch Kontrakt-, Referenz- und Literaltests diszipliniert; es bleibt dennoch die größte einzelne laufende Kostenposition der Architektur. Revisit-Trigger, im Sinne des ADR selbst: der nächste Lockstep-Port, der sich als reine Doppelarbeit ohne semantische Differenz anfühlt, sollte die Grenze erneut auf byte-identisch gewordene Bestände prüfen (Finding 1 zeigt, dass genau das bereits einmal unbemerkt eingetreten ist).

Positiv festzuhalten (kein Muster im Befundsinn, aber Teil des Gesamtbilds): Messen-vor-Optimieren ist durchgängig gelebt (jede Performance-Komplexität im Code trägt eine benchmark-bestätigte Fundstelle), jede auffällige Einzelentscheidung — vom `AtomicLong` statt ThreadLocal im Generator bis zum bewusst fehlenden Zero-Copy-Interface im Response-Dekorator — ist mit ihrer Kraft dokumentiert und meist testgepinnt, und die Testarchitektur nutzt die vorhandenen Nähte, statt sie zu umgehen.

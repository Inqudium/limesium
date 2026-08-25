# Elasticsearch mapping for the `endpoint_*` exchange-log fields

Companion text to
[`limesium-servlet-logging-fields.component-template.json`](limesium-servlet-logging-fields.component-template.json) —
the mapping of the thirteen structured fields this module writes per inbound HTTP exchange.

> **Status note.** Unlike the `adapter_*` template in `web-client`, this is **the definition, not an
> extract**: the `endpoint_*` family is not yet part of any upstream data-stream mapping. Whoever wires
> this module into a log pipeline composes this template there **before** the first event arrives — a
> field that reaches the index unmapped is mapped dynamically, and for a body or a header that means the
> value becomes searchable, the one outcome §8 of the
> [mapping guide](../../../logback-kafka-appender/docs/mapping-settings-elastic-search/mapping-guide-neue-felder.md)
> forbids. Once an upstream mapping exists, it wins, and this file becomes an extract like its `adapter_*`
> sibling.

```bash
curl -X PUT "$ES/_component_template/limesium-servlet-logging-fields" \
     -H 'Content-Type: application/json' \
     --data-binary @limesium-servlet-logging-fields.component-template.json
```

[`EndpointLogFieldTest`](../../src/test/kotlin/eu/inqudium/limesium/servlet/logging/EndpointLogFieldTest.kt)
compares this template's field set against `EndpointLogField.entries` at build time and fails in both
directions — a field added to the enum without a mapping, and a mapping left behind for a removed field.

## The mapping, and the access pattern each line follows

| Field | Type | `index` | `doc_values` | Access pattern |
|---|---|---|---|---|
| `endpoint_outcome` | `keyword` | true | on | aggregate — `success` / `failure` / `timeout`; decoupled from the level |
| `endpoint_duration_ms` | `long` | true | on | **compute** — percentiles; request occupancy, not bare handler time |
| `endpoint_request_method` | `keyword` | true | on | aggregate — closed set of HTTP verbs |
| `endpoint_response_status_code` | `short` | true | on | aggregate — a numeric **label**, never summed |
| `endpoint_url_template` | `keyword` | true | on | aggregate — the handler pattern, parametrised, so it repeats |
| `endpoint_url_path` | `keyword` | true | **off** | filter exactly — resolved ids, repetition factor ≈ 1 |
| `endpoint_url_query` | `keyword` | true | **off** | filter exactly |
| `endpoint_slow` | `boolean` | true | on | aggregate — present only when the slow threshold was reached |
| `endpoint_async` | `boolean` | true | on | aggregate — splits latency panels by processing mode |
| `endpoint_request_headers` | `keyword` | **false** | off | display only — selection and masking happen in code |
| `endpoint_response_headers` | `keyword` | **false** | off | display only |
| `endpoint_request_body` | `keyword` | **false** | off | display only — bounded tee capture |
| `endpoint_response_body` | `keyword` | **false** | off | display only |

The per-field rationale sits next to each constant as an `ELK:` line in
[`EndpointLogFields.kt`](../../src/main/kotlin/eu/inqudium/limesium/servlet/logging/EndpointLogFields.kt); the two
decisions most easily undone by accident — `index: false` on payload fields (sensitivity precedes
analytics, guide §8) and `doc_values: false` on the high-cardinality path pair half (repetition factor,
guide §5) — each have their own explicit assertion in the lockstep test.

## Deliberately not in this template

The **MDC-carried fields** — `endpoint_request_id`, `endpoint_method`, `endpoint_route`, and the bridge's
`traceId`/`spanId` — are absent on purpose: how MDC entries land in the document (flat, nested under
`mdc.`, renamed) is the **encoder's** decision, and mapping a guess here would break the moment a host
picks a different encoder layout. Map them where the encoder configuration lives.

**Related:** the reference configuration in
[`../endpoint-logging-reference.yml`](../endpoint-logging-reference.yml) · the module
[README](../../README.md).

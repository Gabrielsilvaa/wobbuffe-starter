# Architecture decisions — Library stage

1. **Single artifact:** one Maven artifact, `com.wobbuffe:wobbuffe-starter`; a second module is not justified in this stage.
2. **Programmatic API first:** `ResilienceExecutor` is the behavior source. `@ResilientIntegration` is an optional synchronous AOP adapter. Self-invocation bypasses Spring proxies; use the programmatic API for those call paths and for queueable operations.
3. **Retry classification:** retry network I/O, timeout, HTTP 408/429 and 500/502/503/504 only. HTTP 4xx other than 408/429 and 501/505 do not retry.
4. **Timeout boundary:** each attempt has a bounded wait and cancellation request. Blocking client I/O still requires a client-level timeout; Java interruption is not guaranteed to stop it.
5. **Degradation:** circuit open OR the p95 latency threshold OR the transient failure rate threshold, provided the minimum latency sample count is reached. Samples are process-local and expire by the configured latency window.
6. **Admission:** eligible operations use probabilistic admission, starting at 5%, increasing by 5 percentage points each degraded evaluation interval to 25%, then stepping down after two healthy intervals. A store pending limit caps queued work. No SQS publisher/store means no route; an open circuit then fails the operation.
7. **Queueing consistency:** direct SQS publish after idempotency reservation; publication failure is surfaced. At-least-once delivery, process crashes, and ambiguous sends remain possible. Outbox is future work.
8. **Persistence boundary:** the library defines `OperationStore` only. Stage 2 may supply H2 in memory; it is volatile and must not be presented as durable recovery.

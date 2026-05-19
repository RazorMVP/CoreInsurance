package com.nubeero.cia.finance.gl;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Performance scaffolding for the {@link PeriodLockInterceptor} hot path.
 *
 * <h2>Status — Slice 1.7</h2>
 * <p>This class documents the perf gate ({@code &lt; 2 % p99 overhead vs.
 * baseline}) the expert critique tightened from the foundations plan's 5 %
 * target. The full JMH wiring lives in a follow-up commit gated by adding
 * the {@code jmh-core} + {@code jmh-generator-annprocess} dependencies and
 * a {@code module-12-benchmark.yml} GitHub Actions workflow.
 *
 * <h2>What the eventual JMH harness must do</h2>
 * <ol>
 *   <li><strong>Baseline run.</strong> Build a Spring context with
 *       {@code @ActiveProfiles("benchmark-baseline")} so {@link
 *       PeriodLockInterceptorConfig} is excluded — no interceptor on the
 *       SessionFactory. Persist 10,000 balanced two-line JEs and record
 *       p50 / p99 wall-clock per save.</li>
 *   <li><strong>Locked run.</strong> Same workload with the interceptor
 *       registered. Mix lock states so the per-period cache shows hits and
 *       misses realistically: 50 % OPEN, 30 % SOFT in-grace, 15 % SOFT
 *       past-grace with override, 5 % HARD (which throws and rolls back —
 *       still part of the perf envelope).</li>
 *   <li><strong>Gate.</strong> Fail the workflow if
 *       {@code (p99_locked - p99_baseline) / p99_baseline &gt; 0.02}. Anything
 *       between 1 % and 2 % requires a flame-graph in the PR description.
 *       Above 2 % is a blocker — likely a missing {@link
 *       FiscalPeriodLookupCache} hit or a re-read query.</li>
 * </ol>
 *
 * <h2>Why this lives as a Disabled JUnit test (not yet a JMH harness)</h2>
 * <p>JMH adds a benchmark plugin + a separate compile path. Landing that
 * machinery in the same slice as the lock mechanism risks two failures:
 * the perf gate becoming a flaky-test source (because the dev laptop and
 * CI runner have different CPU characteristics), and the benchmark plugin
 * config blocking review of the actual lock logic. Splitting the
 * mechanism (this slice) from the perf gate (follow-up) keeps each
 * review focused. The slice ships with the {@code FiscalPeriodLookupCache}
 * already in place so the gate has a chance of passing on day one.
 *
 * @since Module 12, Slice 1.7 (scaffolding)
 */
@Disabled("scaffolding — replace with JMH @Benchmark methods once jmh-core is on the test classpath")
class PeriodLockInterceptorBenchmark {

    @Test
    void documentsPerformanceGate() {
        // Intentional no-op. The presence of this class signals to reviewers
        // that the perf gate is a tracked deliverable, not an oversight.
    }
}

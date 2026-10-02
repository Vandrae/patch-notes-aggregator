package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.feed.IngestResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Numbers for watching the poller:
 * <ul>
 *   <li>{@code patchnotes.fetch.polls} by outcome (success, failure, rate_limited) and {@code patchnotes.fetch.articles}
 *       by kind (created, updated);</li>
 *   <li>{@code patchnotes.fetch.tracked}: watched games being polled;</li>
 *   <li>{@code patchnotes.fetch.due}: games whose time has come and are waiting for a turn;</li>
 *   <li>{@code patchnotes.fetch.lag.seconds}: how long the most overdue game has waited. Near zero means the poller keeps
 *       up; a number that keeps growing means it cannot (Steam throttling, or too many games for the configured rate).</li>
 * </ul>
 * The gauges ask the database when read, so they cost nothing between scrapes.
 */
@Component
class PollMetrics {

    enum Outcome {
        SUCCESS, FAILURE, RATE_LIMITED
    }

    private final MeterRegistry registry;

    PollMetrics(MeterRegistry registry, FetchStateStore store, Clock clock) {
        this.registry = registry;
        Gauge.builder("patchnotes.fetch.tracked", store, FetchStateStore::countTracked)
                .description("Watched games being polled").register(registry);
        Gauge.builder("patchnotes.fetch.due", store, s -> s.countDue(clock.instant()))
                .description("Games whose poll time has come").register(registry);
        Gauge.builder("patchnotes.fetch.lag.seconds", store, s -> s.oldestOverdueSeconds(clock.instant()))
                .description("How long the most overdue game has been waiting").baseUnit("seconds").register(registry);
    }

    void polled(Outcome outcome) {
        registry.counter("patchnotes.fetch.polls", "outcome", outcome.name().toLowerCase()).increment();
    }

    void ingested(IngestResult result) {
        registry.counter("patchnotes.fetch.articles", "kind", "created").increment(result.created());
        registry.counter("patchnotes.fetch.articles", "kind", "updated").increment(result.updated());
    }
}

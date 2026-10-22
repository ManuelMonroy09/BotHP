package mx.jun.trading.runner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperPollSchedulerTest {
    private final PaperPollScheduler scheduler = new PaperPollScheduler();
    private final Duration retry = Duration.ofSeconds(15);

    @Test
    void waitsUntilNextFourHourCloseWhenFlat() {
        Duration delay = scheduler.delayAfterCycle(true, false,
                Instant.parse("2026-10-22T08:00:00Z"), Duration.ofHours(4).toMillis(),
                Instant.parse("2026-10-22T11:00:00Z"), retry);

        assertEquals(Duration.ofHours(1).plusSeconds(2), delay);
    }

    @Test
    void continuesFastPollingWhilePositionIsOpen() {
        Duration delay = scheduler.delayAfterCycle(true, true,
                Instant.parse("2026-10-22T08:00:00Z"), Duration.ofHours(4).toMillis(),
                Instant.parse("2026-10-22T11:00:00Z"), retry);

        assertEquals(retry, delay);
    }

    @Test
    void retriesAfterShortDelayWhenNoNewCandleOrMarketDataWasLate() {
        Duration delay = scheduler.delayAfterCycle(false, false,
                Instant.parse("2026-10-22T08:00:00Z"), Duration.ofHours(4).toMillis(),
                Instant.parse("2026-10-22T12:00:05Z"), retry);

        assertEquals(retry, delay);
    }

    @Test
    void rejectsInvalidInterval() {
        assertThrows(IllegalArgumentException.class, () -> scheduler.delayAfterCycle(true, false,
                Instant.parse("2026-10-22T08:00:00Z"), 0,
                Instant.parse("2026-10-22T11:00:00Z"), retry));
    }
}

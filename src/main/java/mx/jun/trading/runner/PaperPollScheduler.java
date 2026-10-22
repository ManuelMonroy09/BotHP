package mx.jun.trading.runner;

import java.time.Duration;
import java.time.Instant;

/** Avoids repeated public candle requests while flat, without slowing stop checks while invested. */
final class PaperPollScheduler {
    private static final Duration PUBLISH_GRACE = Duration.ofSeconds(2);

    Duration delayAfterCycle(boolean newClosedCandle, boolean hasPosition,
                             Instant latestCandleOpen, long intervalMillis,
                             Instant now, Duration retryDelay) {
        if (now == null || retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("Reloj y pausa de reintento deben ser válidos");
        }
        if (!newClosedCandle || hasPosition) return retryDelay;
        if (latestCandleOpen == null || intervalMillis <= 0) {
            throw new IllegalArgumentException("Falta el intervalo válido de la última vela cerrada");
        }
        Instant expectedClose = latestCandleOpen.plusMillis(intervalMillis).plus(PUBLISH_GRACE);
        Duration untilNextClose = Duration.between(now, expectedClose);
        return untilNextClose.isNegative() ? Duration.ZERO : untilNextClose;
    }
}

package mx.jun.trading.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceAnalyzerTest {
    @Test
    void reportsEquityMetricsCostsAndSmallSampleWarning() {
        Trade win = new Trade(Instant.parse("2026-01-01T00:15:00Z"),
                Instant.parse("2026-01-01T00:30:00Z"), BigDecimal.TEN,
                new BigDecimal("11"), BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("0.01"), new BigDecimal("0.99"), "TEST");
        Trade loss = new Trade(Instant.parse("2026-01-01T00:45:00Z"),
                Instant.parse("2026-01-01T01:00:00Z"), BigDecimal.TEN,
                new BigDecimal("9"), BigDecimal.ONE, BigDecimal.ONE.negate(),
                new BigDecimal("0.01"), new BigDecimal("-1.01"), "TEST");
        BacktestResult result = new BacktestResult(new BigDecimal("20"),
                new BigDecimal("19.98"), new BigDecimal("-0.02"), new BigDecimal("-0.10"),
                new BigDecimal("5"), 2, 1, 1, new BigDecimal("0.98"), List.of(win, loss),
                List.of(new BigDecimal("20"), new BigDecimal("20.5"), new BigDecimal("19.98")),
                900_000L);

        String summary = new PerformanceAnalyzer().summary(result);

        assertTrue(summary.contains("Costes modelados:     0.02"));
        assertTrue(summary.contains("Exposición estimada:"));
        assertTrue(summary.contains("Sharpe anualizado:"));
        assertTrue(summary.contains("Sortino anualizado:"));
        assertTrue(summary.contains("muestra pequeña (<30 operaciones)"));
    }
}

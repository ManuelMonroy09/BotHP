package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutOfSampleEvaluatorTest {

    @Test
    void separaCronologicamenteYUsaVelasPreviasSoloComoCalentamiento() {
        List<Candle> candles = sampleCandles(200);

        OutOfSampleEvaluator.Evaluation evaluation =
                new OutOfSampleEvaluator().evaluate(candles, new BigDecimal("20"));

        assertEquals(140, evaluation.splitIndex());
        assertEquals(candles.get(0).timestamp(), evaluation.developmentStart().timestamp());
        assertEquals(candles.get(139).timestamp(), evaluation.developmentEnd().timestamp());
        assertEquals(candles.get(140).timestamp(), evaluation.evaluationStart().timestamp());
        assertEquals(candles.get(199).timestamp(), evaluation.evaluationEnd().timestamp());
        assertEquals(110, evaluation.candlesWithWarmup().size());
        assertEquals(evaluation.evaluationStart().timestamp(),
                evaluation.candlesWithWarmup().get(OutOfSampleEvaluator.WARMUP_CANDLES).timestamp());
        assertNotNull(evaluation.result());
        assertEquals(new BigDecimal("20"), evaluation.result().initialCapital());
    }

    @Test
    void rechazaDatasetDemasiadoPequeno() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutOfSampleEvaluator().evaluate(sampleCandles(100), new BigDecimal("20")));
    }

    @Test
    void rechazaFraccionDeDesarrolloInvalida() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutOfSampleEvaluator().evaluate(sampleCandles(200), new BigDecimal("20"), 1.0));
    }

    private List<Candle> sampleCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        BigDecimal previous = new BigDecimal("3500");

        for (int i = 0; i < count; i++) {
            BigDecimal open = previous;
            BigDecimal change = i < 100 ? BigDecimal.ONE : new BigDecimal("-0.25");
            BigDecimal close = open.add(change);
            BigDecimal high = open.max(close).add(new BigDecimal("3"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("3"));
            candles.add(new Candle(start.plusSeconds(i * 900L), open, high, low, close,
                    BigDecimal.TEN));
            previous = close;
        }
        return candles;
    }
}

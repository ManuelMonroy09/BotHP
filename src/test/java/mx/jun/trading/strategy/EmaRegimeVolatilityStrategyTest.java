package mx.jun.trading.strategy;

import mx.jun.trading.market.Candle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EmaRegimeVolatilityStrategyTest {
    @Test
    void noOperaSinSuficientesVelas() {
        var candles = new ArrayList<Candle>();
        var strategy = new EmaRegimeVolatilityStrategy();
        assertEquals(EmaRegimeVolatilityStrategy.Signal.HOLD, strategy.evaluate(candles));
    }

    @Test
    void devuelveUnaSenalValidaConDatosSuficientes() {
        var candles = new ArrayList<Candle>();
        Instant t = Instant.parse("2026-09-25T20:00:00Z");
        BigDecimal price = new BigDecimal("3500");
        for (int i = 0; i < 100; i++) {
            BigDecimal close = price.add(new BigDecimal(i < 70 ? "2" : "-1"));
            candles.add(new Candle(t.plusSeconds(i * 900L), price,
                    price.max(close).add(new BigDecimal("3")),
                    price.min(close).subtract(new BigDecimal("3")),
                    close, BigDecimal.ONE));
            price = close;
        }
        var signal = new EmaRegimeVolatilityStrategy().evaluate(candles);
        assertNotNull(signal);
    }

    @Test
    void calculaLasSenalesEnLoteIgualQueLaEvaluacionIndividual() {
        var candles = new ArrayList<Candle>();
        Instant t = Instant.parse("2026-09-25T20:00:00Z");
        BigDecimal price = new BigDecimal("3500");
        for (int i = 0; i < 180; i++) {
            BigDecimal change = i < 100 ? BigDecimal.ONE : new BigDecimal("-2");
            BigDecimal close = price.add(change);
            candles.add(new Candle(t.plusSeconds(i * 900L), price,
                    price.max(close).add(new BigDecimal("3")),
                    price.min(close).subtract(new BigDecimal("3")),
                    close, BigDecimal.TEN));
            price = close;
        }

        var strategy = new EmaRegimeVolatilityStrategy();
        var batchSignals = strategy.evaluateAll(candles);
        assertEquals(candles.size(), batchSignals.size());
        for (int i = 50; i < candles.size(); i++) {
            assertEquals(strategy.evaluate(candles.subList(0, i + 1)), batchSignals.get(i),
                    "La señal por lote debe coincidir en la vela " + i);
        }
    }
}

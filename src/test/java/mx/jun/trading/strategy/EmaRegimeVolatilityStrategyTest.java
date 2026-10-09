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
    @Test
    void soloCompraCuandoHayUnCruceAlcistaNuevo() {
        var candles = new ArrayList<Candle>();
        Instant t = Instant.parse("2026-09-25T20:00:00Z");
        BigDecimal price = new BigDecimal("3500");
        for (int i = 0; i < 220; i++) {
            BigDecimal change = i < 80 ? new BigDecimal("-1") : new BigDecimal("3");
            BigDecimal close = price.add(change);
            candles.add(new Candle(t.plusSeconds(i * 900L), price,
                    price.max(close).add(new BigDecimal("3")),
                    price.min(close).subtract(new BigDecimal("3")),
                    close, BigDecimal.TEN));
            price = close;
        }

        var strategy = new EmaRegimeVolatilityStrategy();
        var signals = strategy.evaluateAll(candles);
        var closes = candles.stream().map(Candle::close).toList();
        var ema = new mx.jun.trading.indicator.EmaCalculator();
        var ema20 = ema.calculate(closes, 20);
        var ema50 = ema.calculate(closes, 50);
        int buyCount = 0;

        for (int i = 50; i < signals.size(); i++) {
            if (signals.get(i) != EmaRegimeVolatilityStrategy.Signal.BUY) continue;
            buyCount++;
            var previousEma20 = ema20.get(i - 20);
            var previousEma50 = ema50.get(i - 50);
            var currentEma20 = ema20.get(i - 19);
            var currentEma50 = ema50.get(i - 49);
            assertTrue(previousEma20.compareTo(previousEma50) <= 0,
                    "Una compra requiere que EMA20 no estuviera por encima de EMA50 en la vela anterior");
            assertTrue(currentEma20.compareTo(currentEma50) > 0,
                    "Una compra requiere un cruce alcista nuevo");
        }

        assertTrue(buyCount > 0, "Los datos de prueba deben producir al menos una entrada alcista");
    }

}

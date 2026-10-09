package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    @Test
    void ejecutaLaSenalEnLaSiguienteVela() {
        List<Candle> candles = sampleCandles(180);
        var strategy = new EmaRegimeVolatilityStrategy();
        List<EmaRegimeVolatilityStrategy.Signal> signals = strategy.evaluateAll(candles);
        int firstBuySignal = -1;
        for (int i = 50; i < candles.size() - 1; i++) {
            if (signals.get(i) == EmaRegimeVolatilityStrategy.Signal.BUY) {
                firstBuySignal = i;
                break;
            }
        }
        assertTrue(firstBuySignal >= 50, "Los datos de prueba deben producir una señal BUY");

        BacktestResult result = new BacktestEngine().run(candles, new BigDecimal("20"));
        assertFalse(result.trades().isEmpty(), "Debe existir al menos una operación");
        assertEquals(candles.get(firstBuySignal + 1).timestamp(),
                result.trades().get(0).entryTime(),
                "La entrada debe ocurrir en la vela posterior a la señal");
    }

    @Test
    void rechazaCapitalInicialInvalido() {
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestEngine().run(sampleCandles(60), BigDecimal.ZERO));
    }

    @Test
    void rechazaVelasConRangoOhlcInconsistente() {
        List<Candle> candles = new ArrayList<>(sampleCandles(60));
        Candle original = candles.get(55);
        candles.set(55, new Candle(original.timestamp(), original.open(),
                original.open().subtract(BigDecimal.ONE), original.low(),
                original.close(), original.volume()));

        assertThrows(IllegalArgumentException.class,
                () -> new BacktestEngine().run(candles, new BigDecimal("20")));
    }

    @Test
    void limitaElTamanoDePosicionYRespetaElStopLoss() {
        List<Candle> candles = new ArrayList<>(sampleCandles(180));
        List<EmaRegimeVolatilityStrategy.Signal> signals =
                new EmaRegimeVolatilityStrategy().evaluateAll(candles);
        int buySignal = -1;
        for (int i = 50; i < candles.size() - 1; i++) {
            if (signals.get(i) == EmaRegimeVolatilityStrategy.Signal.BUY) {
                buySignal = i;
                break;
            }
        }
        assertTrue(buySignal >= 50, "La serie de prueba debe generar una entrada");
        int entryIndex = buySignal + 1;
        Candle entryCandle = candles.get(entryIndex);
        BigDecimal stressedLow = entryCandle.open().multiply(new BigDecimal("0.97"));
        candles.set(entryIndex, new Candle(entryCandle.timestamp(), entryCandle.open(),
                entryCandle.high(), stressedLow, entryCandle.close(), entryCandle.volume()));

        BacktestResult result = new BacktestEngine().run(candles, new BigDecimal("20"));
        assertFalse(result.trades().isEmpty());
        Trade firstTrade = result.trades().get(0);
        assertEquals(firstTrade.entryTime(), firstTrade.exitTime(),
                "Si el minimo de la vela de entrada toca el stop, la salida debe registrarse en esa vela");
        assertTrue(firstTrade.quantity().multiply(firstTrade.entryPrice())
                        .compareTo(new BigDecimal("10")) <= 0,
                "La exposicion inicial no debe superar el 50% del capital inicial");
        assertTrue(firstTrade.netPnl().compareTo(new BigDecimal("-0.101")) >= 0,
                "La pérdida del stop debe respetar aproximadamente el presupuesto de riesgo de 0.10, incluidas comisiones y deslizamiento");
        assertTrue(firstTrade.exitPrice().compareTo(firstTrade.entryPrice()) < 0,
                "El stop-loss debe cerrar por debajo del precio de entrada");
    }

    @Test
    void rechazaConfiguracionDeRiesgoInvalida() {
        assertThrows(IllegalArgumentException.class, () -> new BacktestRiskConfig(
                new BigDecimal("1.5"), new BigDecimal("0.015"),
                new BigDecimal("0.50"), new BigDecimal("0.02"),
                new BigDecimal("0.10")));
    }

    @Test
    void procesaUnHistorialDeCincoMilVelas() {
        BacktestResult result = new BacktestEngine().run(
                sampleCandles(5_000), new BigDecimal("20"));
        assertNotNull(result);
        assertNotNull(result.finalCapital());
        assertNotNull(result.maxDrawdownPercentage());
    }

    /**
     * Genera primero una tendencia bajista y luego una alcista para que la
     * estrategia pueda observar un cruce EMA20/EMA50 nuevo y válido.
     */
    private List<Candle> sampleCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        BigDecimal previous = new BigDecimal("3500");

        for (int i = 0; i < count; i++) {
            BigDecimal open = previous;
            BigDecimal change = i < 80 ? new BigDecimal("-1") : new BigDecimal("3");
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

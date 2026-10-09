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
    void elDrawdownIncluyeCaidasIntravelares() {
        List<Candle> baselineCandles = sampleCandles(180);
        BacktestResult baseline = new BacktestEngine().run(baselineCandles, new BigDecimal("20"));
        assertFalse(baseline.trades().isEmpty(), "La prueba necesita una entrada");

        Instant firstEntry = baseline.trades().get(0).entryTime();
        List<Candle> wickCandles = new ArrayList<>(baselineCandles);
        int entryIndex = -1;
        for (int i = 0; i < wickCandles.size(); i++) {
            if (wickCandles.get(i).timestamp().equals(firstEntry)) {
                entryIndex = i;
                break;
            }
        }
        assertTrue(entryIndex >= 0);
        Candle entryCandle = wickCandles.get(entryIndex);
        BigDecimal deepLow = entryCandle.low().subtract(new BigDecimal("1000"));
        assertTrue(deepLow.signum() > 0, "La mecha debe mantener un precio positivo");
        wickCandles.set(entryIndex, new Candle(entryCandle.timestamp(), entryCandle.open(),
                entryCandle.high(), deepLow, entryCandle.close(), entryCandle.volume()));

        BacktestResult withWick = new BacktestEngine().run(wickCandles, new BigDecimal("20"));
        assertTrue(withWick.maxDrawdownPercentage().compareTo(baseline.maxDrawdownPercentage()) > 0,
                "El drawdown debe reflejar una caída intravela aunque el cierre se recupere");
    }

    @Test
    void procesaUnHistorialDeCincoMilVelas() {
        BacktestResult result = new BacktestEngine().run(
                sampleCandles(5_000), new BigDecimal("20"));
        assertNotNull(result);
        assertNotNull(result.finalCapital());
        assertNotNull(result.maxDrawdownPercentage());
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

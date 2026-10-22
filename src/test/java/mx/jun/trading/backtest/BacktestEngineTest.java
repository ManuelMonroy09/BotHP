package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import mx.jun.trading.market.FundingRate;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    @Test
    void fundingPositivoDebitaLongYSeReflejaEnPnL() {
        List<Candle> candles = sampleCandles(180);
        BacktestEngine engine = new BacktestEngine();
        BacktestResult withoutFunding = engine.run(candles, new BigDecimal("20"));
        Trade first = withoutFunding.trades().getFirst();
        assertEquals(1, first.side());

        FundingRate event = new FundingRate(first.entryTime().plus(Duration.ofMinutes(1)),
                new BigDecimal("0.001"));
        BacktestResult withFunding = engine.run(candles, new BigDecimal("20"),
                BacktestEngine.DEFAULT_FEE_RATE, BacktestEngine.DEFAULT_SLIPPAGE_RATE,
                BacktestRiskConfig.conservativeDefaults(), true, false,
                EmaRegimeVolatilityStrategy.Mode.EMA_CROSS, List.of(event));

        assertTrue(withFunding.trades().getFirst().fundingPnl().signum() < 0);
        assertTrue(withFunding.finalCapital().compareTo(withoutFunding.finalCapital()) < 0);
        assertEquals(withFunding.trades().getFirst().grossPnl()
                        .subtract(withFunding.trades().getFirst().fees())
                        .add(withFunding.trades().getFirst().fundingPnl()),
                withFunding.trades().getFirst().netPnl());
    }

    @Test
    void fundingPositivoAcreditaShort() {
        List<Candle> candles = upThenDownCandles(180);
        BacktestResult baseline = new BacktestEngine().run(candles, new BigDecimal("20"), true);
        Trade firstShort = baseline.trades().stream().filter(trade -> trade.side() < 0).findFirst().orElseThrow();
        FundingRate event = new FundingRate(firstShort.entryTime().plus(Duration.ofMinutes(1)),
                new BigDecimal("0.001"));

        BacktestResult funded = new BacktestEngine().run(candles, new BigDecimal("20"),
                BacktestEngine.DEFAULT_FEE_RATE, BacktestEngine.DEFAULT_SLIPPAGE_RATE,
                BacktestRiskConfig.conservativeDefaults(), true, true,
                EmaRegimeVolatilityStrategy.Mode.EMA_CROSS, List.of(event));

        Trade fundedShort = funded.trades().stream().filter(trade -> trade.side() < 0).findFirst().orElseThrow();
        assertTrue(fundedShort.fundingPnl().signum() > 0);
    }

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
        assertTrue(firstTrade.exitPrice().compareTo(firstTrade.entryPrice()) < 0,
                "El stop-loss debe cerrar por debajo del precio de entrada");
        assertTrue(firstTrade.netPnl().abs().compareTo(new BigDecimal("0.1")) <= 0,
                "El riesgo al stop debe incluir las comisiones de entrada/salida y el deslizamiento");
    }

    @Test
    void cierraTakeProfitCuandoLaVelaAlcanzaElObjetivo() {
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
        assertTrue(buySignal >= 50);
        int entryIndex = buySignal + 1;
        Candle entryCandle = candles.get(entryIndex);
        candles.set(entryIndex, new Candle(entryCandle.timestamp(), entryCandle.open(),
                entryCandle.open().multiply(new BigDecimal("1.02")),
                entryCandle.open().multiply(new BigDecimal("0.99")), entryCandle.close(), entryCandle.volume()));
        BacktestRiskConfig defaults = BacktestRiskConfig.conservativeDefaults();
        BacktestRiskConfig withTarget = new BacktestRiskConfig(defaults.riskPerTradePercentage(),
                defaults.stopLossPercentage(), defaults.maxPositionNotionalPercentage(),
                defaults.dailyLossLimitPercentage(), defaults.cumulativeDrawdownLimitPercentage(),
                new BigDecimal("0.01"));

        BacktestResult result = new BacktestEngine().run(candles, new BigDecimal("20"),
                BacktestEngine.DEFAULT_FEE_RATE, BacktestEngine.DEFAULT_SLIPPAGE_RATE, withTarget);

        assertEquals("TAKE_PROFIT", result.trades().getFirst().exitReason());
        assertTrue(result.trades().getFirst().netPnl().signum() > 0);
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

    @Test
    void laVarianteBidireccionalRegistraOperacionesShort() {
        BacktestResult result = new BacktestEngine().run(
                upThenDownCandles(180), new BigDecimal("20"), true);

        assertTrue(result.trades().stream().anyMatch(trade -> trade.side() == -1));
        assertTrue(result.trades().stream().allMatch(trade -> trade.side() == 1 || trade.side() == -1));
    }

    @Test
    void rechazaHuecosEnElHistorial() {
        List<Candle> candles = new ArrayList<>(sampleCandles(100));
        for (int i = 60; i < candles.size(); i++) {
            Candle original = candles.get(i);
            candles.set(i, new Candle(original.timestamp().plusSeconds(900), original.open(),
                    original.high(), original.low(), original.close(), original.volume()));
        }

        assertThrows(IllegalArgumentException.class,
                () -> new BacktestEngine().run(candles, new BigDecimal("20")));
    }

    @Test
    void registraEquityPorVelaYElCapitalFinal() {
        List<Candle> candles = sampleCandles(180);
        BacktestResult result = new BacktestEngine().run(candles, new BigDecimal("20"));

        assertEquals(candles.size() - 49, result.equityCurve().size());
        assertEquals(new BigDecimal("20"), result.equityCurve().getFirst());
        assertEquals(result.finalCapital(), result.equityCurve().getLast());
        assertEquals(900_000L, result.candleIntervalMillis());
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

    private List<Candle> upThenDownCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        BigDecimal previous = new BigDecimal("3500");
        for (int i = 0; i < count; i++) {
            BigDecimal open = previous;
            BigDecimal change = i < 100 ? new BigDecimal("3") : new BigDecimal("-3");
            BigDecimal close = open.add(change);
            candles.add(new Candle(start.plusSeconds(i * 900L), open,
                    open.max(close).add(BigDecimal.ONE), open.min(close).subtract(BigDecimal.ONE),
                    close, BigDecimal.TEN));
            previous = close;
        }
        return candles;
    }
}

package mx.jun.trading.strategy;

import mx.jun.trading.indicator.AtrCalculator;
import mx.jun.trading.indicator.EmaCalculator;
import mx.jun.trading.market.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class EmaRegimeVolatilityStrategy {
    private final Mode mode;
    private final EmaCalculator ema = new EmaCalculator();
    private final AtrCalculator atr = new AtrCalculator();

    public EmaRegimeVolatilityStrategy() {
        this(Mode.EMA_CROSS);
    }

    public EmaRegimeVolatilityStrategy(Mode mode) {
        this.mode = java.util.Objects.requireNonNull(mode);
    }

    public Signal evaluate(List<Candle> candles) {
        if (candles == null || candles.size() < 51) return Signal.HOLD;
        return evaluateAll(candles).get(candles.size() - 1);
    }

    /** Calcula las señales históricas disponibles para la variante seleccionada. */
    public List<Signal> evaluateAll(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return List.of();

        int size = candles.size();
        var signals = new java.util.ArrayList<Signal>(
                java.util.Collections.nCopies(size, Signal.HOLD));
        if (size < 51) return List.copyOf(signals);

        List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
        List<BigDecimal> ema20Values = ema.calculate(closes, 20);
        List<BigDecimal> ema50Values = ema.calculate(closes, 50);
        List<BigDecimal> atrValues = atr.calculate(candles, 14);

        for (int i = 50; i < size; i++) {
            if (mode == Mode.RSI_TREND_PULLBACK) {
                BigDecimal rsi = rsi(closes, i, 14);
                BigDecimal e50 = ema50Values.get(i - 49);
                BigDecimal e50p = ema50Values.get(i - 50);
                boolean trendUp = e50.compareTo(e50p) > 0;
                boolean priceAboveTrend = closes.get(i).compareTo(e50) > 0;

                // Buy a pullback only while the medium-term trend is rising. Exit
                // when momentum recovers or price loses that trend filter.
                if (rsi.compareTo(BigDecimal.valueOf(60)) >= 0 || !priceAboveTrend) {
                    signals.set(i, Signal.SELL);
                } else if (trendUp && rsi.compareTo(BigDecimal.valueOf(30)) <= 0) {
                    signals.set(i, Signal.BUY);
                }
                continue;
            }
            BigDecimal e20 = ema20Values.get(i - 19);
            BigDecimal e20p = ema20Values.get(i - 20);
            BigDecimal e50 = ema50Values.get(i - 49);
            BigDecimal e50p = ema50Values.get(i - 50);
            BigDecimal close = closes.get(i);
            BigDecimal atr14 = atrValues.get(i - 14);
            BigDecimal atrPct = atr14.divide(close, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            boolean validVolatility = atrPct.compareTo(BigDecimal.valueOf(0.05)) > 0
                    && atrPct.compareTo(BigDecimal.valueOf(3)) < 0;
            boolean bullishCross = e20p.compareTo(e50p) <= 0 && e20.compareTo(e50) > 0;
            boolean bearishCross = e20p.compareTo(e50p) >= 0 && e20.compareTo(e50) < 0;

            if (bearishCross) {
                signals.set(i, Signal.SELL);
                continue;
            }

            boolean trendingUp = atrPct.compareTo(BigDecimal.valueOf(3)) < 0
                    && atrPct.compareTo(BigDecimal.valueOf(0.05)) > 0
                    && e50.compareTo(e50p) > 0
                    && close.compareTo(e50) > 0;

            if (trendingUp && e20.compareTo(e50) > 0 && e20.compareTo(e20p) > 0
                    && e50.compareTo(e50p) > 0 && close.compareTo(e50) > 0
                    && validVolatility && bullishCross) {
                signals.set(i, Signal.BUY);
            }
        }
        return List.copyOf(signals);
    }

    private static BigDecimal rsi(List<BigDecimal> closes, int index, int period) {
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (int i = index - period + 1; i <= index; i++) {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            if (change.signum() > 0) gains = gains.add(change);
            else losses = losses.add(change.abs());
        }
        if (losses.signum() == 0) return gains.signum() == 0 ? BigDecimal.valueOf(50) : BigDecimal.valueOf(100);
        BigDecimal rs = gains.divide(losses, 10, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100)
                .divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP));
    }

    public enum Mode {
        EMA_CROSS, RSI_TREND_PULLBACK;

        public static Mode parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Estrategia no válida: " + value
                        + ". Opciones: EMA_CROSS, RSI_TREND_PULLBACK");
            }
        }
    }

    public enum Signal { BUY, SELL, HOLD }
}

package mx.jun.trading.strategy;

import mx.jun.trading.indicator.AtrCalculator;
import mx.jun.trading.indicator.EmaCalculator;
import mx.jun.trading.market.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Long-only EMA strategy with a 12-candle momentum confirmation. */
public class EmaRegimeVolatilityStrategy {
    private static final int FIRST_INDICATOR_INDEX = 50;
    private static final int MOMENTUM_LOOKBACK = 12;
    private static final BigDecimal MIN_ATR_PERCENT = new BigDecimal("0.05");
    private static final BigDecimal MAX_ATR_PERCENT = new BigDecimal("3");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EmaCalculator ema = new EmaCalculator();
    private final AtrCalculator atr = new AtrCalculator();

    public Signal evaluate(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return Signal.HOLD;
        List<Signal> signals = evaluateAll(candles);
        return signals.get(signals.size() - 1);
    }

    /**
     * Calculates signals in O(n). BUY requires rising EMA20 above rising EMA50,
     * price above EMA50, valid ATR and positive 12-candle momentum. SELL remains
     * a bearish EMA cross. Signals use only data available at candle close.
     */
    public List<Signal> evaluateAll(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return List.of();

        int size = candles.size();
        var signals = new ArrayList<Signal>(Collections.nCopies(size, Signal.HOLD));
        if (size <= FIRST_INDICATOR_INDEX) return List.copyOf(signals);

        List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
        List<BigDecimal> ema20Values = ema.calculate(closes, 20);
        List<BigDecimal> ema50Values = ema.calculate(closes, 50);
        List<BigDecimal> atrValues = atr.calculate(candles, 14);

        for (int i = FIRST_INDICATOR_INDEX; i < size; i++) {
            BigDecimal e20 = ema20Values.get(i - 19);
            BigDecimal e20Previous = ema20Values.get(i - 20);
            BigDecimal e50 = ema50Values.get(i - 49);
            BigDecimal e50Previous = ema50Values.get(i - 50);
            BigDecimal close = closes.get(i);
            BigDecimal atr14 = atrValues.get(i - 14);
            BigDecimal atrPct = atr14.divide(close, 10, RoundingMode.HALF_UP).multiply(HUNDRED);

            boolean validVolatility = atrPct.compareTo(MIN_ATR_PERCENT) > 0
                    && atrPct.compareTo(MAX_ATR_PERCENT) < 0;
            boolean bearishCross = e20Previous.compareTo(e50Previous) >= 0
                    && e20.compareTo(e50) < 0;
            if (bearishCross) {
                signals.set(i, Signal.SELL);
                continue;
            }

            // Avoid buying a short bounce while the 12-candle price change is negative.
            if (i < FIRST_INDICATOR_INDEX + MOMENTUM_LOOKBACK) continue;
            boolean positiveMomentum = close.compareTo(closes.get(i - MOMENTUM_LOOKBACK)) > 0;
            boolean trendingUp = e50.compareTo(e50Previous) > 0 && close.compareTo(e50) > 0;

            if (trendingUp
                    && e20.compareTo(e50) > 0
                    && e20.compareTo(e20Previous) > 0
                    && e50.compareTo(e50Previous) > 0
                    && validVolatility
                    && positiveMomentum) {
                signals.set(i, Signal.BUY);
            }
        }
        return List.copyOf(signals);
    }

    public enum Signal { BUY, SELL, HOLD }
}

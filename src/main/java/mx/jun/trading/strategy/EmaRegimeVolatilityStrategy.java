package mx.jun.trading.strategy;

import mx.jun.trading.indicator.AtrCalculator;
import mx.jun.trading.indicator.EmaCalculator;
import mx.jun.trading.market.Candle;
import mx.jun.trading.market.MarketRegime;
import mx.jun.trading.market.MarketRegimeDetector;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class EmaRegimeVolatilityStrategy {
    private final EmaCalculator ema = new EmaCalculator();
    private final AtrCalculator atr = new AtrCalculator();
    private final MarketRegimeDetector detector = new MarketRegimeDetector();

    public Signal evaluate(List<Candle> candles) {
        if (candles == null || candles.size() < 51) return Signal.HOLD;
        List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
        List<BigDecimal> ema20 = ema.calculate(closes, 20);
        List<BigDecimal> ema50 = ema.calculate(closes, 50);
        List<BigDecimal> atr14 = atr.calculate(candles, 14);

        BigDecimal e20 = ema20.get(ema20.size() - 1);
        BigDecimal e20p = ema20.get(ema20.size() - 2);
        BigDecimal e50 = ema50.get(ema50.size() - 1);
        BigDecimal e50p = ema50.get(ema50.size() - 2);
        BigDecimal close = closes.get(closes.size() - 1);
        BigDecimal atrPct = atr14.get(atr14.size() - 1)
                .divide(close, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        boolean validVolatility = atrPct.compareTo(BigDecimal.valueOf(0.05)) > 0
                && atrPct.compareTo(BigDecimal.valueOf(3)) < 0;
        boolean bullishCross = e20p.compareTo(e50p) <= 0 && e20.compareTo(e50) > 0;
        boolean bearishCross = e20p.compareTo(e50p) >= 0 && e20.compareTo(e50) < 0;

        if (bearishCross) return Signal.SELL;
        if (detector.detect(candles) != MarketRegime.TRENDING_UP) return Signal.HOLD;

        if (e20.compareTo(e50) > 0 && e20.compareTo(e20p) > 0
                && e50.compareTo(e50p) > 0 && close.compareTo(e50) > 0
                && validVolatility && bullishCross) {
            return Signal.BUY;
        }
        return Signal.HOLD;
    }

    /**
     * Calcula todas las señales en una sola pasada de indicadores.
     * Conserva la misma lógica de evaluate(), pero evita recalcular el historial
     * completo para cada vela durante el backtest.
     */
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

    public enum Signal { BUY, SELL, HOLD }
}

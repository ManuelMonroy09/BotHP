package mx.jun.trading.strategy;

import mx.jun.trading.indicator.EmaCalculator;
import mx.jun.trading.market.Candle;

import java.math.BigDecimal;
import java.util.List;

public class EmaCrossStrategy {

    private final EmaCalculator emaCalculator;

    public EmaCrossStrategy() {
        this.emaCalculator = new EmaCalculator();
    }

    public Signal evaluate(List<Candle> candles) {

        if (candles == null || candles.size() < 51) {
            return Signal.HOLD;
        }

        List<BigDecimal> closingPrices = candles.stream()
                .map(Candle::close)
                .toList();

        List<BigDecimal> ema20 = emaCalculator.calculate(closingPrices, 20);
        List<BigDecimal> ema50 = emaCalculator.calculate(closingPrices, 50);

        int currentIndex = candles.size() - 1;
        int previousIndex = currentIndex - 1;

        BigDecimal previousEma20 = ema20.get(previousIndex - 19);
        BigDecimal currentEma20 = ema20.get(currentIndex - 19);

        BigDecimal previousEma50 = ema50.get(previousIndex - 49);
        BigDecimal currentEma50 = ema50.get(currentIndex - 49);

        if (previousEma20.compareTo(previousEma50) <= 0
                && currentEma20.compareTo(currentEma50) > 0) {
            return Signal.BUY;
        }

        if (previousEma20.compareTo(previousEma50) >= 0
                && currentEma20.compareTo(currentEma50) < 0) {
            return Signal.SELL;
        }

        return Signal.HOLD;
    }

    public enum Signal {
        BUY,
        SELL,
        HOLD
    }
}

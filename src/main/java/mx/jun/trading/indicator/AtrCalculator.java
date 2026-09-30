package mx.jun.trading.indicator;

import mx.jun.trading.market.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class AtrCalculator {
    public List<BigDecimal> calculate(List<Candle> candles, int period) {
        if (candles == null || candles.size() < period + 1) {
            throw new IllegalArgumentException("Velas insuficientes para ATR");
        }
        List<BigDecimal> trs = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle p = candles.get(i - 1);
            BigDecimal tr = c.high().subtract(c.low()).abs()
                    .max(c.high().subtract(p.close()).abs())
                    .max(c.low().subtract(p.close()).abs());
            trs.add(tr);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) sum = sum.add(trs.get(i));
        BigDecimal atr = sum.divide(BigDecimal.valueOf(period),10,RoundingMode.HALF_UP);
        List<BigDecimal> result = new ArrayList<>();
        result.add(atr);
        for (int i = period; i < trs.size(); i++) {
            atr = atr.multiply(BigDecimal.valueOf(period - 1)).add(trs.get(i))
                    .divide(BigDecimal.valueOf(period),10,RoundingMode.HALF_UP);
            result.add(atr);
        }
        return result;
    }
}
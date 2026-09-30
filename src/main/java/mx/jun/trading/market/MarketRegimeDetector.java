package mx.jun.trading.market;

import mx.jun.trading.indicator.AtrCalculator;
import mx.jun.trading.indicator.EmaCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class MarketRegimeDetector {
 private final EmaCalculator ema=new EmaCalculator();
 private final AtrCalculator atr=new AtrCalculator();
 public MarketRegime detect(List<Candle> candles){
 if(candles==null||candles.size()<51)return MarketRegime.RANGING;
 List<BigDecimal> closes=candles.stream().map(Candle::close).toList();
 var ema50=ema.calculate(closes,50);
 var atr14=atr.calculate(candles,14);
 BigDecimal close=closes.get(closes.size()-1);
 BigDecimal currentAtr=atr14.get(atr14.size()-1);
 BigDecimal atrPct=currentAtr.divide(close,10,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
 if(atrPct.compareTo(BigDecimal.valueOf(3))>=0)return MarketRegime.HIGH_VOLATILITY;
 if(atrPct.compareTo(BigDecimal.valueOf(0.05))<=0)return MarketRegime.LOW_VOLATILITY;
 BigDecimal current=ema50.get(ema50.size()-1);
 BigDecimal previous=ema50.get(ema50.size()-2);
 if(current.compareTo(previous)>0 && close.compareTo(current)>0)return MarketRegime.TRENDING_UP;
 if(current.compareTo(previous)<0 && close.compareTo(current)<0)return MarketRegime.TRENDING_DOWN;
 return MarketRegime.RANGING;
 }
}
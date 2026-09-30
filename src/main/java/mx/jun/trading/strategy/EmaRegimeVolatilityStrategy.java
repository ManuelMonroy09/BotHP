package mx.jun.trading.strategy;

import mx.jun.trading.indicator.AtrCalculator;
import mx.jun.trading.indicator.EmaCalculator;
import mx.jun.trading.market.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class EmaRegimeVolatilityStrategy {
 private final EmaCalculator ema=new EmaCalculator();
 private final AtrCalculator atr=new AtrCalculator();
 private final MarketRegimeDetector detector=new MarketRegimeDetector();
 public Signal evaluate(List<Candle> candles){
 if(candles==null||candles.size()<51)return Signal.HOLD;
 if(detector.detect(candles)!=MarketRegime.TRENDING_UP)return Signal.HOLD;
 List<BigDecimal> closes=candles.stream().map(Candle::close).toList();
 var ema20=ema.calculate(closes,20);
 var ema50=ema.calculate(closes,50);
 var atr14=atr.calculate(candles,14);
 BigDecimal e20=ema20.get(ema20.size()-1);
 BigDecimal e20p=ema20.get(ema20.size()-2);
 BigDecimal e50=ema50.get(ema50.size()-1);
 BigDecimal e50p=ema50.get(ema50.size()-2);
 BigDecimal close=closes.get(closes.size()-1);
 BigDecimal atrPct=atr14.get(atr14.size()-1).divide(close,10,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
 boolean validVol=atrPct.compareTo(BigDecimal.valueOf(0.05))>0&&atrPct.compareTo(BigDecimal.valueOf(3))<0;
 if(e20.compareTo(e50)>0&&e20.compareTo(e20p)>0&&e50.compareTo(e50p)>0&&close.compareTo(e50)>0&&validVol){return Signal.BUY;}
 return Signal.HOLD;
 }
 public enum Signal{BUY,HOLD}
}
package mx.jun.trading.backtest;

import java.math.BigDecimal;
import java.util.List;

public record BacktestResult(BigDecimal initialCapital, BigDecimal finalCapital,
                             BigDecimal netPnl, BigDecimal returnPercentage,
                             BigDecimal maxDrawdownPercentage, int totalTrades,
                             int winningTrades, int losingTrades, BigDecimal profitFactor,
                             List<Trade> trades) {}
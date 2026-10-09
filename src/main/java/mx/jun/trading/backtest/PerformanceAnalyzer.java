package mx.jun.trading.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class PerformanceAnalyzer {
    public String summary(BacktestResult r) {
        BigDecimal winRate = r.totalTrades() == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(r.winningTrades() * 100.0 / r.totalTrades()).setScale(2, RoundingMode.HALF_UP);
        String profitFactor = r.profitFactor() == null ? "∞" : r.profitFactor().toPlainString();
        return """
                === BACKTEST OPCION C ===
                Capital inicial: %s
                Capital final:   %s
                PnL neto:        %s
                Retorno:         %s%%
                Max drawdown:    %s%%
                Trades:          %d
                Ganadoras:       %d
                Perdedoras:      %d
                Win rate:        %s%%
                Profit factor:   %s
                """.formatted(r.initialCapital(), r.finalCapital(), r.netPnl(),
                r.returnPercentage(), r.maxDrawdownPercentage(), r.totalTrades(),
                r.winningTrades(), r.losingTrades(), winRate, profitFactor);
    }
}
package mx.jun.trading.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class PerformanceAnalyzer {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final long YEAR_MILLIS = Duration.ofDays(365).toMillis();

    public String summary(BacktestResult result) {
        BigDecimal winRate = result.totalTrades() == 0 ? ZERO
                : BigDecimal.valueOf(result.winningTrades()).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(result.totalTrades()), 2, RoundingMode.HALF_UP);
        BigDecimal totalFees = result.trades().stream().map(Trade::fees).reduce(ZERO, BigDecimal::add);
        BigDecimal totalFunding = result.trades().stream().map(Trade::fundingPnl).reduce(ZERO, BigDecimal::add);
        BigDecimal grossProfit = result.trades().stream().map(Trade::netPnl)
                .filter(pnl -> pnl.signum() > 0).reduce(ZERO, BigDecimal::add);
        BigDecimal grossLoss = result.trades().stream().map(Trade::netPnl)
                .filter(pnl -> pnl.signum() < 0).map(BigDecimal::abs).reduce(ZERO, BigDecimal::add);
        BigDecimal averageWin = average(grossProfit, result.winningTrades());
        BigDecimal averageLoss = average(grossLoss, result.losingTrades());
        BigDecimal expectancy = result.totalTrades() == 0 ? ZERO
                : result.netPnl().divide(BigDecimal.valueOf(result.totalTrades()), 8, RoundingMode.HALF_UP);
        Stats stats = calculateStats(result.equityCurve(), result.candleIntervalMillis());
        BigDecimal exposure = exposurePercentage(result);
        String profitFactor = result.profitFactor() == null ? "∞" : result.profitFactor().toPlainString();

        String warning = result.totalTrades() < 30
                ? "Advertencia: muestra pequeña (<30 operaciones); Sharpe/Sortino y resultados son inestables."
                : "Las métricas anualizadas son descriptivas; no demuestran capacidad predictiva.";

        return """
                === BACKTEST ===
                Capital inicial:      %s
                Capital final:        %s
                PnL neto:             %s
                Retorno neto:         %s%%
                Drawdown máximo:      %s%%
                Operaciones:          %d
                Ganadoras / perdedoras: %d / %d
                Tasa de acierto:      %s%%
                Ganancia media:       %s
                Pérdida media:        %s
                Expectativa/trade:    %s
                Profit factor:        %s
                Costes modelados:     %s
                PnL de funding:       %s
                Exposición estimada:  %s%%
                Sharpe anualizado:    %s
                Sortino anualizado:   %s
                Observaciones equity: %d
                Nota de métricas:     sin tasa libre de riesgo; anualización asume intervalos regulares.
                %s
                """.formatted(result.initialCapital(), result.finalCapital(), result.netPnl(),
                result.returnPercentage(), result.maxDrawdownPercentage(), result.totalTrades(),
                result.winningTrades(), result.losingTrades(), winRate, averageWin, averageLoss,
                expectancy, profitFactor, totalFees, totalFunding, exposure, stats.sharpe(), stats.sortino(),
                stats.observations(), warning);
    }

    private static BigDecimal average(BigDecimal total, int count) {
        return count == 0 ? ZERO : total.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal exposurePercentage(BacktestResult result) {
        long totalPeriods = Math.max(0, result.equityCurve().size() - 1L);
        if (totalPeriods == 0 || result.candleIntervalMillis() <= 0) return ZERO;
        long exposedMillis = 0;
        for (Trade trade : result.trades()) {
            long heldMillis = Math.max(0, Duration.between(trade.entryTime(), trade.exitTime()).toMillis());
            exposedMillis += heldMillis + result.candleIntervalMillis();
        }
        BigDecimal totalMillis = BigDecimal.valueOf(totalPeriods)
                .multiply(BigDecimal.valueOf(result.candleIntervalMillis()));
        return BigDecimal.valueOf(exposedMillis).multiply(HUNDRED)
                .divide(totalMillis, 2, RoundingMode.HALF_UP)
                .min(HUNDRED);
    }

    private static Stats calculateStats(List<BigDecimal> equityCurve, long intervalMillis) {
        if (equityCurve == null || equityCurve.size() < 3 || intervalMillis <= 0) {
            return new Stats("n/d", "n/d", Math.max(0, equityCurve == null ? 0 : equityCurve.size() - 1));
        }

        List<Double> returns = new ArrayList<>(equityCurve.size() - 1);
        for (int i = 1; i < equityCurve.size(); i++) {
            BigDecimal previous = equityCurve.get(i - 1);
            if (previous.signum() <= 0) continue;
            returns.add(equityCurve.get(i).subtract(previous)
                    .divide(previous, 16, RoundingMode.HALF_UP).doubleValue());
        }
        if (returns.size() < 2) return new Stats("n/d", "n/d", returns.size());

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double squaredDeviation = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum();
        double standardDeviation = Math.sqrt(squaredDeviation / (returns.size() - 1));
        double downsideSquares = returns.stream().mapToDouble(value -> value < 0 ? value * value : 0).sum();
        double downsideDeviation = Math.sqrt(downsideSquares / returns.size());
        double annualization = Math.sqrt((double) YEAR_MILLIS / intervalMillis);
        String sharpe = ratio(mean, standardDeviation, annualization);
        String sortino = ratio(mean, downsideDeviation, annualization);
        return new Stats(sharpe, sortino, returns.size());
    }

    private static String ratio(double mean, double deviation, double annualization) {
        if (deviation <= 0 || !Double.isFinite(mean) || !Double.isFinite(deviation)) return "n/d";
        double value = mean / deviation * annualization;
        if (!Double.isFinite(value)) return "n/d";
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private record Stats(String sharpe, String sortino, int observations) {}
}

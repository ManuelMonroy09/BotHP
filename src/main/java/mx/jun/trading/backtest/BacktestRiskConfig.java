package mx.jun.trading.backtest;

import java.math.BigDecimal;

/**
 * Parametros conservadores para simulacion. No constituyen una garantia
 * de perdida maxima: los gaps y el deslizamiento pueden superar el stop.
 */
public record BacktestRiskConfig(
        BigDecimal riskPerTradePercentage,
        BigDecimal stopLossPercentage,
        BigDecimal maxPositionNotionalPercentage,
        BigDecimal dailyLossLimitPercentage,
        BigDecimal cumulativeDrawdownLimitPercentage) {

    public static BacktestRiskConfig conservativeDefaults() {
        return new BacktestRiskConfig(
                new BigDecimal("0.005"),
                new BigDecimal("0.015"),
                new BigDecimal("0.50"),
                new BigDecimal("0.02"),
                new BigDecimal("0.10"));
    }

    public BacktestRiskConfig {
        validateFraction("Riesgo por operacion", riskPerTradePercentage);
        validateFraction("Stop-loss", stopLossPercentage);
        validateFraction("Exposicion maxima", maxPositionNotionalPercentage);
        validateFraction("Limite diario", dailyLossLimitPercentage);
        validateFraction("Limite acumulado", cumulativeDrawdownLimitPercentage);
    }

    private static void validateFraction(String name, BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(name + " debe ser mayor que 0 y menor que 1");
        }
    }
}

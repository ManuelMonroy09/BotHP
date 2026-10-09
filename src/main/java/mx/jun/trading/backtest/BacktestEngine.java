package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class BacktestEngine {
    private static final BigDecimal FEE = new BigDecimal("0.00045");
    private static final BigDecimal SLIPPAGE = new BigDecimal("0.00010");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital) {
        return run(candles, initialCapital, FEE, SLIPPAGE,
                BacktestRiskConfig.conservativeDefaults());
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate) {
        return run(candles, initialCapital, feeRate, slippageRate,
                BacktestRiskConfig.conservativeDefaults());
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate,
                              BacktestRiskConfig riskConfig) {
        validateInputs(candles, initialCapital, feeRate, slippageRate, riskConfig);

        // Los indicadores se calculan una sola vez: O(n), no una vez por cada prefijo.
        var strategy = new EmaRegimeVolatilityStrategy();
        List<EmaRegimeVolatilityStrategy.Signal> signals = strategy.evaluateAll(candles);
        var trades = new ArrayList<Trade>();

        BigDecimal capital = initialCapital;
        BigDecimal peak = initialCapital;
        BigDecimal maxDd = BigDecimal.ZERO;
        BigDecimal entry = null;
        BigDecimal qty = null;
        BigDecimal stopPrice = null;
        Candle entryCandle = null;
        LocalDate currentUtcDay = null;
        BigDecimal dayStartEquity = initialCapital;
        BigDecimal previousCloseEquity = initialCapital;
        boolean dailyLossLocked = false;
        boolean cumulativeLossLocked = false;

        // La señal al cierre de i se ejecuta en la apertura de i+1.
        for (int i = 50; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            LocalDate candleDay = candle.timestamp().atZone(ZoneOffset.UTC).toLocalDate();

            if (!candleDay.equals(currentUtcDay)) {
                currentUtcDay = candleDay;
                dayStartEquity = previousCloseEquity;
                dailyLossLocked = false;
            }

            BigDecimal openEquity = equityAt(candle.open(), capital, entry, qty);
            if (dayStartEquity.signum() > 0
                    && openEquity.compareTo(dayStartEquity.multiply(
                    ONE.subtract(riskConfig.dailyLossLimitPercentage()))) <= 0) {
                dailyLossLocked = true;
            }
            if (openEquity.compareTo(initialCapital.multiply(
                    ONE.subtract(riskConfig.cumulativeDrawdownLimitPercentage()))) <= 0) {
                cumulativeLossLocked = true;
            }

            if (i > 50) {
                var signal = signals.get(i - 1);
                if (entry == null && signal == EmaRegimeVolatilityStrategy.Signal.BUY
                        && !dailyLossLocked && !cumulativeLossLocked) {
                    BigDecimal equity = openEquity.max(BigDecimal.ZERO);
                    BigDecimal executionPrice = candle.open().multiply(ONE.add(slippageRate));
                    BigDecimal riskBudget = equity.multiply(riskConfig.riskPerTradePercentage());
                    stopPrice = executionPrice.multiply(
                            ONE.subtract(riskConfig.stopLossPercentage()));
                    BigDecimal stopDistance = executionPrice.subtract(stopPrice);
                    BigDecimal quantityByRisk = riskBudget.divide(stopDistance, 10, RoundingMode.DOWN);
                    BigDecimal maxNotional = equity.multiply(
                            riskConfig.maxPositionNotionalPercentage());
                    BigDecimal quantityByExposure = maxNotional.divide(
                            executionPrice, 10, RoundingMode.DOWN);
                    BigDecimal quantity = quantityByRisk.min(quantityByExposure);

                    if (quantity.signum() > 0) {
                        entry = executionPrice;
                        qty = quantity;
                        entryCandle = candle;
                        capital = capital.subtract(entry.multiply(qty).multiply(feeRate));
                    }
                } else if (entry != null && signal == EmaRegimeVolatilityStrategy.Signal.SELL) {
                    BigDecimal exit = candle.open().multiply(ONE.subtract(slippageRate));
                    capital = closePosition(capital, entry, qty, exit, feeRate, trades,
                            entryCandle, candle, "BEARISH_CROSS");
                    entry = null;
                    qty = null;
                    stopPrice = null;
                    entryCandle = null;
                }
            }

            // Si el limite ya se cruzo en la apertura, cerrar la posicion antes de
            // mantener mas exposicion. El bloqueo diario se reinicia al cambiar el dia UTC.
            if (entry != null && (dailyLossLocked || cumulativeLossLocked)) {
                BigDecimal emergencyExit = candle.open().multiply(ONE.subtract(slippageRate));
                String lockReason = dailyLossLocked && cumulativeLossLocked
                        ? "DAILY_AND_CUMULATIVE_LOSS_LIMIT"
                        : dailyLossLocked ? "DAILY_LOSS_LIMIT" : "CUMULATIVE_DRAWDOWN_LIMIT";
                capital = closePosition(capital, entry, qty, emergencyExit, feeRate, trades,
                        entryCandle, candle, lockReason);
                entry = null;
                qty = null;
                stopPrice = null;
                entryCandle = null;
            }

            if (entry != null) {
                // OHLC no revela si el maximo ocurrio antes que el minimo.
                // Para el drawdown usamos una secuencia conservadora: maximo y luego minimo/stop.
                BigDecimal highEquity = equityAt(candle.high(), capital, entry, qty);
                if (highEquity.compareTo(peak) > 0) peak = highEquity;

                boolean stopHit = candle.low().compareTo(stopPrice) <= 0;
                BigDecimal stopExecution = null;
                BigDecimal riskEquity;
                if (stopHit) {
                    BigDecimal rawExit = candle.open().compareTo(stopPrice) <= 0
                            ? candle.open() : stopPrice;
                    stopExecution = rawExit.multiply(ONE.subtract(slippageRate));
                    BigDecimal exitFee = stopExecution.multiply(qty).multiply(feeRate);
                    riskEquity = capital.add(stopExecution.subtract(entry).multiply(qty))
                            .subtract(exitFee);
                } else {
                    riskEquity = equityAt(candle.low(), capital, entry, qty);
                }

                maxDd = updateDrawdown(peak, riskEquity, maxDd);
                if (dayStartEquity.signum() > 0
                        && riskEquity.compareTo(dayStartEquity.multiply(
                        ONE.subtract(riskConfig.dailyLossLimitPercentage()))) <= 0) {
                    dailyLossLocked = true;
                }
                if (riskEquity.compareTo(initialCapital.multiply(
                        ONE.subtract(riskConfig.cumulativeDrawdownLimitPercentage()))) <= 0) {
                    cumulativeLossLocked = true;
                }

                // Al tocar un limite diario/acumulado, se cierra al cierre de esta vela
                // si el stop no se activo. Los gaps pueden producir una perdida mayor al limite.
                if (stopHit || dailyLossLocked || cumulativeLossLocked) {
                    BigDecimal exit = stopHit ? stopExecution
                            : candle.close().multiply(ONE.subtract(slippageRate));
                    String exitReason = stopHit ? "STOP_LOSS"
                            : dailyLossLocked && cumulativeLossLocked
                            ? "DAILY_AND_CUMULATIVE_LOSS_LIMIT"
                            : dailyLossLocked ? "DAILY_LOSS_LIMIT" : "CUMULATIVE_DRAWDOWN_LIMIT";
                    capital = closePosition(capital, entry, qty, exit, feeRate,
                            trades, entryCandle, candle, exitReason);
                    entry = null;
                    qty = null;
                    stopPrice = null;
                    entryCandle = null;
                }
            } else {
                if (capital.compareTo(peak) > 0) peak = capital;
                maxDd = updateDrawdown(peak, capital, maxDd);
            }

            previousCloseEquity = equityAt(candle.close(), capital, entry, qty);
        }

        // Liquida al cierre de la ultima vela. No se usa su señal para decidir la entrada.
        if (entry != null) {
            Candle last = candles.get(candles.size() - 1);
            BigDecimal exit = last.close().multiply(ONE.subtract(slippageRate));
            capital = closePosition(capital, entry, qty, exit, feeRate, trades,
                    entryCandle, last, "END_OF_DATA");
            if (capital.compareTo(peak) > 0) peak = capital;
            maxDd = updateDrawdown(peak, capital, maxDd);
        }

        BigDecimal netPnl = capital.subtract(initialCapital);
        BigDecimal returnPct = netPnl.divide(initialCapital, 10, RoundingMode.HALF_UP).multiply(HUNDRED);
        int wins = (int) trades.stream().filter(t -> t.netPnl().signum() > 0).count();
        int losses = (int) trades.stream().filter(t -> t.netPnl().signum() < 0).count();
        BigDecimal profit = trades.stream().filter(t -> t.netPnl().signum() > 0)
                .map(Trade::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loss = trades.stream().filter(t -> t.netPnl().signum() < 0)
                .map(Trade::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        BigDecimal profitFactor = loss.signum() == 0
                ? (profit.signum() > 0 ? null : BigDecimal.ZERO)
                : profit.divide(loss, 4, RoundingMode.HALF_UP);

        return new BacktestResult(initialCapital, capital, netPnl, returnPct, maxDd,
                trades.size(), wins, losses, profitFactor, List.copyOf(trades));
    }

    private static BigDecimal closePosition(BigDecimal capital, BigDecimal entry,
                                            BigDecimal quantity, BigDecimal exit,
                                            BigDecimal feeRate, List<Trade> trades,
                                            Candle entryCandle, Candle exitCandle, String exitReason) {
        BigDecimal gross = exit.subtract(entry).multiply(quantity);
        BigDecimal entryFee = entry.multiply(quantity).multiply(feeRate);
        BigDecimal exitFee = exit.multiply(quantity).multiply(feeRate);
        BigDecimal fees = entryFee.add(exitFee);
        BigDecimal net = gross.subtract(fees);
        // La comision de entrada ya se desconto al abrir la posicion.
        BigDecimal updatedCapital = capital.add(gross).subtract(exitFee);
        trades.add(new Trade(entryCandle.timestamp(), exitCandle.timestamp(),
                entry, exit, quantity, gross, fees, net, exitReason));
        return updatedCapital;
    }

    private static BigDecimal equityAt(BigDecimal price, BigDecimal capital,
                                        BigDecimal entry, BigDecimal quantity) {
        if (entry == null || quantity == null) return capital;
        return capital.add(price.subtract(entry).multiply(quantity));
    }

    private static BigDecimal updateDrawdown(BigDecimal peak, BigDecimal equity, BigDecimal currentMax) {
        if (peak.signum() <= 0 || equity.compareTo(peak) >= 0) return currentMax;
        BigDecimal drawdown = peak.subtract(equity)
                .divide(peak, 10, RoundingMode.HALF_UP).multiply(HUNDRED);
        return drawdown.compareTo(currentMax) > 0 ? drawdown : currentMax;
    }

    private static void validateInputs(List<Candle> candles, BigDecimal initialCapital,
                                       BigDecimal feeRate, BigDecimal slippageRate,
                                       BacktestRiskConfig riskConfig) {
        if (candles == null || candles.size() < 51) {
            throw new IllegalArgumentException("Se requieren al menos 51 velas");
        }
        if (initialCapital == null || initialCapital.signum() <= 0) {
            throw new IllegalArgumentException("Capital invalido");
        }
        if (feeRate == null || feeRate.signum() < 0 || feeRate.compareTo(ONE) >= 0) {
            throw new IllegalArgumentException("La comision debe estar entre 0 y 1");
        }
        if (slippageRate == null || slippageRate.signum() < 0 || slippageRate.compareTo(ONE) >= 0) {
            throw new IllegalArgumentException("El deslizamiento debe estar entre 0 y 1");
        }
        if (riskConfig == null) {
            throw new IllegalArgumentException("La configuracion de riesgo es obligatoria");
        }

        Candle previous = null;
        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (candle == null || candle.timestamp() == null || candle.open() == null
                    || candle.high() == null || candle.low() == null || candle.close() == null
                    || candle.volume() == null) {
                throw new IllegalArgumentException("Vela incompleta en indice " + i);
            }
            if (candle.open().signum() <= 0 || candle.high().signum() <= 0
                    || candle.low().signum() <= 0 || candle.close().signum() <= 0
                    || candle.volume().signum() < 0) {
                throw new IllegalArgumentException("Precio invalido o volumen negativo en indice " + i);
            }
            if (candle.high().compareTo(candle.open()) < 0
                    || candle.high().compareTo(candle.close()) < 0
                    || candle.low().compareTo(candle.open()) > 0
                    || candle.low().compareTo(candle.close()) > 0
                    || candle.high().compareTo(candle.low()) < 0) {
                throw new IllegalArgumentException("Rango OHLC inconsistente en indice " + i);
            }
            if (previous != null && !candle.timestamp().isAfter(previous.timestamp())) {
                throw new IllegalArgumentException("Las velas deben estar ordenadas por tiempo sin duplicados");
            }
            previous = candle;
        }
    }
}

package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import mx.jun.trading.market.FundingRate;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

public class BacktestEngine {
    public static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.00045");
    public static final BigDecimal DEFAULT_SLIPPAGE_RATE = new BigDecimal("0.00010");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital) {
        return run(candles, initialCapital, DEFAULT_FEE_RATE, DEFAULT_SLIPPAGE_RATE,
                BacktestRiskConfig.conservativeDefaults());
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital, boolean allowShorts) {
        return run(candles, initialCapital, DEFAULT_FEE_RATE, DEFAULT_SLIPPAGE_RATE,
                BacktestRiskConfig.conservativeDefaults(), allowShorts);
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              boolean allowLongs, boolean allowShorts) {
        return run(candles, initialCapital, DEFAULT_FEE_RATE, DEFAULT_SLIPPAGE_RATE,
                BacktestRiskConfig.conservativeDefaults(), allowLongs, allowShorts);
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate) {
        return run(candles, initialCapital, feeRate, slippageRate,
                BacktestRiskConfig.conservativeDefaults());
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate,
                              BacktestRiskConfig riskConfig) {
        return run(candles, initialCapital, feeRate, slippageRate, riskConfig, true, false);
    }

    /** Runs the experimental symmetric long/short candidate when enabled. */
    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate,
                              BacktestRiskConfig riskConfig, boolean allowShorts) {
        return run(candles, initialCapital, feeRate, slippageRate, riskConfig, true, allowShorts);
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate,
                              BacktestRiskConfig riskConfig, boolean allowLongs, boolean allowShorts) {
        return run(candles, initialCapital, feeRate, slippageRate, riskConfig,
                allowLongs, allowShorts, EmaRegimeVolatilityStrategy.Mode.EMA_CROSS);
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate,
                              BacktestRiskConfig riskConfig, boolean allowLongs, boolean allowShorts,
                              EmaRegimeVolatilityStrategy.Mode strategyMode) {
        return run(candles, initialCapital, feeRate, slippageRate, riskConfig,
                allowLongs, allowShorts, strategyMode, List.of());
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate,
                              BacktestRiskConfig riskConfig, boolean allowLongs, boolean allowShorts,
                              EmaRegimeVolatilityStrategy.Mode strategyMode, List<FundingRate> fundingRates) {
        long candleIntervalMillis = validateInputs(candles, initialCapital, feeRate, slippageRate, riskConfig);
        if (fundingRates == null) throw new IllegalArgumentException("Funding rates cannot be null");
        List<FundingRate> sortedFundingRates = fundingRates.stream()
                .sorted(Comparator.comparing(FundingRate::timestamp)).toList();
        for (int i = 1; i < sortedFundingRates.size(); i++) {
            if (!sortedFundingRates.get(i).timestamp().isAfter(sortedFundingRates.get(i - 1).timestamp())) {
                throw new IllegalArgumentException("Funding timestamps must be strictly increasing");
            }
        }

        // Los indicadores se calculan una sola vez: O(n), no una vez por cada prefijo.
        var strategy = new EmaRegimeVolatilityStrategy(strategyMode);
        List<EmaRegimeVolatilityStrategy.Signal> signals = strategy.evaluateAll(candles);
        var trades = new ArrayList<Trade>();

        BigDecimal capital = initialCapital;
        BigDecimal peak = initialCapital;
        BigDecimal maxDd = BigDecimal.ZERO;
        BigDecimal entry = null;
        BigDecimal qty = null;
        int positionSide = 0;
        BigDecimal stopPrice = null;
        BigDecimal takeProfitPrice = null;
        Candle entryCandle = null;
        BigDecimal positionFundingPnl = BigDecimal.ZERO;
        int fundingIndex = 0;
        LocalDate currentUtcDay = null;
        BigDecimal dayStartEquity = initialCapital;
        BigDecimal previousCloseEquity = initialCapital;
        boolean dailyLossLocked = false;
        boolean cumulativeLossLocked = false;
        var equityCurve = new ArrayList<BigDecimal>(candles.size() - 49);
        equityCurve.add(initialCapital);

        // La señal al cierre de i se ejecuta en la apertura de i+1.
        for (int i = 50; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            LocalDate candleDay = candle.timestamp().atZone(ZoneOffset.UTC).toLocalDate();

            // Apply events accrued since the preceding candle while the prior position was open.
            // The preceding candle close is a no-lookahead proxy for the oracle price.
            BigDecimal fundingPriceProxy = i > 0 ? candles.get(i - 1).close() : candle.open();
            while (fundingIndex < sortedFundingRates.size()
                    && !sortedFundingRates.get(fundingIndex).timestamp().isAfter(candle.timestamp())) {
                FundingRate fundingRate = sortedFundingRates.get(fundingIndex++);
                if (entry != null && fundingRate.timestamp().isAfter(entryCandle.timestamp())) {
                    BigDecimal cashFlow = qty.multiply(fundingPriceProxy)
                            .multiply(fundingRate.rate()).multiply(BigDecimal.valueOf(-positionSide));
                    capital = capital.add(cashFlow);
                    positionFundingPnl = positionFundingPnl.add(cashFlow);
                }
            }

            if (!candleDay.equals(currentUtcDay)) {
                currentUtcDay = candleDay;
                dayStartEquity = previousCloseEquity;
                dailyLossLocked = false;
            }

            BigDecimal openEquity = equityAt(candle.open(), capital, entry, qty, positionSide);
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
                int signalSide = signal == EmaRegimeVolatilityStrategy.Signal.BUY ? 1
                        : signal == EmaRegimeVolatilityStrategy.Signal.SELL ? -1 : 0;
                if (entry != null && signalSide != 0 && signalSide != positionSide) {
                    BigDecimal reversalExit = candle.open().multiply(
                            positionSide > 0 ? ONE.subtract(slippageRate) : ONE.add(slippageRate));
                    capital = closePosition(capital, entry, qty, reversalExit, feeRate, trades,
                            entryCandle, candle, positionSide, positionFundingPnl, "SIGNAL_REVERSAL");
                    entry = null;
                    qty = null;
                    stopPrice = null;
                    takeProfitPrice = null;
                    entryCandle = null;
                    positionSide = 0;
                    positionFundingPnl = BigDecimal.ZERO;
                }
                if (entry == null && signalSide != 0
                        && (signalSide > 0 ? allowLongs : allowShorts)
                        && !dailyLossLocked && !cumulativeLossLocked) {
                    BigDecimal equity = openEquity.max(BigDecimal.ZERO);
                    BigDecimal executionPrice = candle.open().multiply(
                            signalSide > 0 ? ONE.add(slippageRate) : ONE.subtract(slippageRate));
                    BigDecimal riskBudget = equity.multiply(riskConfig.riskPerTradePercentage());
                    stopPrice = executionPrice.multiply(signalSide > 0
                            ? ONE.subtract(riskConfig.stopLossPercentage())
                            : ONE.add(riskConfig.stopLossPercentage()));
                    takeProfitPrice = riskConfig.takeProfitPercentage().signum() == 0 ? null
                            : executionPrice.multiply(signalSide > 0
                            ? ONE.add(riskConfig.takeProfitPercentage())
                            : ONE.subtract(riskConfig.takeProfitPercentage()));
                    BigDecimal stopExecutionPrice = stopPrice.multiply(
                            signalSide > 0 ? ONE.subtract(slippageRate) : ONE.add(slippageRate));
                    BigDecimal riskPerUnit = stopExecutionPrice.subtract(executionPrice).abs()
                            .add(executionPrice.multiply(feeRate))
                            .add(stopExecutionPrice.multiply(feeRate));
                    BigDecimal quantityByRisk = riskBudget.divide(riskPerUnit, 10, RoundingMode.DOWN);
                    BigDecimal maxNotional = equity.multiply(
                            riskConfig.maxPositionNotionalPercentage());
                    BigDecimal quantityByExposure = maxNotional.divide(
                            executionPrice, 10, RoundingMode.DOWN);
                    BigDecimal quantity = quantityByRisk.min(quantityByExposure);

                    if (quantity.signum() > 0) {
                        entry = executionPrice;
                        qty = quantity;
                        positionSide = signalSide;
                        entryCandle = candle;
                        capital = capital.subtract(entry.multiply(qty).multiply(feeRate));
                    }
                }
            }

            // Process settlement events falling inside this bar while the position is
            // active. OHLC cannot establish the exact intrabar exit time; this applies
            // such payments before stop/target checks and is an explicit approximation.
            Instant candleCloseTime = candle.timestamp().plusMillis(candleIntervalMillis);
            while (fundingIndex < sortedFundingRates.size()
                    && !sortedFundingRates.get(fundingIndex).timestamp().isAfter(candleCloseTime)) {
                FundingRate fundingRate = sortedFundingRates.get(fundingIndex++);
                if (entry != null && fundingRate.timestamp().isAfter(entryCandle.timestamp())) {
                    BigDecimal cashFlow = qty.multiply(fundingPriceProxy).multiply(fundingRate.rate())
                            .multiply(BigDecimal.valueOf(-positionSide));
                    capital = capital.add(cashFlow);
                    positionFundingPnl = positionFundingPnl.add(cashFlow);
                }
            }

            // Si el limite ya se cruzo en la apertura, cerrar la posicion antes de
            // mantener mas exposicion. El bloqueo diario se reinicia al cambiar el dia UTC.
            if (entry != null && (dailyLossLocked || cumulativeLossLocked)) {
                BigDecimal emergencyExit = candle.open().multiply(
                        positionSide > 0 ? ONE.subtract(slippageRate) : ONE.add(slippageRate));
                String lockReason = dailyLossLocked && cumulativeLossLocked
                        ? "DAILY_AND_CUMULATIVE_LOSS_LIMIT"
                        : dailyLossLocked ? "DAILY_LOSS_LIMIT" : "CUMULATIVE_DRAWDOWN_LIMIT";
                capital = closePosition(capital, entry, qty, emergencyExit, feeRate, trades,
                        entryCandle, candle, positionSide, positionFundingPnl, lockReason);
                entry = null;
                qty = null;
                stopPrice = null;
                takeProfitPrice = null;
                entryCandle = null;
                positionSide = 0;
                positionFundingPnl = BigDecimal.ZERO;
            }

            if (entry != null) {
                // OHLC no revela si el maximo ocurrio antes que el minimo.
                // Para el drawdown usamos una secuencia conservadora: maximo y luego minimo/stop.
                BigDecimal favorableEquity = equityAt(positionSide > 0 ? candle.high() : candle.low(),
                        capital, entry, qty, positionSide);
                if (favorableEquity.compareTo(peak) > 0) peak = favorableEquity;

                boolean stopHit = positionSide > 0 ? candle.low().compareTo(stopPrice) <= 0
                        : candle.high().compareTo(stopPrice) >= 0;
                boolean takeProfitHit = takeProfitPrice != null && (positionSide > 0
                        ? candle.high().compareTo(takeProfitPrice) >= 0
                        : candle.low().compareTo(takeProfitPrice) <= 0);
                BigDecimal stopExecution = null;
                BigDecimal riskEquity;
                if (stopHit) {
                    BigDecimal rawExit;
                    if (positionSide > 0) {
                        rawExit = candle.open().compareTo(stopPrice) <= 0 ? candle.open() : stopPrice;
                    } else {
                        rawExit = candle.open().compareTo(stopPrice) >= 0 ? candle.open() : stopPrice;
                    }
                    stopExecution = rawExit.multiply(
                            positionSide > 0 ? ONE.subtract(slippageRate) : ONE.add(slippageRate));
                    BigDecimal exitFee = stopExecution.multiply(qty).multiply(feeRate);
                    riskEquity = capital.add(stopExecution.subtract(entry)
                                    .multiply(qty).multiply(BigDecimal.valueOf(positionSide)))
                            .subtract(exitFee);
                } else {
                    riskEquity = equityAt(positionSide > 0 ? candle.low() : candle.high(),
                            capital, entry, qty, positionSide);
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

                // Con OHLC no se conoce el recorrido intravela. Si se activa un límite
                // de equity sin tocar el stop, se asume salida en el mínimo de la vela.
                if (stopHit || dailyLossLocked || cumulativeLossLocked || takeProfitHit) {
                    BigDecimal adversePrice = positionSide > 0 ? candle.low() : candle.high();
                    BigDecimal targetExecution = takeProfitPrice == null ? null : takeProfitPrice.multiply(
                            positionSide > 0 ? ONE.subtract(slippageRate) : ONE.add(slippageRate));
                    boolean lossLimitHit = dailyLossLocked || cumulativeLossLocked;
                    BigDecimal exit = stopHit ? stopExecution : takeProfitHit && !lossLimitHit
                            ? targetExecution : adversePrice.multiply(
                            positionSide > 0 ? ONE.subtract(slippageRate) : ONE.add(slippageRate));
                    String exitReason = stopHit ? "STOP_LOSS"
                            : lossLimitHit && dailyLossLocked && cumulativeLossLocked
                            ? "DAILY_AND_CUMULATIVE_LOSS_LIMIT"
                            : lossLimitHit && dailyLossLocked ? "DAILY_LOSS_LIMIT"
                            : lossLimitHit ? "CUMULATIVE_DRAWDOWN_LIMIT" : "TAKE_PROFIT";
                    capital = closePosition(capital, entry, qty, exit, feeRate,
                            trades, entryCandle, candle, positionSide, positionFundingPnl, exitReason);
                    entry = null;
                    qty = null;
                    stopPrice = null;
                    takeProfitPrice = null;
                    entryCandle = null;
                    positionSide = 0;
                    positionFundingPnl = BigDecimal.ZERO;
                }
            } else {
                if (capital.compareTo(peak) > 0) peak = capital;
                maxDd = updateDrawdown(peak, capital, maxDd);
            }

            previousCloseEquity = equityAt(candle.close(), capital, entry, qty, positionSide);
            equityCurve.add(previousCloseEquity);
        }

        // Liquida al cierre de la ultima vela. No se usa su señal para decidir la entrada.
        if (entry != null) {
            Candle last = candles.get(candles.size() - 1);
            Instant lastCloseTime = last.timestamp().plusMillis(candleIntervalMillis);
            while (fundingIndex < sortedFundingRates.size()
                    && !sortedFundingRates.get(fundingIndex).timestamp().isAfter(lastCloseTime)) {
                FundingRate fundingRate = sortedFundingRates.get(fundingIndex++);
                if (fundingRate.timestamp().isAfter(entryCandle.timestamp())) {
                    BigDecimal cashFlow = qty.multiply(last.close()).multiply(fundingRate.rate())
                            .multiply(BigDecimal.valueOf(-positionSide));
                    capital = capital.add(cashFlow);
                    positionFundingPnl = positionFundingPnl.add(cashFlow);
                }
            }
            BigDecimal exit = last.close().multiply(
                    positionSide > 0 ? ONE.subtract(slippageRate) : ONE.add(slippageRate));
            capital = closePosition(capital, entry, qty, exit, feeRate, trades,
                    entryCandle, last, positionSide, positionFundingPnl, "END_OF_DATA");
            if (capital.compareTo(peak) > 0) peak = capital;
            maxDd = updateDrawdown(peak, capital, maxDd);
            equityCurve.set(equityCurve.size() - 1, capital);
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
                trades.size(), wins, losses, profitFactor, List.copyOf(trades),
                List.copyOf(equityCurve), candleIntervalMillis);
    }

    private static BigDecimal closePosition(BigDecimal capital, BigDecimal entry,
                                            BigDecimal quantity, BigDecimal exit,
                                            BigDecimal feeRate, List<Trade> trades,
                                            Candle entryCandle, Candle exitCandle,
                                            int side, BigDecimal fundingPnl, String exitReason) {
        BigDecimal gross = exit.subtract(entry).multiply(quantity).multiply(BigDecimal.valueOf(side));
        BigDecimal entryFee = entry.multiply(quantity).multiply(feeRate);
        BigDecimal exitFee = exit.multiply(quantity).multiply(feeRate);
        BigDecimal fees = entryFee.add(exitFee);
        BigDecimal net = gross.subtract(fees).add(fundingPnl);
        // La comision de entrada ya se desconto al abrir la posicion.
        BigDecimal updatedCapital = capital.add(gross).subtract(exitFee);
        trades.add(new Trade(entryCandle.timestamp(), exitCandle.timestamp(),
                entry, exit, quantity, gross, fees, fundingPnl, net, side, exitReason));
        return updatedCapital;
    }

    private static BigDecimal equityAt(BigDecimal price, BigDecimal capital,
                                        BigDecimal entry, BigDecimal quantity, int side) {
        if (entry == null || quantity == null) return capital;
        return capital.add(price.subtract(entry).multiply(quantity).multiply(BigDecimal.valueOf(side)));
    }

    private static BigDecimal updateDrawdown(BigDecimal peak, BigDecimal equity, BigDecimal currentMax) {
        if (peak.signum() <= 0 || equity.compareTo(peak) >= 0) return currentMax;
        BigDecimal drawdown = peak.subtract(equity)
                .divide(peak, 10, RoundingMode.HALF_UP).multiply(HUNDRED);
        return drawdown.compareTo(currentMax) > 0 ? drawdown : currentMax;
    }

    private static long validateInputs(List<Candle> candles, BigDecimal initialCapital,
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
        long candleIntervalMillis = -1;
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
            if (previous != null) {
                long currentIntervalMillis = java.time.Duration.between(
                        previous.timestamp(), candle.timestamp()).toMillis();
                if (currentIntervalMillis <= 0) {
                    throw new IllegalArgumentException("Las velas deben estar ordenadas por tiempo sin duplicados");
                }
                if (candleIntervalMillis < 0) {
                    candleIntervalMillis = currentIntervalMillis;
                } else if (currentIntervalMillis != candleIntervalMillis) {
                    throw new IllegalArgumentException("El historial contiene huecos o intervalos inconsistentes");
                }
            }
            previous = candle;
        }
        if (candleIntervalMillis <= 0) {
            throw new IllegalArgumentException("No se pudo determinar el intervalo de las velas");
        }
        return candleIntervalMillis;
    }
}

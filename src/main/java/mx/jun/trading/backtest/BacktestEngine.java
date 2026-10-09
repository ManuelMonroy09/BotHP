package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class BacktestEngine {
    private static final BigDecimal FEE = new BigDecimal("0.00045");
    private static final BigDecimal SLIPPAGE = new BigDecimal("0.00010");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital) {
        return run(candles, initialCapital, FEE, SLIPPAGE);
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate) {
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

        // Los indicadores se calculan una sola vez: O(n), no una vez por cada prefijo.
        var strategy = new EmaRegimeVolatilityStrategy();
        List<EmaRegimeVolatilityStrategy.Signal> signals = strategy.evaluateAll(candles);
        var trades = new ArrayList<Trade>();

        BigDecimal capital = initialCapital;
        BigDecimal peak = initialCapital;
        BigDecimal maxDd = BigDecimal.ZERO;
        BigDecimal entry = null;
        BigDecimal qty = null;
        Candle entryCandle = null;

        // Una señal al cierre de la vela i se ejecuta en la apertura de la vela i+1.
        for (int i = 50; i < candles.size(); i++) {
            Candle candle = candles.get(i);

            if (i > 50) {
                var signal = signals.get(i - 1);
                if (entry == null && signal == EmaRegimeVolatilityStrategy.Signal.BUY) {
                    BigDecimal executionPrice = candle.open().multiply(ONE.add(slippageRate));
                    BigDecimal availableCapital = capital;
                    BigDecimal quantity = availableCapital.divide(executionPrice, 10, RoundingMode.DOWN);
                    if (quantity.signum() > 0) {
                        entry = executionPrice;
                        qty = quantity;
                        entryCandle = candle;
                        capital = capital.subtract(entry.multiply(qty).multiply(feeRate));
                    }
                } else if (entry != null && signal == EmaRegimeVolatilityStrategy.Signal.SELL) {
                    BigDecimal exit = candle.open().multiply(ONE.subtract(slippageRate));
                    BigDecimal gross = exit.subtract(entry).multiply(qty);
                    BigDecimal entryFee = entry.multiply(qty).multiply(feeRate);
                    BigDecimal exitFee = exit.multiply(qty).multiply(feeRate);
                    BigDecimal fees = entryFee.add(exitFee);
                    BigDecimal net = gross.subtract(fees);

                    capital = capital.add(gross).subtract(exitFee);
                    trades.add(new Trade(entryCandle.timestamp(), candle.timestamp(),
                            entry, exit, qty, gross, fees, net));
                    entry = null;
                    qty = null;
                    entryCandle = null;
                }
            }

            BigDecimal equity = capital;
            if (entry != null) {
                equity = capital.add(candle.close().subtract(entry).multiply(qty));
            }
            if (equity.compareTo(peak) > 0) peak = equity;
            BigDecimal drawdown = peak.signum() == 0 ? BigDecimal.ZERO
                    : peak.subtract(equity).divide(peak, 10, RoundingMode.HALF_UP).multiply(HUNDRED);
            if (drawdown.compareTo(maxDd) > 0) maxDd = drawdown;
        }

        // Liquidacion al cierre de la ultima vela, sin usar una señal de esa misma vela.
        if (entry != null) {
            Candle last = candles.get(candles.size() - 1);
            BigDecimal exit = last.close().multiply(ONE.subtract(slippageRate));
            BigDecimal gross = exit.subtract(entry).multiply(qty);
            BigDecimal entryFee = entry.multiply(qty).multiply(feeRate);
            BigDecimal exitFee = exit.multiply(qty).multiply(feeRate);
            BigDecimal fees = entryFee.add(exitFee);
            BigDecimal net = gross.subtract(fees);

            capital = capital.add(gross).subtract(exitFee);
            trades.add(new Trade(entryCandle.timestamp(), last.timestamp(),
                    entry, exit, qty, gross, fees, net));

            if (capital.compareTo(peak) > 0) peak = capital;
            BigDecimal drawdown = peak.signum() == 0 ? BigDecimal.ZERO
                    : peak.subtract(capital).divide(peak, 10, RoundingMode.HALF_UP).multiply(HUNDRED);
            if (drawdown.compareTo(maxDd) > 0) maxDd = drawdown;
        }

        BigDecimal netPnl = capital.subtract(initialCapital);
        BigDecimal returnPct = netPnl.divide(initialCapital, 10, RoundingMode.HALF_UP).multiply(HUNDRED);
        int wins = (int) trades.stream().filter(t -> t.netPnl().signum() > 0).count();
        int losses = (int) trades.stream().filter(t -> t.netPnl().signum() < 0).count();
        BigDecimal profit = trades.stream().filter(t -> t.netPnl().signum() > 0)
                .map(Trade::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loss = trades.stream().filter(t -> t.netPnl().signum() < 0)
                .map(Trade::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add).abs();

        // null representa un profit factor infinito: hubo ganancias y ninguna perdida.
        BigDecimal profitFactor = loss.signum() == 0
                ? (profit.signum() > 0 ? null : BigDecimal.ZERO)
                : profit.divide(loss, 4, RoundingMode.HALF_UP);

        return new BacktestResult(initialCapital, capital, netPnl, returnPct, maxDd,
                trades.size(), wins, losses, profitFactor, List.copyOf(trades));
    }
}

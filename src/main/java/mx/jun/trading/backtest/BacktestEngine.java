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

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital) {
        return run(candles, initialCapital, FEE, SLIPPAGE);
    }

    public BacktestResult run(List<Candle> candles, BigDecimal initialCapital,
                              BigDecimal feeRate, BigDecimal slippageRate) {
        if (candles == null || candles.size() < 51) throw new IllegalArgumentException("Se requieren al menos 51 velas");
        if (initialCapital == null || initialCapital.signum() <= 0) throw new IllegalArgumentException("Capital invalido");

        var strategy = new EmaRegimeVolatilityStrategy();
        var trades = new ArrayList<Trade>();
        BigDecimal capital = initialCapital, peak = capital, maxDd = BigDecimal.ZERO;
        BigDecimal entry = null, qty = null;
        Candle entryCandle = null;

        for (int i = 50; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            var signal = strategy.evaluate(candles.subList(0, i + 1));

            if (entry == null && signal == EmaRegimeVolatilityStrategy.Signal.BUY) {
                entry = candle.close().multiply(BigDecimal.ONE.add(slippageRate));
                qty = capital.divide(entry, 10, RoundingMode.DOWN);
                if (qty.signum() == 0) { entry = null; qty = null; continue; }
                entryCandle = candle;
                capital = capital.subtract(entry.multiply(qty).multiply(feeRate));
            } else if (entry != null && signal == EmaRegimeVolatilityStrategy.Signal.SELL) {
                BigDecimal exit = candle.close().multiply(BigDecimal.ONE.subtract(slippageRate));
                BigDecimal gross = exit.subtract(entry).multiply(qty);
                BigDecimal exitFee = exit.multiply(qty).multiply(feeRate);
                BigDecimal fees = entry.multiply(qty).multiply(feeRate).add(exitFee);
                BigDecimal net = gross.subtract(fees);
                capital = capital.add(gross).subtract(exitFee);
                trades.add(new Trade(entryCandle.timestamp(), candle.timestamp(), entry, exit, qty, gross, fees, net));
                entry = null; qty = null; entryCandle = null;
            }

            BigDecimal equity = capital;
            if (entry != null) equity = capital.add(candle.close().subtract(entry).multiply(qty));
            if (equity.compareTo(peak) > 0) peak = equity;
            BigDecimal dd = peak.signum() == 0 ? BigDecimal.ZERO :
                    peak.subtract(equity).divide(peak, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            if (dd.compareTo(maxDd) > 0) maxDd = dd;
        }

        if (entry != null) {
            Candle last = candles.get(candles.size() - 1);
            BigDecimal exit = last.close().multiply(BigDecimal.ONE.subtract(slippageRate));
            BigDecimal gross = exit.subtract(entry).multiply(qty);
            BigDecimal exitFee = exit.multiply(qty).multiply(feeRate);
            BigDecimal fees = entry.multiply(qty).multiply(feeRate).add(exitFee);
            BigDecimal net = gross.subtract(fees);
            capital = capital.add(gross).subtract(exitFee);
            trades.add(new Trade(entryCandle.timestamp(), last.timestamp(), entry, exit, qty, gross, fees, net));
        }

        BigDecimal netPnl = capital.subtract(initialCapital);
        BigDecimal returnPct = netPnl.divide(initialCapital, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        int wins = (int) trades.stream().filter(t -> t.netPnl().signum() > 0).count();
        int losses = (int) trades.stream().filter(t -> t.netPnl().signum() < 0).count();
        BigDecimal profit = trades.stream().filter(t -> t.netPnl().signum() > 0).map(Trade::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loss = trades.stream().filter(t -> t.netPnl().signum() < 0).map(Trade::netPnl).reduce(BigDecimal.ZERO, BigDecimal::add).abs();

        // BigDecimal no admite infinito. null representa un profit factor infinito
        // cuando hay ganancias pero ninguna operación perdedora.
        BigDecimal pf = loss.signum() == 0
                ? (profit.signum() > 0 ? null : BigDecimal.ZERO)
                : profit.divide(loss, 4, RoundingMode.HALF_UP);

        return new BacktestResult(initialCapital, capital, netPnl, returnPct, maxDd,
                trades.size(), wins, losses, pf, List.copyOf(trades));
    }
}
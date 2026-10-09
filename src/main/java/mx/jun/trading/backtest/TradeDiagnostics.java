package mx.jun.trading.backtest;

import mx.jun.trading.indicator.AtrCalculator;
import mx.jun.trading.indicator.EmaCalculator;
import mx.jun.trading.market.Candle;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a per-trade diagnostic report. These metrics explain the market context;
 * they do not alter entries, exits, or the backtest result.
 */
public class TradeDiagnostics {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String HEADER = String.join(",",
            "entryTime", "exitTime", "exitReason", "entrySignalClose",
            "ema20AtSignal", "ema50AtSignal", "ema20SlopePct", "ema50SlopePct",
            "atrPct", "closeVsEma50Pct", "return3CandlesPct", "return12CandlesPct",
            "maxAdverseExcursionPct", "maxFavorableExcursionPct",
            "grossPnl", "fees", "netPnl");

    private final EmaCalculator emaCalculator = new EmaCalculator();
    private final AtrCalculator atrCalculator = new AtrCalculator();

    public Path save(Path datasetPath, List<Candle> candles, List<Trade> trades) {
        if (candles == null || candles.size() < 51) {
            throw new IllegalArgumentException("Se requieren al menos 51 velas para diagnosticar operaciones");
        }
        List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
        List<BigDecimal> ema20 = emaCalculator.calculate(closes, 20);
        List<BigDecimal> ema50 = emaCalculator.calculate(closes, 50);
        List<BigDecimal> atr14 = atrCalculator.calculate(candles, 14);

        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (Trade trade : trades) {
            int entryIndex = indexOf(candles, trade.entryTime());
            int exitIndex = indexOf(candles, trade.exitTime());
            if (entryIndex < 1 || exitIndex < entryIndex) {
                throw new IllegalArgumentException("No se pudo ubicar temporalmente la operación " + trade.entryTime());
            }

            // The strategy evaluates the previous candle and enters at this candle's open.
            int signalIndex = entryIndex - 1;
            if (signalIndex < 50) {
                throw new IllegalArgumentException("No hay suficientes velas previas para diagnosticar " + trade.entryTime());
            }
            BigDecimal e20 = ema20.get(signalIndex - 19);
            BigDecimal e20Previous = ema20.get(signalIndex - 20);
            BigDecimal e50 = ema50.get(signalIndex - 49);
            BigDecimal e50Previous = ema50.get(signalIndex - 50);
            BigDecimal signalClose = candles.get(signalIndex).close();
            BigDecimal atrPct = percent(atr14.get(signalIndex - 14), signalClose);
            BigDecimal closeVsEma50Pct = percent(signalClose.subtract(e50), e50);
            BigDecimal return3 = percent(signalClose.subtract(candles.get(signalIndex - 3).close()),
                    candles.get(signalIndex - 3).close());
            BigDecimal return12 = percent(signalClose.subtract(candles.get(signalIndex - 12).close()),
                    candles.get(signalIndex - 12).close());

            BigDecimal lowest = candles.get(entryIndex).low();
            BigDecimal highest = candles.get(entryIndex).high();
            for (int i = entryIndex + 1; i <= exitIndex; i++) {
                lowest = lowest.min(candles.get(i).low());
                highest = highest.max(candles.get(i).high());
            }
            BigDecimal adverseExcursion = percent(lowest.subtract(trade.entryPrice()), trade.entryPrice());
            BigDecimal favorableExcursion = percent(highest.subtract(trade.entryPrice()), trade.entryPrice());

            append(csv, trade.entryTime().toString());
            append(csv, trade.exitTime().toString());
            append(csv, trade.exitReason());
            append(csv, signalClose);
            append(csv, e20);
            append(csv, e50);
            append(csv, percent(e20.subtract(e20Previous), e20Previous));
            append(csv, percent(e50.subtract(e50Previous), e50Previous));
            append(csv, atrPct);
            append(csv, closeVsEma50Pct);
            append(csv, return3);
            append(csv, return12);
            append(csv, adverseExcursion);
            append(csv, favorableExcursion);
            append(csv, trade.grossPnl());
            append(csv, trade.fees());
            csv.append(trade.netPnl().toPlainString()).append('\n');
        }

        String filename = datasetPath.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) filename = filename.substring(0, dot);
        Path target = datasetPath.resolveSibling(filename + "_diagnostics.csv");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, csv.toString(), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el informe de diagnóstico: " + target, e);
        }
    }

    private static int indexOf(List<Candle> candles, java.time.Instant timestamp) {
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).timestamp().equals(timestamp)) return i;
        }
        return -1;
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, 10, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    private static void append(StringBuilder csv, String value) {
        csv.append(value).append(',');
    }

    private static void append(StringBuilder csv, BigDecimal value) {
        append(csv, value.toPlainString());
    }
}

package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeDiagnosticsTest {
    @TempDir
    Path tempDir;

    @Test
    void guardaIndicadoresExcursionesYMotivoDeSalidaPorOperacion() throws Exception {
        List<Candle> candles = sampleCandles(180);
        BacktestResult result = new BacktestEngine().run(candles, new BigDecimal("20"));
        assertFalse(result.trades().isEmpty());

        Path dataset = tempDir.resolve("ETH_15m.csv");
        Path report = new TradeDiagnostics().save(dataset, candles, result.trades());

        assertTrue(Files.exists(report));
        List<String> lines = Files.readAllLines(report);
        assertTrue(lines.getFirst().contains("exitReason"));
        assertTrue(lines.getFirst().contains("atrPct"));
        assertTrue(lines.getFirst().contains("maxAdverseExcursionPct"));
        assertEquals(result.trades().size() + 1, lines.size());
        assertTrue(lines.get(1).split(",", -1).length >= 17);
        assertTrue(result.trades().stream().allMatch(t -> t.exitReason() != null && !t.exitReason().isBlank()));
    }

    @Test
    void guardaMotivoDeSalidaEnCsvDeOperaciones() throws Exception {
        Candle candle = new Candle(Instant.parse("2026-08-01T00:00:00Z"),
                new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("98"),
                new BigDecimal("101"), BigDecimal.TEN);
        Trade trade = new Trade(candle.timestamp(), candle.timestamp(),
                new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("0.1"),
                new BigDecimal("-0.1"), new BigDecimal("0.01"), new BigDecimal("-0.11"),
                "STOP_LOSS");
        CandleCsvStore store = new CandleCsvStore();
        Path dataset = tempDir.resolve("ETH.csv");
        Path tradesPath = store.saveTrades(dataset.toString(), List.of(trade));

        List<String> lines = Files.readAllLines(tradesPath);
        assertTrue(lines.getFirst().endsWith("exitReason"));
        assertTrue(lines.get(1).endsWith("STOP_LOSS"));
    }

    private List<Candle> sampleCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        BigDecimal previous = new BigDecimal("3500");
        for (int i = 0; i < count; i++) {
            BigDecimal open = previous;
            BigDecimal change = i < 80 ? new BigDecimal("-1") : new BigDecimal("3");
            BigDecimal close = open.add(change);
            BigDecimal high = open.max(close).add(new BigDecimal("3"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("3"));
            candles.add(new Candle(start.plusSeconds(i * 900L), open, high, low, close, BigDecimal.TEN));
            previous = close;
        }
        return candles;
    }
}

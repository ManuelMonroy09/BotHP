package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleCsvStoreTest {
    @TempDir
    Path tempDir;

    private final CandleCsvStore store = new CandleCsvStore();

    @Test
    void cargaDatasetCsvOrdenadoYValido() throws Exception {
        Path csv = tempDir.resolve("eth_15m.csv");
        Files.writeString(csv, validCsv(51));

        List<Candle> candles = store.load(csv);

        assertEquals(51, candles.size());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), candles.get(0).timestamp());
        assertEquals(Instant.parse("2026-08-01T12:30:00Z"), candles.get(50).timestamp());
        assertEquals(new BigDecimal("100"), candles.get(0).close());
    }

    @Test
    void rechazaDatasetConMarcasDeTiempoDuplicadas() throws Exception {
        Path csv = tempDir.resolve("duplicate.csv");
        String header = "timestamp,open,high,low,close,volume\n";
        String candle = "2026-08-01T00:00:00Z,100,102,99,101,10\n";
        Files.writeString(csv, header + candle.repeat(51).stripTrailing());

        assertThrows(IllegalArgumentException.class, () -> store.load(csv));
    }

    private String validCsv(int count) {
        StringBuilder csv = new StringBuilder("timestamp,open,high,low,close,volume\n");
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            Instant timestamp = start.plusSeconds(i * 900L);
            csv.append(timestamp).append(",100,102,99,100,10\n");
        }
        return csv.toString();
    }
}

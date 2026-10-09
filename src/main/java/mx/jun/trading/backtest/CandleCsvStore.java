package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Guarda datasets de velas y operaciones para poder reproducir y auditar backtests.
 */
@Component
public class CandleCsvStore {
    private static final String CANDLE_HEADER = "timestamp,open,high,low,close,volume";
    private static final String TRADE_HEADER =
            "entryTime,exitTime,entryPrice,exitPrice,quantity,grossPnl,fees,netPnl";

    public Path saveSnapshot(String coin, String interval, List<Candle> candles) {
        if (candles == null || candles.size() < 2) {
            throw new IllegalArgumentException("Se necesitan al menos dos velas para guardar un dataset");
        }
        String safeCoin = safeFilePart(coin);
        String safeInterval = safeFilePart(interval);
        Candle first = candles.get(0);
        Candle last = candles.get(candles.size() - 1);
        String filename = "%s_%s_%d_%d.csv".formatted(safeCoin, safeInterval,
                first.timestamp().toEpochMilli(), last.timestamp().toEpochMilli());
        Path path = Path.of("data", "backtests", filename);
        writeCandles(path, candles);
        return path;
    }

    public List<Candle> load(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() < 2 || !CANDLE_HEADER.equals(lines.get(0))) {
                throw new IllegalArgumentException("CSV inválido o sin velas. Encabezado esperado: " + CANDLE_HEADER);
            }
            List<Candle> candles = new ArrayList<>(lines.size() - 1);
            Instant previousTimestamp = null;
            for (int i = 1; i < lines.size(); i++) {
                String[] fields = lines.get(i).split(",", -1);
                if (fields.length != 6) {
                    throw new IllegalArgumentException("Número de columnas inválido en línea " + (i + 1));
                }
                Candle candle = new Candle(Instant.parse(fields[0]), new BigDecimal(fields[1]),
                        new BigDecimal(fields[2]), new BigDecimal(fields[3]),
                        new BigDecimal(fields[4]), new BigDecimal(fields[5]));
                if (previousTimestamp != null && !candle.timestamp().isAfter(previousTimestamp)) {
                    throw new IllegalArgumentException("Las marcas de tiempo deben ser estrictamente crecientes");
                }
                validate(candle, i + 1);
                candles.add(candle);
                previousTimestamp = candle.timestamp();
            }
            if (candles.size() < 51) {
                throw new IllegalArgumentException("El dataset debe contener al menos 51 velas");
            }
            return List.copyOf(candles);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el dataset CSV: " + path.toAbsolutePath(), e);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            throw new IllegalArgumentException("El dataset CSV contiene valores inválidos: " + path, e);
        }
    }

    public Path saveTrades(String datasetPath, List<Trade> trades) {
        Path source = Path.of(datasetPath);
        Path target = source.resolveSibling(removeExtension(source.getFileName().toString()) + "_trades.csv");
        StringBuilder csv = new StringBuilder(TRADE_HEADER).append('\n');
        for (Trade trade : trades) {
            csv.append(trade.entryTime()).append(',')
                    .append(trade.exitTime()).append(',')
                    .append(trade.entryPrice().toPlainString()).append(',')
                    .append(trade.exitPrice().toPlainString()).append(',')
                    .append(trade.quantity().toPlainString()).append(',')
                    .append(trade.grossPnl().toPlainString()).append(',')
                    .append(trade.fees().toPlainString()).append(',')
                    .append(trade.netPnl().toPlainString()).append('\n');
        }
        writeText(target, csv.toString());
        return target;
    }

    public String sha256(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo calcular la huella del dataset: " + path, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible en esta JVM", e);
        }
    }

    private static void writeCandles(Path path, List<Candle> candles) {
        StringBuilder csv = new StringBuilder(CANDLE_HEADER).append('\n');
        Instant previousTimestamp = null;
        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            validate(candle, i + 2);
            if (previousTimestamp != null && !candle.timestamp().isAfter(previousTimestamp)) {
                throw new IllegalArgumentException("Las marcas de tiempo deben ser estrictamente crecientes");
            }
            csv.append(candle.timestamp()).append(',')
                    .append(candle.open().toPlainString()).append(',')
                    .append(candle.high().toPlainString()).append(',')
                    .append(candle.low().toPlainString()).append(',')
                    .append(candle.close().toPlainString()).append(',')
                    .append(candle.volume().toPlainString()).append('\n');
            previousTimestamp = candle.timestamp();
        }
        writeText(path, csv.toString());
    }

    private static void validate(Candle c, int line) {
        if (c.timestamp() == null || c.open() == null || c.high() == null || c.low() == null
                || c.close() == null || c.volume() == null) {
            throw new IllegalArgumentException("Campo vacío en línea " + line);
        }
        if (c.open().signum() <= 0 || c.high().signum() <= 0 || c.low().signum() <= 0
                || c.close().signum() <= 0 || c.volume().signum() < 0
                || c.high().compareTo(c.low()) < 0 || c.high().compareTo(c.open()) < 0
                || c.high().compareTo(c.close()) < 0 || c.low().compareTo(c.open()) > 0
                || c.low().compareTo(c.close()) > 0) {
            throw new IllegalArgumentException("Vela OHLCV inválida en línea " + line);
        }
    }

    private static void writeText(Path path, String content) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el archivo: " + path.toAbsolutePath(), e);
        }
    }

    private static String safeFilePart(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Activo e intervalo no pueden estar vacíos");
        }
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}

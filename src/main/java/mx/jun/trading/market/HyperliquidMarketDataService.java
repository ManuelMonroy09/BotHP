package mx.jun.trading.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Cliente de solo lectura para las velas públicas de Hyperliquid.
 * No firma ni envía órdenes.
 */
@Service
public class HyperliquidMarketDataService {
    private static final Map<String, Duration> INTERVALS = Map.ofEntries(
            Map.entry("1m", Duration.ofMinutes(1)),
            Map.entry("3m", Duration.ofMinutes(3)),
            Map.entry("5m", Duration.ofMinutes(5)),
            Map.entry("15m", Duration.ofMinutes(15)),
            Map.entry("30m", Duration.ofMinutes(30)),
            Map.entry("1h", Duration.ofHours(1)),
            Map.entry("2h", Duration.ofHours(2)),
            Map.entry("4h", Duration.ofHours(4)),
            Map.entry("8h", Duration.ofHours(8)),
            Map.entry("12h", Duration.ofHours(12)),
            Map.entry("1d", Duration.ofDays(1)),
            Map.entry("3d", Duration.ofDays(3)),
            Map.entry("1w", Duration.ofDays(7))
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String infoUrl;

    @Autowired
    public HyperliquidMarketDataService(
            @Value("${hyperliquid.info-url:https://api.hyperliquid.xyz/info}") String infoUrl) {
        this(RestClient.builder().build(), new ObjectMapper(), infoUrl);
    }

    // Constructor separado para probar respuestas HTTP controladas sin llamar al mercado real.
    HyperliquidMarketDataService(RestClient restClient, ObjectMapper objectMapper, String infoUrl) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.infoUrl = infoUrl;
    }

    public List<Candle> getCandles(String coin, String interval, int count) {
        if (coin == null || coin.isBlank()) {
            throw new IllegalArgumentException("El activo no puede estar vacío");
        }
        Duration intervalDuration = INTERVALS.get(interval);
        if (intervalDuration == null) {
            throw new IllegalArgumentException("Intervalo no soportado: " + interval);
        }
        if (count < 51 || count > 5000) {
            throw new IllegalArgumentException("La cantidad de velas debe estar entre 51 y 5000");
        }

        long endTime = Instant.now().toEpochMilli();
        long startTime = Instant.ofEpochMilli(endTime).minus(intervalDuration.multipliedBy(count + 2L)).toEpochMilli();
        Map<String, Object> payload = Map.of(
                "type", "candleSnapshot",
                "req", Map.of(
                        "coin", coin.trim().toUpperCase(),
                        "interval", interval,
                        "startTime", startTime,
                        "endTime", endTime
                )
        );

        String response = restClient.post()
                .uri(infoUrl)
                .body(payload)
                .retrieve()
                .body(String.class);

        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Hyperliquid devolvió una respuesta vacía");
        }

        try {
            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                throw new IllegalStateException("Respuesta de velas con formato inesperado");
            }

            List<Candle> candles = new ArrayList<>();
            for (JsonNode node : root) {
                Candle candle = new Candle(
                        Instant.ofEpochMilli(requiredLong(node, "t")),
                        requiredDecimal(node, "o"),
                        requiredDecimal(node, "h"),
                        requiredDecimal(node, "l"),
                        requiredDecimal(node, "c"),
                        requiredDecimal(node, "v")
                );
                validate(candle);
                candles.add(candle);
            }

            candles.sort(Comparator.comparing(Candle::timestamp));
            List<Candle> unique = new ArrayList<>();
            Instant now = Instant.ofEpochMilli(endTime);
            for (Candle candle : candles) {
                // Excluir la vela en curso para evitar señales basadas en datos que aún cambian.
                if (candle.timestamp().plus(intervalDuration).isAfter(now)) {
                    continue;
                }
                if (unique.isEmpty() || !unique.get(unique.size() - 1).timestamp().equals(candle.timestamp())) {
                    unique.add(candle);
                }
            }
            if (unique.size() > count) {
                unique = new ArrayList<>(unique.subList(unique.size() - count, unique.size()));
            }

            if (unique.size() < 51) {
                throw new IllegalStateException(
                        "Hyperliquid devolvió solo " + unique.size() + " velas válidas; se necesitan al menos 51");
            }
            return List.copyOf(unique);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("No se pudieron interpretar las velas de Hyperliquid", e);
        }
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalStateException("Falta el campo numérico " + field + " en una vela");
        }
        return value.asLong();
    }

    private static BigDecimal requiredDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Falta el campo " + field + " en una vela");
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Valor numérico inválido en campo " + field, e);
        }
    }

    private static void validate(Candle candle) {
        if (candle.open().signum() <= 0 || candle.high().signum() <= 0
                || candle.low().signum() <= 0 || candle.close().signum() <= 0
                || candle.volume().signum() < 0) {
            throw new IllegalStateException("Vela con precios no positivos o volumen negativo");
        }
        if (candle.high().compareTo(candle.low()) < 0
                || candle.high().compareTo(candle.open()) < 0
                || candle.high().compareTo(candle.close()) < 0
                || candle.low().compareTo(candle.open()) > 0
                || candle.low().compareTo(candle.close()) > 0) {
            throw new IllegalStateException("Vela con valores OHLC inconsistentes");
        }
    }
}
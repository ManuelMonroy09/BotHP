package mx.jun.trading.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
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
    private final Clock clock;

    @Autowired
    public HyperliquidMarketDataService(
            @Value("${hyperliquid.info-url:https://api.hyperliquid.xyz/info}") String infoUrl) {
        this(RestClient.builder().build(), new ObjectMapper(), infoUrl, Clock.systemUTC());
    }

    // Constructor separado para probar respuestas HTTP controladas sin llamar al mercado real.
    HyperliquidMarketDataService(RestClient restClient, ObjectMapper objectMapper, String infoUrl) {
        this(restClient, objectMapper, infoUrl, Clock.systemUTC());
    }

    HyperliquidMarketDataService(RestClient restClient, ObjectMapper objectMapper, String infoUrl, Clock clock) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.infoUrl = infoUrl;
        this.clock = clock;
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

        long endTime = clock.millis();
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
            Candle previousResponseCandle = null;
            for (JsonNode node : root) {
                if (!node.isObject()) {
                    throw new IllegalStateException("La respuesta contiene una vela con formato inválido");
                }
                String responseCoin = requiredText(node, "s");
                String responseInterval = requiredText(node, "i");
                String expectedCoin = coin.trim().toUpperCase(java.util.Locale.ROOT);
                if (!expectedCoin.equals(responseCoin) || !interval.equals(responseInterval)) {
                    throw new IllegalStateException("Hyperliquid devolvió una vela de otro activo o intervalo");
                }
                Candle candle = new Candle(
                        Instant.ofEpochMilli(requiredLong(node, "t")),
                        requiredDecimal(node, "o"),
                        requiredDecimal(node, "h"),
                        requiredDecimal(node, "l"),
                        requiredDecimal(node, "c"),
                        requiredDecimal(node, "v")
                );
                long closeTime = requiredLong(node, "T");
                long expectedCloseTime = candle.timestamp().plus(intervalDuration).toEpochMilli() - 1;
                if (closeTime != expectedCloseTime) {
                    throw new IllegalStateException("Timestamp de cierre inconsistente en una vela");
                }
                validate(candle);
                if (previousResponseCandle != null
                        && !candle.timestamp().isAfter(previousResponseCandle.timestamp())) {
                    throw new IllegalStateException("Hyperliquid devolvió velas desordenadas o duplicadas");
                }
                candles.add(candle);
                previousResponseCandle = candle;
            }

            List<Candle> unique = new ArrayList<>();
            Instant now = clock.instant();
            for (Candle candle : candles) {
                // Excluir la vela en curso para evitar señales basadas en datos que aún cambian.
                if (candle.timestamp().plus(intervalDuration).isAfter(now)) {
                    continue;
                }
                if (!unique.isEmpty()) {
                    Instant previous = unique.get(unique.size() - 1).timestamp();
                    if (!candle.timestamp().isAfter(previous)) {
                        throw new IllegalStateException("Hyperliquid devolvió velas desordenadas o duplicadas");
                    }
                    if (!candle.timestamp().equals(previous.plus(intervalDuration))) {
                        throw new IllegalStateException("Hyperliquid devolvió un hueco entre velas cerradas");
                    }
                }
                unique.add(candle);
            }
            if (unique.size() > count) {
                unique = new ArrayList<>(unique.subList(unique.size() - count, unique.size()));
            }

            if (unique.size() < 51) {
                throw new IllegalStateException(
                        "Hyperliquid devolvió solo " + unique.size() + " velas válidas; se necesitan al menos 51");
            }
            Instant lastCandleClose = unique.getLast().timestamp().plus(intervalDuration);
            if (Duration.between(lastCandleClose, now).compareTo(intervalDuration.multipliedBy(2)) > 0) {
                throw new IllegalStateException("Los datos de Hyperliquid están obsoletos");
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

    /** Reads the public mid-price map; it never signs or sends an order. */
    public BigDecimal getMidPrice(String coin) {
        if (coin == null || coin.isBlank()) {
            throw new IllegalArgumentException("El activo no puede estar vacío");
        }
        String normalizedCoin = coin.trim().toUpperCase(java.util.Locale.ROOT);
        String response = restClient.post()
                .uri(infoUrl)
                .body(Map.of("type", "allMids"))
                .retrieve()
                .body(String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Hyperliquid devolvió mids vacío");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode value = root.isObject() ? root.get(normalizedCoin) : null;
            if (value == null || value.isNull()) {
                throw new IllegalStateException("Hyperliquid no devolvió mid para " + normalizedCoin);
            }
            BigDecimal mid = new BigDecimal(value.asText());
            if (mid.signum() <= 0) {
                throw new IllegalStateException("Hyperliquid devolvió un mid no positivo para " + normalizedCoin);
            }
            return mid;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Mid inválido para " + normalizedCoin, e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException("No se pudo interpretar el mid de Hyperliquid", e);
        }
    }

    /** Reads public perpetual metadata (asset id, size precision and leverage ceiling). */
    public PerpetualMarketSpec getPerpetualMarketSpec(String coin) {
        if (coin == null || coin.isBlank()) {
            throw new IllegalArgumentException("El activo no puede estar vacío");
        }
        String normalizedCoin = coin.trim().toUpperCase(java.util.Locale.ROOT);
        String response = restClient.post().uri(infoUrl).body(Map.of("type", "meta"))
                .retrieve().body(String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Hyperliquid devolvió metadata vacía");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode universe = root.path("universe");
            if (!universe.isArray()) {
                throw new IllegalStateException("Metadata perpetua con formato inesperado");
            }
            for (int i = 0; i < universe.size(); i++) {
                JsonNode market = universe.get(i);
                if (normalizedCoin.equals(requiredText(market, "name"))) {
                    int sizeDecimals = requiredInt(market, "szDecimals");
                    int maxLeverage = requiredInt(market, "maxLeverage");
                    return new PerpetualMarketSpec(normalizedCoin, i, sizeDecimals, maxLeverage);
                }
            }
            throw new IllegalStateException("Hyperliquid no publica metadata perpetua para " + normalizedCoin);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Metadata perpetua inválida para " + normalizedCoin, e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException("No se pudo interpretar metadata perpetua", e);
        }
    }

    /** Finds a canonical spot pair and resolves its API market name and order precision. */
    public SpotMarketSpec getSpotMarketSpec(String baseAsset, String quoteAsset) {
        if (baseAsset == null || baseAsset.isBlank() || quoteAsset == null || quoteAsset.isBlank()) {
            throw new IllegalArgumentException("Base and quote assets are required");
        }
        String base = baseAsset.trim().toUpperCase(java.util.Locale.ROOT);
        String quote = quoteAsset.trim().toUpperCase(java.util.Locale.ROOT);
        String response = restClient.post().uri(infoUrl).body(Map.of("type", "spotMeta"))
                .retrieve().body(String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Hyperliquid devolvió metadata spot vacía");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode tokens = root.path("tokens");
            JsonNode universe = root.path("universe");
            if (!tokens.isArray() || !universe.isArray()) {
                throw new IllegalStateException("Metadata spot con formato inesperado");
            }
            JsonNode baseToken = findSpotToken(tokens, base);
            JsonNode quoteToken = findSpotToken(tokens, quote);
            if (baseToken == null || quoteToken == null) {
                throw new IllegalStateException("Hyperliquid no publica uno de los tokens " + base + "/" + quote);
            }
            int baseTokenIndex = requiredInt(baseToken, "index");
            int quoteTokenIndex = requiredInt(quoteToken, "index");
            int sizeDecimals = requiredInt(baseToken, "szDecimals");
            for (JsonNode market : universe) {
                JsonNode marketTokens = market.path("tokens");
                if (marketTokens.isArray() && marketTokens.size() == 2
                        && marketTokens.get(0).asInt(-1) == baseTokenIndex
                        && marketTokens.get(1).asInt(-1) == quoteTokenIndex) {
                    int index = requiredInt(market, "index");
                    String name = requiredText(market, "name");
                    return new SpotMarketSpec(base, quote, name, 10_000 + index, sizeDecimals);
                }
            }
            throw new IllegalStateException("No existe el par spot " + base + "/" + quote);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Metadata spot inválida para " + base + "/" + quote, e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException("No se pudo interpretar metadata spot", e);
        }
    }

    private static JsonNode findSpotToken(JsonNode tokens, String tokenName) {
        for (JsonNode token : tokens) {
            if (tokenName.equals(requiredText(token, "name"))) {
                return token;
            }
        }
        return null;
    }

    /** Fetches public historical funding rates in bounded weekly windows. */
    public List<FundingRate> getFundingHistory(String coin, Instant start, Instant end) {
        if (coin == null || coin.isBlank() || start == null || end == null || end.isBefore(start)) {
            throw new IllegalArgumentException("Funding coin and a valid time range are required");
        }
        String normalizedCoin = coin.trim().toUpperCase(java.util.Locale.ROOT);
        long cursor = start.toEpochMilli();
        long endMillis = end.toEpochMilli();
        long windowMillis = Duration.ofDays(14).toMillis();
        List<FundingRate> rates = new ArrayList<>();
        while (cursor <= endMillis) {
            long windowEnd = Math.min(endMillis, cursor + windowMillis - 1);
            String response = postFundingWindow(normalizedCoin, cursor, windowEnd);
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Hyperliquid devolvió funding history vacío");
            }
            try {
                JsonNode root = objectMapper.readTree(response);
                if (!root.isArray()) {
                    throw new IllegalStateException("Funding history tiene un formato inesperado");
                }
                for (JsonNode node : root) {
                    String responseCoin = requiredText(node, "coin");
                    if (!normalizedCoin.equals(responseCoin)) {
                        throw new IllegalStateException("Funding history contiene otro activo");
                    }
                    long timestamp = requiredLong(node, "time");
                    if (timestamp < cursor || timestamp > windowEnd) {
                        throw new IllegalStateException("Funding history devolvió un evento fuera del rango pedido");
                    }
                    rates.add(new FundingRate(Instant.ofEpochMilli(timestamp), requiredDecimal(node, "fundingRate")));
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof IllegalStateException stateException) throw stateException;
                throw new IllegalStateException("No se pudo interpretar funding history", e);
            }
            cursor = windowEnd + 1;
            if (cursor <= endMillis) pause(2_500);
        }
        rates.sort(java.util.Comparator.comparing(FundingRate::timestamp));
        for (int i = 1; i < rates.size(); i++) {
            if (!rates.get(i).timestamp().isAfter(rates.get(i - 1).timestamp())) {
                throw new IllegalStateException("Funding history contiene eventos duplicados o desordenados");
            }
        }
        return List.copyOf(rates);
    }

    private String postFundingWindow(String coin, long startTime, long endTime) {
        long retryDelayMillis = 2_000;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                return restClient.post()
                        .uri(infoUrl)
                        .body(Map.of("type", "fundingHistory", "coin", coin,
                                "startTime", startTime, "endTime", endTime))
                        .retrieve()
                        .body(String.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt == 5) {
                    throw new IllegalStateException("Hyperliquid sigue limitando funding history tras varios reintentos", e);
                }
                long waitMillis = retryDelayMillis;
                String retryAfter = e.getResponseHeaders() == null ? null
                        : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                if (retryAfter != null) {
                    try {
                        waitMillis = Math.max(waitMillis, Long.parseLong(retryAfter.trim()) * 1_000);
                    } catch (NumberFormatException ignored) {
                        // Retry-After may be an HTTP date rather than a number of seconds.
                    }
                }
                pause(Math.min(waitMillis, 60_000));
                retryDelayMillis = Math.min(retryDelayMillis * 2, 30_000);
            }
        }
        throw new IllegalStateException("No se pudo consultar funding history");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la descarga de funding history", e);
        }
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException("Falta el campo numérico " + field + " en una vela");
        }
        return value.asLong();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalStateException("Campo numérico inválido: " + field);
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Falta el campo de texto " + field + " en una vela");
        }
        return value.asText();
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

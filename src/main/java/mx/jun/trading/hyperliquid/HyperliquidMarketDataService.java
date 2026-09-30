package mx.jun.trading.hyperliquid;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import mx.jun.trading.market.Candle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class HyperliquidMarketDataService {
    private static final String INFO_ENDPOINT = "https://api.hyperliquid.xyz/info";
    private final RestClient client = RestClient.builder().baseUrl(INFO_ENDPOINT).build();

    public List<Candle> getCandles(String coin, String interval, int count) {
        if (count <= 0 || count > 5000) {
            throw new IllegalArgumentException("count debe estar entre 1 y 5000");
        }

        long intervalMillis = intervalMillis(interval);
        long endTime = System.currentTimeMillis();
        long startTime = endTime - intervalMillis * count;

        var request = new CandleSnapshotRequest(
                "candleSnapshot",
                new CandleRequest(coin, interval, startTime, endTime)
        );

        JsonNode response = client.post()
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.isArray()) {
            throw new IllegalStateException("Hyperliquid devolvio una respuesta de velas invalida");
        }

        List<Candle> candles = new ArrayList<>();
        for (JsonNode item : response) {
            candles.add(new Candle(
                    Instant.ofEpochMilli(item.get("t").asLong()),
                    new BigDecimal(item.get("o").asText()),
                    new BigDecimal(item.get("h").asText()),
                    new BigDecimal(item.get("l").asText()),
                    new BigDecimal(item.get("c").asText()),
                    new BigDecimal(item.get("v").asText())
            ));
        }

        return candles;
    }

    private long intervalMillis(String interval) {
        return switch (interval) {
            case "1m" -> 60_000L;
            case "3m" -> 180_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "30m" -> 1_800_000L;
            case "1h" -> 3_600_000L;
            case "2h" -> 7_200_000L;
            case "4h" -> 14_400_000L;
            case "8h" -> 28_800_000L;
            case "12h" -> 43_200_000L;
            case "1d" -> 86_400_000L;
            case "3d" -> 259_200_000L;
            case "1w" -> 604_800_000L;
            case "1M" -> 2_592_000_000L;
            default -> throw new IllegalArgumentException("Intervalo no soportado: " + interval);
        };
    }

    private record CandleSnapshotRequest(String type, CandleRequest req) {}
    private record CandleRequest(String coin, String interval, long startTime, long endTime) {}
}

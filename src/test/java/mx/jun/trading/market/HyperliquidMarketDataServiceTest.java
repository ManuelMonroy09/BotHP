package mx.jun.trading.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class HyperliquidMarketDataServiceTest {
    private static final String INFO_URL = "https://example.test/info";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestsPublicEthCandlesAndReturnsSortedUniqueClosedCandles() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        Instant now = Instant.now();
        Instant first = now.minusSeconds(24 * 60 * 60L);
        ArrayNode response = mapper.createArrayNode();

        for (int i = 0; i < 60; i++) {
            response.add(candle(first.plusSeconds(i * 15L * 60).toEpochMilli()));
        }
        // Duplicado deliberado: el cliente debe conservar una sola vela por timestamp.
        response.add(candle(first.toEpochMilli()));
        // Vela aún abierta: debe excluirse para no generar señales con datos cambiantes.
        response.add(candle(now.minusSeconds(5 * 60L).toEpochMilli()));

        server.expect(requestTo(INFO_URL))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("candleSnapshot"))
                .andExpect(jsonPath("$.req.coin").value("ETH"))
                .andExpect(jsonPath("$.req.interval").value("15m"))
                .andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(client, mapper, INFO_URL);
        List<Candle> candles = service.getCandles("eth", "15m", 51);

        assertEquals(51, candles.size());
        assertEquals(first.plusSeconds(9 * 15L * 60), candles.getFirst().timestamp());
        assertEquals(first.plusSeconds(59 * 15L * 60), candles.getLast().timestamp());
        assertTrue(candles.get(0).timestamp().isBefore(candles.getLast().timestamp()));
        server.verify();
    }

    @Test
    void rejectsUnsupportedIntervalWithoutCallingHyperliquid() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(builder.build(), mapper, INFO_URL);

        assertThrows(IllegalArgumentException.class, () -> service.getCandles("ETH", "2m", 100));
        server.verify();
    }

    @Test
    void rejectsCandlesWithInconsistentOhlcValues() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        ArrayNode response = mapper.createArrayNode();
        ObjectNode invalid = candle(Instant.now().minusSeconds(60 * 60L).toEpochMilli());
        invalid.put("h", "90");
        response.add(invalid);

        server.expect(requestTo(INFO_URL))
                .andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(client, mapper, INFO_URL);

        assertThrows(IllegalStateException.class, () -> service.getCandles("ETH", "15m", 51));
        server.verify();
    }

    private ObjectNode candle(long timestamp) {
        ObjectNode node = mapper.createObjectNode();
        node.put("t", timestamp);
        node.put("o", "100");
        node.put("h", "105");
        node.put("l", "95");
        node.put("c", "101");
        node.put("v", "12");
        return node;
    }
}

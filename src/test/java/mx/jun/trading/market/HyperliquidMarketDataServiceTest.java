package mx.jun.trading.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
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
        Instant now = Instant.parse("2026-01-01T12:07:00Z");
        Instant currentOpen = Instant.parse("2026-01-01T12:00:00Z");
        Instant first = currentOpen.minusSeconds(15L * 60).minusSeconds(59 * 15L * 60);
        ArrayNode response = mapper.createArrayNode();

        for (int i = 0; i < 60; i++) {
            response.add(candle(first.plusSeconds(i * 15L * 60).toEpochMilli()));
        }
        // Vela aún abierta: debe excluirse para no generar señales con datos cambiantes.
        response.add(candle(currentOpen.toEpochMilli()));

        server.expect(requestTo(INFO_URL))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("candleSnapshot"))
                .andExpect(jsonPath("$.req.coin").value("ETH"))
                .andExpect(jsonPath("$.req.interval").value("15m"))
                .andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));

        HyperliquidMarketDataService service = new HyperliquidMarketDataService(
                client, mapper, INFO_URL, Clock.fixed(now, ZoneOffset.UTC));
        List<Candle> candles = service.getCandles("eth", "15m", 51);

        assertEquals(51, candles.size());
        assertEquals(first.plusSeconds(9 * 15L * 60), candles.getFirst().timestamp());
        assertEquals(first.plusSeconds(59 * 15L * 60), candles.getLast().timestamp());
        assertTrue(candles.get(0).timestamp().isBefore(candles.getLast().timestamp()));
        server.verify();
    }

    @Test
    void rejectsDuplicateCandlesInsteadOfSilentlyRemovingThem() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:07:00Z");
        Instant first = Instant.parse("2026-01-01T12:00:00Z")
                .minusSeconds(15L * 60).minusSeconds(59 * 15L * 60);
        ArrayNode response = mapper.createArrayNode();
        for (int i = 0; i < 60; i++) {
            response.add(candle(first.plusSeconds(i * 15L * 60).toEpochMilli()));
        }
        response.add(candle(first.toEpochMilli()));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service = new HyperliquidMarketDataService(
                builder.build(), mapper, INFO_URL, Clock.fixed(now, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> service.getCandles("ETH", "15m", 51));
        server.verify();
    }

    @Test
    void rejectsMissingIntervalsBetweenClosedCandles() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:07:00Z");
        Instant first = Instant.parse("2026-01-01T11:45:00Z").minusSeconds(60 * 15L * 60);
        ArrayNode response = mapper.createArrayNode();
        for (int i = 0; i < 61; i++) {
            if (i != 20) response.add(candle(first.plusSeconds(i * 15L * 60).toEpochMilli()));
        }

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service = new HyperliquidMarketDataService(
                builder.build(), mapper, INFO_URL, Clock.fixed(now, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> service.getCandles("ETH", "15m", 51));
        server.verify();
    }

    @Test
    void rejectsAStaleClosedDataset() throws Exception {
        Instant now = Instant.parse("2026-01-01T12:07:00Z");
        Instant lastCandleOpen = now.minusSeconds(4 * 60 * 60L);
        Instant first = lastCandleOpen.minusSeconds(59 * 15L * 60);
        ArrayNode response = mapper.createArrayNode();
        for (int i = 0; i < 60; i++) {
            response.add(candle(first.plusSeconds(i * 15L * 60).toEpochMilli()));
        }

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service = new HyperliquidMarketDataService(
                builder.build(), mapper, INFO_URL, Clock.fixed(now, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> service.getCandles("ETH", "15m", 51));
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
    void readsPublicMidPriceWithoutCredentials() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("allMids"))
                .andRespond(withSuccess("{\"ETH\":\"2500.5\"}", MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(builder.build(), mapper, INFO_URL);

        assertEquals(new java.math.BigDecimal("2500.5"), service.getMidPrice("eth"));
        server.verify();
    }

    @Test
    void readsPerpetualMarketRulesAndFloorsOrderSize() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("meta"))
                .andRespond(withSuccess("""
                        {"universe":[
                          {"name":"BTC","szDecimals":5,"maxLeverage":40},
                          {"name":"ETH","szDecimals":4,"maxLeverage":25}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(builder.build(), mapper, INFO_URL);

        PerpetualMarketSpec market = service.getPerpetualMarketSpec("eth");

        assertEquals("ETH", market.coin());
        assertEquals(1, market.assetId());
        assertEquals(4, market.sizeDecimals());
        assertEquals(25, market.maxLeverage());
        assertEquals(new java.math.BigDecimal("0.1234"),
                market.quantizeSizeDown(new java.math.BigDecimal("0.12349")));
        server.verify();
    }

    @Test
    void rejectsUnknownPerpetualMarket() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andRespond(withSuccess("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":5,\"maxLeverage\":40}]}",
                        MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(builder.build(), mapper, INFO_URL);

        assertThrows(IllegalStateException.class, () -> service.getPerpetualMarketSpec("ETH"));
        server.verify();
    }

    @Test
    void resolvesSpotPairUsingSpotTokenAndPairIndexes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andExpect(jsonPath("$.type").value("spotMeta"))
                .andRespond(withSuccess("""
                        {"tokens":[
                          {"name":"USDC","index":0,"szDecimals":8},
                          {"name":"ETH","index":1,"szDecimals":4}
                        ],"universe":[
                          {"name":"@107","tokens":[1,0],"index":107}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(builder.build(), mapper, INFO_URL);

        SpotMarketSpec market = service.getSpotMarketSpec("eth", "usdc");

        assertEquals("ETH", market.baseAsset());
        assertEquals("USDC", market.quoteAsset());
        assertEquals("@107", market.marketName());
        assertEquals(10107, market.assetId());
        assertEquals(4, market.sizeDecimals());
        assertEquals(new java.math.BigDecimal("0.1234"),
                market.quantizeSizeDown(new java.math.BigDecimal("0.12349")));
        server.verify();
    }

    @Test
    void refusesSpotPairMissingFromMetadata() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andRespond(withSuccess("""
                        {"tokens":[
                          {"name":"USDC","index":0,"szDecimals":8},
                          {"name":"ETH","index":1,"szDecimals":4}
                        ],"universe":[]}
                        """, MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(builder.build(), mapper, INFO_URL);

        assertThrows(IllegalStateException.class, () -> service.getSpotMarketSpec("ETH", "USDC"));
        server.verify();
    }

    @Test
    void readsHistoricalFundingRatesInRequestedRange() throws Exception {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        long eventTime = start.plusSeconds(3600).toEpochMilli();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("fundingHistory"))
                .andExpect(jsonPath("$.coin").value("ETH"))
                .andExpect(jsonPath("$.startTime").value(start.toEpochMilli()))
                .andRespond(withSuccess("""
                        [{"coin":"ETH","fundingRate":"0.0001","premium":"0.0002","time":%d}]
                        """.formatted(eventTime), MediaType.APPLICATION_JSON));

        HyperliquidMarketDataService service = new HyperliquidMarketDataService(
                builder.build(), mapper, INFO_URL);
        List<FundingRate> rates = service.getFundingHistory("eth", start, start.plusSeconds(7200));

        assertEquals(1, rates.size());
        assertEquals(Instant.ofEpochMilli(eventTime), rates.getFirst().timestamp());
        assertEquals(new java.math.BigDecimal("0.0001"), rates.getFirst().rate());
        server.verify();
    }

    @Test
    void rejectsMissingPublicMidPrice() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL))
                .andRespond(withSuccess("{\"BTC\":\"65000\"}", MediaType.APPLICATION_JSON));
        HyperliquidMarketDataService service =
                new HyperliquidMarketDataService(builder.build(), mapper, INFO_URL);

        assertThrows(IllegalStateException.class, () -> service.getMidPrice("ETH"));
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
        node.put("T", timestamp + 15 * 60 * 1000L - 1);
        node.put("s", "ETH");
        node.put("i", "15m");
        node.put("o", "100");
        node.put("h", "105");
        node.put("l", "95");
        node.put("c", "101");
        node.put("v", "12");
        return node;
    }
}

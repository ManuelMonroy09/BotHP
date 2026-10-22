package mx.jun.trading.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HyperliquidAccountServiceTest {
    private static final String INFO_URL = "https://example.test/info";
    private static final String USER = "0x1234567890abcdef1234567890abcdef12345678";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsSpotBalancesOpenOrdersAndPositionsAcrossPerpetualDexes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(INFO_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("userRole"))
                .andRespond(withSuccess("{\"role\":\"user\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(INFO_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("spotClearinghouseState"))
                .andExpect(jsonPath("$.user").value(USER))
                .andRespond(withSuccess("""
                        {"balances":[{"coin":"USDC","token":0,"hold":"1.25","total":"20.00"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(INFO_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("perpDexs"))
                .andRespond(withSuccess("[null,{\"name\":\"xyz\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(INFO_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("openOrders"))
                .andExpect(jsonPath("$.user").value(USER))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(INFO_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("clearinghouseState"))
                .andExpect(jsonPath("$.user").value(USER))
                .andRespond(withSuccess("""
                        {"marginSummary":{"totalMarginUsed":"0"},"assetPositions":[]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(INFO_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("openOrders"))
                .andExpect(jsonPath("$.dex").value("xyz"))
                .andRespond(withSuccess("""
                        [{"coin":"xyz:XYZ100","oid":1234,"side":"B","sz":"1"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(INFO_URL)).andExpect(method(POST))
                .andExpect(jsonPath("$.type").value("clearinghouseState"))
                .andExpect(jsonPath("$.dex").value("xyz"))
                .andRespond(withSuccess("""
                        {"marginSummary":{"totalMarginUsed":"2.5"},"assetPositions":[
                          {"position":{"coin":"xyz:XYZ100","szi":"-1.2",
                            "leverage":{"type":"isolated"},"marginUsed":"2.5"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        HyperliquidAccountService service = new HyperliquidAccountService(builder.build(), mapper, INFO_URL);
        PublicAccountSnapshot snapshot = service.getSnapshot(USER.toUpperCase().replace("0X", "0x"));

        assertEquals("user", snapshot.accountRole());
        assertEquals(new BigDecimal("18.75"), snapshot.available("USDC"));
        assertEquals(1, snapshot.openOrders().size());
        assertEquals("xyz", snapshot.openOrders().getFirst().dex());
        assertEquals(1, snapshot.openPerpetualPositions().size());
        assertEquals("xyz:XYZ100", snapshot.openPerpetualPositions().getFirst().coin());
        assertEquals(new BigDecimal("2.5"), snapshot.perpetualMarginUsed());
        assertTrue(snapshot.hasPerpetualExposure());
        assertTrue(snapshot.accountModeRequiresManualVerification());
        server.verify();
    }

    @Test
    void rejectsMalformedAccountAddressBeforeMakingRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HyperliquidAccountService service = new HyperliquidAccountService(builder.build(), mapper, INFO_URL);

        assertThrows(IllegalArgumentException.class, () -> service.getSnapshot("0x1234"));

        server.verify();
    }

    @Test
    void failsClosedWhenBalancesHaveInvalidHeldAmount() {
        assertThrows(IllegalArgumentException.class, () -> new SpotBalance(
                "USDC", 0, new BigDecimal("1"), new BigDecimal("2")));
    }
}

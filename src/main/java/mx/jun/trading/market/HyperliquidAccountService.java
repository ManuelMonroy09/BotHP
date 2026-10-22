package mx.jun.trading.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Public, read-only account reconciliation. It never signs or submits actions. */
@Service
public class HyperliquidAccountService {
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String infoUrl;

    @Autowired
    public HyperliquidAccountService(
            @Value("${hyperliquid.info-url:https://api.hyperliquid.xyz/info}") String infoUrl) {
        this(RestClient.builder().build(), new ObjectMapper(), infoUrl);
    }

    HyperliquidAccountService(RestClient restClient, ObjectMapper objectMapper, String infoUrl) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.infoUrl = infoUrl;
    }

    public PublicAccountSnapshot getSnapshot(String accountAddress) {
        String address = normalizeAddress(accountAddress);
        String roleResponse = request(Map.of("type", "userRole", "user", address));
        String balancesResponse = request(Map.of("type", "spotClearinghouseState", "user", address));
        try {
            String role = requiredText(objectMapper.readTree(roleResponse), "role");
            List<SpotBalance> balances = parseBalances(objectMapper.readTree(balancesResponse));
            List<String> dexes = readPerpetualDexes();
            List<PublicAccountSnapshot.OpenOrder> orders = new ArrayList<>();
            List<PublicAccountSnapshot.PerpetualPosition> positions = new ArrayList<>();
            BigDecimal marginUsed = BigDecimal.ZERO;
            for (String dex : dexes) {
                var ordersPayload = new java.util.HashMap<String, Object>();
                ordersPayload.put("type", "openOrders");
                ordersPayload.put("user", address);
                if (!dex.isEmpty()) ordersPayload.put("dex", dex);
                orders.addAll(parseOrders(objectMapper.readTree(request(ordersPayload)), dex));

                var perpsPayload = new java.util.HashMap<String, Object>();
                perpsPayload.put("type", "clearinghouseState");
                perpsPayload.put("user", address);
                if (!dex.isEmpty()) perpsPayload.put("dex", dex);
                PerpetualSummary summary = parsePerpetualSummary(
                        objectMapper.readTree(request(perpsPayload)), dex);
                positions.addAll(summary.positions());
                marginUsed = marginUsed.add(summary.marginUsed());
            }
            return new PublicAccountSnapshot(address, role, balances, orders, positions, marginUsed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Hyperliquid devolvió un snapshot de cuenta inválido", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException("No se pudo interpretar el snapshot público de la cuenta", e);
        }
    }

    static String normalizeAddress(String address) {
        if (address == null || !ADDRESS.matcher(address.trim()).matches()) {
            throw new IllegalArgumentException("La cuenta debe ser una dirección EVM 0x de 40 caracteres hexadecimales");
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }

    private String request(Map<String, Object> payload) {
        String response = restClient.post().uri(infoUrl).body(payload).retrieve().body(String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Hyperliquid devolvió vacío en " + payload.get("type"));
        }
        return response;
    }

    private List<SpotBalance> parseBalances(JsonNode root) {
        JsonNode balances = root.path("balances");
        if (!balances.isArray()) throw new IllegalStateException("Balances spot con formato inesperado");
        List<SpotBalance> result = new ArrayList<>();
        for (JsonNode balance : balances) {
            result.add(new SpotBalance(requiredText(balance, "coin"), requiredInt(balance, "token"),
                    requiredDecimal(balance, "total"), requiredDecimal(balance, "hold")));
        }
        return List.copyOf(result);
    }

    private List<String> readPerpetualDexes() throws Exception {
        JsonNode root = objectMapper.readTree(request(Map.of("type", "perpDexs")));
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalStateException("Lista de DEX perpetuos con formato inesperado");
        }
        List<String> dexes = new ArrayList<>();
        dexes.add(""); // Primary DEX; omit the dex field as required by the endpoint.
        for (JsonNode dex : root) {
            if (dex.isNull()) continue; // The first entry represents the primary DEX.
            if (!dex.isObject()) throw new IllegalStateException("Metadata de DEX perpetuo inválida");
            String name = requiredText(dex, "name");
            if (!dexes.contains(name)) dexes.add(name);
        }
        if (dexes.size() > 100) throw new IllegalStateException("Demasiados DEX perpetuos para conciliar de forma segura");
        return List.copyOf(dexes);
    }

    private List<PublicAccountSnapshot.OpenOrder> parseOrders(JsonNode root, String dex) {
        if (!root.isArray()) throw new IllegalStateException("Órdenes abiertas con formato inesperado");
        List<PublicAccountSnapshot.OpenOrder> result = new ArrayList<>();
        for (JsonNode order : root) {
            result.add(new PublicAccountSnapshot.OpenOrder(dex, requiredText(order, "coin"),
                    requiredLong(order, "oid")));
        }
        return List.copyOf(result);
    }

    private PerpetualSummary parsePerpetualSummary(JsonNode root, String dex) {
        JsonNode marginSummary = root.path("marginSummary");
        JsonNode positions = root.path("assetPositions");
        if (!marginSummary.isObject() || !positions.isArray()) {
            throw new IllegalStateException("Estado perpetuo con formato inesperado");
        }
        BigDecimal marginUsed = requiredDecimal(marginSummary, "totalMarginUsed");
        if (marginUsed.signum() < 0) throw new IllegalStateException("Margen perpetuo negativo en la respuesta");
        List<PublicAccountSnapshot.PerpetualPosition> openPositions = new ArrayList<>();
        for (JsonNode assetPosition : positions) {
            JsonNode position = assetPosition.path("position");
            BigDecimal size = requiredDecimal(position, "szi");
            if (size.signum() != 0) {
                JsonNode leverage = position.path("leverage");
                openPositions.add(new PublicAccountSnapshot.PerpetualPosition(
                        dex, requiredText(position, "coin"), size, requiredText(leverage, "type"),
                        requiredDecimal(position, "marginUsed")));
            }
        }
        return new PerpetualSummary(List.copyOf(openPositions), marginUsed);
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Falta " + key);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.canConvertToInt()) throw new IllegalArgumentException("Falta " + key);
        return value.asInt();
    }

    private static long requiredLong(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.canConvertToLong()) throw new IllegalArgumentException("Falta " + key);
        return value.asLong();
    }

    private static BigDecimal requiredDecimal(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) throw new IllegalArgumentException("Falta " + key);
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Número inválido en " + key, e);
        }
    }

    private record PerpetualSummary(List<PublicAccountSnapshot.PerpetualPosition> positions,
                                    BigDecimal marginUsed) {}
}

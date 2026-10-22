package mx.jun.trading.runner;

import mx.jun.trading.market.HyperliquidAccountService;
import mx.jun.trading.market.HyperliquidMarketDataService;
import mx.jun.trading.market.PublicAccountSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Read-only account check; it never creates an order or claims live readiness. */
@Component
public class AccountCheckRunner implements CommandLineRunner {
    private final HyperliquidAccountService accountService;
    private final HyperliquidMarketDataService marketDataService;
    private final String mode;
    private final String address;
    private final String baseAsset;
    private final String quoteAsset;

    public AccountCheckRunner(HyperliquidAccountService accountService,
                              HyperliquidMarketDataService marketDataService,
                              @Value("${trading.mode:BACKTEST}") String mode,
                              @Value("${hyperliquid.account.address:}") String address,
                              @Value("${hyperliquid.coin:ETH}") String baseAsset,
                              @Value("${hyperliquid.quote-asset:USDC}") String quoteAsset) {
        this.accountService = accountService;
        this.marketDataService = marketDataService;
        this.mode = mode;
        this.address = address;
        this.baseAsset = baseAsset;
        this.quoteAsset = quoteAsset;
    }

    @Override
    public void run(String... args) {
        if (TradingMode.parse(mode) != TradingMode.ACCOUNT_CHECK) return;
        TradingMode.ACCOUNT_CHECK.requireImplemented();
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("ACCOUNT_CHECK requiere --hyperliquid.account.address=0x...");
        }

        var market = marketDataService.getSpotMarketSpec(baseAsset, quoteAsset);
        PublicAccountSnapshot snapshot = accountService.getSnapshot(address);
        boolean hasUnexpectedRole = !"user".equalsIgnoreCase(snapshot.accountRole());
        boolean blockingExposure = hasUnexpectedRole || !snapshot.openOrders().isEmpty()
                || snapshot.hasPerpetualExposure();
        String status = blockingExposure ? "BLOCKED" : "REVIEW_REQUIRED";
        BigDecimal baseAvailable = snapshot.available(baseAsset);
        BigDecimal quoteAvailable = snapshot.available(quoteAsset);

        System.out.println("=== ACCOUNT_CHECK (solo lectura, sin órdenes) ===");
        System.out.printf("Cuenta: %s | rol=%s%n", snapshot.accountAddress(), snapshot.accountRole());
        System.out.printf("Spot: %s/%s | disponible base=%s | disponible quote=%s%n",
                market.marketName(), quoteAsset, baseAvailable, quoteAvailable);
        System.out.printf("Órdenes abiertas en DEX consultados: %d | posiciones perps abiertas: %d | margen perps: %s%n",
                snapshot.openOrders().size(), snapshot.openPerpetualPositions().size(), snapshot.perpetualMarginUsed());
        System.out.println("Modalidad de cuenta: requiere verificación manual en Hyperliquid");
        System.out.println("Resultado: " + status + " (ACCOUNT_CHECK nunca habilita trading)");
        if (blockingExposure) {
            System.out.println("Motivo: la cuenta tiene rol no admitido, órdenes abiertas o exposición perpetua.");
        } else {
            System.out.println("Motivo: sin exposición detectada en las consultas; falta confirmar modalidad Standard en la interfaz.");
        }
    }
}

package mx.jun.trading.runner;

import mx.jun.trading.market.HyperliquidMarketDataService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("hyperliquid")
public class HyperliquidConnectionRunner implements CommandLineRunner {
    private final HyperliquidMarketDataService marketDataService;

    public HyperliquidConnectionRunner(HyperliquidMarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @Override
    public void run(String... args) {
        var candles = marketDataService.getCandles("ETH", "15m", 100);

        System.out.println("=== HYPERLIQUID CONECTADO ===");
        System.out.println("Mercado: ETH-PERP");
        System.out.println("Intervalo: 15m");
        System.out.println("Velas recibidas: " + candles.size());

        if (!candles.isEmpty()) {
            System.out.println("Primera vela: " + candles.getFirst());
            System.out.println("Última vela:  " + candles.getLast());
        }
    }
}

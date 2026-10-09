package mx.jun.trading.runner;

import mx.jun.trading.backtest.BacktestEngine;
import mx.jun.trading.backtest.PerformanceAnalyzer;
import mx.jun.trading.market.Candle;
import mx.jun.trading.market.HyperliquidMarketDataService;
import mx.jun.trading.market.MarketDataService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Component
public class StartupRunner implements CommandLineRunner {
    private final MarketDataService syntheticMarketDataService;
    private final HyperliquidMarketDataService hyperliquidMarketDataService;
    private final BacktestEngine backtestEngine = new BacktestEngine();
    private final PerformanceAnalyzer performanceAnalyzer = new PerformanceAnalyzer();
    private final String dataSource;
    private final String coin;
    private final String interval;
    private final int candleCount;

    public StartupRunner(
            MarketDataService syntheticMarketDataService,
            HyperliquidMarketDataService hyperliquidMarketDataService,
            @Value("${trading.data-source:synthetic}") String dataSource,
            @Value("${hyperliquid.coin:ETH}") String coin,
            @Value("${hyperliquid.interval:15m}") String interval,
            @Value("${trading.candle-count:500}") int candleCount) {
        this.syntheticMarketDataService = syntheticMarketDataService;
        this.hyperliquidMarketDataService = hyperliquidMarketDataService;
        this.dataSource = dataSource;
        this.coin = coin;
        this.interval = interval;
        this.candleCount = candleCount;
    }

    @Override
    public void run(String... args) {
        List<Candle> candles;
        if ("hyperliquid".equals(dataSource.toLowerCase(Locale.ROOT))) {
            candles = hyperliquidMarketDataService.getCandles(coin, interval, candleCount);
            System.out.printf("Fuente: Hyperliquid pública | Activo: %s | Intervalo: %s%n", coin, interval);
        } else if ("synthetic".equals(dataSource.toLowerCase(Locale.ROOT))) {
            candles = syntheticMarketDataService.getCandles();
            System.out.println("Fuente: velas sintéticas de prueba (no usar para evaluar rentabilidad)");
        } else {
            throw new IllegalArgumentException(
                    "trading.data-source debe ser 'synthetic' o 'hyperliquid', valor recibido: " + dataSource);
        }

        System.out.println("Velas recibidas: " + candles.size());
        System.out.println("Primera vela: " + candles.get(0).timestamp());
        System.out.println("Última vela: " + candles.get(candles.size() - 1).timestamp());
        System.out.println("Último cierre: " + candles.get(candles.size() - 1).close());

        var result = backtestEngine.run(candles, new BigDecimal("20"));
        System.out.println(performanceAnalyzer.summary(result));
    }
}

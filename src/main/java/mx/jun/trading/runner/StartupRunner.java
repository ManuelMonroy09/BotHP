package mx.jun.trading.runner;

import mx.jun.trading.backtest.BacktestEngine;
import mx.jun.trading.backtest.PerformanceAnalyzer;
import mx.jun.trading.market.Candle;
import mx.jun.trading.market.MarketDataService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;

@Component
public class StartupRunner implements CommandLineRunner {
    private final MarketDataService marketDataService;
    private final BacktestEngine backtestEngine = new BacktestEngine();
    private final PerformanceAnalyzer performanceAnalyzer = new PerformanceAnalyzer();

    public StartupRunner(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @Override
    public void run(String... args) {
        List<Candle> candles = marketDataService.getCandles();
        System.out.println("Velas recibidas: " + candles.size());
        System.out.println("Último cierre: " + candles.get(candles.size() - 1).close());

        var result = backtestEngine.run(candles, new BigDecimal("20"));
        System.out.println(performanceAnalyzer.summary(result));
    }
}

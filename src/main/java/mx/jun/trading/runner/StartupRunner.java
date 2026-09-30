package mx.jun.trading.runner;

import mx.jun.trading.indicator.EmaCalculator;
import mx.jun.trading.market.Candle;
import mx.jun.trading.market.MarketDataService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class StartupRunner implements CommandLineRunner {

    private final MarketDataService marketDataService;
    private final EmaCalculator emaCalculator;

    public StartupRunner(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
        this.emaCalculator = new EmaCalculator();
    }

    @Override
    public void run(String... args) {

        List<Candle> candles = marketDataService.getCandles();

        System.out.println("Velas recibidas: " + candles.size());

        List<BigDecimal> closingPrices = candles.stream()
                .map(Candle::close)
                .toList();

        List<BigDecimal> ema20 = emaCalculator.calculate(closingPrices, 20);
        List<BigDecimal> ema50 = emaCalculator.calculate(closingPrices, 50);

        System.out.println("EMA20 calculada: " + ema20.size() + " valores");
        System.out.println("EMA50 calculada: " + ema50.size() + " valores");

        System.out.println("Último cierre: " + closingPrices.get(closingPrices.size() - 1));
        System.out.println("Última EMA20: " + ema20.get(ema20.size() - 1));
        System.out.println("Última EMA50: " + ema50.get(ema50.size() - 1));
    }
}

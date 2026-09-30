package mx.jun.trading.runner;

import mx.jun.trading.market.Candle;
import mx.jun.trading.market.MarketDataService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StartupRunner implements CommandLineRunner {
    private final MarketDataService marketDataService;

    public StartupRunner(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @Override
    public void run(String... args){

        List<Candle> candles = marketDataService.getCandles();
        System.out.println("Velas recibidas: " + candles.size());

        for(Candle candle : candles){
            System.out.println(candle);
        }

    }
}

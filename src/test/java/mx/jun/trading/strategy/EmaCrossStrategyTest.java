package mx.jun.trading.strategy;

import mx.jun.trading.market.Candle;
import mx.jun.trading.market.MarketDataService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmaCrossStrategyTest {

    @Test
    void shouldDetectBuyAndSellCrosses() {

        MarketDataService marketDataService = new MarketDataService();
        List<Candle> candles = marketDataService.getCandles();

        EmaCrossStrategy strategy = new EmaCrossStrategy();

        List<EmaCrossStrategy.Signal> signals = new ArrayList<>();

        for (int i = 51; i <= candles.size(); i++) {
            signals.add(strategy.evaluate(candles.subList(0, i)));
        }

        assertTrue(signals.contains(EmaCrossStrategy.Signal.BUY));
        assertTrue(signals.contains(EmaCrossStrategy.Signal.SELL));
    }

    @Test
    void shouldHoldWhenThereAreNotEnoughCandles() {

        EmaCrossStrategy strategy = new EmaCrossStrategy();

        List<Candle> candles = List.of(
                candle("3500"),
                candle("3501")
        );

        assertEquals(
                EmaCrossStrategy.Signal.HOLD,
                strategy.evaluate(candles)
        );
    }

    private Candle candle(String close) {
        BigDecimal price = new BigDecimal(close);

        return new Candle(
                Instant.now(),
                price,
                price,
                price,
                price,
                BigDecimal.ONE
        );
    }
}

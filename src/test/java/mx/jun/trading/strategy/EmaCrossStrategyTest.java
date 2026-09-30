package mx.jun.trading.strategy;

import mx.jun.trading.market.Candle;
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

        List<Candle> candles = createTrendCandles();

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

    private List<Candle> createTrendCandles() {

        List<Candle> candles = new ArrayList<>();

        BigDecimal price = new BigDecimal("3500");

        for (int i = 0; i < 150; i++) {

            BigDecimal movement;

            if (i < 50) {
                movement = new BigDecimal("-2");
            } else if (i < 100) {
                movement = new BigDecimal("4");
            } else {
                movement = new BigDecimal("-3");
            }

            price = price.add(movement);

            candles.add(candle(price.toPlainString()));
        }

        return candles;
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

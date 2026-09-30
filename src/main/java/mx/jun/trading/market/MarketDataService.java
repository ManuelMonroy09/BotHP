package mx.jun.trading.market;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class MarketDataService {

    public List<Candle> getCandles() {

        List<Candle> candles = new ArrayList<>();

        Instant start = Instant.parse("2026-09-25T20:00:00Z");
        BigDecimal price = new BigDecimal("3500");

        for (int i = 0; i < 100; i++) {

            BigDecimal open = price;

            BigDecimal movement;

            if (i < 30) {
                movement = new BigDecimal("2");
            } else if (i < 60) {
                movement = new BigDecimal("-1");
            } else if (i < 80) {
                movement = new BigDecimal("4");
            } else {
                movement = new BigDecimal("-2");
            }

            BigDecimal close = open.add(movement);

            BigDecimal high = open.max(close).add(new BigDecimal("3"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("3"));
            BigDecimal volume = new BigDecimal(100 + i);

            candles.add(new Candle(
                    start.plusSeconds(i * 15L * 60),
                    open,
                    high,
                    low,
                    close,
                    volume
            ));

            price = close;
        }

        return candles;
    }
}

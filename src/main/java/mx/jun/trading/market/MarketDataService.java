package mx.jun.trading.market;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class MarketDataService {

    public List<Candle> getCandles() {
        return List.of(
                new Candle(
                        Instant.parse("2026-09-25t20:00:00Z"),
                        new BigDecimal("3500"),
                        new BigDecimal("3520"),
                        new BigDecimal("3490"),
                        new BigDecimal("3510"),
                        new BigDecimal("120")
                ),
                new Candle(
                        Instant.parse("2026-09-25T20:15:00Z"),
                        new BigDecimal("3510"),
                        new BigDecimal("3540"),
                        new BigDecimal("3505"),
                        new BigDecimal("3535"),
                        new BigDecimal("135")
                ),
                new Candle(
                        Instant.parse("2026-09-25T20:30:00Z"),
                        new BigDecimal("3535"),
                        new BigDecimal("3550"),
                        new BigDecimal("3520"),
                        new BigDecimal("3545"),
                        new BigDecimal("150")
                )
        );
    }
}

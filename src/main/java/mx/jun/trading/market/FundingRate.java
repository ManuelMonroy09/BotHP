package mx.jun.trading.market;

import java.math.BigDecimal;
import java.time.Instant;

/** Historical hourly funding rate returned by Hyperliquid's public info API. */
public record FundingRate(Instant timestamp, BigDecimal rate) {
    public FundingRate {
        if (timestamp == null || rate == null) {
            throw new IllegalArgumentException("Funding time and rate are required");
        }
    }
}

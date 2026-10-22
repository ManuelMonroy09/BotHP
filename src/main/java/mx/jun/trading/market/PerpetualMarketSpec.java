package mx.jun.trading.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Public contract metadata needed to validate a perpetual order before execution. */
public record PerpetualMarketSpec(String coin, int assetId, int sizeDecimals, int maxLeverage) {
    public PerpetualMarketSpec {
        if (coin == null || coin.isBlank() || assetId < 0 || sizeDecimals < 0 || sizeDecimals > 6
                || maxLeverage < 1) {
            throw new IllegalArgumentException("Perpetual market metadata is invalid");
        }
    }

    /** Floors size to the exchange precision; it never rounds exposure upward. */
    public BigDecimal quantizeSizeDown(BigDecimal requestedSize) {
        if (requestedSize == null || requestedSize.signum() <= 0) {
            throw new IllegalArgumentException("Order size must be positive");
        }
        return requestedSize.setScale(sizeDecimals, RoundingMode.DOWN).stripTrailingZeros();
    }
}

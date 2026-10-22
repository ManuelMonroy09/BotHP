package mx.jun.trading.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Public spot pair metadata. Spot execution uses only quote funds already available. */
public record SpotMarketSpec(String baseAsset, String quoteAsset, String marketName,
                             int assetId, int sizeDecimals) {
    public SpotMarketSpec {
        if (baseAsset == null || baseAsset.isBlank() || quoteAsset == null || quoteAsset.isBlank()
                || marketName == null || marketName.isBlank() || assetId < 10_000
                || sizeDecimals < 0 || sizeDecimals > 8) {
            throw new IllegalArgumentException("Spot market metadata is invalid");
        }
    }

    /** Floors base size to exchange precision, never increasing the requested spend. */
    public BigDecimal quantizeSizeDown(BigDecimal requestedSize) {
        if (requestedSize == null || requestedSize.signum() <= 0) {
            throw new IllegalArgumentException("Spot size must be positive");
        }
        return requestedSize.setScale(sizeDecimals, RoundingMode.DOWN).stripTrailingZeros();
    }
}

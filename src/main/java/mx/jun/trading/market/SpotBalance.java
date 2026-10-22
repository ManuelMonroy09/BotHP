package mx.jun.trading.market;

import java.math.BigDecimal;

/** Public balance snapshot returned by Hyperliquid's spot account endpoint. */
public record SpotBalance(String coin, int token, BigDecimal total, BigDecimal hold) {
    public SpotBalance {
        if (coin == null || coin.isBlank() || token < 0 || total == null || hold == null
                || total.signum() < 0 || hold.signum() < 0 || hold.compareTo(total) > 0) {
            throw new IllegalArgumentException("Invalid spot balance returned by Hyperliquid");
        }
    }

    public BigDecimal available() {
        return total.subtract(hold);
    }
}

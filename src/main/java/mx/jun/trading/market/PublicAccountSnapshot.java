package mx.jun.trading.market;

import java.math.BigDecimal;
import java.util.List;

/** Read-only account facts; it does not assert that an account is safe to trade. */
public record PublicAccountSnapshot(String accountAddress, String accountRole,
                                    List<SpotBalance> spotBalances,
                                    List<OpenOrder> openOrders,
                                    List<PerpetualPosition> openPerpetualPositions,
                                    BigDecimal perpetualMarginUsed) {
    public PublicAccountSnapshot {
        if (accountAddress == null || accountRole == null || accountRole.isBlank()
                || spotBalances == null || openOrders == null || openPerpetualPositions == null
                || perpetualMarginUsed == null || perpetualMarginUsed.signum() < 0) {
            throw new IllegalArgumentException("Incomplete public account snapshot");
        }
        spotBalances = List.copyOf(spotBalances);
        openOrders = List.copyOf(openOrders);
        openPerpetualPositions = List.copyOf(openPerpetualPositions);
    }

    public BigDecimal available(String coin) {
        return spotBalances.stream().filter(balance -> balance.coin().equalsIgnoreCase(coin))
                .map(SpotBalance::available).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean hasPerpetualExposure() {
        return !openPerpetualPositions.isEmpty() || perpetualMarginUsed.signum() > 0;
    }

    /** Hyperliquid currently does not expose the account abstraction mode in these info responses. */
    public boolean accountModeRequiresManualVerification() {
        return true;
    }

    public record OpenOrder(String dex, String coin, long orderId) {
        public OpenOrder {
            if (dex == null || coin == null || coin.isBlank() || orderId < 0) {
                throw new IllegalArgumentException("Invalid open order in account snapshot");
            }
        }
    }

    public record PerpetualPosition(String dex, String coin, BigDecimal size, String marginType,
                                    BigDecimal marginUsed) {
        public PerpetualPosition {
            if (dex == null || coin == null || coin.isBlank() || size == null || size.signum() == 0
                    || marginType == null || marginType.isBlank()
                    || marginUsed == null || marginUsed.signum() < 0) {
                throw new IllegalArgumentException("Invalid perpetual position in account snapshot");
            }
        }
    }
}

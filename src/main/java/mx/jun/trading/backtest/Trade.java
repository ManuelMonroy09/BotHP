package mx.jun.trading.backtest;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(Instant entryTime, Instant exitTime, BigDecimal entryPrice,
                    BigDecimal exitPrice, BigDecimal quantity, BigDecimal grossPnl,
                    BigDecimal fees, BigDecimal fundingPnl, BigDecimal netPnl, int side, String exitReason) {
    public Trade(Instant entryTime, Instant exitTime, BigDecimal entryPrice,
                 BigDecimal exitPrice, BigDecimal quantity, BigDecimal grossPnl,
                 BigDecimal fees, BigDecimal netPnl, int side, String exitReason) {
        this(entryTime, exitTime, entryPrice, exitPrice, quantity, grossPnl, fees,
                BigDecimal.ZERO, netPnl, side, exitReason);
    }

    public Trade(Instant entryTime, Instant exitTime, BigDecimal entryPrice,
                 BigDecimal exitPrice, BigDecimal quantity, BigDecimal grossPnl,
                 BigDecimal fees, BigDecimal netPnl, String exitReason) {
        this(entryTime, exitTime, entryPrice, exitPrice, quantity, grossPnl, fees,
                BigDecimal.ZERO, netPnl, 1, exitReason);
    }

    public String direction() {
        return side > 0 ? "LONG" : "SHORT";
    }
}

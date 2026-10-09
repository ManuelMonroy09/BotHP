package mx.jun.trading.backtest;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(Instant entryTime, Instant exitTime, BigDecimal entryPrice,
                    BigDecimal exitPrice, BigDecimal quantity, BigDecimal grossPnl,
                    BigDecimal fees, BigDecimal netPnl, String exitReason) {}

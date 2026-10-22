package mx.jun.trading.paper;

import java.math.BigDecimal;
import java.time.Instant;

public record PaperEvent(Instant timestamp, String type, String reason,
                         BigDecimal price, BigDecimal quantity,
                         BigDecimal fee, BigDecimal equity, BigDecimal realizedPnl) {}

package mx.jun.trading.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Persisted virtual portfolio and risk state. It contains no exchange credentials. */
public record PaperTradingState(
        BigDecimal cash,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        Instant entryTime,
        Instant lastProcessedCandle,
        BigDecimal referenceEquity,
        BigDecimal peakEquity,
        BigDecimal dayStartEquity,
        String riskDayUtc,
        boolean dailyLocked,
        boolean cumulativeLocked,
        boolean killSwitchLatched,
        BigDecimal entryTotalCost,
        BigDecimal totalFees,
        BigDecimal realizedPnl,
        int closedTrades,
        int winningTrades,
        int losingTrades,
        int breakEvenTrades,
        List<PaperEvent> eventHistory,
        String sessionProfile) {

    public static final String LEGACY_UNBOUND_PROFILE = "LEGACY_UNBOUND";

    public PaperTradingState {
        eventHistory = eventHistory == null ? List.of() : List.copyOf(eventHistory);
        sessionProfile = sessionProfile == null || sessionProfile.isBlank()
                ? LEGACY_UNBOUND_PROFILE : sessionProfile;
    }

    /** Compatibility constructor for state fixtures and legacy records without a session identity. */
    public PaperTradingState(BigDecimal cash, BigDecimal quantity, BigDecimal entryPrice,
                             BigDecimal stopPrice, Instant entryTime, Instant lastProcessedCandle,
                             BigDecimal referenceEquity, BigDecimal peakEquity, BigDecimal dayStartEquity,
                             String riskDayUtc, boolean dailyLocked, boolean cumulativeLocked,
                             boolean killSwitchLatched, BigDecimal entryTotalCost, BigDecimal totalFees,
                             BigDecimal realizedPnl, int closedTrades, int winningTrades,
                             int losingTrades, int breakEvenTrades, List<PaperEvent> eventHistory) {
        this(cash, quantity, entryPrice, stopPrice, entryTime, lastProcessedCandle, referenceEquity,
                peakEquity, dayStartEquity, riskDayUtc, dailyLocked, cumulativeLocked, killSwitchLatched,
                entryTotalCost, totalFees, realizedPnl, closedTrades, winningTrades, losingTrades,
                breakEvenTrades, eventHistory, LEGACY_UNBOUND_PROFILE);
    }

    public static PaperTradingState initial(BigDecimal startingCapital, String riskDayUtc) {
        return initial(startingCapital, riskDayUtc, LEGACY_UNBOUND_PROFILE);
    }

    public static PaperTradingState initial(BigDecimal startingCapital, String riskDayUtc,
                                            String sessionProfile) {
        if (startingCapital == null || startingCapital.signum() <= 0) {
            throw new IllegalArgumentException("El capital inicial paper debe ser positivo");
        }
        return new PaperTradingState(startingCapital, BigDecimal.ZERO, null, null,
                null, null, startingCapital, startingCapital, startingCapital, riskDayUtc,
                false, false, false, null, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, 0, 0, List.of(), sessionProfile);
    }

    public PaperTradingState bindSession(String profile) {
        if (hasPosition() || closedTrades != 0 || lastProcessedCandle != null || !eventHistory.isEmpty()) {
            throw new IllegalStateException("No se puede reasignar el perfil de una sesión PAPER usada");
        }
        return new PaperTradingState(cash, quantity, entryPrice, stopPrice, entryTime, lastProcessedCandle,
                referenceEquity, peakEquity, dayStartEquity, riskDayUtc, dailyLocked, cumulativeLocked,
                killSwitchLatched, entryTotalCost, totalFees, realizedPnl, closedTrades, winningTrades,
                losingTrades, breakEvenTrades, eventHistory, profile);
    }

    public boolean hasPosition() {
        return quantity != null && quantity.signum() > 0;
    }

    public BigDecimal equityAt(BigDecimal markPrice) {
        if (markPrice == null || markPrice.signum() <= 0) {
            throw new IllegalArgumentException("El precio de valoración debe ser positivo");
        }
        if (!hasPosition()) return cash;
        // PAPER is fully paid spot: cash is unspent quote currency and quantity is owned base.
        return cash.add(markPrice.multiply(quantity));
    }
}

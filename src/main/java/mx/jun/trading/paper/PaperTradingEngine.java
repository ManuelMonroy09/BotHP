package mx.jun.trading.paper;

import mx.jun.trading.market.Candle;
import mx.jun.trading.market.SpotMarketSpec;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Virtual-only portfolio. It has no exchange order or signing dependency. */
public class PaperTradingEngine {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.00045");
    private static final BigDecimal SLIPPAGE_RATE = new BigDecimal("0.00010");
    private static final BigDecimal RISK_PER_TRADE = new BigDecimal("0.005");
    private static final BigDecimal STOP_LOSS = new BigDecimal("0.015");
    private static final BigDecimal MAX_NOTIONAL = new BigDecimal("0.50");
    private static final BigDecimal DAILY_LOSS_LIMIT = new BigDecimal("0.02");
    private static final BigDecimal CUMULATIVE_LOSS_LIMIT = new BigDecimal("0.10");

    private final EmaRegimeVolatilityStrategy strategy;

    public PaperTradingEngine() {
        this(EmaRegimeVolatilityStrategy.Mode.EMA_CROSS);
    }

    public PaperTradingEngine(EmaRegimeVolatilityStrategy.Mode strategyMode) {
        this.strategy = new EmaRegimeVolatilityStrategy(strategyMode);
    }

    public Outcome process(PaperTradingState previousState, List<Candle> candles,
                           BigDecimal midPrice, boolean killSwitchRequested, Instant now) {
        return process(previousState, candles, midPrice, killSwitchRequested, now, null);
    }

    public Outcome process(PaperTradingState previousState, List<Candle> candles,
                           BigDecimal midPrice, boolean killSwitchRequested, Instant now,
                           SpotMarketSpec marketSpec) {
        if (previousState == null) throw new IllegalArgumentException("Falta el estado paper persistido");
        if (midPrice == null || midPrice.signum() <= 0 || now == null) {
            throw new IllegalArgumentException("Precio o reloj inválido para la sesión paper");
        }
        long intervalMillis = validateCandles(candles, now);
        Candle latest = candles.getLast();
        int processedIndex = indexOf(candles, previousState.lastProcessedCandle());
        boolean newClosedCandle;
        if (previousState.lastProcessedCandle() == null || previousState.killSwitchLatched()
                || killSwitchRequested) {
            newClosedCandle = false;
        } else {
            if (processedIndex < 0) {
                throw new IllegalStateException("No se puede reconciliar el último cierre paper con el feed");
            }
            int unseen = candles.size() - 1 - processedIndex;
            if (unseen > 1) {
                throw new IllegalStateException("Se detectaron velas cerradas no procesadas; PAPER queda bloqueado");
            }
            newClosedCandle = unseen == 1;
        }

        BigDecimal cash = previousState.cash();
        BigDecimal quantity = previousState.quantity();
        BigDecimal entryPrice = previousState.entryPrice();
        BigDecimal stopPrice = previousState.stopPrice();
        Instant entryTime = previousState.entryTime();
        BigDecimal entryTotalCost = previousState.entryTotalCost();
        BigDecimal totalFees = previousState.totalFees();
        BigDecimal realizedPnl = previousState.realizedPnl();
        int closedTrades = previousState.closedTrades();
        int winningTrades = previousState.winningTrades();
        int losingTrades = previousState.losingTrades();
        int breakEvenTrades = previousState.breakEvenTrades();
        Instant lastProcessedCandle = previousState.lastProcessedCandle();
        BigDecimal referenceEquity = previousState.referenceEquity();
        BigDecimal peakEquity = previousState.peakEquity();
        BigDecimal dayStartEquity = previousState.dayStartEquity();
        String riskDayUtc = previousState.riskDayUtc();
        boolean dailyLocked = previousState.dailyLocked();
        boolean cumulativeLocked = previousState.cumulativeLocked();
        boolean killSwitchLatched = previousState.killSwitchLatched() || killSwitchRequested;
        boolean exitedThisCycle = false;
        String today = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
        var events = new ArrayList<PaperEvent>();

        BigDecimal markEquity = equityAt(midPrice, cash, entryPrice, quantity);
        if (!today.equals(riskDayUtc)) {
            riskDayUtc = today;
            dayStartEquity = markEquity;
            dailyLocked = false;
            events.add(event(now, "RISK_DAY_RESET", "UTC day changed", midPrice, quantity,
                    BigDecimal.ZERO, markEquity));
        }
        if (markEquity.compareTo(peakEquity) > 0) peakEquity = markEquity;
        if (killSwitchLatched && !previousState.killSwitchLatched()) {
            events.add(event(now, "KILL_SWITCH", "Local kill switch latched", midPrice, quantity,
                    BigDecimal.ZERO, markEquity));
        }
        if (killSwitchLatched && quantity.signum() > 0) {
            BigDecimal exitPrice = midPrice.multiply(ONE.subtract(SLIPPAGE_RATE));
            TradeClosure closure = closeTrade(cash, exitPrice, quantity, entryTotalCost);
            cash = closure.cashAfter();
            totalFees = totalFees.add(closure.exitFee());
            realizedPnl = realizedPnl.add(closure.netPnl());
            closedTrades++;
            if (closure.netPnl().signum() > 0) winningTrades++;
            else if (closure.netPnl().signum() < 0) losingTrades++;
            else breakEvenTrades++;
            events.add(event(now, "PAPER_EXIT", "KILL_SWITCH", exitPrice, quantity,
                    closure.exitFee(), cash, closure.netPnl()));
            quantity = BigDecimal.ZERO;
            entryPrice = null;
            stopPrice = null;
            entryTime = null;
            entryTotalCost = null;
            exitedThisCycle = true;
        }

        if (dayStartEquity.signum() > 0 && markEquity.compareTo(
                dayStartEquity.multiply(ONE.subtract(DAILY_LOSS_LIMIT))) <= 0) {
            dailyLocked = true;
        }
        if (markEquity.compareTo(referenceEquity.multiply(ONE.subtract(CUMULATIVE_LOSS_LIMIT))) <= 0) {
            cumulativeLocked = true;
        }
        if ((dailyLocked && !previousState.dailyLocked())
                || (cumulativeLocked && !previousState.cumulativeLocked())) {
            events.add(event(now, "RISK_LOCK", dailyLocked && cumulativeLocked
                    ? "DAILY_AND_CUMULATIVE_LIMIT" : dailyLocked ? "DAILY_LIMIT" : "CUMULATIVE_LIMIT",
                    midPrice, quantity, BigDecimal.ZERO, markEquity));
        }

        if (previousState.lastProcessedCandle() == null) {
            lastProcessedCandle = latest.timestamp();
            events.add(event(now, "BASELINE", "No retroactive paper entries; tracking starts now",
                    midPrice, BigDecimal.ZERO, BigDecimal.ZERO, markEquity));
        } else if (quantity.signum() > 0) {
            boolean candleAfterEntry = latest.timestamp().isAfter(entryTime);
            boolean stopHit = (candleAfterEntry && latest.low().compareTo(stopPrice) <= 0)
                    || midPrice.compareTo(stopPrice) <= 0;
            if (stopHit || dailyLocked || cumulativeLocked) {
                BigDecimal exitPrice;
                String reason;
                if (stopHit) {
                    BigDecimal rawExit = latest.open().compareTo(stopPrice) <= 0
                            ? latest.open().min(midPrice) : stopPrice;
                    exitPrice = rawExit.multiply(ONE.subtract(SLIPPAGE_RATE));
                    reason = "STOP_LOSS";
                } else {
                    exitPrice = midPrice.multiply(ONE.subtract(SLIPPAGE_RATE));
                    reason = dailyLocked && cumulativeLocked ? "DAILY_AND_CUMULATIVE_LIMIT"
                            : dailyLocked ? "DAILY_LIMIT" : "CUMULATIVE_LIMIT";
                }
                TradeClosure closure = closeTrade(cash, exitPrice, quantity, entryTotalCost);
                cash = closure.cashAfter();
                totalFees = totalFees.add(closure.exitFee());
                realizedPnl = realizedPnl.add(closure.netPnl());
                closedTrades++;
                if (closure.netPnl().signum() > 0) winningTrades++;
                else if (closure.netPnl().signum() < 0) losingTrades++;
                else breakEvenTrades++;
                BigDecimal closedEquity = cash;
                events.add(event(now, "PAPER_EXIT", reason, exitPrice, quantity,
                        closure.exitFee(), closedEquity, closure.netPnl()));
                quantity = BigDecimal.ZERO;
                entryPrice = null;
                stopPrice = null;
                entryTime = null;
                entryTotalCost = null;
                exitedThisCycle = true;
            } else if (newClosedCandle) {
                var signal = strategy.evaluateAll(candles).getLast();
                if (signal == EmaRegimeVolatilityStrategy.Signal.SELL) {
                    BigDecimal exitPrice = midPrice.multiply(ONE.subtract(SLIPPAGE_RATE));
                    TradeClosure closure = closeTrade(cash, exitPrice, quantity, entryTotalCost);
                    cash = closure.cashAfter();
                    totalFees = totalFees.add(closure.exitFee());
                    realizedPnl = realizedPnl.add(closure.netPnl());
                    closedTrades++;
                    if (closure.netPnl().signum() > 0) winningTrades++;
                    else if (closure.netPnl().signum() < 0) losingTrades++;
                    else breakEvenTrades++;
                    events.add(event(now, "PAPER_EXIT", "BEARISH_SIGNAL", exitPrice, quantity,
                            closure.exitFee(), cash, closure.netPnl()));
                    quantity = BigDecimal.ZERO;
                    entryPrice = null;
                    stopPrice = null;
                    entryTime = null;
                    entryTotalCost = null;
                    exitedThisCycle = true;
                }
            }
        }

        if (previousState.lastProcessedCandle() != null && newClosedCandle && !exitedThisCycle
                && quantity.signum() == 0 && !dailyLocked && !cumulativeLocked && !killSwitchLatched) {
            var signal = strategy.evaluateAll(candles).getLast();
            if (signal == EmaRegimeVolatilityStrategy.Signal.BUY) {
                BigDecimal currentEquity = equityAt(midPrice, cash, entryPrice, quantity).max(BigDecimal.ZERO);
                BigDecimal executionPrice = midPrice.multiply(ONE.add(SLIPPAGE_RATE));
                BigDecimal stopExecutionPrice = executionPrice.multiply(ONE.subtract(STOP_LOSS))
                        .multiply(ONE.subtract(SLIPPAGE_RATE));
                BigDecimal riskPerUnit = executionPrice.subtract(stopExecutionPrice)
                        .add(executionPrice.multiply(FEE_RATE)).add(stopExecutionPrice.multiply(FEE_RATE));
                BigDecimal quantityByRisk = currentEquity.multiply(RISK_PER_TRADE)
                        .divide(riskPerUnit, 10, RoundingMode.DOWN);
                BigDecimal quantityByExposure = currentEquity.multiply(MAX_NOTIONAL)
                        .divide(executionPrice, 10, RoundingMode.DOWN);
                BigDecimal quantityByAvailableCash = cash.divide(
                        executionPrice.multiply(ONE.add(FEE_RATE)), 10, RoundingMode.DOWN);
                BigDecimal candidateQuantity = quantityByRisk.min(quantityByExposure).min(quantityByAvailableCash);
                if (marketSpec != null) candidateQuantity = marketSpec.quantizeSizeDown(candidateQuantity);
                if (candidateQuantity.signum() > 0) {
                    BigDecimal entryFee = executionPrice.multiply(candidateQuantity).multiply(FEE_RATE);
                    cash = cash.subtract(executionPrice.multiply(candidateQuantity)).subtract(entryFee);
                    totalFees = totalFees.add(entryFee);
                    quantity = candidateQuantity;
                    entryPrice = executionPrice;
                    entryTotalCost = executionPrice.multiply(candidateQuantity).add(entryFee);
                    stopPrice = executionPrice.multiply(ONE.subtract(STOP_LOSS));
                    entryTime = now;
                    events.add(event(now, "PAPER_ENTRY", "BULLISH_SIGNAL", executionPrice,
                            quantity, entryFee, equityAt(midPrice, cash, entryPrice, quantity)));
                }
            }
        }

        if (previousState.lastProcessedCandle() != null && newClosedCandle) {
            lastProcessedCandle = latest.timestamp();
        }
        var eventHistory = new ArrayList<>(previousState.eventHistory());
        eventHistory.addAll(events);
        PaperTradingState nextState = new PaperTradingState(cash, quantity, entryPrice, stopPrice,
                entryTime, lastProcessedCandle, referenceEquity, peakEquity, dayStartEquity,
                riskDayUtc, dailyLocked, cumulativeLocked, killSwitchLatched, entryTotalCost,
                totalFees, realizedPnl, closedTrades, winningTrades, losingTrades, breakEvenTrades,
                eventHistory, previousState.sessionProfile());
        return new Outcome(nextState, List.copyOf(events), intervalMillis);
    }

    private static long validateCandles(List<Candle> candles, Instant now) {
        if (candles == null || candles.size() < 51) {
            throw new IllegalArgumentException("PAPER requiere al menos 51 velas cerradas");
        }
        long intervalMillis = -1;
        Candle previous = null;
        for (Candle candle : candles) {
            if (candle == null || candle.timestamp() == null || candle.open() == null
                    || candle.high() == null || candle.low() == null || candle.close() == null
                    || candle.volume() == null) {
                throw new IllegalArgumentException("PAPER recibió una vela incompleta");
            }
            if (previous != null) {
                long difference = Duration.between(previous.timestamp(), candle.timestamp()).toMillis();
                if (difference <= 0 || (intervalMillis > 0 && difference != intervalMillis)) {
                    throw new IllegalArgumentException("PAPER recibió velas duplicadas, desordenadas o con huecos");
                }
                intervalMillis = difference;
            }
            previous = candle;
        }
        if (intervalMillis <= 0 || candles.getLast().timestamp().plusMillis(intervalMillis).isAfter(now)) {
            throw new IllegalArgumentException("PAPER recibió datos sin cierre confirmado");
        }
        return intervalMillis;
    }

    private static int indexOf(List<Candle> candles, Instant timestamp) {
        if (timestamp == null) return -1;
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).timestamp().equals(timestamp)) return i;
        }
        return -1;
    }

    private static BigDecimal equityAt(BigDecimal price, BigDecimal cash,
                                       BigDecimal entryPrice, BigDecimal quantity) {
        if (quantity == null || quantity.signum() == 0 || entryPrice == null) return cash;
        return cash.add(price.multiply(quantity));
    }

    private static PaperEvent event(Instant timestamp, String type, String reason,
                                    BigDecimal price, BigDecimal quantity,
                                    BigDecimal fee, BigDecimal equity) {
        return event(timestamp, type, reason, price, quantity, fee, equity, BigDecimal.ZERO);
    }

    private static PaperEvent event(Instant timestamp, String type, String reason,
                                    BigDecimal price, BigDecimal quantity,
                                    BigDecimal fee, BigDecimal equity, BigDecimal realizedPnl) {
        return new PaperEvent(timestamp, type, reason, price, quantity, fee, equity, realizedPnl);
    }

    private static TradeClosure closeTrade(BigDecimal cash, BigDecimal exitPrice,
                                           BigDecimal quantity, BigDecimal entryTotalCost) {
        BigDecimal proceeds = exitPrice.multiply(quantity);
        BigDecimal exitFee = proceeds.multiply(FEE_RATE);
        BigDecimal cashAfter = cash.add(proceeds).subtract(exitFee);
        BigDecimal netPnl = cashAfter.subtract(cash).subtract(entryTotalCost);
        return new TradeClosure(cashAfter, exitFee, netPnl);
    }

    private record TradeClosure(BigDecimal cashAfter, BigDecimal exitFee, BigDecimal netPnl) {}

    public record Outcome(PaperTradingState state, List<PaperEvent> events, long candleIntervalMillis) {}
}

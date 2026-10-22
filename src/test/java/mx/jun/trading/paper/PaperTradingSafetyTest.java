package mx.jun.trading.paper;

import mx.jun.trading.market.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaperTradingSafetyTest {
    @TempDir
    Path tempDir;

    @Test
    void stateIsPersistedAndRestored() {
        PaperTradingState initial = PaperTradingState.initial(new BigDecimal("20"), "2026-10-22");
        PaperTradingStateStore store = new PaperTradingStateStore(tempDir.resolve("paper/state.properties"));

        store.save(initial);

        assertEquals(initial, store.load().orElseThrow());
    }

    @Test
    void paperStateKeepsItsMarketIntervalAndStrategyProfile() {
        String profile = "ETH/USDC|4h|EMA_CROSS|20|spot-risk-v1";
        PaperTradingState initial = PaperTradingState.initial(
                new BigDecimal("20"), "2026-10-22", profile);
        PaperTradingStateStore store = new PaperTradingStateStore(tempDir.resolve("isolated/state.properties"));

        store.save(initial);

        assertEquals(profile, store.load().orElseThrow().sessionProfile());
    }

    @Test
    void usedLegacySessionCannotBeReassignedToADifferentInterval() {
        Instant now = Instant.parse("2026-10-22T12:45:00Z");
        List<Candle> candles = candles(now.minusSeconds(100L * 15 * 60));
        PaperTradingState baseline = new PaperTradingEngine().process(
                PaperTradingState.initial(new BigDecimal("20"), "2026-10-22"),
                candles, new BigDecimal("100"), false, now).state();

        assertThrows(IllegalStateException.class,
                () -> baseline.bindSession("ETH/USDC|4h|EMA_CROSS|20|spot-risk-v1"));
    }

    @Test
    void paperEventsArePersistedWithPortfolioState() {
        Instant now = Instant.parse("2026-10-22T12:45:00Z");
        String profile = "ETH/USDC|4h|EMA_CROSS|20|spot-risk-v1";
        PaperTradingState initial = PaperTradingState.initial(new BigDecimal("20"), "2026-10-22", profile);
        List<Candle> candles = candles(now.minusSeconds(100L * 15 * 60));
        PaperTradingState withBaseline = new PaperTradingEngine().process(
                initial, candles, new BigDecimal("3500"), false, now).state();
        PaperTradingStateStore store = new PaperTradingStateStore(tempDir.resolve("journal/state.properties"));

        store.save(withBaseline);

        PaperTradingState restored = store.load().orElseThrow();
        assertEquals(withBaseline.eventHistory(), restored.eventHistory());
        assertEquals(profile, restored.sessionProfile());
        assertEquals("BASELINE", restored.eventHistory().getFirst().type());
    }

    @Test
    void versionThreeStateMigratesWithoutLosingAccounting() throws Exception {
        Path stateFile = tempDir.resolve("migration/state.properties");
        java.nio.file.Files.createDirectories(stateFile.getParent());
        java.nio.file.Files.writeString(stateFile, """
                version=3
                cash=19.5
                quantity=0
                entryPrice=
                stopPrice=
                entryTime=
                lastProcessedCandle=2026-10-22T12:30:00Z
                referenceEquity=20
                peakEquity=20
                dayStartEquity=20
                riskDayUtc=2026-10-22
                dailyLocked=false
                cumulativeLocked=false
                killSwitchLatched=false
                entryTotalCost=
                totalFees=0.01
                realizedPnl=-0.5
                closedTrades=1
                winningTrades=0
                losingTrades=1
                breakEvenTrades=0
                """);
        PaperTradingStateStore store = new PaperTradingStateStore(stateFile);

        PaperTradingState migrated = store.load().orElseThrow();

        assertEquals(new BigDecimal("-0.5"), migrated.realizedPnl());
        assertEquals(1, migrated.closedTrades());
        assertTrue(migrated.eventHistory().isEmpty());
        assertTrue(java.nio.file.Files.readString(stateFile).contains("version=5"));
        assertTrue(java.nio.file.Files.readString(stateFile).contains(
                "sessionProfile=LEGACY_UNBOUND"));
    }

    @Test
    void versionFourStateMigratesEventsAndMarksItsSessionUnbound() throws Exception {
        Path stateFile = tempDir.resolve("migration-v4/state.properties");
        java.nio.file.Files.createDirectories(stateFile.getParent());
        java.nio.file.Files.writeString(stateFile, """
                version=4
                cash=20
                quantity=0
                entryPrice=
                stopPrice=
                entryTime=
                lastProcessedCandle=
                referenceEquity=20
                peakEquity=20
                dayStartEquity=20
                riskDayUtc=2026-10-22
                dailyLocked=false
                cumulativeLocked=false
                killSwitchLatched=false
                entryTotalCost=
                totalFees=0
                realizedPnl=0
                closedTrades=0
                winningTrades=0
                losingTrades=0
                breakEvenTrades=0
                eventCount=0
                """);

        PaperTradingState migrated = new PaperTradingStateStore(stateFile).load().orElseThrow();

        assertEquals(PaperTradingState.LEGACY_UNBOUND_PROFILE, migrated.sessionProfile());
        assertTrue(migrated.eventHistory().isEmpty());
        assertTrue(java.nio.file.Files.readString(stateFile).contains("version=5"));
    }

    @Test
    void corruptStateFailsClosed() throws Exception {
        Path stateFile = tempDir.resolve("broken.properties");
        java.nio.file.Files.writeString(stateFile, "version=unknown\n");
        PaperTradingStateStore store = new PaperTradingStateStore(stateFile);

        assertThrows(IllegalStateException.class, store::load);
    }

    @Test
    void firstPaperCycleOnlyCreatesBaseline() {
        Instant now = Instant.parse("2026-10-22T12:45:00Z");
        List<Candle> candles = candles(now.minusSeconds(100L * 15 * 60));
        PaperTradingState state = PaperTradingState.initial(new BigDecimal("20"), "2026-10-22");

        PaperTradingEngine.Outcome result = new PaperTradingEngine().process(
                state, candles, new BigDecimal("3500"), false, now);

        assertEquals(candles.getLast().timestamp(), result.state().lastProcessedCandle());
        assertTrue(result.events().stream().anyMatch(event -> event.type().equals("BASELINE")));
        assertFalse(result.state().hasPosition());
    }

    @Test
    void killSwitchClosesVirtualPositionAndRemainsLatched() {
        Instant now = Instant.parse("2026-10-22T12:45:00Z");
        List<Candle> candles = candles(now.minusSeconds(100L * 15 * 60));
        Instant lastCandle = candles.getLast().timestamp();
        PaperTradingState open = new PaperTradingState(new BigDecimal("14.99775"), new BigDecimal("0.05"),
                new BigDecimal("100"), new BigDecimal("98.5"), now.minusSeconds(3600), lastCandle,
                new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("20"), "2026-10-22",
                false, false, false, new BigDecimal("5.00225"), new BigDecimal("0.00225"),
                BigDecimal.ZERO, 0, 0, 0, 0, List.of());

        PaperTradingEngine.Outcome result = new PaperTradingEngine().process(
                open, candles, new BigDecimal("100"), true, now);

        assertTrue(result.state().killSwitchLatched());
        assertFalse(result.state().hasPosition());
        assertTrue(result.events().stream().anyMatch(event -> event.reason().equals("KILL_SWITCH")));
        assertTrue(result.state().cash().compareTo(new BigDecimal("20")) < 0);
        assertEquals(1, result.state().closedTrades());
        assertEquals(1, result.state().losingTrades());
        assertEquals(0, result.state().realizedPnl().compareTo(new BigDecimal("-0.004999775")));
        assertTrue(result.state().totalFees().compareTo(new BigDecimal("0.004")) > 0);

        PaperTradingStateStore store = new PaperTradingStateStore(tempDir.resolve("closed/state.properties"));
        store.save(result.state());
        assertEquals(result.state(), store.load().orElseThrow());
    }

    @Test
    void spotEquityAddsOwnedBaseAtCurrentMarkInsteadOfLeveragedPnl() {
        PaperTradingState state = new PaperTradingState(new BigDecimal("15"), new BigDecimal("0.05"),
                new BigDecimal("100"), new BigDecimal("98.5"), Instant.parse("2026-10-22T11:00:00Z"),
                Instant.parse("2026-10-22T12:30:00Z"), new BigDecimal("20"), new BigDecimal("20"),
                new BigDecimal("20"), "2026-10-22", false, false, false, new BigDecimal("5"),
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, List.of());

        assertEquals(new BigDecimal("20.50"), state.equityAt(new BigDecimal("110")));
    }

    @Test
    void missedClosedCandlesBlockPaperProcessing() {
        Instant now = Instant.parse("2026-10-22T12:45:00Z");
        List<Candle> candles = candles(now.minusSeconds(100L * 15 * 60));
        PaperTradingState initial = PaperTradingState.initial(new BigDecimal("20"), "2026-10-22");
        PaperTradingState late = new PaperTradingState(initial.cash(), initial.quantity(), null, null,
                null, candles.get(candles.size() - 3).timestamp(), initial.referenceEquity(),
                initial.peakEquity(), initial.dayStartEquity(), initial.riskDayUtc(), false, false, false,
                null, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, List.of());

        assertThrows(IllegalStateException.class, () -> new PaperTradingEngine().process(
                late, candles, new BigDecimal("3500"), false, now));
    }

    private static List<Candle> candles(Instant first) {
        var candles = new ArrayList<Candle>();
        for (int i = 0; i < 100; i++) {
            Instant timestamp = first.plusSeconds(i * 15L * 60);
            candles.add(new Candle(timestamp, new BigDecimal("100"), new BigDecimal("101"),
                    new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE));
        }
        return List.copyOf(candles);
    }
}

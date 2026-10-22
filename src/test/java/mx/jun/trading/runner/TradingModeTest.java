package mx.jun.trading.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradingModeTest {
    @Test
    void acceptsBacktestCaseInsensitively() {
        assertEquals(TradingMode.BACKTEST, TradingMode.parse(" backtest "));
        TradingMode.parse("backtest").requireImplemented();
    }

    @Test
    void rejectsUnimplementedModesIncludingLive() {
        for (String mode : new String[]{"TESTNET", "LIVE"}) {
            assertThrows(IllegalStateException.class,
                    () -> TradingMode.parse(mode).requireImplemented());
        }
    }

    @Test
    void allowsPaperSimulation() {
        TradingMode.parse("PAPER").requireImplemented();
    }

    @Test
    void allowsReadOnlyAccountCheckButNotLiveTrading() {
        TradingMode.parse("ACCOUNT_CHECK").requireImplemented();
        assertThrows(IllegalStateException.class, () -> TradingMode.LIVE.requireImplemented());
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> TradingMode.parse("REAL-ish"));
    }
}

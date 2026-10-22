package mx.jun.trading.runner;

import java.util.Locale;

/** Explicitly named modes; PAPER is simulated and cannot send exchange orders. */
public enum TradingMode {
    BACKTEST,
    PAPER,
    ACCOUNT_CHECK,
    TESTNET,
    LIVE;

    public static TradingMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("trading.mode no puede estar vacío");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "trading.mode debe ser BACKTEST, PAPER, ACCOUNT_CHECK, TESTNET o LIVE", e);
        }
    }

    public void requireImplemented() {
        if (this != BACKTEST && this != PAPER && this != ACCOUNT_CHECK) {
            throw new IllegalStateException(
                    "Modo " + this + " bloqueado: no está implementado; no se iniciará ninguna operación");
        }
    }
}

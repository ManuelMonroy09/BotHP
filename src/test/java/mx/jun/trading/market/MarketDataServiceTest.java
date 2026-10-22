package mx.jun.trading.market;

import mx.jun.trading.backtest.BacktestEngine;
import mx.jun.trading.backtest.BacktestResult;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataServiceTest {

    @Test
    void syntheticCandlesProduceBullishSignalAndAtLeastOneBacktestTrade() {
        List<Candle> candles = new MarketDataService().getCandles();

        List<EmaRegimeVolatilityStrategy.Signal> signals =
                new EmaRegimeVolatilityStrategy().evaluateAll(candles);

        assertTrue(signals.contains(EmaRegimeVolatilityStrategy.Signal.BUY),
                "Las velas sintéticas deben incluir un cruce alcista válido");

        BacktestResult result = new BacktestEngine().run(candles, new BigDecimal("20"));

        assertFalse(result.trades().isEmpty(),
                "El backtest de demostración debe ejecutar al menos una operación");
    }
}

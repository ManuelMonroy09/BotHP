package mx.jun.trading.runner;

import mx.jun.trading.market.Candle;
import mx.jun.trading.market.HyperliquidMarketDataService;
import mx.jun.trading.market.SpotMarketSpec;
import mx.jun.trading.paper.PaperEvent;
import mx.jun.trading.paper.PaperTradingEngine;
import mx.jun.trading.paper.PaperTradingState;
import mx.jun.trading.paper.PaperTradingStateStore;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Public-data-only PAPER loop. This class has no order or signing client. */
@Component
public class PaperTradingRunner implements CommandLineRunner {
    private final HyperliquidMarketDataService marketData;
    private final String mode;
    private final String coin;
    private final String quoteAsset;
    private final String interval;
    private final EmaRegimeVolatilityStrategy.Mode strategyMode;
    private final BigDecimal startingCapital;
    private final Path statePath;
    private final long pollSeconds;
    private final boolean allowLongs;
    private final boolean allowShorts;
    private final PaperTradingEngine engine;
    private final PaperPollScheduler pollScheduler = new PaperPollScheduler();
    private final Clock clock = Clock.systemUTC();

    public PaperTradingRunner(HyperliquidMarketDataService marketData,
                              @Value("${trading.mode:BACKTEST}") String mode,
                              @Value("${hyperliquid.coin:ETH}") String coin,
                              @Value("${hyperliquid.quote-asset:USDC}") String quoteAsset,
                              @Value("${hyperliquid.interval:15m}") String interval,
                              @Value("${trading.paper.strategy:EMA_CROSS}") String strategy,
                              @Value("${trading.paper.starting-capital:20}") BigDecimal startingCapital,
                              @Value("${trading.paper.state-path:data/paper/state.properties}") String statePath,
                              @Value("${trading.paper.poll-seconds:15}") long pollSeconds,
                              @Value("${trading.strategy.allow-longs:true}") boolean allowLongs,
                              @Value("${trading.strategy.allow-shorts:false}") boolean allowShorts) {
        this.marketData = marketData;
        this.mode = mode;
        this.coin = coin;
        this.quoteAsset = quoteAsset;
        this.interval = interval;
        this.strategyMode = EmaRegimeVolatilityStrategy.Mode.parse(strategy);
        this.startingCapital = startingCapital;
        this.statePath = Path.of(statePath).toAbsolutePath().normalize();
        this.pollSeconds = pollSeconds;
        this.allowLongs = allowLongs;
        this.allowShorts = allowShorts;
        this.engine = new PaperTradingEngine(strategyMode);
    }

    @Override
    public void run(String... args) {
        if (TradingMode.parse(mode) != TradingMode.PAPER) return;
        if (allowShorts || !allowLongs) {
            throw new IllegalStateException("PAPER solo admite la estrategia long mientras la variante direccional no pase validación");
        }
        if (strategyMode != EmaRegimeVolatilityStrategy.Mode.EMA_CROSS) {
            throw new IllegalStateException("PAPER solo habilita EMA_CROSS; RSI_TREND_PULLBACK sigue en investigación");
        }
        if (pollSeconds < 1 || startingCapital.signum() <= 0) {
            throw new IllegalArgumentException("Configuración PAPER inválida");
        }
        TradingMode.PAPER.requireImplemented();
        PaperTradingStateStore store = new PaperTradingStateStore(statePath);
        SpotMarketSpec marketSpec = marketData.getSpotMarketSpec(coin, quoteAsset);
        String sessionProfile = String.join("|", marketSpec.marketName(), interval,
                strategyMode.name(), startingCapital.stripTrailingZeros().toPlainString(), "spot-risk-v1");
        Path killSwitch = statePath.getParent().resolve("KILL_SWITCH");
        store.load().ifPresent(persisted -> validateSessionProfile(persisted, sessionProfile));
        System.out.printf("PAPER SPOT activo para %s %s con %s. Datos públicos solamente; capital virtual: %s. "
                        + "Par=%s assetId=%d szDecimals=%d; compras solo con efectivo disponible, sin margen/apalancamiento. Estado: %s%n",
                coin, interval, strategyMode, startingCapital, marketSpec.marketName(), marketSpec.assetId(),
                marketSpec.sizeDecimals(), statePath);
        if (Files.exists(killSwitch) && Files.isSymbolicLink(killSwitch)) {
            throw new IllegalStateException("KILL_SWITCH no puede ser un enlace simbólico");
        }

        while (!Thread.currentThread().isInterrupted()) {
            java.time.Duration nextDelay = java.time.Duration.ofSeconds(pollSeconds);
            try {
                if (Files.isSymbolicLink(killSwitch)) {
                    throw new IllegalStateException("KILL_SWITCH no puede ser un enlace simbólico");
                }
                PaperTradingState state = store.load().map(persisted -> validateSessionProfile(
                        persisted, sessionProfile)).orElseGet(() -> PaperTradingState.initial(
                        startingCapital, clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        sessionProfile));
                List<Candle> candles = marketData.getCandles(marketSpec.marketName(), interval, 100);
                boolean killSwitchRequested = Files.exists(killSwitch);
                boolean newClosedCandle = state.lastProcessedCandle() == null
                        || candles.getLast().timestamp().isAfter(state.lastProcessedCandle());
                boolean fetchLiveMid = state.hasPosition() || newClosedCandle || killSwitchRequested;
                BigDecimal markPrice = fetchLiveMid
                        ? marketData.getMidPrice(marketSpec.marketName()) : candles.getLast().close();
                PaperTradingEngine.Outcome outcome = engine.process(
                        state, candles, markPrice, killSwitchRequested, clock.instant(), marketSpec);
                nextDelay = pollScheduler.delayAfterCycle(newClosedCandle,
                        outcome.state().hasPosition(), candles.getLast().timestamp(),
                        outcome.candleIntervalMillis(), clock.instant(), java.time.Duration.ofSeconds(pollSeconds));
                boolean stateChanged = !outcome.state().equals(state);
                if (stateChanged) store.save(outcome.state());
                for (PaperEvent event : outcome.events()) printEvent(event);
                if (newClosedCandle || outcome.state().hasPosition() || !outcome.events().isEmpty()) {
                    BigDecimal equity = outcome.state().equityAt(markPrice);
                    BigDecimal totalPnl = equity.subtract(outcome.state().referenceEquity());
                    BigDecimal unrealizedPnl = totalPnl.subtract(outcome.state().realizedPnl());
                    System.out.printf("PAPER SPOT %s | mark=%s (%s) | equity=%s | PnL neto=%s "
                                    + "(realizado=%s no realizado=%s) | fees=%s | trades=%d W/L/BE=%d/%d/%d "
                                    + "| base=%s quote=%s | cierre=%s | locks=%s%s%s%n",
                            marketSpec.marketName(), markPrice, fetchLiveMid ? "mid público" : "último cierre confirmado",
                            equity, totalPnl, outcome.state().realizedPnl(), unrealizedPnl,
                            outcome.state().totalFees(), outcome.state().closedTrades(),
                            outcome.state().winningTrades(), outcome.state().losingTrades(),
                            outcome.state().breakEvenTrades(), outcome.state().quantity(),
                            outcome.state().cash(), outcome.state().lastProcessedCandle(),
                            outcome.state().dailyLocked() ? "daily " : "",
                            outcome.state().cumulativeLocked() ? "cumulative " : "",
                            outcome.state().killSwitchLatched() ? "kill-switch" : "");
                }
            } catch (RuntimeException e) {
                System.err.println("PAPER bloqueado en este ciclo; no se actualizará el estado: " + e.getMessage());
            }
            try {
                sleepResponsive(nextDelay, killSwitch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sleepResponsive(Duration delay, Path killSwitch) throws InterruptedException {
        long remainingMillis = delay.toMillis();
        long retryMillis = Duration.ofSeconds(pollSeconds).toMillis();
        while (remainingMillis > 0 && !Thread.currentThread().isInterrupted()
                && !Files.exists(killSwitch)) {
            long sleepMillis = Math.min(remainingMillis, retryMillis);
            Thread.sleep(sleepMillis);
            remainingMillis -= sleepMillis;
        }
    }

    private static PaperTradingState validateSessionProfile(PaperTradingState state, String expectedProfile) {
        if (PaperTradingState.LEGACY_UNBOUND_PROFILE.equals(state.sessionProfile())) {
            return state.bindSession(expectedProfile);
        }
        if (!expectedProfile.equals(state.sessionProfile())) {
            throw new IllegalStateException("El estado PAPER pertenece a otra configuración ("
                    + state.sessionProfile() + "); usa una ruta de estado nueva para esta sesión");
        }
        return state;
    }

    private static void printEvent(PaperEvent event) {
        System.out.printf("PAPER_EVENT %s type=%s reason=%s price=%s quantity=%s fee=%s equity=%s tradePnl=%s%n",
                Instant.now(), event.type(), event.reason(), event.price(), event.quantity(),
                event.fee(), event.equity(), event.realizedPnl());
    }
}

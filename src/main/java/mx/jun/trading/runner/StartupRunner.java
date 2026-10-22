package mx.jun.trading.runner;

import mx.jun.trading.backtest.BacktestEngine;
import mx.jun.trading.backtest.BacktestRiskConfig;
import mx.jun.trading.backtest.CandleCsvStore;
import mx.jun.trading.backtest.OutOfSampleEvaluator;
import mx.jun.trading.backtest.PerformanceAnalyzer;
import mx.jun.trading.backtest.TradeDiagnostics;
import mx.jun.trading.market.Candle;
import mx.jun.trading.market.FundingRate;
import mx.jun.trading.market.HyperliquidMarketDataService;
import mx.jun.trading.market.MarketDataService;
import mx.jun.trading.strategy.EmaRegimeVolatilityStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Component
public class StartupRunner implements CommandLineRunner {
    private final MarketDataService syntheticMarketDataService;
    private final HyperliquidMarketDataService hyperliquidMarketDataService;
    private final CandleCsvStore candleCsvStore;
    private final BacktestEngine backtestEngine = new BacktestEngine();
    private final OutOfSampleEvaluator outOfSampleEvaluator = new OutOfSampleEvaluator();
    private final PerformanceAnalyzer performanceAnalyzer = new PerformanceAnalyzer();
    private final String dataSource;
    private final String coin;
    private final String interval;
    private final int candleCount;
    private final String csvPath;
    private final String evaluationMode;
    private final String tradingMode;
    private final boolean allowLongs;
    private final boolean allowShorts;
    private final BigDecimal feeRate;
    private final BigDecimal slippageRate;
    private final BigDecimal takeProfitPercentage;
    private final EmaRegimeVolatilityStrategy.Mode strategyMode;
    private final boolean includeFunding;

    public StartupRunner(
            MarketDataService syntheticMarketDataService,
            HyperliquidMarketDataService hyperliquidMarketDataService,
            CandleCsvStore candleCsvStore,
            @Value("${trading.data-source:synthetic}") String dataSource,
            @Value("${hyperliquid.coin:ETH}") String coin,
            @Value("${hyperliquid.interval:15m}") String interval,
            @Value("${trading.candle-count:500}") int candleCount,
            @Value("${trading.csv-path:}") String csvPath,
            @Value("${trading.evaluation:standard}") String evaluationMode,
            @Value("${trading.mode:BACKTEST}") String tradingMode,
            @Value("${trading.strategy.allow-longs:true}") boolean allowLongs,
            @Value("${trading.strategy.allow-shorts:false}") boolean allowShorts,
            @Value("${trading.backtest.fee-rate:0.00045}") BigDecimal feeRate,
            @Value("${trading.backtest.slippage-rate:0.00010}") BigDecimal slippageRate,
            @Value("${trading.backtest.take-profit-percentage:0}") BigDecimal takeProfitPercentage,
            @Value("${trading.backtest.strategy:EMA_CROSS}") String strategyMode,
            @Value("${trading.backtest.include-funding:true}") boolean includeFunding) {
        this.syntheticMarketDataService = syntheticMarketDataService;
        this.hyperliquidMarketDataService = hyperliquidMarketDataService;
        this.candleCsvStore = candleCsvStore;
        this.dataSource = dataSource;
        this.coin = coin;
        this.interval = interval;
        this.candleCount = candleCount;
        this.csvPath = csvPath;
        this.evaluationMode = evaluationMode;
        this.tradingMode = tradingMode;
        this.allowLongs = allowLongs;
        this.allowShorts = allowShorts;
        this.feeRate = feeRate;
        this.slippageRate = slippageRate;
        this.takeProfitPercentage = takeProfitPercentage;
        this.strategyMode = EmaRegimeVolatilityStrategy.Mode.parse(strategyMode);
        this.includeFunding = includeFunding;
    }

    @Override
    public void run(String... args) {
        long runStartedAt = System.nanoTime();
        TradingMode mode = TradingMode.parse(tradingMode);
        mode.requireImplemented();
        if (mode == TradingMode.PAPER || mode == TradingMode.ACCOUNT_CHECK) return;
        BacktestRiskConfig defaults = BacktestRiskConfig.conservativeDefaults();
        BacktestRiskConfig riskConfig = new BacktestRiskConfig(defaults.riskPerTradePercentage(),
                defaults.stopLossPercentage(), defaults.maxPositionNotionalPercentage(),
                defaults.dailyLossLimitPercentage(), defaults.cumulativeDrawdownLimitPercentage(),
                takeProfitPercentage);
        List<Candle> candles;
        Path datasetPath = null;
        String source = dataSource.toLowerCase(Locale.ROOT);
        String evalMode = evaluationMode.toLowerCase(Locale.ROOT);
        if ("hyperliquid".equals(source)) {
            candles = hyperliquidMarketDataService.getCandles(coin, interval, candleCount);
            datasetPath = candleCsvStore.saveSnapshot(coin, interval, candles);
            System.out.printf("Fuente: Hyperliquid pública | Activo: %s | Intervalo: %s%n", coin, interval);
        } else if ("csv".equals(source)) {
            if (csvPath == null || csvPath.isBlank()) {
                throw new IllegalArgumentException(
                        "Para trading.data-source=csv debes indicar --trading.csv-path=ruta/al/dataset.csv");
            }
            datasetPath = Path.of(csvPath);
            candles = candleCsvStore.load(datasetPath);
            System.out.println("Fuente: dataset CSV guardado | " + datasetPath.toAbsolutePath());
        } else if ("synthetic".equals(source)) {
            candles = syntheticMarketDataService.getCandles();
            System.out.println("Fuente: velas sintéticas de prueba (no usar para evaluar rentabilidad)");
        } else {
            throw new IllegalArgumentException(
                    "trading.data-source debe ser 'synthetic', 'hyperliquid' o 'csv', valor recibido: " + dataSource);
        }
        List<FundingRate> fundingRates = List.of();
        if (includeFunding && "synthetic".equals(source)) {
            System.out.println("Funding: omitido en datos sintéticos");
        }
        if (includeFunding && datasetPath != null && !"synthetic".equals(source)) {
            boolean outOfSample = "out-of-sample".equals(evalMode);
            List<Candle> fundingCandles = candles;
            Path fundingBasePath = datasetPath;
            if (outOfSample) {
                int splitIndex = (int) Math.floor(candles.size() * OutOfSampleEvaluator.DEFAULT_DEVELOPMENT_FRACTION);
                int fundingStartIndex = Math.max(0, splitIndex - OutOfSampleEvaluator.WARMUP_CANDLES);
                fundingCandles = List.copyOf(candles.subList(fundingStartIndex, candles.size()));
                fundingBasePath = Path.of(datasetPath.toString().replaceFirst("(?i)\\.csv$", "_out_of_sample.csv"));
            }
            Path fundingPath = fundingBasePath.resolveSibling(
                    fundingBasePath.getFileName().toString().replaceFirst("(?i)\\.csv$", "_funding.csv"));
            if (Files.exists(fundingPath)) {
                fundingRates = candleCsvStore.loadFundingRates(fundingPath);
                if (fundingRates.isEmpty()) {
                    throw new IllegalStateException("El archivo de funding no contiene eventos: " + fundingPath);
                }
                validateFundingCoverage(fundingCandles, fundingRates);
                System.out.println("Funding histórico CSV: " + fundingPath.toAbsolutePath());
                System.out.println("Eventos de funding cargados: " + fundingRates.size());
            } else {
                long intervalMillis = fundingCandles.size() > 1
                        ? java.time.Duration.between(fundingCandles.get(0).timestamp(), fundingCandles.get(1).timestamp()).toMillis()
                        : 0;
                fundingRates = hyperliquidMarketDataService.getFundingHistory(coin,
                        fundingCandles.getFirst().timestamp().minus(java.time.Duration.ofHours(1)),
                        fundingCandles.getLast().timestamp().plusMillis(intervalMillis));
                if (fundingRates.isEmpty()) {
                    throw new IllegalStateException("No hay funding histórico para cubrir el dataset; se cancela el backtest.");
                }
                validateFundingCoverage(fundingCandles, fundingRates);
                candleCsvStore.saveFundingRates(fundingBasePath.toString(), fundingRates);
                System.out.println("Funding histórico descargado: " + fundingRates.size() + " eventos (solo lectura pública)");
            }
        }
        long dataLoadedAt = System.nanoTime();

        System.out.println("Velas recibidas: " + candles.size());
        System.out.println("Primera vela: " + candles.get(0).timestamp());
        System.out.println("Última vela: " + candles.get(candles.size() - 1).timestamp());
        System.out.println("Último cierre: " + candles.get(candles.size() - 1).close());
        if (datasetPath != null) {
            System.out.println("Dataset CSV: " + datasetPath.toAbsolutePath());
            System.out.println("SHA-256 dataset: " + candleCsvStore.sha256(datasetPath));
        }

        if ("out-of-sample".equals(evalMode)) {
            if (!"csv".equals(source)) {
                throw new IllegalArgumentException(
                        "La evaluacion fuera de muestra requiere un dataset CSV fijo. Usa --trading.data-source=csv");
            }
            var evaluation = outOfSampleEvaluator.evaluate(candles, new BigDecimal("20"),
                    OutOfSampleEvaluator.DEFAULT_DEVELOPMENT_FRACTION, allowLongs, allowShorts,
                    feeRate, slippageRate, riskConfig, strategyMode, fundingRates);
            long calculationDoneAt = System.nanoTime();
            System.out.println("=== EVALUACION FUERA DE MUESTRA (70/30) ===");
            System.out.println("Direcciones permitidas: " + directionLabel());
            System.out.println("Take-profit: " + takeProfitPercentage.multiply(BigDecimal.valueOf(100)) + "%");
            System.out.println("Estrategia: " + strategyMode);
            System.out.println("Periodo de desarrollo (no se optimiza en esta ejecucion): "
                    + evaluation.developmentStart().timestamp() + " -> "
                    + evaluation.developmentEnd().timestamp());
            System.out.println("Periodo de evaluacion: "
                    + evaluation.evaluationStart().timestamp() + " -> "
                    + evaluation.evaluationEnd().timestamp());
            System.out.println("Velas de calentamiento previas: " + evaluation.warmupCandles()
                    + " (solo para inicializar indicadores; sin operaciones durante el calentamiento)");
            System.out.println("Velas del periodo de evaluacion: "
                    + (candles.size() - evaluation.splitIndex()));
            System.out.println(performanceAnalyzer.summary(evaluation.result())
                    .replace("=== BACKTEST OPCION C ===", "=== RESULTADOS SOLO DEL PERIODO DE EVALUACION ==="));
            String evaluationDatasetPath = datasetPath.toString().replaceFirst("(?i)\\.csv$", "_out_of_sample.csv");
            Path tradesPath = candleCsvStore.saveTrades(evaluationDatasetPath, evaluation.result().trades());
            System.out.println("Operaciones fuera de muestra CSV: " + tradesPath.toAbsolutePath());
            Path diagnosticsPath = new TradeDiagnostics().save(
                    Path.of(evaluationDatasetPath), candles, evaluation.result().trades());
            System.out.println("Diagnostico fuera de muestra CSV: " + diagnosticsPath.toAbsolutePath());
            printTimings(runStartedAt, dataLoadedAt, calculationDoneAt, System.nanoTime());
            System.out.println("Nota: esta prueba no garantiza rentabilidad futura y no envia ordenes reales.");
            return;
        }
        if (!"standard".equals(evalMode)) {
            throw new IllegalArgumentException(
                    "trading.evaluation debe ser 'standard' o 'out-of-sample', valor recibido: " + evaluationMode);
        }

        System.out.println("Direcciones permitidas: " + directionLabel());
        System.out.println("Take-profit: " + takeProfitPercentage.multiply(BigDecimal.valueOf(100)) + "%");
        System.out.println("Estrategia: " + strategyMode);
        System.out.println("Funding incluido: " + !fundingRates.isEmpty());
        var result = backtestEngine.run(candles, new BigDecimal("20"), feeRate, slippageRate,
                riskConfig, allowLongs, allowShorts, strategyMode, fundingRates);
        long calculationDoneAt = System.nanoTime();
        System.out.println(performanceAnalyzer.summary(result));
        if (datasetPath != null) {
            Path tradesPath = candleCsvStore.saveTrades(datasetPath.toString(), result.trades());
            System.out.println("Operaciones CSV: " + tradesPath.toAbsolutePath());
            Path diagnosticsPath = new TradeDiagnostics().save(datasetPath, candles, result.trades());
            System.out.println("Diagnostico de operaciones CSV: " + diagnosticsPath.toAbsolutePath());
        }
        printTimings(runStartedAt, dataLoadedAt, calculationDoneAt, System.nanoTime());
    }

    private String directionLabel() {
        if (allowLongs && allowShorts) return "LONG y SHORT";
        if (allowShorts) return "solo SHORT";
        if (allowLongs) return "solo LONG";
        return "ninguna";
    }

    private static void validateFundingCoverage(List<Candle> candles, List<FundingRate> rates) {
        long intervalMillis = java.time.Duration.between(
                candles.get(0).timestamp(), candles.get(1).timestamp()).toMillis();
        long maxGapMillis = java.time.Duration.ofHours(1).toMillis() + 5_000;
        if (rates.getFirst().timestamp().isAfter(candles.getFirst().timestamp().plusMillis(maxGapMillis))) {
            throw new IllegalStateException("El historial de funding no cubre el inicio del dataset.");
        }
        Instant lastCandleClose = candles.getLast().timestamp().plusMillis(intervalMillis);
        if (rates.getLast().timestamp().isBefore(lastCandleClose.minusMillis(maxGapMillis))) {
            throw new IllegalStateException("El historial de funding no cubre el final del dataset.");
        }
        for (int i = 1; i < rates.size(); i++) {
            long gap = java.time.Duration.between(rates.get(i - 1).timestamp(), rates.get(i).timestamp()).toMillis();
            if (gap > maxGapMillis) {
                throw new IllegalStateException("Hay un hueco de más de una hora en el historial de funding: "
                        + rates.get(i - 1).timestamp() + " -> " + rates.get(i).timestamp() + " ("
                        + java.time.Duration.ofMillis(gap) + "); "
                        + "se cancela el backtest para evitar ignorar costes.");
            }
        }
    }

    private static void printTimings(long start, long dataLoaded, long calculationDone, long finished) {
        System.out.printf("Tiempo: carga=%d ms | cálculo=%d ms | exportación=%d ms | total=%d ms%n",
                (dataLoaded - start) / 1_000_000,
                (calculationDone - dataLoaded) / 1_000_000,
                (finished - calculationDone) / 1_000_000,
                (finished - start) / 1_000_000);
    }
}

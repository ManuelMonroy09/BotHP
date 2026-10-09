package mx.jun.trading.runner;

import mx.jun.trading.backtest.BacktestEngine;
import mx.jun.trading.backtest.CandleCsvStore;
import mx.jun.trading.backtest.OutOfSampleEvaluator;
import mx.jun.trading.backtest.PerformanceAnalyzer;
import mx.jun.trading.market.Candle;
import mx.jun.trading.market.HyperliquidMarketDataService;
import mx.jun.trading.market.MarketDataService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
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

    public StartupRunner(
            MarketDataService syntheticMarketDataService,
            HyperliquidMarketDataService hyperliquidMarketDataService,
            CandleCsvStore candleCsvStore,
            @Value("${trading.data-source:synthetic}") String dataSource,
            @Value("${hyperliquid.coin:ETH}") String coin,
            @Value("${hyperliquid.interval:15m}") String interval,
            @Value("${trading.candle-count:500}") int candleCount,
            @Value("${trading.csv-path:}") String csvPath,
            @Value("${trading.evaluation:standard}") String evaluationMode) {
        this.syntheticMarketDataService = syntheticMarketDataService;
        this.hyperliquidMarketDataService = hyperliquidMarketDataService;
        this.candleCsvStore = candleCsvStore;
        this.dataSource = dataSource;
        this.coin = coin;
        this.interval = interval;
        this.candleCount = candleCount;
        this.csvPath = csvPath;
        this.evaluationMode = evaluationMode;
    }

    @Override
    public void run(String... args) {
        List<Candle> candles;
        Path datasetPath = null;
        String source = dataSource.toLowerCase(Locale.ROOT);
        String mode = evaluationMode.toLowerCase(Locale.ROOT);
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

        System.out.println("Velas recibidas: " + candles.size());
        System.out.println("Primera vela: " + candles.get(0).timestamp());
        System.out.println("Última vela: " + candles.get(candles.size() - 1).timestamp());
        System.out.println("Último cierre: " + candles.get(candles.size() - 1).close());
        if (datasetPath != null) {
            System.out.println("Dataset CSV: " + datasetPath.toAbsolutePath());
            System.out.println("SHA-256 dataset: " + candleCsvStore.sha256(datasetPath));
        }

        if ("out-of-sample".equals(mode)) {
            if (!"csv".equals(source)) {
                throw new IllegalArgumentException(
                        "La evaluacion fuera de muestra requiere un dataset CSV fijo. Usa --trading.data-source=csv");
            }
            var evaluation = outOfSampleEvaluator.evaluate(candles, new BigDecimal("20"));
            System.out.println("=== EVALUACION FUERA DE MUESTRA (70/30) ===");
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
            System.out.println("Nota: esta prueba no garantiza rentabilidad futura y no envia ordenes reales.");
            return;
        }
        if (!"standard".equals(mode)) {
            throw new IllegalArgumentException(
                    "trading.evaluation debe ser 'standard' o 'out-of-sample', valor recibido: " + evaluationMode);
        }

        var result = backtestEngine.run(candles, new BigDecimal("20"));
        System.out.println(performanceAnalyzer.summary(result));
        if (datasetPath != null) {
            Path tradesPath = candleCsvStore.saveTrades(datasetPath.toString(), result.trades());
            System.out.println("Operaciones CSV: " + tradesPath.toAbsolutePath());
        }
    }
}

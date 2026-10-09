package mx.jun.trading.backtest;

import mx.jun.trading.market.Candle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Evalua la estrategia en un tramo cronologico posterior al periodo de desarrollo.
 * No optimiza parametros ni usa resultados del periodo de evaluacion para ajustarlos.
 */
public class OutOfSampleEvaluator {
    public static final double DEFAULT_DEVELOPMENT_FRACTION = 0.70;
    public static final int WARMUP_CANDLES = 50;

    private final BacktestEngine backtestEngine;

    public OutOfSampleEvaluator() {
        this(new BacktestEngine());
    }

    OutOfSampleEvaluator(BacktestEngine backtestEngine) {
        this.backtestEngine = backtestEngine;
    }

    public Evaluation evaluate(List<Candle> candles, BigDecimal initialCapital) {
        return evaluate(candles, initialCapital, DEFAULT_DEVELOPMENT_FRACTION);
    }

    public Evaluation evaluate(List<Candle> candles, BigDecimal initialCapital, double developmentFraction) {
        if (candles == null) {
            throw new IllegalArgumentException("El dataset no puede ser null");
        }
        if (initialCapital == null || initialCapital.signum() <= 0) {
            throw new IllegalArgumentException("El capital inicial debe ser positivo");
        }
        if (!Double.isFinite(developmentFraction)
                || developmentFraction <= 0.0 || developmentFraction >= 1.0) {
            throw new IllegalArgumentException("La fraccion de desarrollo debe estar entre 0 y 1");
        }

        int splitIndex = (int) Math.floor(candles.size() * developmentFraction);
        int evaluationCandleCount = candles.size() - splitIndex;
        if (splitIndex < WARMUP_CANDLES + 1 || evaluationCandleCount < WARMUP_CANDLES + 1) {
            throw new IllegalArgumentException(
                    "Se necesitan al menos 51 velas en desarrollo y 51 en evaluacion; dataset recibido: "
                            + candles.size());
        }

        validateChronologicalData(candles);
        // Se incluyen 50 velas previas solo para inicializar indicadores.
        // BacktestEngine no abre operaciones durante el calentamiento: la primera
        // señal evaluable corresponde a splitIndex y se ejecuta, como pronto, en splitIndex + 1.
        List<Candle> evaluationWithWarmup = List.copyOf(
                candles.subList(splitIndex - WARMUP_CANDLES, candles.size()));
        BacktestResult result = backtestEngine.run(evaluationWithWarmup, initialCapital);

        return new Evaluation(splitIndex, WARMUP_CANDLES, candles.get(0),
                candles.get(splitIndex - 1), candles.get(splitIndex),
                candles.get(candles.size() - 1), evaluationWithWarmup, result);
    }

    private static void validateChronologicalData(List<Candle> candles) {
        Candle previous = null;
        for (int i = 0; i < candles.size(); i++) {
            Candle current = candles.get(i);
            if (current == null || current.timestamp() == null) {
                throw new IllegalArgumentException("Vela o timestamp null en indice " + i);
            }
            if (previous != null && !current.timestamp().isAfter(previous.timestamp())) {
                throw new IllegalArgumentException(
                        "El dataset completo debe estar ordenado cronologicamente sin duplicados");
            }
            previous = current;
        }
    }

    public record Evaluation(
            int splitIndex,
            int warmupCandles,
            Candle developmentStart,
            Candle developmentEnd,
            Candle evaluationStart,
            Candle evaluationEnd,
            List<Candle> candlesWithWarmup,
            BacktestResult result) {
    }
}

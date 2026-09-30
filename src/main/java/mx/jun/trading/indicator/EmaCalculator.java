package mx.jun.trading.indicator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class EmaCalculator {

    public List<BigDecimal> calculate(List<BigDecimal> prices, int period) {

        if (prices == null || prices.isEmpty()) {
            return List.of();
        }

        if (period <= 0) {
            throw new IllegalArgumentException("El periodo debe ser mayor que 0");
        }

        if (prices.size() < period) {
            throw new IllegalArgumentException(
                    "No hay suficientes precios para calcular una EMA " + period);
        }

        List<BigDecimal> emaValues = new ArrayList<>();

        // Primera EMA: utilizamos una SMA como punto de partida
        BigDecimal sum = BigDecimal.ZERO;

        for (int i = 0; i < period; i++) {
            sum = sum.add(prices.get(i));
        }

        BigDecimal previousEma = sum
                .divide(BigDecimal.valueOf(period), 10, java.math.RoundingMode.HALF_UP);

        emaValues.add(previousEma);

        // Factor de suavizado
        BigDecimal multiplier = BigDecimal.valueOf(2)
                .divide(
                        BigDecimal.valueOf(period + 1),
                        10,
                        java.math.RoundingMode.HALF_UP
                );

        // Calculamos las siguientes EMA
        for (int i = period; i < prices.size(); i++) {

            BigDecimal currentPrice = prices.get(i);

            BigDecimal currentEma = currentPrice
                    .subtract(previousEma)
                    .multiply(multiplier)
                    .add(previousEma);

            emaValues.add(currentEma);

            previousEma = currentEma;
        }

        return emaValues;
    }
}

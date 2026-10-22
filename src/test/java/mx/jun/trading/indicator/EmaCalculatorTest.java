package mx.jun.trading.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmaCalculatorTest {

    @Test
    void shouldCalculateEma() {

        EmaCalculator calculator = new EmaCalculator();

        List<BigDecimal> prices = List.of(
                new BigDecimal("100"),
                new BigDecimal("101"),
                new BigDecimal("102"),
                new BigDecimal("103"),
                new BigDecimal("104")
        );

        List<BigDecimal> result = calculator.calculate(prices, 3);

        assertEquals(3, result.size());

        assertEquals(
                new BigDecimal("101.0000000000"),
                result.get(0)
        );

        assertEquals(
                0,
                result.get(1).compareTo(new BigDecimal("102.0000000000"))
        );
    }

    @Test
    void mantienePrecisionAcotadaEnSeriesLargas() {
        List<BigDecimal> prices = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(i -> BigDecimal.valueOf(2_000 + (i % 17), 2))
                .toList();

        List<BigDecimal> result = new EmaCalculator().calculate(prices, 20);

        assertEquals(4_981, result.size());
        assertTrue(result.stream().allMatch(value -> value.scale() <= 10));
    }
}

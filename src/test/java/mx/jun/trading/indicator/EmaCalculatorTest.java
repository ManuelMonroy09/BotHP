package mx.jun.trading.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
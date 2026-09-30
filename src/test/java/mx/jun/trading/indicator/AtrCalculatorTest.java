package mx.jun.trading.indicator;

import mx.jun.trading.market.Candle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AtrCalculatorTest {
    @Test
    void calculaAtrConVelasValidas() {
        Instant t = Instant.parse("2026-09-25T20:00:00Z");
        List<Candle> candles = List.of(
                new Candle(t, bd("10"), bd("12"), bd("9"), bd("11"), bd("1")),
                new Candle(t.plusSeconds(900), bd("11"), bd("14"), bd("10"), bd("13"), bd("1")),
                new Candle(t.plusSeconds(1800), bd("13"), bd("15"), bd("12"), bd("14"), bd("1"))
        );
        var values = new AtrCalculator().calculate(candles, 2);
        assertEquals(1, values.size());
        assertTrue(values.get(0).compareTo(BigDecimal.ZERO) > 0);
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
# Auditoría del motor de backtest y diagnóstico

Fecha: 2026-10-22  
Rama auditada: `fix/strategy-entry-signal`  
Commit base: `eda36cdc45a679b251a9f0d42661a654017ce4e5`

## Alcance

Revisión estática del código de estrategia, motor de backtest, descarga de velas, evaluación fuera de muestra, cálculo de métricas y modos de seguridad. No se ejecutaron órdenes ni se habilitaron modos de trading. Las conclusiones describen el código inspeccionado, no una validación de rentabilidad.

## Hallazgos

### 1. Estrategia activa para `EMA_CROSS`

`StartupRunner` lee `trading.backtest.strategy`, la convierte a `EmaRegimeVolatilityStrategy.Mode` y la pasa a `BacktestEngine`. El motor crea `EmaRegimeVolatilityStrategy(strategyMode)`. `EmaCrossStrategy` es una clase separada y no es la implementación que ejecuta el backtest principal.

Para `EMA_CROSS`, una compra requiere un nuevo cruce alcista EMA20/EMA50, EMA20 por encima y creciendo, EMA50 creciendo, cierre sobre EMA50 y ATR porcentual estrictamente entre 0.05 % y 3 %. No hay un filtro explícito de distancia máxima del cierre a EMA50 ni de retorno reciente, así que las señales pueden aparecer después de un movimiento extendido. Añadir tal filtro es una hipótesis de investigación, no una mejora demostrada.

### 2. Uso de velas cerradas y anticipación

El cliente público valida el timestamp de apertura/cierre, activo, intervalo, orden cronológico, OHLC y continuidad. Descarta velas aún abiertas y rechaza datos obsoletos. El motor usa la señal calculada para la vela anterior y ejecuta en la apertura de la vela siguiente. Las EMAs y el ATR son causales, por lo que la ruta revisada no muestra un uso evidente de datos futuros para las señales. La prueba `ejecutaLaSenalEnLaSiguienteVela` verifica el desplazamiento señal/entrada.

### 3. Stops y orden intravela

El backtest calcula un stop inicial y dimensiona cantidad por presupuesto de riesgo y exposición máxima. Si el precio abre más allá del stop, usa la apertura como precio bruto de salida; de lo contrario, ejecuta al nivel de stop, añadiendo el deslizamiento modelado. Si stop y take-profit se tocan en una misma vela, el stop tiene prioridad. Esta es una suposición conservadora, pero OHLC no permite reconstruir el recorrido intravela. Los límites de pérdida que se detectan dentro de una vela también aproximan el precio de salida usando el extremo adverso de esa vela.

### 4. Comisiones y funding

La comisión de entrada se descuenta al abrir y la de salida al cerrar; el PnL neto de la operación incorpora ambas y el flujo de funding. El funding usa el cierre de la vela anterior como proxy de precio y, si ocurre dentro de una vela, se procesa antes de resolver stop/objetivo. Es una aproximación explícita, más relevante en intervalos superiores a una hora. El modelo tampoco reproduce fills parciales ni impacto de mercado.

### 5. Excursiones MFE/MAE en el diagnóstico

**Defecto corregido en este cambio:** el diagnóstico anterior agregaba máximos y mínimos de la vela de salida completa, aunque `SIGNAL_REVERSAL` cierra en la apertura de esa vela. Esto podía incluir movimiento posterior a la salida y exagerar MFE/MAE. El cálculo ahora incorpora las velas completas anteriores a la salida y el precio de ejecución como punto terminal. Solo para `END_OF_DATA`, donde la posición permanece abierta hasta el cierre, incluye el rango completo de la última vela.

Para salidas intravela, la secuencia real sigue siendo desconocida con OHLC. El nuevo cálculo evita atribuir automáticamente los extremos de la vela de salida a la operación, pero puede subestimar el recorrido favorable/adverso que ocurrió antes de ejecutarse la salida. Estas métricas son diagnósticas, no afectan al backtest.

### 6. Evaluación fuera de muestra

`OutOfSampleEvaluator` separa cronológicamente 70 % de desarrollo y 30 % de evaluación y reserva 50 velas previas para calentar indicadores. No ajusta parámetros usando el tramo evaluado. El resultado sigue dependiendo de una sola partición; para una conclusión más robusta se necesitan varios bloques temporales no utilizados durante la exploración.

### 7. Hallazgo adicional: semántica SELL en RSI_TREND_PULLBACK

La variante RSI emite `SELL` cuando RSI recupera 60 o el precio cae bajo EMA50, con semántica documentada de cerrar una posición LONG. El motor genérico también interpreta `SELL` como señal de apertura SHORT si `allow-shorts=true`. Por tanto, al habilitar shorts en el backtest, esta variante podría abrir cortos que no forman parte de la regla descrita. Antes de usar esa combinación hay que impedir que una señal de salida de la estrategia RSI abra una posición corta y añadir una prueba específica.

### 8. Seguridad

`TESTNET` y `LIVE` se rechazan en `TradingMode.requireImplemented()`. El código inspeccionado no contiene una integración de órdenes firmadas. PAPER es una simulación spot con estado persistente y controles de riesgo; no equivale a validar fills o pérdidas en el exchange. No debe habilitarse capital real basándose en estos backtests.

## Próximas investigaciones recomendadas

1. Corregir y probar la semántica `SELL` de `RSI_TREND_PULLBACK` cuando `allow-shorts=true`.
2. Comparar EMA_CROSS base frente a filtros de sobreextensión predefinidos, sin optimizar con el mismo tramo usado para evaluar.
3. Ejecutar tests y backtests reproducibles sobre datasets fijos, conservando SHA-256, parámetros, costes y resultados fuera de muestra.
4. Mantener PAPER aislado y `LIVE`/`TESTNET` bloqueados hasta completar las validaciones.

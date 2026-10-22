# Auditoría inicial y estado de preparación

Fecha: 2026-10-22

## Alcance comprobado

- Aplicación Java 21 / Spring Boot 4.1.1 con Maven Wrapper.
- Flujo actual: descarga opcional de velas públicas, señales y backtest local.
- No se encontró un adaptador de órdenes, firma de transacciones ni cliente autenticado del exchange.
- La configuración predeterminada usa datos sintéticos; una ejecución exitosa con esa fuente no evalúa el mercado.
- No aparecieron archivos `.env`, wallet o credenciales durante el inventario inicial. Esto no sustituye una revisión de secretos del contenido completo ni del historial del repositorio.
- `./mvnw test` en la línea base: 26 pruebas, 0 fallos, 0 errores.
- La prueba de arranque imprime un backtest sintético de 100 velas y una operación ganadora. No es evidencia de estrategia ni rentabilidad.

## Hallazgos priorizados

### Alta prioridad

1. El servicio de velas ordenaba los datos y descartaba duplicados silenciosamente; no detectaba huecos ni datos obsoletos. Los datos defectuosos podían llegar al backtest como si fueran una serie válida.
2. El dimensionamiento por riesgo estimaba la pérdida hasta el stop, pero no sumaba comisiones de ambos lados ni el deslizamiento de salida. El riesgo teórico podía exceder el porcentaje configurado.
3. En la línea base no existían PAPER, límites persistentes ni manejo de posiciones reales. PAPER se añadió en esta iteración; la reconciliación de órdenes reales y recuperación ante fallos siguen pendientes, así que todavía no está preparado para operar en real.

### Evidencia cuantitativa insuficiente

- La suite no valida resultados fuera de muestra con datos representativos suficientes.
- No hay modelo de funding, margen, reglas de tamaño/precisión del mercado ni ejecución parcial.
- La equity del backtest y métricas de riesgo son básicas; Sharpe, Sortino, exposición y análisis de costes no están implementados.
- El modo `out-of-sample` separa cronológicamente los datos, pero no prueba robustez por régimen ni evita por sí solo sobreajuste por iteraciones manuales.

## Cambios de esta iteración

- El adaptador de velas ahora valida activo, intervalo y timestamp de cierre según el esquema publicado por Hyperliquid.
- Rechaza respuestas con velas duplicadas, desordenadas o con huecos; ya no las corrige silenciosamente.
- Rechaza una serie cuyo último cierre confirmado tiene más de dos intervalos de antigüedad.
- Permite inyectar un reloj para que las pruebas del feed sean reproducibles.
- El tamaño de posición ahora incluye comisiones estimadas de entrada y salida, y deslizamiento adverso al stop.
- `trading.mode=PAPER` ahora ejecuta un ciclo virtual con velas cerradas y mid público; `TESTNET` y `LIVE` siguen bloqueados.
- PAPER fija una línea base al inicio, persiste cartera y límites en un archivo atómico, bloquea estado corrupto y huecos de más de una vela, y no reabre una posición en el mismo ciclo en que la cerró.
- Límites simulados: riesgo de 0.5 % por operación, stop de 1.5 %, exposición de 50 %, pérdida diaria de 2 % y pérdida acumulada de 10 %. El kill switch local cierra la posición virtual y queda enclavado tras reiniciar.
- Los estados PAPER locales y material de firma quedan excluidos mediante `.gitignore`.
- La EMA ahora redondea cada paso a 10 decimales para impedir que la precisión BigDecimal crezca en cada vela; la prueba con 5,000 valores confirma que la escala queda acotada.
- Backtest admite comparar `LONG`, `SHORT` o ambos; shorts es experimental y PAPER rechaza esa configuración. Los CSV registran dirección y las excursiones MAE/MFE de shorts usan el sentido correcto.
- Comisión y deslizamiento del backtest ahora son configurables por propiedad; el valor predeterminado representa taker 0.045 % y slippage 0.01 % por lado. No se modelan funding ni liquidaciones.
- El backtest admite un take-profit fijo experimental, desactivado por defecto y no implementado en PAPER. Si stop y objetivo aparecen en la misma vela, asume primero el stop.
- En esa etapa del trabajo el backtest permitía elegir EMA_CROSS, TIME_SERIES_MOMENTUM, DONCHIAN_BREAKOUT y RSI_MEAN_REVERSION; esos resultados quedan como registro histórico. La selección activa se redujo después a EMA_CROSS y RSI_TREND_PULLBACK, descritos al final.
- Se eliminó el servidor Tomcat MVC innecesario de la aplicación CLI; se conserva `spring-web` para el cliente de solo lectura.
- `.gitignore` ahora excluye archivos locales `.env`, material de firma y carpetas de secretos; en el inventario no aparecieron esos archivos.
- Descarga pública real verificada: ETH 15m, 5,000 velas cerradas del 2026-08-31T15:30Z al 2026-10-22T17:15Z. El lector aceptó el esquema del endpoint y guardó el CSV con huella SHA-256 `18fc1a77f22b1194ea0ecfb7af2d76aef5add9c91034a89272005f7f4869afd0`.

## Validación

Después de los cambios: `./mvnw test` pasó con 47 pruebas, 0 fallos y 0 errores.

La corrección de precisión aceleró la evaluación fuera de muestra SHORT-only sobre el mismo CSV de 5,000 velas: el tiempo interno bajó de 33,711 ms a 170 ms, y su CSV diagnóstico de 1,292,217 bytes a 3,986 bytes. El backtest completo LONG-only más su exportación midió 192 ms dentro de la aplicación (el arranque de Maven/Spring añade unos segundos).

Se arrancó PAPER durante una comprobación acotada con ETH 15m y endpoints públicos. El proceso guardó una línea base y mostró un saldo virtual de 20; no usó credenciales ni emitió órdenes. El estado de esa comprobación se escribió en `/tmp`, no en la carpeta del proyecto.

La ejecución pública de solo lectura y sin servidor local se completó tras permitir la conexión saliente. En 5,000 velas, el backtest estándar produjo 53 operaciones (9 ganadoras), PnL neto `-0.7116076514` sobre capital inicial de 20, retorno `-3.55803826%`, drawdown máximo reportado `6.65537744%`, profit factor `0.6169`, costes modelados `0.2975436550`, Sharpe anualizado `-2.392`, Sortino anualizado `-3.503` y exposición estimada `47.56%`.

Ejecución más reciente en consola visible: se descargaron otras 5,000 velas ETH 15m, del 2026-08-31T16:00Z al 2026-10-22T17:45Z. El backtest produjo 53 operaciones: 9 ganadoras y 44 perdedoras (16.98 % de acierto), PnL neto `-0.7258572851` sobre 20, retorno `-3.62928643%`, drawdown máximo `6.65537745%`, profit factor `0.6120`, costes modelados `0.2973213476`, Sharpe anualizado `-2.442` y Sortino anualizado `-3.574`. La huella SHA-256 del dataset actual es `8fa340b8e4011c0f71105495c84bfe949c54803cb419fb9ca1fe4e84b66defb0`.

La evaluación cronológica 70/30 reservó 3,500 velas de desarrollo y evaluó las últimas 1,500, del 2026-10-07T02:30Z al 2026-10-22T17:15Z, con 50 velas previas para calentamiento. Resultado fuera de muestra: 15 operaciones (2 ganadoras), PnL neto `-0.5797313493`, retorno `-2.89865675%`, drawdown máximo reportado `3.72371677%`, profit factor `0.1151`, costes modelados `0.0834198458`, Sharpe anualizado `-10.103`, Sortino anualizado `-12.765` y exposición estimada `36.60%`. Es una señal desfavorable que requiere investigación; con solo 15 operaciones, el resultado sigue siendo estadísticamente débil y no prueba por sí solo el comportamiento futuro.

Se probó take-profit fijo de 1 % y 2 % en la última ventana 70/30 del dataset actual (pruebas exploratorias posteriores a inspeccionar MFE; no independientes). El 1 % redujo la pérdida a `-0.3116085026` (`-1.5580%`), con 5 ganadoras y 10 perdedoras, profit factor `0.4740` y drawdown `2.4757%`. El 2 % terminó en `-0.3913595057` (`-1.9568%`), con 3 ganadoras y 12 perdedoras, profit factor `0.3992`. Ambos perdieron; el 1 % mejora frente al baseline de esa ventana, pero no justifica activarlo ni afirmar rentabilidad. La opción queda apagada en `0` por defecto. Hace falta validación en datos posteriores y varios regímenes antes de considerarla.

Se evaluaron variantes direccionales en ese mismo tramo final. LONG+SHORT: 27 operaciones, 5 ganadoras y 22 perdedoras, retorno `-2.75934031%`. Solo SHORT: 15 operaciones, 3 ganadoras y 12 perdedoras, retorno `-0.22680786%`, profit factor `0.8963`. Ninguna pasó como rentable; la configuración predeterminada y PAPER siguen solo LONG. Son pruebas exploratorias, ya expuestas al análisis de este período, y no constituyen un conjunto de validación independiente.

## Investigación de estrategias y nueva comparación

La evidencia publicada de momentum de series temporales estudia persistencia de 1 a 12 meses en futuros líquidos de distintas clases de activos, no asegura rentabilidad en ETH 15m ([Moskowitz, Ooi y Pedersen, 2012](https://fairmodel.econ.yale.edu/ec439/mosk.pdf)). Un estudio reciente de 75,360 reglas de Bitcoin incorpora costes y pruebas por hipótesis múltiples y encuentra algunas reglas con mejor rendimiento ajustado por riesgo fuera de muestra; no establece un 90 % de aciertos ni garantiza transferencia a perps ETH ([Deprez y Frömmel, 2024](https://doi.org/10.1016/j.iref.2024.05.003)). Los precios de mercados de predicción pueden calibrarse como probabilidades, pero un análisis de Kalshi encuentra que la habilidad predictiva y la rentabilidad son fenómenos distintos ([Kalshi Research, 2026](https://kalshi.com/research/publications/who-calibrates-markets)).

Se añadieron cuatro modos para experimentación reproducible. Comparación en la misma ventana final de 1,500 velas ETH 15m, comisiones taker y slippage configurados, capital 20:

| Señal | Direcciones | Operaciones | Acierto | Retorno | Profit factor |
|---|---|---:|---:|---:|---:|
| EMA_CROSS | LONG | 15 | 13.33 % | -2.90 % | 0.1151 |
| TIME_SERIES_MOMENTUM | LONG | 52 | 19.23 % | -1.88 % | 0.5285 |
| TIME_SERIES_MOMENTUM | LONG+SHORT | 105 | 19.05 % | -1.09 % | 0.8614 |
| DONCHIAN_BREAKOUT | LONG | 17 | 41.18 % | -0.45 % | 0.8253 |
| DONCHIAN_BREAKOUT | LONG+SHORT | 35 | 45.71 % | +1.73 % | 1.3462 |
| RSI_MEAN_REVERSION | LONG | 33 | 42.42 % | -3.86 % | 0.5405 |

La variante Donchian LONG+SHORT fue positiva en esta ventana, pero se identificó después de probar varias variantes sobre el mismo tramo; por tanto, ese resultado ya no es una prueba independiente y puede ser selección por azar. Además, su tasa de acierto fue 45.71 %, exposición 100 % y drawdown máximo 3.43 %. No se selecciona como predeterminada ni se habilita en PAPER. Ninguna variante alcanza el umbral solicitado de 90 % de operaciones ganadoras. El histórico actual tampoco permite estimar con confianza una probabilidad de ganancia de 90 %; requiere datos intactos posteriores, varios regímenes y más operaciones.

La comisión importa: la documentación vigente de Hyperliquid publica en nivel base de perps 0.045 % taker y 0.015 % maker, sujetos al volumen rodante y otros descuentos ([tarifas oficiales](https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees)). Las comparaciones de esa tabla se hicieron antes de integrar funding; la ejecución documentada más abajo ya descuenta funding histórico. El backtest sigue usando una tarifa fija, y no modela impacto de mercado ni fills parciales.

## Extensión de histórico para Donchian

La descarga oficial de velas tiene un límite de 5,000 por intervalo. El marco 4h produjo 4,999 velas con volumen real desde 2024-07-11 hasta 2026-10-22 (aprox. 2.28 años), SHA-256 `5b69dda4acea6bfcca933a772cdaef99a484a193448520789bc08dea5d2201a4`. Donchian LONG+SHORT sobre todo ese tramo: 176 trades, 28 ganadores y 148 perdedores, retorno `+16.2661%`, profit factor `1.1904`, drawdown máximo `21.5866%`. En su último 30 % cronológico (2026-02-14 a 2026-10-22): 22 trades, 0 ganadores y 22 perdedores, retorno `-10.2499%`, drawdown `11.1411%`. El acumulado positivo está concentrado antes de esa caída reciente.

La consulta diaria devolvió 2,255 barras desde 2020-08-19, pero 921 barras anteriores a 2023-02-26 tenían volumen cero. Se excluyeron para evitar contar el tramo backfilled pre-lanzamiento como operaciones reales, siguiendo el indicador de volumen observado y la documentación del endpoint (el SDK de referencia también advierte que estas barras son precios pre-lanzamiento sin trades: https://web3-ethereum-defi.readthedocs.io/api/hyperliquid/_autosummary_hyperliquid/eth_defi.hyperliquid.api.fetch_candle_snapshot.html). Dataset limpio: 1,334 velas diarias con volumen desde 2023-02-26 hasta 2026-10-21, SHA-256 `3c78a5deeb4becb5e08d9260a44069c0596937198977398b7db1718ad3a982e5`. Donchian LONG+SHORT en todo el período: 61 trades, 10 ganadores y 51 perdedores, retorno `+12.7609%`, profit factor `1.5011`, drawdown `9.8912%`. El último 30 % (2025-09-16 a 2026-10-21): 19 trades, 3 ganadores y 16 perdedores, retorno `+4.2985%`, profit factor `1.5404`, drawdown `8.3463%`.

El resultado diario es positivo fuera de muestra para esta partición, pero solo incluye 19 operaciones y la tasa de acierto es 15.79 %: la ganancia depende de pocas ganancias grandes. El marco 4h pierde en su tramo final. No hay validación robusta de que ambos lados o los dos marcos funcionen de forma estable; los datos no respaldan una probabilidad de ganar por trade cercana al 90 %. Los resultados usan comisión fija de 0.045 % y slippage de 0.01 % por ejecución, sin funding; cualquier conclusión es provisional.

Datasets ampliados:

- [ETH 4h completo, 4,999 velas](/home/jun/Escritorio/Proyecto%20bot%20Hyperliquid/hyperliquid-trader/data/backtests/ETH_4h_1720699200000_1792670400000.csv)
- [ETH diario con volumen real, 1,334 velas](/home/jun/Escritorio/Proyecto%20bot%20Hyperliquid/hyperliquid-trader/data/backtests/ETH_1d_20230226_onward.csv)

Archivos reproducibles:

- Dataset: `data/backtests/ETH_15m_1788190200000_1792689300000.csv`.
- Operaciones completas: `data/backtests/ETH_15m_1788190200000_1792689300000_trades.csv`.
- Diagnóstico completo: `data/backtests/ETH_15m_1788190200000_1792689300000_diagnostics.csv`.
- Operaciones fuera de muestra: `data/backtests/ETH_15m_1788190200000_1792689300000_out_of_sample_trades.csv`.
- Diagnóstico fuera de muestra: `data/backtests/ETH_15m_1788190200000_1792689300000_out_of_sample_diagnostics.csv`.

## Funding histórico en el backtest

Se integró la consulta pública `fundingHistory` de Hyperliquid, que devuelve la tasa y la marca temporal por activo ([documentación del endpoint](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals)). El funding se liquida cada hora; con tasa positiva, LONG paga y SHORT recibe, y con tasa negativa ocurre lo contrario ([documentación de funding](https://hyperliquid.gitbook.io/hyperliquid-docs/trading/funding)). El motor aplica el flujo firmado al capital y al PnL neto de cada operación. Las tasas se guardan en un CSV lateral junto al dataset de velas para permitir reproducir el cálculo sin otra consulta.

Comparación pareada de la misma evaluación fuera de muestra 70/30, estrategia Donchian LONG+SHORT, 1,500 velas de 15m del 2026-10-07T02:30Z al 2026-10-22T17:15Z, capital inicial $20 y 1,250 eventos de funding descargados para el dataset completo (2026-08-31 a 2026-10-22):

| Modelo | Operaciones | Ganadoras/perdedoras | PnL neto | Retorno | Drawdown máx. | Profit factor | PnL funding |
|---|---:|---:|---:|---:|---:|---:|---:|
| Sin funding | 35 | 16/19 | $0.336573 | 1.6829 % | 3.4260 % | 1.3376 | $0 |
| Funding histórico | 35 | 16/19 | $0.334181 | 1.6709 % | 3.4336 % | 1.3347 | -$0.002361 |

En esta ventana el funding cambió poco el resultado y no cambia la conclusión estadística: son solo 35 operaciones, 45.71 % ganadoras, en una ventana ya usada durante la exploración. Un resultado positivo aquí no confirma rentabilidad futura ni hace viable el despliegue real.

Supuestos y límites del modelo: la API de tasas no contiene el precio oracle horario, así que el importe usa el cierre de la vela anterior como proxy de precio. Cuando una tasa cae dentro de una vela, se carga antes de resolver el stop/target porque OHLC no permite saber el orden intravela; puede cobrar o acreditar funding en una operación que habría cerrado antes del evento. Esta aproximación es más relevante para velas de 4h o 1d. La comparación de arriba usa 15m. Las tasas históricas faltantes o CSV de funding vacío hacen fallar el backtest; puede desactivarse explícitamente con `--trading.backtest.include-funding=false`, pero los resultados sin funding no representan todos los costes.

Artefacto de tasas utilizado: `data/backtests/ETH_15m_1788190200000_1792689300000_funding.csv` (1,250 eventos).

La misma comparación en la partición 70/30 de ETH 4h evaluó las últimas 1,500 velas (2026-02-14T16:00Z a 2026-10-22T12:00Z), tras calentar indicadores con 50 velas. El funding se descargó solo para esa ventana y el calentamiento: 6,201 eventos completos. Sin funding: 22 operaciones, 0 ganadoras, PnL `-$2.049974` (`-10.2499%`), drawdown máximo `11.1411%`. Con funding: 21 operaciones, 0 ganadoras, PnL `-$2.005043` (`-10.0252%`), drawdown `10.9175%`, PnL de funding `-$0.007307`. La diferencia final también refleja que los débitos alteran el capital y el tamaño de operaciones posteriores; no significa que el funding haya mejorado la estrategia. Ambas ejecuciones perdieron sustancialmente.

Intentar cubrir todo el dataset 4h desde julio de 2024 reveló una separación de dos horas en la respuesta histórica entre `2024-08-15T12:00:00.001Z` y `2024-08-15T14:00:00.074Z`. El validador rechazó ese backtest completo; no se rellenó el dato ni se supuso funding cero. Para la evaluación 70/30 se descargó un sidecar independiente, limitado a la ventana realmente operada y su calentamiento. En consecuencia, no se debe usar ese sidecar fuera de muestra para un backtest estándar de todo el dataset.

Comando para reproducir la suite desde esta carpeta:

```bash
./mvnw test
```

## Límites pendientes antes de una prueba firmada

1. Mantener PAPER durante un período prolongado y revisar errores, desconexiones, estados y comportamiento por cierre; el motor usa polling y puede bloquearse para revisión tras perder más de una vela.
2. El historial de eventos PAPER ya se conserva atómicamente junto al estado; falta reconciliación explícita de sesión tras reinicios o huecos, además de una ejecución más precisa para stops y límites de precio. La verificación actual solo cubre sesiones breves.
3. Completar revisión de dependencias, amenazas, límites de tamaño/precisión, margen, funding y comisiones reales para el mercado y la cuenta objetivo.
4. Mejorar y reevaluar la estrategia con nuevos períodos fuera de muestra y varios regímenes. La muestra actual da pérdidas en estándar y fuera de muestra; no es evidencia para desplegar capital.
5. Solo después de resolver esos puntos, diseñar un adaptador independiente de órdenes con firma protegida, límites duros, reconciliación de fills/posiciones y un modo de ensayo explícito. `TESTNET` y `LIVE` permanecen bloqueados; no se solicitan credenciales en esta etapa.

El modo LIVE y el adaptador de órdenes no están implementados. No se han usado credenciales, firmado transacciones ni enviado órdenes.

## Continuación: auditoría durable de PAPER

- El estado PAPER ahora es versión 4 e incluye el historial completo de eventos (línea base, entradas, salidas y bloqueos) en la misma escritura atómica que la cartera y sus métricas.
- Los estados versión 3 se migran automáticamente y conservan saldo, PnL, comisiones y contadores; sus eventos previos no estaban guardados y no se pueden reconstruir. Las versiones anteriores a la 3 siguen bloqueándose para evitar inicializar una cartera sin datos compatibles.
- El historial valida estructura y límites al cargar; un estado dañado o con más de 100,000 eventos falla de forma cerrada.
- Verificación más reciente: `./mvnw test`, 57 pruebas, 0 fallos, 0 errores. Incluye persistencia del historial y migración v3.

Esto mejora la auditoría local de PAPER, pero aún no es reconciliación de órdenes del exchange ni una prueba prolongada. Los modos `TESTNET` y `LIVE` siguen bloqueados y la estrategia no ha demostrado rentabilidad estable.

## Continuación: consulta pública de cuenta

- Se añadió `ACCOUNT_CHECK`, un modo exclusivamente de lectura que consulta el rol del address, balances spot, órdenes abiertas y posiciones/margen de todos los DEX perpetuos que publica `perpDexs`.
- La consulta valida la forma de la dirección y cada respuesta; falta de datos, balances inconsistentes o respuestas incompletas cancelan el informe.
- `BLOCKED` se informa si hay órdenes abiertas, posiciones, margen o el address consultado no es de rol `user`. Si esas comprobaciones salen limpias, el resultado queda `REVIEW_REQUIRED`: la modalidad de cuenta debe confirmarse manualmente como `Standard`.
- La API de balances muestra fondos/holds spot, y el endpoint `clearinghouseState` se consulta por DEX; en Unified Account/Portfolio Margin los balances se comparten, por lo que el resultado no autoriza trading. La modalidad manual sigue siendo un bloqueo de seguridad.
- Verificación de la integración: `./mvnw test`, 61 pruebas, 0 fallos, 0 errores. Incluye mocks de spot, órdenes abiertas y exposición en un DEX no primario.

Esta función no requiere clave privada ni dirección todavía para compilar o probar. No hay adaptador de órdenes, y `ACCOUNT_CHECK` no habilita `TESTNET` ni `LIVE`.

## Comparación adicional fuera de muestra: LONG spot

Se ejecutaron 12 comparaciones nuevas con cuatro reglas experimentales (`EMA_CROSS`, `TIME_SERIES_MOMENTUM`, `DONCHIAN_BREAKOUT`, `RSI_MEAN_REVERSION`) en los tramos cronológicos finales del 30 % de los datasets ETH 15m, 4h y 1d. Todas permitieron LONG y deshabilitaron SHORT; se usó capital inicial de $20, comisión de 0.045 % y slippage de 0.01 % por ejecución. Funding se desactivó para representar este experimento spot. Las reglas son simulaciones de backtest y ninguna está autorizada para PAPER.

| Marco | Periodo de evaluación | Regla | Operaciones | Ganadoras / perdedoras | PnL neto | Retorno | Drawdown máx. | Profit factor |
|---|---|---|---:|---:|---:|---:|---:|---:|
| 15m | 2026-10-07 a 2026-10-22 | EMA_CROSS | 15 | 2 / 13 | -$0.58 | -2.90 % | 3.72 % | 0.1151 |
| 15m | 2026-10-07 a 2026-10-22 | TIME_SERIES_MOMENTUM | 52 | 10 / 42 | -$0.38 | -1.88 % | 2.86 % | 0.5285 |
| 15m | 2026-10-07 a 2026-10-22 | DONCHIAN_BREAKOUT | 17 | 7 / 10 | -$0.09 | -0.45 % | 2.32 % | 0.8253 |
| 15m | 2026-10-07 a 2026-10-22 | RSI_MEAN_REVERSION | 33 | 14 / 19 | -$0.76 | -3.82 % | 3.98 % | 0.5456 |
| 4h | 2026-02-14 a 2026-10-22 | EMA_CROSS | 18 | 2 / 16 | +$0.47 | +2.37 % | 6.60 % | 1.3010 |
| 4h | 2026-02-14 a 2026-10-22 | TIME_SERIES_MOMENTUM | 47 | 7 / 40 | -$2.01 | -10.04 % | 10.15 % | 0.3551 |
| 4h | 2026-02-14 a 2026-10-22 | DONCHIAN_BREAKOUT | 22 | 0 / 22 | -$2.01 | -10.07 % | 11.85 % | 0.0000 |
| 4h | 2026-02-14 a 2026-10-22 | RSI_MEAN_REVERSION | 62 | 18 / 44 | +$0.71 | +3.57 % | 9.95 % | 1.1603 |
| 1d | 2025-09-16 a 2026-10-21 | EMA_CROSS | 0 | 0 / 0 | $0.00 | 0.00 % | 0.00 % | 0.0000 |
| 1d | 2025-09-16 a 2026-10-21 | TIME_SERIES_MOMENTUM | 29 | 3 / 26 | -$0.20 | -0.99 % | 7.85 % | 0.9222 |
| 1d | 2025-09-16 a 2026-10-21 | DONCHIAN_BREAKOUT | 11 | 1 / 10 | -$0.71 | -3.55 % | 5.48 % | 0.2741 |
| 1d | 2025-09-16 a 2026-10-21 | RSI_MEAN_REVERSION | 40 | 3 / 37 | -$2.04 | -10.21 % | 16.19 % | 0.4183 |

En conjunto, 11 de las 12 combinaciones generaron operaciones: 67 ganadoras y 279 perdedoras de 346 operaciones cerradas. Solo dos combinaciones terminaron con PnL positivo, y ambas tuvieron menos operaciones ganadoras que perdedoras (EMA_CROSS 4h y RSI_MEAN_REVERSION 4h); unas pocas ganancias grandes explican su rentabilidad en esa partición. EMA_CROSS diario no abrió operaciones, por lo que su retorno 0 % no es una validación. No se agrupan los PnL de distintos marcos: son carteras hipotéticas independientes y sus períodos se solapan.

Esta comparación no selecciona ni activa una estrategia: los resultados son inestables entre marcos, con pérdidas de hasta 10.21 % y drawdown de hasta 16.19 %. Además, el segmento de 15m cubre solo 15 días y el backtest no modela fills parciales ni impacto de mercado. Los archivos de velas se mantuvieron inalterados; sus SHA-256 son `18fc1a77f22b1194ea0ecfb7af2d76aef5add9c91034a89272005f7f4869afd0` (15m), `5b69dda4acea6bfcca933a772cdaef99a484a193448520789bc08dea5d2201a4` (4h) y `3c78a5deeb4becb5e08d9260a44069c0596937198977398b7db1718ad3a982e5` (1d). La simulación no envía órdenes ni permite estimar ganancias futuras.

## Dos candidatas sencillas y evaluación por bloques

Para limitar la investigación se conservaron dos reglas seleccionables en backtest: `EMA_CROSS` y `RSI_TREND_PULLBACK`. La segunda es una variante nueva y sencilla de pullback: compra RSI14 ≤ 30 solo si el precio está sobre EMA50 y EMA50 sube; vende cuando RSI14 ≥ 60 o el cierre cae bajo EMA50. Los niveles quedan fijos, sin búsqueda automática de parámetros. Se mantiene la PAPER existente con su señal EMA; la variante RSI todavía no se conecta a PAPER.

Se compararon en segmentos cronológicos consecutivos de los históricos 4h y 1d, con $20 separados por segmento, LONG-only, comisión 0.045 %, slippage 0.01 % por ejecución y sin funding. Se reservaron 50 velas anteriores para calentar indicadores en los segmentos posteriores. Los períodos se solapan solo en esas velas de calentamiento; el capital no se arrastra entre segmentos.

| Marco / regla | Segmentos positivos | Operaciones cerradas | Ganadoras / perdedoras | Observación |
|---|---:|---:|---:|---|
| 4h EMA_CROSS | 3 de 4 | 48 | 7 / 41 | PnL positivo en 3 segmentos; el cuarto dio -2.97 %. Sus ganancias dependen de pocas operaciones grandes; no equivale a alta tasa de acierto. |
| 4h RSI_MEAN_REVERSION, referencia anterior | 1 de 4 | 200 | 38 / 162 | Pérdidas netas en los primeros tres segmentos; solo el último fue ligeramente positivo. |
| 4h RSI_TREND_PULLBACK, variante nueva | 0 de 4 | 14 | 1 / 13 | Pérdida en los cuatro; actividad demasiado baja para sugerir una ventaja. |
| 1d EMA_CROSS | 0 de 3 | 0 | 0 / 0 | No hubo señales; no cuenta como resultado favorable. |
| 1d RSI_MEAN_REVERSION, referencia anterior | 2 de 3 | 87 | 12 / 75 | Dos tramos modestamente positivos y el último perdió 10.21 %. |
| 1d RSI_TREND_PULLBACK, variante nueva | 1 de 3 | 6 | 1 / 5 | Muy pocas operaciones; no hay muestra suficiente. |

Lectura para el producto: EMA_CROSS 4h es la única candidata que justifica continuar PAPER como observación, no LIVE; su tasa de acierto histórica agrupada fue 14.6 % (7/48), compensada por ganancias mayores que las pérdidas. Si se busca una proporción alta de operaciones ganadoras, estos datos no la respaldan. RSI_TREND_PULLBACK no mostró ventaja y no se promoverá ni conectará a PAPER. Las ventanas ya se inspeccionaron y por eso son exploratorias, no una validación final intacta; cualquier evaluación futura debe usar precios posteriores al 2026-10-22 y contabilizar gastos reales spot. No se optimizaron parámetros para “arreglar” los resultados.

La suite de código tras reducir las opciones pasó: `./mvnw -q test`, 62 pruebas, 0 fallos y 0 errores. Los 21 backtests por segmentos terminaron correctamente. En los datos existentes no aparece evidencia de una probabilidad calibrada de ganancia; el objetivo de desarrollo debe ser expectativa neta positiva y drawdown controlado, no un porcentaje fijo de aciertos.

## Resistencia a costes y aislamiento de sesión PAPER

Se sometió EMA_CROSS a un escenario de estrés en los mismos cuatro bloques 4h: comisión duplicada a 0.09 % y slippage multiplicado por cinco a 0.05 % por ejecución frente al caso base de 0.045 % y 0.01 %. El retorno de cada fila usa un capital independiente de $20:

| Bloque 4h | Retorno base | Retorno escenario de estrés | Operaciones (estrés) | Ganadoras / perdedoras (estrés) |
|---|---:|---:|---:|---:|
| 1 | +5.75 % | +6.82 % | 13 | 2 / 11 |
| 2 | +13.62 % | +11.89 % | 10 | 2 / 8 |
| 3 | -2.97 % | -3.22 % | 12 | 2 / 10 |
| 4 | +4.97 % | +4.29 % | 13 | 2 / 11 |

En el OOS final 70/30 (2026-02-14 a 2026-10-22), EMA_CROSS quedó en +1.71 % bajo el escenario de estrés, con 2 ganadoras y 16 perdedoras y drawdown máximo de 6.54 %. La mayor fricción no eliminó el signo positivo en esa ventana, pero tampoco corrigió la baja tasa de acierto; un bloque histórico siguió perdiendo. En el primer bloque el PnL estresado superó al base porque el dimensionamiento por riesgo recalcula la cantidad al cambiar los costes; esto no es evidencia de que cobrar más mejore el resultado. Las cifras de retorno no son monotónicas y solo funcionan como prueba de sensibilidad, no como comisión real de una cuenta spot.

PAPER ahora acepta la configuración explícita `trading.paper.strategy=EMA_CROSS`; rechaza `RSI_TREND_PULLBACK` aunque el modo esté disponible en backtest. La versión 5 del estado persiste un perfil de mercado spot, intervalo, estrategia, capital de referencia y versión de política de riesgo. Si se intenta abrir el archivo con otra configuración, el arranque falla cerrado. Los estados v3/v4 se migran conservando contabilidad e historial disponible, pero se marcan `LEGACY_UNBOUND`; si ya tienen línea base o actividad no se reasignan silenciosamente a otro intervalo. El ejemplo recomendado usa un archivo separado `data/paper/eth_4h_ema_cross.properties`.

Verificaciones de perfil y migración: 65 pruebas, 0 fallos y 0 errores; 8 pruebas de sensibilidad (cuatro bloques con costes base y de estrés) y una evaluación final 70/30 con costes de estrés finalizaron correctamente.

## Menos consultas de velas en PAPER

Cuando PAPER está plano y procesa una nueva vela cerrada, ahora duerme hasta el siguiente cierre esperado en lugar de solicitar el mismo historial de velas cada 15 segundos. Durante esa espera revisa el archivo KILL_SWITCH con la frecuencia configurada. Si hay una posición abierta mantiene la lectura de mid y stop a la frecuencia de polling; si el exchange devuelve el mismo cierre después de la hora esperada, reintenta sin espera activa.

El scheduler se probó con un cierre 4h, posición abierta, respuesta atrasada y un intervalo inválido. Suite final: `./mvnw -q test`, 69 pruebas, 0 fallos y 0 errores. `git diff --check` no reportó errores de formato. Las simulaciones se ejecutaron en segundo plano; no quedó PAPER activo.


## Auditoría adicional del código (2026-10-22)

Rama: `fix/strategy-entry-signal`. Revisión estática del código fuente remoto. No se ejecutaron órdenes ni se habilitaron modos de trading.

### Selección de estrategia y señales

`StartupRunner` lee `trading.backtest.strategy` y pasa el modo a `BacktestEngine`, que usa `EmaRegimeVolatilityStrategy`. La clase `EmaCrossStrategy` no es la implementación que ejecuta el backtest principal.

En `EMA_CROSS`, una compra requiere un nuevo cruce alcista EMA20/EMA50, EMA20 por encima y creciendo, EMA50 creciendo, cierre sobre EMA50 y ATR porcentual entre 0.05 % y 3 %. No hay filtro explícito de distancia máxima a EMA50 ni de retorno reciente; una entrada tras un movimiento extendido es posible, aunque eso por sí solo no demuestra que un filtro mejore el resultado.

### Velas cerradas y anticipación

El cliente público valida activo, intervalo, timestamp de cierre, orden cronológico, OHLC y continuidad; descarta velas aún abiertas y rechaza datos obsoletos. El motor calcula señales con datos de la vela anterior y ejecuta en la apertura de la siguiente. La ruta revisada no muestra un uso evidente de datos futuros para las señales, y la prueba `ejecutaLaSenalEnLaSiguienteVela` comprueba el desplazamiento señal/entrada.

### Stops, costes y funding

El motor calcula el stop inicial y limita el tamaño por presupuesto de riesgo y exposición. Si la apertura ya cruzó el stop, usa la apertura como precio bruto de salida; de lo contrario usa el nivel de stop y aplica el deslizamiento configurado. Si stop y take-profit se alcanzan en la misma vela, el stop tiene prioridad. OHLC no permite reconstruir el orden intravela, por lo que los resultados siguen siendo una aproximación.

La comisión de entrada se descuenta al abrir y la comisión de salida al cerrar. El PnL neto de cada operación incorpora ambas comisiones y el flujo de funding. El funding usa el cierre de la vela anterior como proxy de precio y se aplica antes de resolver stop/objetivo si ocurre dentro de una vela. No se modelan fills parciales ni impacto de mercado.

### Defecto corregido: MAE/MFE en la vela de salida

El diagnóstico anterior agregaba los máximos y mínimos de toda la vela de salida aunque una salida `SIGNAL_REVERSAL` se registra en la apertura de esa vela. Eso podía incluir movimiento posterior al cierre y exagerar MFE/MAE.

El cálculo se corrigió para incluir velas completas anteriores a la salida y el precio de ejecución como punto terminal. Para `END_OF_DATA`, donde la posición permanece abierta hasta el cierre, se conserva el rango completo de la última vela. En salidas intravela, el orden real sigue siendo desconocido con OHLC; el nuevo cálculo evita atribuir automáticamente extremos posteriores, pero puede subestimar el recorrido ocurrido antes de la salida. Se añadió una prueba que pone extremos artificiales en la vela de salida y verifica que no contaminen las métricas. **Esta modificación está pendiente de ejecución de la suite de pruebas en CI/local; no afirmar que ya pasó hasta verificarlo.**

### Hallazgo pendiente: semántica SELL de RSI_TREND_PULLBACK

La estrategia RSI emite `SELL` para cerrar una posición LONG cuando RSI recupera 60 o el precio pierde EMA50. El motor genérico también interpreta `SELL` como entrada SHORT si `allow-shorts=true`. Esa combinación podría abrir cortos no contemplados por la regla documentada. Debe corregirse y probarse antes de usar esa variante con shorts habilitados.

### Evaluación fuera de muestra y seguridad

`OutOfSampleEvaluator` separa cronológicamente 70 % de desarrollo y 30 % de evaluación, con 50 velas de calentamiento. No ajusta parámetros con el tramo evaluado, pero una sola partición no demuestra robustez entre regímenes.

`TESTNET` y `LIVE` siguen bloqueados por `TradingMode.requireImplemented()`; no se encontró adaptador de órdenes firmadas en la ruta revisada. PAPER es simulación, no validación de ejecución real. No habilitar capital real a partir de estos backtests.

### Próximas tareas

1. Ejecutar `./mvnw test` y confirmar el estado del CI para el arreglo de MAE/MFE.
2. Corregir la semántica de `SELL` en `RSI_TREND_PULLBACK` cuando `allow-shorts=true`, con una prueba que impida aperturas SHORT involuntarias.
3. Comparar EMA_CROSS base con filtros de sobreextensión definidos antes de evaluar y usar datos intactos fuera de muestra.
4. Mantener PAPER aislado y LIVE/TESTNET bloqueados.

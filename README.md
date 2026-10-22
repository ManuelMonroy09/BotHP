# Hyperliquid Trader

Bot experimental en Java 21 y Spring Boot. La versión actual descarga velas públicas, calcula señales y ejecuta backtests locales. **No envía órdenes ni necesita claves privadas.** Los resultados del backtest no garantizan rentabilidad.

Los modos disponibles son `BACKTEST` y `PAPER`. `TESTNET` y `LIVE` se rechazan al arrancar; configurar `trading.mode=LIVE` no habilita operaciones. `PAPER` solo simula posiciones con datos públicos.

## Requisitos

- Java 21
- Maven 3.8 o posterior
- Conexión a internet para consultar la API pública de Hyperliquid

## Ejecutar pruebas

```bash
mvn clean test
```

## Ejecutar la demostración sintética

La configuración predeterminada usa velas sintéticas. Sirven para comprobar el flujo del programa, no para medir rentabilidad.

```bash
mvn spring-boot:run
```

## Consultar velas reales de Hyperliquid

Para descargar hasta 500 velas cerradas de ETH en intervalo de 15 minutos y ejecutar un backtest sobre ellas:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--trading.data-source=hyperliquid --hyperliquid.coin=ETH --hyperliquid.interval=15m --trading.candle-count=500"
```

Intervalos admitidos: `1m`, `3m`, `5m`, `15m`, `30m`, `1h`, `2h`, `4h`, `8h`, `12h`, `1d`, `3d` y `1w`. La cantidad debe estar entre 51 y 5000 velas. Las velas que todavía no han cerrado se descartan para evitar señales basadas en precios que siguen cambiando.

Al elegir Hyperliquid, el programa guarda una copia CSV del dataset, otro CSV con las operaciones simuladas y un tercer CSV de diagnóstico dentro de `data/backtests/`. También imprime la huella SHA-256 del dataset para ayudar a identificar exactamente qué datos se evaluaron. Conserva esos archivos para comparar ejecuciones. El CSV de operaciones incluye `exitReason` (cruce bajista, stop-loss, límite de pérdida o fin del dataset). El diagnóstico incluye EMA20/EMA50 y sus pendientes al generarse la entrada, ATR porcentual, distancia del cierre a EMA50, retornos de las últimas 3 y 12 velas y excursiones adversa/favorable observadas mientras la posición estuvo abierta. Estas excursiones son descriptivas y no cambian la simulación.

## Evaluación fuera de muestra

Usa un CSV previamente guardado para separar cronológicamente el 70 % inicial del 30 % final:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--trading.data-source=csv --trading.csv-path=data/backtests/ARCHIVO.csv --trading.evaluation=out-of-sample"
```

Reemplaza `ARCHIVO.csv` por el nombre real del dataset. El archivo debe contener el encabezado `timestamp,open,high,low,close,volume` y al menos 51 velas válidas.

El backtest puede comparar posiciones `LONG` y `SHORT` con `--trading.strategy.allow-shorts=true`; también se puede aislar cada lado con `--trading.strategy.allow-longs=false` o `--trading.strategy.allow-shorts=false`. Esas opciones solo controlan simulaciones históricas. PAPER continúa long-only hasta que la variante corta pase validaciones adicionales. No elijas la configuración por el mejor resultado de un solo período.

La investigación activa se limita a dos reglas simples: `--trading.backtest.strategy=EMA_CROSS` (predeterminada), que busca cruces EMA20/EMA50 con filtro de tendencia/volatilidad, y `RSI_TREND_PULLBACK`, que solo compra retrocesos RSI14 mientras el cierre está sobre una EMA50 ascendente y sale si RSI recupera 60 o el precio pierde EMA50. PAPER conserva su señal EMA_CROSS sin cambios; RSI_TREND_PULLBACK solo está disponible para backtest y sus resultados no justifican conectarla a PAPER por ahora. Ninguna calcula una probabilidad calibrada de ganancia ni garantiza un porcentaje de acierto.

Los costes se ajustan con `trading.backtest.fee-rate` y `trading.backtest.slippage-rate` (fracciones por ejecución); por defecto usa 0.045 % de taker fee y 0.01 % de slippage por lado. `trading.backtest.take-profit-percentage` define un objetivo porcentual fijo solo para backtest (0 lo desactiva); si stop y objetivo aparecen en la misma vela, el simulador asume que ocurrió primero el stop. Hyperliquid calcula el nivel de comisión según el volumen rodante de 14 días y distingue maker/taker, así que fija una comisión compatible con la cuenta y el tipo de orden antes de evaluar rentabilidad ([tabla oficial de tarifas](https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees)).

Para datasets `csv` e `hyperliquid`, el backtest incluye funding histórico de forma predeterminada (`--trading.backtest.include-funding=false` lo desactiva). Descarga las tasas públicas mediante `fundingHistory` y las conserva como `*_funding.csv` junto a las velas ([endpoint oficial](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals)). Si la serie está vacía, incompleta o tiene huecos mayores a una hora, se cancela la evaluación. El CSV de trades incluye `fundingPnl` y la consola informa el funding total. Como la API no devuelve el precio oracle horario en esa serie, se usa el cierre de la vela anterior; con velas de más de una hora, el orden entre funding y una salida intravela es una aproximación. Las liquidaciones, el impacto de mercado y los fills parciales siguen sin modelarse.

## Seguimiento PAPER con datos públicos

PAPER ahora simula exclusivamente compras spot de `ETH/USDC` (configurable con `hyperliquid.coin` y `hyperliquid.quote-asset`), sin posiciones cortas, perpetuos, margen ni apalancamiento. Resuelve el par y la precisión en `spotMeta`; rechaza el inicio si el par no existe. Las compras descuentan el valor total de la base y la comisión del saldo quote disponible, y el tamaño se redondea hacia abajo. La primera consulta solo fija una línea base; las entradas y salidas son virtuales. El saldo inicial predeterminado es 20 USDC virtuales. No conecta una wallet, firma ni envía órdenes. Los IDs spot se calculan como `10000 + index` de `spotMeta` ([metadata spot](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/spot), [reglas oficiales de tamaño](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/tick-and-lot-size)).

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--trading.mode=PAPER --hyperliquid.coin=ETH --hyperliquid.interval=4h --trading.paper.strategy=EMA_CROSS --trading.paper.state-path=data/paper/eth_4h_ema_cross.properties"
```

El estado se guarda atómicamente en el archivo indicado por `trading.paper.state-path` y conserva el historial de eventos junto a cartera y métricas. La versión 5 añade un perfil persistente (`mercado|intervalo|estrategia|capital inicial|política de riesgo`); el arranque falla si el archivo pertenece a otra configuración. Estados antiguos se migran sin borrar su contabilidad, pero quedan sin perfil: una sesión ya usada no puede ligarse automáticamente y debe conservarse/archivarse antes de elegir una ruta nueva. Se recomienda una ruta nueva por intervalo, como la del ejemplo 4h. Si falta más de una vela, los datos no son válidos o el estado está dañado, el ciclo se bloquea y no procesa entradas. Cuando no hay posición, PAPER espera al próximo cierre confirmado y solo revisa el interruptor local cada `trading.paper.poll-seconds` (15 s por defecto), evitando repetir consultas de velas durante el intervalo; con una posición abierta mantiene la vigilancia del precio con la misma frecuencia. El resumen de equity, PnL neto realizado y no realizado, comisiones acumuladas y operaciones ganadas/perdidas/en equilibrio se emite al cambiar de vela, al generarse un evento o mientras haya una posición; se omiten líneas idénticas cuando el bot está plano. PAPER solo admite `EMA_CROSS`, limita el riesgo previsto a 0.5 % del equity por operación, usa un stop inicial de 1.5 %, exposición máxima de 50 %, límite diario de 2 % y pérdida acumulada máxima de 10 %. Esos topes no garantizan evitar pérdidas: los stops pueden ejecutarse peor que el precio indicado, y el activo puede perder valor. En una futura ejecución spot, no se permitirá gastar más quote disponible ni abrir cortos.

Para activar el interruptor de emergencia, crea `data/paper/KILL_SWITCH` mientras el proceso está activo. El ciclo siguiente cierra la posición virtual y deja el bloqueo persistente. El interruptor queda enclavado; para iniciar una nueva sesión simulada hay que detener la aplicación, retirar el archivo y archivar o eliminar manualmente `data/paper/state.properties`.

## Consulta de cuenta pública (solo lectura)

`ACCOUNT_CHECK` consulta el rol, saldos spot, órdenes abiertas y posiciones/margen en el DEX perpetuo principal y los DEX publicados por Hyperliquid. Solo necesita la dirección pública de la cuenta; no usa clave privada ni envía acciones. El modo informa `BLOCKED` cuando detecta órdenes, posiciones, margen o un rol distinto de `user`. Si no detecta esas condiciones, devuelve `REVIEW_REQUIRED`: la API pública consultada no revela de forma fiable la modalidad de abstracción de cuenta, así que todavía debe verificarse manualmente que esté en `Standard`. Esta comprobación nunca autoriza operaciones.

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--trading.mode=ACCOUNT_CHECK --hyperliquid.account.address=0x..."
```

Hyperliquid unifica los balances spot y perps en Unified Account y Portfolio Margin. Por eso un saldo spot disponible no demuestra por sí solo que esté aislado de posiciones apalancadas. `ACCOUNT_CHECK` informa la exposición observada, pero no detecta la modalidad de cuenta.

## Estado antes de una prueba real

La integración de órdenes firmadas está deliberadamente ausente: `TESTNET` y `LIVE` fallan de forma cerrada. Si se implementa, debe ser spot-only, comprobar saldo quote disponible antes de cada compra, impedir gasto superior a ese saldo y rechazar shorts, perps y margen. Aun en spot, no se puede garantizar que el capital no pierda valor. Antes de una prueba hacen falta verificación manual del modo `Standard`, reconciliación repetida de saldos/órdenes, firma protegida y una sesión PAPER estable. No pegues una clave privada en el chat; cuando esa integración esté lista, usaremos un gestor de secretos o archivo local protegido.

## Límites de seguridad actuales

- No hay envío de órdenes ni integración de claves privadas.
- Los resultados de datos sintéticos no son evidencia de rentabilidad.
- Un backtest corto puede ser engañoso. Evalúa múltiples regímenes de mercado y considera comisiones, deslizamiento y riesgo antes de cualquier decisión.
- No habilites trading real basándote solo en una ejecución exitosa o en una muestra ganadora.

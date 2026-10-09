# Hyperliquid Trader

Bot experimental en Java 21 y Spring Boot. La versión actual descarga velas públicas, calcula señales y ejecuta backtests locales. **No envía órdenes ni necesita claves privadas.** Los resultados del backtest no garantizan rentabilidad.

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

## Límites de seguridad actuales

- No hay envío de órdenes ni integración de claves privadas.
- Los resultados de datos sintéticos no son evidencia de rentabilidad.
- Un backtest corto puede ser engañoso. Evalúa múltiples regímenes de mercado y considera comisiones, deslizamiento y riesgo antes de cualquier decisión.
- No habilites trading real basándote solo en una ejecución exitosa o en una muestra ganadora.

# PRD: Pre-Earnings Risk Gate

Status: proposed, 2026-08-27. Author: Juan. Not planned, not scoped, no wave assigned.

## 1. Objetivo

Agregar un módulo al discount screener. El módulo calcula, antes de cada reporte de resultados, el riesgo de sorpresa y su impacto esperado en el precio. El objetivo es decidir con anticipación si vender, reducir, o mantener una posición antes del reporte.

## 2. Alcance

El módulo cubre:
- Cálculo del movimiento implícito por opciones (implied move).
- Regresión histórica entre sorpresas de resultados y retorno anormal del precio.
- Score de riesgo pre-reporte por ticker.
- Matriz de decisión que separa valuación (DCF) de riesgo de evento.
- Reglas de cobertura con opciones.
- Log de paper trading para validación.

Fuera de alcance:
- El módulo no calibra el DCF. El consenso y el implied move son inputs de riesgo, nunca inputs de valuación.
- El módulo no genera señales de entrada. Solo evalúa riesgo antes de un evento conocido.

## 3. Principio de diseño

La decisión de valuación y la decisión de riesgo de evento son independientes. Un precio caro se reduce por valuación, con o sin reporte cerca. Un riesgo de evento alto se gestiona con tamaño de posición o cobertura, no cambia el fair value.

## 4. Componentes funcionales

### 4.1 Implied move

Fórmula:

```
implied_move = (C(K0, T) + P(K0, T)) / F
```

- T: vencimiento inmediato posterior al reporte.
- K0: strike más cercano al forward F.
- F = S × exp((r − q) × T), si se calcula a mano. Los proveedores de opciones ya lo entregan calculado.

El resultado es el breakeven del straddle bajo medida de riesgo neutral. No es una probabilidad ni un pronóstico puntual. Se usa solo como gate de riesgo.

### 4.2 Regresión histórica

Variable dependiente: retorno anormal.

```
retorno_anormal = retorno_accion − beta_mkt_ex_evento × retorno_mercado
```

beta_mkt_ex_evento se estima excluyendo días de reporte, para no contaminar la beta con el evento.

Variables independientes:
- SUE: (EPS real − consenso) / desvío estándar de estimaciones.
- Sorpresa de revenue, winsorizada.
- Sorpresa de la métrica sectorial, solo si existe consenso histórico para esa métrica.
- Interacción sorpresa × dummy_positiva, para capturar asimetría.

Reglas de estimación:
- Ventana mínima: 16–20 trimestres por ticker, con prior sectorial informado por 80–120 eventos del sector (shrinkage bayesiano).
- El coeficiente de asimetría se usa en la decisión solo si es significativo en validación cruzada. Si no, el modelo queda simétrico.
- Retorno del día siguiente completo si el reporte es after-hours. Retorno del mismo día si es pre-market.

### 4.3 Score de riesgo

```
ratio_riesgo = implied_move / mediana(|retorno_anormal| histórico del ticker)
```

Categorías iniciales, calibradas después con distribución empírica por sector:
- ratio_riesgo > 1.3 → riesgo alto.
- ratio_riesgo < 0.8 → riesgo bajo.
- 0.8–1.3 → riesgo normal.

Los umbrales se normalizan por sector y, opcionalmente, por VIX o por el implied move promedio del sector en el mismo período.

### 4.4 Override de métrica sectorial

Aplica solo cuando no hay consenso histórico para la métrica sectorial.

Regla explícita: si la métrica real cae por debajo de la mediana de los últimos 4 trimestres en más de 1 desvío estándar, y la decisión base era "mantener", la posición se reduce a la mitad. Todo override queda registrado con esta justificación, para mantener auditabilidad.

### 4.5 Matriz de decisión

| Valuación DCF | Riesgo pre-reporte | Acción |
|---|---|---|
| Caro (precio > DCF justo) | Alto | Reducir o salir antes del reporte |
| Caro | Normal | Reducir por valuación, no por el evento |
| Justo/barato (precio ≤ 0.9 × DCF justo) | Alto | Mantener con tamaño reducido (media posición o un tercio), o cubrir |
| Justo/barato | Normal | Mantener |

"Justo/barato" se define como precio ≤ 0.9 × DCF justo. Por encima de ese umbral, se trata como "caro".

### 4.6 Regla de cobertura

En la celda "justo/barato + riesgo alto", la cobertura preferida es put protector o put spread, no collar. Vender un call limita el upside que la posición barata busca capturar.

Tope de costo:
- Put protector: si cuesta más del 1.5–2% del valor de la posición, se reduce tamaño en vez de cubrir.
- Put spread: si cuesta más del 1%, se reduce tamaño en vez de cubrir.

## 5. Fuentes de datos

| Dato | Fuente |
|---|---|
| Cadena de opciones, implied move | IBKR, Tradier, Polygon, ORATS |
| Consenso histórico, desvío estándar de estimados (SUE) | I/B/E/S, Zacks, FactSet, Visible Alpha |
| Resultados reales (EPS, revenue) | EDGAR XBRL |
| Métrica sectorial | Reportes trimestrales, armado manual por familia |

EDGAR no tiene datos de consenso. Solo tiene resultados reales.

## 6. Validación (paper trading)

Antes de producción, el módulo corre en paper trading sobre los últimos 8–12 trimestres de la cartera actual.

Reglas del backtest:
- El DCF justo usado en cada evento es el vigente antes del reporte, no el revisado después. Se registra la fecha de cálculo junto al valor.
- El paper trading de 8–12 trimestres valida el proceso de decisión. No sirve para fijar los umbrales del ratio_riesgo.
- Los umbrales del ratio_riesgo se calibran con la mayor historia disponible del universo, idealmente 5–10 años de eventos.

## 7. Esquema de log (un registro por evento)

Pre-reporte:
- ticker, fecha_reporte, timing (BMO/AMC)
- fecha_calculo_dcf, dcf_fair_value, precio_pre_reporte, ratio_valuacion
- implied_move, vencimiento_usado, forward_price
- mediana_historica_retorno_anormal, ratio_riesgo
- consenso_eps, desvio_std_eps, consenso_revenue
- metrica_sectorial_estimada, fuente_consenso_historico (bool)

Decisión:
- celda_matriz, accion, tamano_posicion
- tipo_cobertura, costo_cobertura_pct
- override_sectorial_aplicado (bool), justificacion

Post-reporte:
- eps_real, sue_calculado
- revenue_real, sorpresa_revenue_winsorizada
- metrica_sectorial_real
- retorno_accion, retorno_mercado, retorno_anormal
- decision_correcta (bool, medida contra el retorno anormal real)

## 8. Riesgos y limitaciones conocidas

- Con 16–20 trimestres, el coeficiente de asimetría puede no ser significativo. En ese caso, el modelo simétrico es el que se usa en producción.
- La métrica sectorial sin consenso histórico queda fuera de la regresión. Su peso en la decisión es menor que el de EPS y revenue.
- El implied move es una medida de riesgo neutral, no una probabilidad real. Sirve para comparar contra la historia del propio ticker, no para estimar probabilidad de movimiento.

## 9. Criterios de éxito

- El módulo corre sin intervención manual para cualquier ticker con datos de opciones y consenso disponibles.
- Después del paper trading, la matriz de decisión muestra una diferencia medible en retorno anormal entre los casos "riesgo alto" y "riesgo normal".
- Los umbrales quedan calibrados por sector, con evidencia empírica documentada, no fijados a mano.

## 10. Estado del repo frente a este PRD (2026-08-27)

Medido leyendo el repo, no supuesto.

Ya existe y sirve tal cual:
- **Fecha de reporte.** `calendarEvents.earningsDate` se parsea en `YahooFinanceClient.kt` y en `quote_summary.rs`. Hay marca de earnings en la UI (`EarningsMarkTest`).
- **DCF justo por ticker**, con familia de modelo por clase de negocio. Es la columna izquierda de la matriz §4.5.
- **Consenso de analistas.** El módulo `earningsTrend` de Yahoo ya se pide (`FORWARD_FORECAST_MODULES = "earningsTrend,price"`). Cada período trae `earningsEstimate.{low, avg, high, numberOfAnalysts}` y `revenueEstimate`. Hoy el parser solo lee el período `+1y`; el trimestre en curso (`0q`) viene en la misma respuesta y se descarta.
- **Series de precio históricas** para el retorno del ticker, y las mismas series para un índice dan el retorno de mercado y la beta.

No existe todavía:
- **Cadena de opciones.** No hay ningún cliente de opciones en el repo. Sin esto no hay §4.1 ni §4.3.
- **Log de eventos** (§7) ni arnés de paper trading (§6).
- **Desvío estándar de estimados.** Yahoo entrega `low`, `high` y `numberOfAnalysts`, no el desvío. El SUE de §4.2 necesita una definición de reemplazo declarada, no el desvío verdadero.

## 11. Lo que falta de verdad, y cómo se consigue

Ninguna pieza del módulo está bloqueada por dinero. Lo único que no se compra gratis es la **historia** de precios de opciones.

| Falta | Cómo se consigue | Costo |
|---|---|---|
| Implied move de hoy | Endpoint de opciones de Yahoo (`/v7/finance/options/{symbol}`): trae vencimientos, strikes, y precio de call y put. Alcanza para §4.1. | Gratis, mismo proveedor que ya usa la app |
| Consenso EPS y revenue del trimestre en curso | `earningsTrend`, período `0q`. Ya viene en la respuesta que el repo pide; hay que dejar de descartarlo. | Gratis, cero llamadas nuevas |
| Dispersión de estimados | `(high − low)` y `numberOfAnalysts` de `earningsEstimate`. No es el desvío estándar. Se declara la definición usada y se mantiene fija. | Gratis |
| EPS y revenue reales | `earningsHistory` de Yahoo da 4 trimestres con real, estimado y sorpresa. EDGAR XBRL da los reales sin límite de historia. | Gratis |
| Retorno de mercado y beta ex-evento | Serie de precios de un índice por el mismo endpoint de chart que ya se usa. | Gratis |
| **Historia de implied move** | **No se backfillea gratis.** O se compra (ORATS y similares venden cadenas históricas), o se empieza a capturar hoy, un registro por evento. | Compra, o dos años de espera |
| **Historia de consenso más allá de 4 trimestres** | Misma respuesta: capturar desde hoy, o comprar. | Compra, o espera |

### Qué implica esto para el orden de trabajo

El log de eventos (§7) deja de ser el último paso y pasa a ser el primero. Es la única pieza que empieza a pagar el día que se escribe: cada reporte que pasa sin log es un evento que no vuelve. Con el log corriendo, la historia se acumula sola mientras se construye el resto.

Con la historia que existe hoy (4 trimestres de `earningsHistory`) no se puede correr la regresión de §4.2, que pide 16–20 trimestres. La regresión llega después. El score de §4.3 sí se puede correr antes, porque su denominador es la mediana del retorno anormal, y eso sale de las series de precio, que sí tienen historia.

## 12. Preguntas abiertas

1. ~~**Horizonte del ratio.**~~ **Resuelto.** Ver §13, "El horizonte del ratio". El implied move se separa en movimiento de evento y deriva tranquila antes de dividir.
2. **Potencia del criterio §9.** 8–12 trimestres sobre la cartera actual da N eventos; partidos en dos celdas, la diferencia de retorno anormal entre "alto" y "normal" puede caer dentro del ruido. Hay que contar N y medir la dispersión antes de comprometer ese criterio.
3. **Definición de SUE sin desvío estándar.** Con `low`, `high` y `numberOfAnalysts` se puede definir un denominador, pero es una decisión de modelo que hay que fijar una vez y no mover.

## 13. Lo que ya está construido

Todo en `apps/android/core/src/main/kotlin/com/discountscreener/core/earnings/`. Puro, sin red, probado contra fixtures. Ninguna prueba llama a Yahoo.

| Archivo | Qué hace | §PRD |
|---|---|---|
| `ImpliedMove.kt` | Straddle ATM sobre el forward. Rechaza grilla de strikes muy gruesa, cotización cruzada y bid en cero. | 4.1 |
| `YahooOptionChain.kt` | Parsea `/v7/finance/options/{symbol}`. Une calls y puts por strike; un strike de un solo lado no entra. | 4.1 |
| `ConsensusEstimate.kt` | Lee el período `0q` de `earningsTrend`, el bloque que la app ya baja para el DCF y descarta. | 4.2, 7 |
| `EarningsEventRecord.kt` | Esquema del evento: pre, decisión, resultado. | 7 |
| `PreReportBuilder.kt` | Arma el bloque pre-reporte y el ratio de riesgo. `reportTimingOf` decide antes/después de la campana en hora de Nueva York. | 4.3, 7 |
| `EarningsEventLog.kt` | Bitácora JSONL, solo agrega. La última copia gana; las líneas dañadas se cuentan. | 7 |
| `EventSettlement.kt` | Precia la reacción: cierre base y cierre de reacción según el horario, retorno del índice en la misma ventana, retorno anormal por diferencia. | 4.2, 7 |
| `DecisionMatrix.kt` | Clasifica el riesgo (>1.3 alto, <0.8 bajo), resuelve la celda de la matriz con el precio contra el DCF (barato ≤ 0.9×) y aplica el tope de costo de cobertura. | 4.3, 4.5, 4.6 |
| `YahooLiveShapeTest` | Corre los dos parsers contra cuerpos reales de Yahoo, guardados sin tocar. Ninguna prueba llama a la red. | 7 |
| `EventMove.kt` | Separa el movimiento del evento de la deriva de los días tranquilos que quedan hasta el vencimiento. Cuenta días hábiles y lee el movimiento diario típico del ticker por mediana. | 4.3 |
| `HedgeQuote.kt` | Precia la cobertura sobre la misma escalera del straddle: put ATM solo, y put spread contra el strike más cercano a 5% abajo. El costo se lee contra el precio de la acción, en bps. | 4.6 |

Fixtures: `core/src/test/resources/yahoo/options/LVS-2026-08-28.json` y `yahoo/earningsTrend/{LVS,THIN}.json`. Además, dos cuerpos bajados de Yahoo en vivo el 2026-08-27 y guardados tal cual: `LVS-live-2026-08-27.json` de la cadena y del quoteSummary. `YahooLiveShapeTest` corre los dos parsers contra ellos.

Cobertura: 166 pruebas en el paquete `earnings` de `:core`; en `:app`, 28 del grabador, 8 de los endpoints, 28 del presentador y 12 de la pantalla. Cada bloque se verificó por mutación — se rompió la lógica a propósito y se confirmó que las pruebas se ponen en rojo.

### Cableado en la app

| Pieza | Dónde |
|---|---|
| `YahooFinanceClient.fetchOptionChain` | Pega a `/v7/finance/options/{symbol}`. Sin fecha lista los vencimientos; con `date` trae la escalera. |
| `YahooFinanceClient.fetchConsensus` | Lee `earningsTrend`, que ahora viaja en `QUOTE_SUMMARY_MODULES`. Cero módulos nuevos en el pedido. |
| `EarningsEventRecorder` | Toma las filas del refresh, filtra las que reportan dentro de 10 días, baja la cadena y escribe el bloque ya decidido. Antes de capturar, liquida los reportes que ya pasaron (1 a 30 días) contra SPY. Lee la bitácora una vez por pasada. |
| `DefaultDashboardRepository.finishRefresh` | Llama al grabador al lado de `journalScores`, con la misma política: los fallos se loguean y se descartan. |
| `DiscountScreenerAppContainer` | Arma el grabador con `filesDir/earnings/events.jsonl`. No `cacheDir`: el sistema borra el caché primero y esta es la única cosa de la app que no se puede volver a bajar. |

Un evento se escribe una sola vez. La segunda pasada sobre el mismo reporte no hace ni una llamada de red, así que el precio y la cadena guardados son los del primer día en que el reporte apareció.

Un reporte con hora sin confirmar se guarda igual, con `timing = Unknown`, y su ventana de reacción abarca el día entero. Yahoo manda la hora dentro de la rueda cuando la fecha todavía no está confirmada; descartarlo perdía la mayoría de los eventos reales.

### La pantalla

Pestaña **Earnings** en el dashboard.

| Pieza | Dónde |
|---|---|
| `EarningsGatePresentation.kt` | `presentEarningsGate` parte la bitácora en "reportan pronto" y "ya reportaron", y traduce bps a porcentajes, ratios y tamaños. |
| `EarningsGateScreen.kt` | Una tarjeta por evento: celda de la matriz, movimiento implícito, historia propia del ticker, ratio, precio contra DCF, acción, tamaño, cobertura y lo que la cobertura cuesta. El reporte ya liquidado muestra el movimiento que el índice no explica. |
| `DashboardRepository.earningsEvents` | Lee la bitácora y presenta. Los fallos se loguean y devuelven vacío. |
| `GetEarningsEventsUseCase` | La pestaña carga al abrirse, como Estimates y Discovery. |

Las líneas dañadas de la bitácora se muestran contadas en la pantalla. Nunca se ocultan.

### El horizonte del ratio

El straddle del vencimiento siguiente al reporte no precia solo el reporte. Precia el reporte más los días tranquilos que faltan hasta el vencimiento. La mediana de retorno anormal, en cambio, es de un día. Dividir uno por otro mezclaba dos ventanas distintas y el ratio salía inflado, tanto más cuanto más lejos caía el vencimiento.

Ahora el movimiento del evento se separa antes de dividir:

```
evento² = total² − (movimiento diario típico)² × (días hábiles hasta el vencimiento − 1)
```

- El movimiento diario típico sale de la mediana del movimiento absoluto diario de los últimos 3 meses del ticker. La mediana aguanta el salto del reporte anterior sin moverse.
- Vencimiento el mismo día del reporte: el total ya es todo evento, no se resta nada.
- Ticker sin historia legible (menos de 20 días): se queda el total. No se inventa una resta.
- La resta nunca deja el evento por debajo del 30% del total. Un ticker tranquilo con un vencimiento lejano no puede quedar con riesgo de evento cero.

El ratio de §4.3 se lee contra este número, no contra el total. La pantalla muestra los dos: el movimiento priceado al vencimiento y el que queda para el evento.

### Lo que la cadena en vivo enseñó

Los parsers se escribieron contra fixtures a mano. Bajar la cadena real de LVS el 2026-08-27, fuera de rueda, mostró dos cosas que la fixture no tenía.

**Strikes sin oferta.** Todos los strikes por debajo del dinero venían con `bid` en cero. La pata corta del spread se elegía por cercanía al 5% y después se descartaba por no tener precio, así que el spread quedaba sin cotizar aunque más abajo hubiera un strike que sí se opera. Ahora la elección solo mira strikes cotizables.

**Cadena cotizada más ancha que su propio mid.** El call ATM venía 0.25 / 2.49 contra un straddle de 1.74: la horquilla era 154% del straddle. El mid de eso no precia el reporte, precia el spread del market maker.

La respuesta no es descartar el evento. Una cadena no se vuelve a publicar, así que el evento se guarda igual, con `quoteSpreadBps` al lado del movimiento. Lo que se frena es la decisión: por encima del 50% de ancho, la celda queda `Undecided` y la justificación dice que hay que leerla con el mercado abierto. La bitácora conserva el número crudo; el gate no actúa sobre él.

### El tope de costo (§4.6)

El bloque pre-reporte guarda `putSpreadCostBps`, `protectivePutCostBps` y los dos strikes. Se precian en la misma pasada que el straddle, sobre la escalera que ya se bajó, así que no cuesta ni una llamada de red más.

En la celda "barato + riesgo alto":

- Put spread hasta 1% del valor de la posición: se cubre, a media posición, y la justificación dice el precio.
- Put spread por encima del 1%: se recorta el tamaño y no se cubre. La justificación nombra el costo y el tope que lo dejó afuera.
- Cadena que no cotiza spread: se pide la cobertura sin precio, como antes.

**Falta:** la regresión de §4.2 espera a que la bitácora junte 16–20 trimestres.

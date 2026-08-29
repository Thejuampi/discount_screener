# PRD: Pre-Earnings Risk Gate

Status: proposed, 2026-08-27. Actualizado 2026-08-28 con §4.7. Author: Juan. Not planned, not scoped, no wave assigned.

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
- Lectura de la bitácora por ticker: buscador en la lista de eventos y sección de earnings en el detalle del ticker.

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

### 4.7 Lectura por ticker

El gate escribe un registro por reporte. Hoy la única forma de leerlo es una lista ordenada por fecha. Con cinco tickers alcanza. Con el universo entero, el lector que quiere saber de un símbolo tiene que recorrerla entera, y el evento del ticker que está mirando en el detalle no aparece en la pantalla donde lo está mirando.

**Buscador en la pestaña Earnings.** Campo de texto arriba de la lista. Filtra las dos secciones —"reportan pronto" y "ya reportaron"— por ticker, sin distinguir mayúsculas, por prefijo del símbolo. Campo vacío es la lista completa.

Lo que el filtro no puede tapar:
- La línea de última captura y el conteo de líneas dañadas quedan visibles siempre. Dicen si el módulo sigue corriendo y si la bitácora está sana. Un filtro que las esconde convierte una captura rota en una búsqueda sin resultados.
- Una búsqueda sin coincidencias dice que no hay coincidencias, y nombra el término. Nunca muestra el vacío de instalación nueva: ese texto dice "no hay reportes en la bitácora" y sería mentira con la bitácora llena.

**Sección de earnings en el detalle del ticker.** Al abrir el detalle de un ticker, si la bitácora tiene un evento suyo, el detalle lo muestra. La misma tarjeta que la pestaña, sin una segunda forma de leer los mismos bps. Si la bitácora tiene varios, muestra el próximo que reporta y el último que ya liquidó.

Sin evento, una sola línea dice por qué, y nunca repite la fecha que el encabezado ya trae:
- Reporte más allá de la ventana de captura: la cadena se precia dentro de los 10 días.
- Reporte dentro de la ventana y todavía sin preciar: falta una pasada con el mercado abierto. Esta es la única de las tres que señala algo que puede fallar.
- Sin fecha de reporte: no hay nada que preciar.

Reglas comunes a las dos superficies:
- Las dos solo leen. Abrir un detalle o tipear en el buscador no baja una cadena, no dispara una captura y no liquida nada. La captura tiene su propio reloj (§13, `EarningsCaptureWorker`); una pantalla que pidiera la cadena gastaría el pedido que el worker necesita y podría quemar la única pasada en rueda del día.
- Las dos leen la misma bitácora ya presentada. El detalle no abre el archivo por su cuenta.

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
- La captura de fondo ya no depende de la fecha que dejó el último refresh: cada pasada pregunta el calendario de hasta doce símbolos cuya fecha venció o falta, y arranca donde paró la anterior. Queda un límite más chico: un símbolo entra a la cola solo una vez por día, así que un reporte adelantado de golpe puede tardar hasta un día en aparecer.
- El cierre del evento se lee el día que la empresa presentó el 8-K, no el que decía el calendario. Una fecha sin presentación cerca queda sin cerrar en vez de inventar una reacción que el reporte nunca causó, porque esa reacción sería la mediana contra la que se divide todo ratio de riesgo posterior. Límite: un ticker sin archivo en EDGAR conserva la fecha del calendario.
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
| EPS y revenue reales | `earningsHistory` de Yahoo da 4 trimestres con real, estimado y sorpresa; `incomeStatementHistoryQuarterly` da el revenue de los mismos trimestres. Ya cableado. EDGAR XBRL daría los reales sin límite de historia, si algún día hacen falta más de 4. | Gratis |
| Retorno de mercado y beta ex-evento | Serie de precios de un índice por el mismo endpoint de chart que ya se usa. | Gratis |
| **Fechas exactas de reportes pasados** | EDGAR: el 8-K con item 2.02 es el anuncio de resultados, fechado por la propia empresa. La marca de aceptación dice si salió antes de la apertura o después del cierre. AVGO da 20 trimestres. | Gratis, mismo host que el proveedor SEC que ya usa la app |
| **Historia de implied move** | **No se backfillea gratis.** O se compra (ORATS y similares venden cadenas históricas), o se empieza a capturar hoy, un registro por evento. | Compra, o dos años de espera |
| **Historia de consenso más allá de 4 trimestres** | Misma respuesta: capturar desde hoy, o comprar. | Compra, o espera |

### Qué implica esto para el orden de trabajo

El log de eventos (§7) deja de ser el último paso y pasa a ser el primero. Es la única pieza que empieza a pagar el día que se escribe: cada reporte que pasa sin log es un evento que no vuelve. Con el log corriendo, la historia se acumula sola mientras se construye el resto.

Con la historia que existe hoy (4 trimestres de `earningsHistory`) no se puede correr la regresión de §4.2, que pide 16–20 trimestres. El 8-K de EDGAR sí llega: da 20 trimestres de fechas exactas, y con las series de precio eso alcanza para la regresión y para el denominador de §4.3.

El denominador de §4.3 sale de ahí desde la primera pasada. Antes salía solo de la bitácora propia, y en una instalación nueva la bitácora está vacía: cada tarjeta decía `Undecided` hasta que la app viera pasar sus propios reportes, o sea años. Ahora la bitácora acumula el implied move, que EDGAR no tiene, y EDGAR aporta las fechas, que la bitácora todavía no juntó.

## 12. Preguntas abiertas

0. **Beta ex-evento.** **Hecho.** El retorno anormal ya descuenta `beta × retorno_mercado` en vez de restar el mercado uno a uno. La beta se ajusta sobre los retornos diarios sacando cada día de reporte y el día de cada lado, porque dejarlos adentro deja que los mismos eventos que se miden fijen la vara que los mide. Bajo 60 días pareados no se reporta beta y la resta vuelve a ser uno a uno. La beta usada queda guardada con el evento, en `PostReport.marketBetaBps`, para que el retorno anormal se pueda auditar después. Medido en vivo sobre AVGO contra SPY: beta 1,61, el retorno anormal del mismo evento pasó de −122 a −120 bps, y la historia propia del ticker de 5,84% a 5,89%. La tarjeta lo muestra: "Abnormal move -1.20%, beta 1.61x" y "EPS -6.40 of the analyst spread, revenue -24.62%".

1. ~~**Horizonte del ratio.**~~ **Resuelto.** Ver §13, "El horizonte del ratio". El implied move se separa en movimiento de evento y deriva tranquila antes de dividir.
2. **Potencia del criterio §9.** 8–12 trimestres sobre la cartera actual da N eventos; partidos en dos celdas, la diferencia de retorno anormal entre "alto" y "normal" puede caer dentro del ruido. Hay que contar N y medir la dispersión antes de comprometer ese criterio.
3. ~~**Definición de SUE sin desvío estándar.**~~ **Resuelto.** El denominador es la mitad del rango de los estimados, `(high − low) / 2`. Con estimados simétricos alrededor de la media esa es la distancia de la media a cualquiera de los bordes, que es lo que el desvío estándar viene a representar. Un solo analista, o un panel que dijo todos el mismo número, no tiene rango: el score queda sin reportar antes que dividir por nada. Fijado en `SurpriseScore.kt` y no se mueve.

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
| `EventSettlement.kt` | Precia la reacción: cierre base y cierre de reacción según el horario, retorno del índice en la misma ventana, retorno anormal descontando beta × mercado. Al liquidar también escribe el EPS y el revenue reales del trimestre, con sus dos sorpresas. | 4.2, 7 |
| `DecisionMatrix.kt` | Clasifica el riesgo (>1.3 alto, <0.8 bajo), resuelve la celda de la matriz con el precio contra el DCF (barato ≤ 0.9×) y aplica el tope de costo de cobertura. | 4.3, 4.5, 4.6 |
| `YahooLiveShapeTest` | Corre los dos parsers contra cuerpos reales de Yahoo, guardados sin tocar. Ninguna prueba llama a la red. | 7 |
| `MarketBeta.kt` | Estima cuánto del movimiento diario del ticker explica el índice, excluyendo los días de reporte y el día de cada lado. Bajo 60 días pareados no devuelve nada, y el retorno anormal vuelve a la resta uno a uno. | 4.2, 4.3 |
| `EventMove.kt` | Separa el movimiento del evento de la deriva de los días tranquilos que quedan hasta el vencimiento. Cuenta días hábiles y lee el movimiento diario típico del ticker por mediana. | 4.3 |
| `EdgarFilings.kt` | Lee las presentaciones de EDGAR y saca cada anuncio de resultados: forma 8-K con item 2.02. Fecha por marca de aceptación en hora de Nueva York, porque `filingDate` pasa al día hábil siguiente después de las 17:30. Calcula los retornos anormales pasados con la misma regla de ventana que la liquidación. | 4.2, 4.3 |
| `ReportedQuarter.kt` | Lee los trimestres que la empresa ya reportó: EPS real, el estimado contra el que se lo midió, y el revenue del mismo trimestre. Une `earningsHistory` con `incomeStatementHistoryQuarterly` por fecha de cierre. | 4.2, 7 |
| `SurpriseScore.kt` | Puntúa la sorpresa en unidades de dispersión de los analistas, y la sorpresa de revenue contra el consenso guardado antes del reporte. | 4.2, 7 |
| `HedgeQuote.kt` | Precia la cobertura sobre la misma escalera del straddle: put ATM solo, y put spread contra el strike más cercano a 5% abajo. El costo se lee contra el precio de la acción, en bps. | 4.6 |

Fixtures: `core/src/test/resources/yahoo/options/LVS-2026-08-28.json` y `yahoo/earningsTrend/{LVS,THIN}.json`. Además, dos cuerpos bajados de Yahoo en vivo el 2026-08-27 y guardados tal cual: `LVS-live-2026-08-27.json` de la cadena y del quoteSummary. `YahooLiveShapeTest` corre los dos parsers contra ellos.

Cobertura: 240 pruebas en el paquete `earnings` de `:core`; en `:app`, 37 del grabador, 11 de los endpoints, 34 del presentador y 14 de la pantalla. Cada bloque se verificó por mutación — se rompió la lógica a propósito y se confirmó que las pruebas se ponen en rojo.

### Cableado en la app

| Pieza | Dónde |
|---|---|
| `YahooFinanceClient.fetchOptionChain` | Pega a `/v7/finance/options/{symbol}`. Sin fecha lista los vencimientos; con `date` trae la escalera. |
| `YahooFinanceClient.fetchConsensus` | Lee `earningsTrend`, que ahora viaja en `QUOTE_SUMMARY_MODULES`. Cero módulos nuevos en el pedido. |
| `EarningsEventRecorder` | Toma las filas del refresh, filtra las que reportan dentro de 10 días, baja la cadena y escribe el bloque ya decidido. Antes de capturar, liquida los reportes que ya pasaron (1 a 30 días) contra SPY. Lee la bitácora una vez por pasada. |
| `YahooFinanceClient.fetchReportedQuarters` | Pide `earningsHistory` e `incomeStatementHistoryQuarterly` en su propio par de módulos. Solo un evento que liquida los necesita: meterlos en `QUOTE_SUMMARY_MODULES` haría que cada símbolo de cada refresh cargue un estado de resultados trimestral que nunca lee. |
| `SecEdgarTimeseriesProvider.earningsAnnouncements` | Pide `data.sec.gov/submissions/CIK##########.json` por el mismo gobernador de pedidos y el mismo caché en disco que el resto de SEC. Un solo cliente por host: dos habrían inventado su propio límite. |
| `DefaultDashboardRepository.finishRefresh` | Llama al grabador al lado de `journalScores`, con la misma política: los fallos se loguean y se descartan. |
| `DiscountScreenerAppContainer` | Arma el grabador con `filesDir/earnings/events.jsonl`. No `cacheDir`: el sistema borra el caché primero y esta es la única cosa de la app que no se puede volver a bajar. |
| `EarningsEventRecorder.refreshStaleDates` | Antes de precisar nada, pide a Yahoo la fecha del próximo reporte de los símbolos cuya fecha venció o falta: doce por pasada, rotando con un cursor guardado al lado de la bitácora. La respuesta se guarda con la hora en que se preguntó, así un símbolo sin fecha futura no vuelve a la cola hasta el día siguiente. |
| `EventSettlement.settlementOf` | Cierra el evento el día del 8-K con ítem 2.02, y toma de ahí también la hora. Si la empresa tiene archivos en EDGAR y ninguno cae a menos de siete días de la fecha del calendario, no cierra: una reacción leída en un día sin reporte entra a la mediana que denomina todos los ratios de riesgo siguientes. El día usado queda escrito en `PostReport.reportedOnEpochDay` y la tarjeta lo muestra cuando difiere del calendario. |
| `EarningsCaptureWorker` | Trabajo periódico de WorkManager, cada 90 minutos, con red exigida. Pregunta primero si la rueda está abierta y, si lo está, restaura el universo que ya vive en el teléfono y pide solo las cadenas de los reportes dentro de la ventana. Nunca refresca el tablero. |

Un evento con la cadena ya preciada se escribe una sola vez. La segunda pasada sobre él no hace ni una llamada de red, así que el precio y la cadena guardados son los del primer día en que el reporte apareció.

Un evento que quedó sin implied move se vuelve a pedir en cada pasada, mientras el reporte siga dentro de la ventana de captura. Una cadena de opciones no se vuelve a publicar: una sola consulta fallida le costaría al evento su movimiento priceado para siempre, que es justo la pérdida que esta bitácora existe para evitar.

Por eso `fetchOptionChain` distingue tres respuestas que antes se veían iguales:

- **`result` vacío.** El proveedor no contestó por ese símbolo. Es la forma que toma una cookie vencida. Se limpia la sesión y se pregunta una vez más; si vuelve a pasar, el evento queda sin precio y la próxima pasada lo pide de nuevo.
- **`result` con el subyacente y la lista de vencimientos vacía.** El ticker no tiene opciones. No se pregunta dos veces.
- **La escalera entera cotizada en cero.** Fuera de rueda Yahoo devuelve bid y ask en cero para los 101 strikes: `marketState: PRE`. El straddle la rechaza a propósito. La tarjeta ahora dice "the chain for this expiry is not quoted yet" en vez de "no option chain", porque la cadena está y solo está cerrada. Medido en vivo sobre AVGO el 2026-08-28: 0 de 101 calls con bid.

### La captura no puede depender de que el usuario abra la app

Fuera de rueda cada strike cotiza bid cero, así que solo una pasada dentro del horario regular deja un movimiento priceado. Hasta acá la única pasada era la del refresh, y el refresh solo corre con la app abierta: un reporte quedaba priceado nada más si el usuario abría la app durante una rueda dentro de sus diez días de ventana. Una cadena de opciones no se vuelve a publicar, y cada rueda perdida era un reporte sin precio para siempre.

`EarningsCaptureWorker` cierra ese agujero. Corre cada 90 minutos —cuatro pasadas por rueda—, pregunta `quotesAreLive` antes de gastar un pedido y sale sin hacer nada si el mercado está cerrado. El trabajo se encola con nombre único y política `UPDATE`: un pedido idéntico no cambia nada, así que un relanzamiento no le reinicia la cadencia, y un período nuevo en una versión nueva sí llega a un teléfono que ya tenía el viejo encolado. Con `KEEP` ese teléfono se quedaba con la cadencia vieja para siempre.

`quotesAreLive` lee la hora de Nueva York, nunca la zona en la que está el teléfono. Un feriado se ve como rueda abierta: la cadena vuelve sin cotizar, el straddle la rechaza y el costo es un pedido gastado en vez de un número equivocado.

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

### Lectura por ticker (§4.7)

| Pieza | Dónde |
|---|---|
| `EarningsGateUi.matching` | Filtra las dos listas por prefijo del símbolo. `damagedLines` y `lastCapture` no se tocan: describen la lectura entera y tienen que quedar visibles detrás de cualquier filtro. |
| `EarningsGateUi.eventsFor` | Lo que ve el detalle de un ticker: el próximo reporte que tiene y el último que liquidó. Las dos listas llegan ordenadas, así que la primera coincidencia de cada una es la que lleva decisión viva. |
| `EarningsGateScreen` | Campo de filtro arriba de la lista, con el estado en el composable. Una búsqueda sin coincidencias nombra el término y nunca muestra el vacío de instalación nueva. |
| `DetailScreen` | Sección `EARNINGS` dentro del subtab Snapshot, debajo del encabezado de score. La tarjeta es la misma que la pestaña: `EarningsEventCard` pasó a `internal`. |
| `earningsGateAbsence` | Sin evento, la razón en una línea. Vive al lado de `earningsMark`, que ya traduce la misma fecha, y lee `CAPTURE_WINDOW_DAYS`. |
| `CAPTURE_WINDOW_DAYS` | Pasó del grabador a `:core`. La pantalla y el grabador tienen que nombrar la misma ventana o la explicación miente. |
| `DashboardViewModel.openDetail` | Carga la bitácora una sola vez si todavía está vacía. `loadEarningsGate` no está cacheado, así que llamarlo por cada detalle releería el archivo entero. |

Las dos superficies solo leen. Abrir un detalle o tipear en el filtro no baja una cadena ni dispara una captura: el worker conserva su única pasada en rueda.

**Falta:** la regresión de §4.2 espera a que la bitácora junte 16–20 trimestres.

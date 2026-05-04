# Corrección urgente: Quant Lens Horizon Context

Fecha: 2026-05-04
Estado: listo para implementación
Tipo: corrección BMad one-shot para Android Quant Lens
Alcance de este artefacto: planificación/especificación solamente; no modifica código fuente, tests, PRs ni validaciones.

## 1. Requisito refinado

El hallazgo es claro: Android Quant Lens no es, ni debe convertirse en, un motor de predicción futura por timeframe. La corrección no puede limitarse a disclaimers, porque el usuario hizo una pregunta de producto legítima: "¿qué contexto tengo para 5m, 1D y 3M?".

La nueva capacidad debe agregar una lente/sección de detalle llamada `HorizonContext` o equivalente que responda con contexto histórico descriptivo, calculado desde velas ya cargadas en memoria/local cache. Debe mostrar rangos históricos de movimiento absoluto para horizontes 5m, 1D y 3M, con estados de suficiencia/unavailable cuando los datos no alcancen.

La respuesta de producto permitida es:

- "En las velas cargadas, este símbolo se ha movido típicamente X en este horizonte."
- "Hay N ventanas históricas para estimar este baseline descriptivo."
- "No hay suficientes velas cargadas para este horizonte."

La respuesta de producto prohibida es:

- "Va a subir/bajar en 5m, 1D o 3M."
- "Probabilidad de ganancia/pérdida."
- "Precio objetivo, señal buy/sell/hold o recomendación operativa."
- "Forecast", "prediction", "target", "expected return" o lenguaje equivalente como conclusión de `HorizonContext`.

Esta corrección es bold-but-honest: agrega una capacidad real y útil para el horizonte temporal, pero conserva la frontera epistemológica correcta. El producto da baseline histórico, no promesa futura.

## 2. Criterios de aceptación

1. `QuantLensReport` expone una nueva sección `horizonContext` con tres resultados, uno por horizonte: 5m, 1D y 3M.
2. La sección se calcula exclusivamente en `apps/android/core` como función pura sobre `QuantLensInput.selectedCandlesByRange`.
3. El repositorio Android no agrega fetches nuevos para esta lente. Usa las velas que ya están en `chartCache` y que hoy se pasan a `QuantLensInput`.
4. El mapping de fuentes queda explícito y testeado:
   - 5m: `ChartRange.Day`, ventanas adjacent close-to-close, porque `YahooFinanceClient.chartRangeSpec(ChartRange.Day)` usa `1d` + `5m`.
   - 1D: `ChartRange.Month`, ventanas adjacent close-to-close, porque `ChartRange.Month` usa `1mo` + `1d`.
   - 3M: `ChartRange.FiveYears`, ventanas con lag de 3 velas close-to-close, porque `ChartRange.FiveYears` usa `5y` + `1mo`.
5. Cada horizonte requiere al menos 10 ventanas válidas. Con menos de 10 ventanas, el horizonte queda `Insufficient`; si el rango fuente no existe o no trae velas válidas, queda `Unavailable`.
6. Para cada horizonte disponible se reporta:
   - estado primario;
   - etiqueta de horizonte;
   - lookback/rango fuente;
   - cantidad de muestras;
   - mediana/P50 de movimiento absoluto en bps;
   - P25 y P75 de movimiento absoluto en bps;
   - reason codes estructurados.
7. Los cálculos usan el estilo fijo del repo: centavos (`closeCents`) y basis points (`*_bps`) como enteros.
8. La UI de detalle de Quant Lens muestra una nueva sección `Horizon context` o `Historical horizon context`. El copy dice baseline/contexto histórico, no forecast.
9. Las filas/list row chips no promocionan `HorizonContext` salvo que el implementador lo haga sin cómputo adicional ni ruido visual. Para esta corrección urgente, el default recomendado es detalle solamente.
10. Compose sigue siendo vista pasiva: recibe `QuantLensUiState` ya mapeado y no calcula ventanas, percentiles, thresholds ni reason codes.
11. Los tests cubren motor core, mapping UI y el contrato de row summary si se agrega un nuevo `QuantLensLensId`.
12. No se cambia proveedor, persistencia SQLite ni política de carga de datos para satisfacer esta lente.

## 3. Contrato de arquitectura y datos

### Frontera conceptual

`HorizonContext` es un baseline descriptivo de volatilidad/movimiento histórico por horizonte. No es un forecast. Debe quedar separado de `ExpectedValueRange`, que trabaja con anchors de valor razonable, y de `TrendReliability`, que mide ajuste/tendencia del rango seleccionado.

### Entrada existente

El punto de entrada ya existe:

- `QuantLensInput.selectedCandlesByRange: Map<ChartRange, List<HistoricalCandle>>`
- `DefaultDashboardRepository.buildSelectedQuantLensLocked(...)` ya llena ese mapa con `ChartRange.entries.associateWith { chartCache[...] }`.
- `quantLensFingerprintLocked(...)` ya hashea todas las velas por `ChartRange`, por lo que cambios en `Day`, `Month` o `FiveYears` invalidan el cache del reporte.

No hace falta cambiar la fuente remota para implementar esta capacidad. La hidratación de detalle ya intenta cargar `ChartRange.entries` en `ensureDetailLoaded(...)`, y el enrichment también rellena rangos faltantes. Si las velas no están cargadas, el resultado debe comunicar `Unavailable`/`Insufficient`, no disparar fetches desde core ni desde el mapper.

### Modelo core propuesto

Extender `apps/android/core/src/main/kotlin/com/discountscreener/core/model/QuantLensModels.kt` con nombres cercanos a estos:

```kotlin
@Serializable
enum class QuantLensLensId {
    EvidenceStrength,
    ExpectedValueRange,
    CorrelationRisk,
    TrendReliability,
    SimilarSetups,
    HorizonContext,
}

@Serializable
enum class QuantLensHorizon {
    FiveMinutes,
    OneDay,
    ThreeMonths,
}

@Serializable
data class QuantLensHorizonContext(
    val primaryStatus: QuantLensPrimaryStatus,
    val horizons: List<QuantLensHorizonBaseline>,
    val reasonCodes: List<QuantLensReasonCode>,
)

@Serializable
data class QuantLensHorizonBaseline(
    val horizon: QuantLensHorizon,
    val primaryStatus: QuantLensPrimaryStatus,
    val sourceRange: ChartRange,
    val lagCandles: Int,
    val sampleCount: Int,
    val medianAbsoluteMoveBps: Int? = null,
    val p25AbsoluteMoveBps: Int? = null,
    val p75AbsoluteMoveBps: Int? = null,
    val reasonCodes: List<QuantLensReasonCode>,
)
```

Reason codes recomendados para agregar a `QuantLensReasonCode`:

- `HistoricalBaselineAvailable`
- `MissingHorizonCandles`
- `InsufficientHorizonSamples`
- `InvalidHorizonClose`

También agregar `val horizonContext: QuantLensHorizonContext` a `QuantLensReport` e incrementar `QuantLensModelVersion.CURRENT` de `1` a `2`, porque cambia la forma pública serializable del reporte aunque el MVP no persista outputs derivados como hechos canónicos.

### Mapeo de horizontes

| Horizonte UI | Enum core | Rango fuente | Lag | Ventana | Mínimo | Justificación |
| --- | --- | --- | ---: | --- | ---: | --- |
| `5m` | `FiveMinutes` | `ChartRange.Day` | 1 | close actual vs close anterior | 10 | Yahoo Day está configurado como interval `5m`. |
| `1D` | `OneDay` | `ChartRange.Month` | 1 | close diario actual vs close diario anterior | 10 | Yahoo Month está configurado como interval `1d`. |
| `3M` | `ThreeMonths` | `ChartRange.FiveYears` | 3 | close mensual actual vs close de 3 velas atrás | 10 | Yahoo FiveYears está configurado como interval `1mo`; 3 velas aproxima 3 meses. |

### Algoritmo exacto

Para cada horizonte:

1. Tomar `candles = selectedCandlesByRange[sourceRange].orEmpty()`.
2. Filtrar velas con `closeCents > 0L`.
3. Ordenar por `epochSeconds` ascendente.
4. Si no quedan velas válidas, devolver `Unavailable` con `MissingHorizonCandles` o `InvalidHorizonClose`.
5. Construir ventanas con `lagCandles`:
   - índice actual `i` desde `lagCandles` hasta `lastIndex`;
   - close base `candles[i - lagCandles].closeCents`;
   - close actual `candles[i].closeCents`;
   - retorno bps `((actual - base) / base) * 10_000`, redondeado a entero y acotado si el helper local lo requiere;
   - movimiento absoluto bps `abs(retornoBps)`.
6. `sampleCount = movimientos.size`.
7. Si `sampleCount < 10`, devolver `Insufficient` con `InsufficientHorizonSamples`, manteniendo `sampleCount` y dejando percentiles en `null`.
8. Ordenar `movimientos` ascendente.
9. Calcular percentiles por nearest-rank determinístico:
   - `rankIndex(percent) = ceil(percent * sampleCount).toInt().coerceIn(1, sampleCount) - 1`;
   - P25 usa `0.25`, mediana/P50 usa `0.50`, P75 usa `0.75`.
10. Devolver `Available` con `HistoricalBaselineAvailable` y los tres bps absolutos.

La sección agregada debe combinar estado así:

- `Available` si los tres horizontes están `Available`.
- `Partial` si al menos un horizonte está `Available` y al menos uno no lo está.
- `Insufficient` si ninguno está `Available` pero al menos uno está `Insufficient`.
- `Unavailable` si todos están `Unavailable`.

Agregar `horizonContext.primaryStatus` a `combinedStatus(...)` de `QuantLensEngine.analyze(...)`.

### Contrato UI/presenter

Extender `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/QuantLensUiModels.kt`:

- Agregar `horizonContextSection(report)` a `mapQuantLensReport(...)`.
- Ubicación recomendada: después de `trendSection(report)` y antes de `similarSection(report)`, porque es chart-derived y complementa `Trend reliability`.
- `title`: `Horizon context` o `Historical baseline`.
- `chip`:
  - todos disponibles: `Baseline ready`, severity `Neutral`;
  - parcial: `Baseline partial`, severity `Neutral`;
  - insuficiente: `Baseline sparse`, severity `Muted`;
  - unavailable: `Baseline unavailable`, severity `Muted`.
- `primaryLine`: `Historical baseline from loaded candles`.
- `rows`: una fila por horizonte, por ejemplo:
  - `5m` -> `Median 0.42% · P25-P75 0.18%-0.91% · 72 windows`
  - `1D` -> `Need 10 windows · Month candles`
  - `3M` -> `Unavailable · FiveYears candles`
- `footerChips`: `Historical context`, `No forecast`, y opcionalmente `Loaded candles`.

No usar `signedPercent(...)` para estos valores porque son movimientos absolutos. Agregar un formatter local, por ejemplo `absolutePercent(bps: Int): String`, que no muestre signo.

Compose no requiere cambios estructurales: `DetailScreen.QuantLensContent(...)` ya itera `quantLens.sections`, y `QuantLensSection(...)` ya renderiza filas genéricas. Solo se necesita asegurar que `HorizonContext` no active comportamiento clickable reservado para `SimilarSetups`.

### Row chips y row summary

Recomendación para la corrección urgente: `HorizonContext` es detail-only.

Si se agrega `QuantLensLensId.HorizonContext`, actualizar tests que hoy asumen `QuantLensLensId.entries.toList()` como contrato completo de row summary. Ese test debe pasar a verificar explícitamente los lens ids visibles en filas: `EvidenceStrength`, `ExpectedValueRange`, `CorrelationRisk`, `TrendReliability`, `SimilarSetups`.

No agregar chip de fila para `HorizonContext` en esta entrega salvo que el implementador pueda hacerlo sin:

- cómputo extra por toda la lista;
- fetches;
- ruido visual en filas;
- cambiar la prioridad actual de chips.

## 4. Mapa de partición de implementación

### Core puro: `apps/android/core`

Archivos esperados:

- `core/model/QuantLensModels.kt`
- `core/engine/QuantLensEngine.kt`
- `core/src/test/.../QuantLensEngineTest.kt`
- opcional: `core/src/test/.../QuantLensModelTest.kt` si hay invariantes serializables/model-level.

Responsabilidades:

- Añadir modelos/enums/reason codes.
- Añadir `horizonContext` al reporte.
- Implementar `analyzeHorizonContext(input)` como función pura.
- Implementar helpers privados de ventanas/percentiles en `QuantLensEngine.kt`, sin Android, red, SQLite, reloj ni coroutines.
- Mantener validaciones de bps (`0..100_000` para movimientos absolutos descriptivos; no deberían ser negativos).
- Mantener invariantes con `require(...)` simples.

### Imperative shell/repositorio: `apps/android/app/data/repository`

Archivo esperado:

- `DefaultDashboardRepository.kt`

Responsabilidades:

- Confirmar que `selectedCandlesByRange` ya incluye todos los rangos. El código actual lo hace.
- No agregar fetches nuevos por `HorizonContext`.
- Confirmar que el fingerprint ya incluye velas de todos los rangos. El código actual lo hace.
- Si se modifica row summary por el nuevo enum, mantenerlo barato y derivado solo de datos ya presentes.

### Presenter/MVP: `apps/android/app/presentation/dashboard`

Archivo esperado:

- `QuantLensUiModels.kt`
- tests en `QuantLensUiModelsTest.kt`

Responsabilidades:

- Mapear `QuantLensHorizonContext` a `QuantLensSectionUi`.
- Formatear bps absolutos.
- Emitir labels y copy de baseline histórico.
- Mantener `mapRowQuantLensSummary(...)` sin promoción de `HorizonContext` en filas, salvo decisión explícita y testeada.

### Compose pasivo: `apps/android/app/ui/dashboard`

Archivo esperado:

- probablemente no requiere cambio en `DetailScreen.kt`.

Responsabilidades:

- Renderizar la nueva sección desde `quantLens.sections` existente.
- No calcular estadísticas ni parsear reason codes.
- No crear textos de predicción o recomendaciones.

### Documentación/artifacts

No es obligatorio para esta corrección urgente modificar PRD/UX/architecture completas, pero el implementador debe considerar este artefacto como override de la frontera de producto para la siguiente historia/cambio.

## 5. Estrategia de pruebas

### Tests core engine

Agregar tests enfocados en `QuantLensEngineTest`:

1. `horizon_context_computes_all_three_baselines_from_loaded_chart_ranges`
   - Entrar con `Day`, `Month`, `FiveYears` suficientes.
   - Verificar que hay tres horizontes y status general `Available`.

2. `horizon_context_uses_day_adjacent_returns_for_five_minute_baseline`
   - Construir cierres con movimientos conocidos.
   - Verificar P50/P25/P75 para `FiveMinutes`.

3. `horizon_context_uses_month_adjacent_returns_for_one_day_baseline`
   - Asegurar que usa `ChartRange.Month`, no `selectedRange` ni `ChartRange.Day`.

4. `horizon_context_uses_five_years_three_candle_lag_for_three_month_baseline`
   - Dataset mensual con lag 3; un cambio en lag 1 debe romper el expected.

5. `horizon_context_marks_missing_range_unavailable`
   - Sin `ChartRange.Day` o lista vacía, `FiveMinutes` queda `Unavailable` con reason code correcto.

6. `horizon_context_marks_short_history_insufficient`
   - 9 ventanas válidas exactas produce `Insufficient` y percentiles `null`.

7. `horizon_context_ignores_invalid_close_cents`
   - Velas con close 0 o negativo no crean ventanas válidas ni dividen por cero.

8. `horizon_context_is_not_a_forecast_contract`
   - Test de modelo/mapper más útil que test semántico: verificar que el contrato no tiene campos de target/probability/recommendation y que UI labels usan baseline/contexto.

Mantener tests simples. Si un test necesita varios checks, preferir comparar un objeto esperado compacto o usar el mecanismo de soft assertions disponible en el proyecto.

### Tests de modelos

En `QuantLensModelTest` si existe cobertura de invariantes:

- `QuantLensHorizonBaseline` rechaza sampleCount negativo.
- Rechaza bps negativos para movimientos absolutos.
- Permite percentiles `null` cuando status no disponible.

### Tests UI/presenter

En `QuantLensUiModelsTest`:

1. `detail_quant_lens_maps_horizon_context_section_after_trend`
   - Verificar orden de secciones o lista de titles.

2. `horizon_context_available_rows_show_absolute_baseline_copy`
   - Verificar labels `5m`, `1D`, `3M`, `Median`, `P25-P75`, `windows`.

3. `horizon_context_sparse_and_unavailable_rows_do_not_show_forecast_language`
   - Verificar `Need 10 windows`/`Unavailable` y ausencia de `forecast`, `predict`, `target`, `gain probability` en labels mapeados.

4. `row_summary_does_not_promote_horizon_context_chip`
   - Si el enum existe, construir summary con `HorizonContext` y verificar que `mapRowQuantLensSummary(...)` mantiene la prioridad/cap anterior.

### Tests repositorio/contrato

En `DefaultDashboardRepositoryTest`:

- Si se agrega `QuantLensLensId.HorizonContext`, actualizar `bootstrap_row_quant_lens_summary_exposes_all_lens_states` para no usar `QuantLensLensId.entries.toList()` como contrato global. El row summary debe verificar los cinco lens ids visibles en filas, o incluir `HorizonContext` solo si se implementa un estado barato y deliberado.
- Agregar test de detalle si es práctico: seed/hidratar chart candles para `Day`, `Month`, `FiveYears`, ejecutar `ensureDetailLoaded(...)` o `currentSnapshot(...)`, y verificar que `selectedQuantLens?.horizonContext` refleja las velas cargadas.
- No agregar expectativas de llamadas de red nuevas para Quant Lens. Si el fake client permite contar llamadas, asegurar que el cambio no introduce fetch adicional fuera de la hidratación existente de detalle/enrichment.

### Mutación manual recomendada

Como la corrección toca un algoritmo pequeño, hacer mutaciones manuales tras tests verdes:

- cambiar min samples de 10 a 11 y confirmar fallo;
- cambiar 3M lag de 3 a 1 y confirmar fallo;
- cambiar `ChartRange.Month` por `ChartRange.Year` en 1D y confirmar fallo;
- remover `abs(...)` y confirmar fallo de baseline absoluto.

## 6. Estrategia de QA manual

No ejecutar desde este planning worker. Para el implementador:

1. Instalar/abrir Android con un perfil que ya tenga símbolos con charts hidratados.
2. Abrir detalle de un símbolo líquido, esperar a que cargue `Quant Lens`.
3. Verificar que aparece una sección de contexto histórico de horizontes con filas 5m, 1D y 3M.
4. Verificar que la sección muestra sample counts y movimientos absolutos, no dirección esperada.
5. Cambiar chart range en el subtab Lens. El baseline de horizontes debe mantenerse estable salvo que cambien los datos cargados, porque usa rangos fuente fijos por horizonte.
6. Probar un símbolo con datos incompletos o red deshabilitada tras warm start. La sección debe mostrar `Unavailable` o `Need 10 windows` sin crash.
7. Revisar filas de `Tracked`, `Watch` y `Opportunities`: no debe aparecer un nuevo chip prioritario de horizonte salvo decisión explícita de implementación.
8. Revisar narrow phone layout: la sección se lee como filas compactas y no empuja chips/valores fuera de pantalla.
9. Confirmar copy visible: debe contener baseline/contexto histórico y no debe contener forecast, prediction, target price, probability of gain, buy, sell o hold.

## 7. Riesgos y no objetivos

### Riesgos

- El horizonte 3M es una aproximación con tres velas mensuales, no una ventana calendario exacta día-a-día. Esto es aceptable porque la fuente definida es `ChartRange.FiveYears` con interval `1mo`.
- El baseline 5m depende de que Yahoo mantenga `ChartRange.Day` como interval `5m`. El código actual lo hace, pero si el proveedor cambia payload o granularidad, el estado debe degradar por muestras insuficientes o revisión futura del mapper.
- Si el implementador agrega `HorizonContext` a `QuantLensLensId`, tests que asumían `entries` como lista de row lens states van a fallar. Eso es una señal de contrato, no un bug misterioso.
- Mostrar un baseline de movimiento puede ser leído como forecast por usuarios. Mitigación: copy de contexto histórico, no forecast; sin probabilidad, target ni dirección.
- Calcular horizon context para todas las filas podría ampliar costo y ruido visual. Mantener detail-only en esta entrega.

### No objetivos

- No crear motor predictivo.
- No calcular probabilidad de ganancia/pérdida.
- No agregar target price por horizonte.
- No agregar buy/sell/hold.
- No cambiar Yahoo provider ni granularidad de fetch.
- No persistir outputs de `HorizonContext` como hechos independientes.
- No crear nueva tab global de Quant Lens.
- No rediseñar todo Quant Lens ni refactorizar secciones existentes.

## 8. Notas de readiness y bloqueadores

### Readiness

- La arquitectura actual ya soporta el cambio: `QuantLensInput` recibe velas por rango, `QuantLensEngine` es core puro, `DashboardViewModel` mapea `selectedQuantLens`, y `DetailScreen` renderiza secciones genéricas.
- La data requerida ya se puede obtener de `chartCache` para el símbolo seleccionado. No hace falta ampliar el proveedor.
- `YahooFinanceClient.chartRangeSpec(...)` confirma el mapping de intervalos requerido: Day=`5m`, Month=`1d`, FiveYears=`1mo`.
- `DefaultDashboardRepository.quantLensFingerprintLocked(...)` ya incluye hashes de velas por todos los rangos, por lo que el cache del reporte puede invalidarse cuando cambian los datos de cualquier horizonte.

### Bloqueadores

- No hay bloqueador técnico para implementar el MVP de `HorizonContext`.
- Sí hay una decisión de contrato que el implementador debe cerrar explícitamente: si `HorizonContext` entra en `QuantLensLensId`, row summary debe actualizar tests/contrato para no asumir que todos los lens ids son row-visible.
- Este planning worker no ejecutó validación por restricción del encargo. La validación queda para el agente implementador.

## 9. Handoff recomendado

Ruta recomendada: ajuste directo dentro de la historia/corrección Android Quant Lens existente.

Agente siguiente: developer/implementation agent.

Orden sugerido:

1. Agregar modelos core y tests de contrato.
2. Implementar `analyzeHorizonContext(...)` con tests rojos/verdes.
3. Agregar campo a `QuantLensReport`, version bump y `combinedStatus`.
4. Mapear UI section en presenter con tests.
5. Ajustar tests de row summary si el enum cambió.
6. Ejecutar formato y validación Android según la guía del repo, fuera de este planning worker.

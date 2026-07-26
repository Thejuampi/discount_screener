# Windows — Dashboard Clásico y Dashboard 2.0  
## Especificación funcional y guía de regresión manual

| Campo | Valor |
| --- | --- |
| **App** | `apps/windows` (Tauri + React) |
| **Alcance** | Pantalla Dashboard (edición Clásico + edición 2.0) y efectos colaterales sobre scoring global |
| **Audiencia** | QA / regresión manual / agentes de verificación |
| **Idioma de UI** | ES / EN (i18n); este documento usa **ES** como referencia principal y nota EN entre paréntesis cuando ayuda |
| **Fecha de referencia** | 2026-07-25 |
| **Estado** | Implementación v1 (price path en backend + ConditionalPlan en frontend) |

**Cómo usar este documento en regresión**

1. Ejecutar los casos en orden cuando sea posible (estado y `localStorage` se afectan entre sí).  
2. Cada caso tiene: **Precondiciones → Pasos → Resultado esperado → Fallo si…**.  
3. Marcar `PASS` / `FAIL` / `BLOCKED` y anotar símbolo de ejemplo y modelo activo.  
4. No inventar datos: si el feed no cargó, marcar `BLOCKED` y no forzar PASS.

---

## 1. Propósito del producto (contexto)

### 1.1 Problema que resuelve

El Dashboard **Clásico** resume oportunidades, movers y alertas, pero el mensaje dominante puede quedarse en “buen setup / buen momento” sin decir:

- **dónde** tiene sentido operar (zona de precio),  
- **por qué** el precio podría moverse en contra primero (motivos),  
- **qué tan plausible** es ver esa zona (probabilidad de toque / path),  
- **qué invalida** el plan.

El Dashboard **2.0** prioriza **relación señal/ruido** y **planes condicionales** generados a partir de:

1. Score V3 del símbolo (`aggressive_v3` o `short_v3`, con o sin contexto de mercado).  
2. Estimador de path de precio multi-ancla en Rust (`price_path`).  
3. Capa de presentación `ConditionalPlan` (stance + copy de densidad media).

### 1.2 Qué no es (límites v1)

| No es | Detalle |
| --- | --- |
| Predicción de mercado | `p20` y zonas son estimaciones de path/vol/estructura, no profecía |
| LLM local/cloud | No hay generación de texto con IA; plantillas + datos estructurados |
| Dual long+short simultáneo | Un solo modelo global en el backend |
| Reemplazo del screener | El detalle profundo sigue en Screener al hacer click |
| Aggressive V2 en 2.0 | Al entrar a 2.0 desde V2 se migra a Long V3 |

---

## 2. Mapa de navegación y estado global

### 2.1 Entrada a la pantalla

| Elemento | Ubicación | Comportamiento |
| --- | --- | --- |
| Íem de menú **Dashboard** | Sidebar izquierdo (`view.dashboard`) | `viewMode = "dashboard"`; se persiste en `localStorage` `ds_view_mode` |
| Edición Clásico / 2.0 | Toggle en el propio dashboard | `ds_dashboard_edition` = `legacy` \| `v2` |
| Modelo de scoring | Global de la app | `ds_scoring_model` = `aggressive_v2` \| `aggressive_v3` \| `short_v3` |
| Contexto de mercado | Global de la app | `ds_regime_scoring` = `1` \| `0` |

### 2.2 Shell de la app (fuera del dashboard pero relevante)

| Sector | En Dashboard Clásico | En Dashboard 2.0 |
| --- | --- | --- |
| Sidebar (Dashboard, Screener, …) | Visible, sin cambio | Visible, sin cambio |
| Header global (búsqueda, Long V2/V3/Short, contexto) | **Solo aparece en vista Screener**, no en Dashboard | Igual: en Dashboard 2.0 los controles de modelo están **dentro** del panel 2.0 |
| Status bar / toasts | Globales | Globales |

**Implicación de regresión:** cambiar Long V3 / Short / Contexto desde el **Dashboard 2.0** altera el scoring global de la app. Al ir al Screener, el modelo activo debe ser el último elegido.

### 2.3 Flujo de apertura de símbolo

| Acción | Resultado |
| --- | --- |
| Click en card de plan (2.0) o en oportunidad/mover (Clásico) | `viewMode → screener` + `selectedSymbol = SYM` |
| Teclado en card 2.0: Enter o Espacio | Igual que click |

---

## 3. Pantalla: Dashboard Clásico (`legacy`)

**Componente:** `DashboardPanel` + barra `DashboardEditionToggle` arriba.  
**Cuándo se muestra:** `viewMode === "dashboard"` y `ds_dashboard_edition !== "v2"`.

### 3.1 Layout de sectores (de arriba hacia abajo)

```text
┌─────────────────────────────────────────────────────────────┐
│ [Clásico | 2.0]                          ← barra edición   │
├─────────────────────────────────────────────────────────────┤
│ Header: "Buen día" [, nombre] + subtítulo                   │
├─────────────────────────────────────────────────────────────┤
│ RegimeBanner (contexto de mercado global)                   │
├─────────────────────────────────────────────────────────────┤
│ Mejores oportunidades (hasta 6) + “Ver todas →”             │
├─────────────────────────────────────────────────────────────┤
│ Grid 3 columnas:                                            │
│   Subas del día | Bajas del día | Alertas recientes         │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Sector: barra de edición

| ID sector | `S-LEG-EDITION` |
| --- | --- |
| Controles | Botones **Clásico** / **2.0** |
| Activo | Clásico con estilo `is-active` |
| Acción 2.0 | Cambia a pantalla Dashboard 2.0 y guarda `ds_dashboard_edition=v2` |

### 3.3 Sector: header clásico

| Campo | ES | EN (ref) |
| --- | --- | --- |
| Título | `Buen día` o `Buen día, {ds_display_name}` | `Good day` |
| Subtítulo | `Tu panorama de mercado de un vistazo` | `Your market at a glance` |

`ds_display_name` se lee de `localStorage` (si el usuario lo configuró en settings/otro flujo).

### 3.4 Sector: RegimeBanner

- Compartido con Screener y Dashboard 2.0.  
- Muestra lectura de régimen de mercado (fase, tesis, pilares, etc.) cuando hay datos.  
- **No** es por-símbolo; es contexto global.  
- Si el fetch falla o aún no hay datos, el banner puede quedar vacío o en estado de carga (no bloquear el resto del dashboard).

### 3.5 Sector: Mejores oportunidades

| Regla | Detalle |
| --- | --- |
| Inclusión | `setup_label ∈ {StrongBuy, Buy, Accumulate, StrongAccumulate}` **o** `decision === "Act"` |
| Orden | `composite_score` desc, luego `setup_score` desc |
| Tope | 6 tarjetas |
| Contenido de card | Símbolo, label de setup (i18n del modelo activo), company, sparkline, precio, % día |
| CTA | **Ver todas →** navega a Screener |
| Vacío con rows | Mensaje empty del presentation del modelo |
| Vacío sin datos / loading | Loading `(loaded/total)` o “Datos de mercado no disponibles” |

**Dependencia de modelo:** el copy de título/empty de oportunidades usa `getScoringPresentation(scoringModel)` (long vs short). El modelo activo es el **global** (puede ser V2/V3/Short aunque el Clásico no muestre el selector de modelo en esta vista).

### 3.6 Sector: Subas del día

| Regla | Detalle |
| --- | --- |
| Filtro | `daily_change_bps != null` |
| Orden | `daily_change_bps` desc |
| Tope | 5 |
| Fila | Símbolo, precio, % con color verde/rojo |

### 3.7 Sector: Bajas del día

| Regla | Detalle |
| --- | --- |
| Igual que subas | Orden ascendente de `daily_change_bps` |

### 3.8 Sector: Alertas recientes

| Regla | Detalle |
| --- | --- |
| Fuente | `api.getAlerts()` al montar el panel |
| Tope UI | 8 |
| Tipos mostrados | EnteredQualified / ExitedQualified / ConfidenceUpgraded (copy localizado) |
| CTA | Enlace a Advisor |
| Vacío | “Sin alertas todavía” |
| Click fila | Abre símbolo en Screener |

### 3.9 Lo que el Clásico **no** muestra

- Zona de entrada $A–$B del price path  
- `p20` / invalidación de plan  
- Stance ActNow / WaitZone / ScaleIn como semántica de card  
- Selector Long V3 / Short embebido (usa el global de la app)

---

## 4. Pantalla: Dashboard 2.0 (`v2`)

**Componente:** `DashboardV2Panel`.  
**Cuándo se muestra:** `viewMode === "dashboard"` y `ds_dashboard_edition === "v2"`.

### 4.1 Layout de sectores

```text
┌─────────────────────────────────────────────────────────────┐
│ Header: "Decisiones" [, nombre] + subtítulo                 │
│ Controles: [Clásico|2.0] [Long V3|Short] [Contexto ON/OFF]  │
├─────────────────────────────────────────────────────────────┤
│ RegimeBanner                                                │
├─────────────────────────────────────────────────────────────┤
│ Resumen mercado: N Actuar · N Escalar · N Esperar · N Evitar│
│ Resumen cripto:  N Actuar · N Escalar · N Esperar · N Evitar│
│ (hint opcional si feed parcial loaded/total)                │
├─────────────────────────────────────────────────────────────┤
│ Planes de mercado (acciones/ETFs, hasta 8)                  │
│ Planes cripto (hasta 4)                                     │
└─────────────────────────────────────────────────────────────┘
```

**Ausente a propósito (vs Clásico):** grid de subas/bajas del día y lista de alertas en el hero (ruido).

**Prioridad de producto:** las acciones/ETFs van primero; cripto es sección secundaria, no mezcla en el mismo ranking.

### 4.2 Sector: header 2.0

| Campo | ES | EN |
| --- | --- | --- |
| Título | `Decisiones` (+ nombre opcional) | `Decisions` |
| Subtítulo | `Planes condicionales con zona y path — sin ruido` | `Conditional plans with zone and path — low noise` |

### 4.3 Sector: controles (derecha del header)

#### 4.3.1 Edición Clásico | 2.0

| Acción | Efecto |
| --- | --- |
| Clásico | `ds_dashboard_edition=legacy`; se monta `DashboardPanel` |
| 2.0 | Activo; permanece en 2.0 |

#### 4.3.2 Modelo Long V3 | Short

| Botón | Modelo backend | Side del path |
| --- | --- | --- |
| Long V3 (`scoring.longV3`) | `aggressive_v3` | `long` |
| Short (`scoring.short`) | `short_v3` | `short` |

**No hay botón Long V2 en 2.0.**

Si el estado global era `aggressive_v2` al **entrar** a 2.0:

1. Se llama `selectScoringModel("aggressive_v3")`.  
2. La UI de 2.0 fuerza visualmente `aggressive_v3` si aún viera V2 (`scoringModel === "aggressive_v2" ? "aggressive_v3" : scoringModel`).

Tras cambiar modelo: refresh de oportunidades; scores, decisions y `price_path.side` se recalculan en backend.

#### 4.3.3 Contexto de mercado ON | OFF

| Estado | Backend | Efecto en score V3 |
| --- | --- | --- |
| ON | `set_regime_scoring_enabled(true)` | Puede incluir 4º bucket `regime_score` en composite (cuando hay policy) |
| OFF | `false` | Sin bucket de régimen en composite; status Disabled |

También puede marcar `regime_risk` en el path si hay señales adversas de régimen en la fila.

Persistencia: `ds_regime_scoring`.

### 4.4 Sector: RegimeBanner

Igual que en Clásico (global). En 2.0 convive con el toggle de contexto: el banner puede mostrar tesis de mercado aunque el scoring de régimen esté OFF (son caminos distintos: banner = lectura de mercado; toggle = si el score del símbolo incorpora fit de régimen).

### 4.5 Sector: resumen de buckets (counts)

**Regla anti-bloqueo (R-021):** los counts se muestran en cuanto hay **filas con score** (`rows.length > 0`), aunque el feed esté incompleto (p.ej. 571/572 por un símbolo stuck). No se espera `loaded >= total` para ver Act/Scale/Wait/Avoid.

Hay **dos líneas de counts**:

1. **Mercado (acciones/ETFs)** — `asset_type ∈ {stock, etf}`  
2. **Cripto** — `asset_type === crypto`

| Contador | Stance contada | Color semántico UI |
| --- | --- | --- |
| Actuar | `ActNow` | verde |
| Escalar | `ScaleIn` | cyan |
| Esperar zona | `WaitZone` | ámbar |
| Evitar | `Avoid` | gris |

**Importante:** los contadores abarcan el universo de esa clase de activo, no solo las cards. Las cards son el top prioritario de la sección.

#### Loading / feed parcial

| Estado | UI |
| --- | --- |
| Sin filas aún | loading + `(loaded/total)` en el resumen |
| Con filas y `loaded < total` | counts visibles + hint “Feed parcial loaded/total…” |
| Feed completo | counts sin hint de parcial |

### 4.6 Sectores: Planes de mercado y Planes cripto

| Sección | Filtro | Tope cards |
| --- | --- | --- |
| **Planes de mercado** | stock + etf | 8 |
| **Planes cripto** | crypto | 4 |

| Regla | Detalle |
| --- | --- |
| Construcción | `rankDashboardV2Sections(rows, model, 8, 4)` — ranking **independiente** por sección |
| Filtro de lista de cards | Excluye `Avoid` del ranking de cards; los Avoid solo entran en el contador de su clase |
| Orden interno | Urgencia desc, luego `compositeScore` |
| Mezcla | Prioriza ActNow, luego ScaleIn, luego WaitZone, relleno por urgencia |
| Empty mercado | “Sin planes prioritarios en acciones/ETFs ahora.” |
| Empty cripto | “Sin planes prioritarios en cripto ahora.” |
| Orden en pantalla | Mercado **arriba**, cripto **abajo** |

### 4.7 Anatomía de una Plan Card

```text
┌──────────────────────────────────────────┐
│ SYM   [STANCE]   p20 NN%   $A–$B · conf  │  ← top
│ Headline (1–2 líneas, densidad media)    │
│ [sparkline]                              │
│ • evidencia 1                            │
│ • evidencia 2                            │
│ • evidencia 3                            │
│ $precio   Inv. $X   Σ score              │  ← foot
└──────────────────────────────────────────┘
```

| Elemento | Origen | Notas de regresión |
| --- | --- | --- |
| **Símbolo** | `row.symbol` | Siempre visible |
| **Badge stance** | `ActNow` / `ScaleIn` / `WaitZone` / `Avoid` | Color de borde izquierdo de card + badge |
| **p20** | `price_path.p_touch_20d` | Solo si no null; tooltip explica path hist./vol |
| **Zona** | `zone_low`–`zone_high` | Formato `$X.XX–$Y.YY`; + conf baja/media/alta |
| **Headline** | Template i18n + vars | Debe incluir zona/p20/inv/sesiones cuando el template lo pide |
| **Sparkline** | `row.spark` | Solo si `length > 1` |
| **Evidencias** | caution + support, máx 3 total en UI | Primero riesgos de path, luego soportes |
| **Precio** | `market_price_cents` | |
| **Inv.** | `invalidation_cents` | Solo si presente |
| **Σ** | `composite_score` | Score del modelo activo |

#### Stances y copy de badge (ES)

| Stance | Badge ES | Significado operativo |
| --- | --- | --- |
| `ActNow` | Actuar | Entrada / short viable ahora según plan |
| `ScaleIn` | Escalar | Ir de a poco / timing parcial |
| `WaitZone` | Esperar | Setup útil pero path pide zona mejor |
| `Avoid` | Evitar | No priorizar (casi no aparece en cards) |

#### Borde de color

| Stance | Semántica visual |
| --- | --- |
| ActNow | Verde |
| ScaleIn | Cyan |
| WaitZone | Ámbar (wait de primera clase, no “error”) |
| Avoid | Gris |

### 4.8 Headlines (contratos de copy)

Variables posibles: `{zone}`, `{p20}`, `{inv}`, `{sessions}`, `{symbol}`.

#### Long

| Stance | Con zona | Sin zona |
| --- | --- | --- |
| ActNow | Entrada viable cerca de {zone}. p20≈{p20}%. Inv. {inv}. | Plan de entrada ahora: el path no exige esperar un pullback material. |
| ScaleIn | Escalar hacia {zone} (p20≈{p20}%). No forzar fuera de banda. | Escalar con paciencia: setup útil, timing solo parcial. |
| WaitZone | Buen setup, pero el path favorece esperar {zone} (p20≈{p20}%). Si rompe {inv} o no aparece en ~{sessions} sesiones, el plan se cae. | Buen setup, pero el path sugiere no perseguir el precio todavía. |
| Avoid | — | No actuar: el score y el path no respaldan una entrada. |

#### Short (simetría)

| Stance | Idea del copy |
| --- | --- |
| ActNow | Short viable ahora / cerca de zona de rebote para entrar short |
| ScaleIn | Escalar el short |
| WaitZone | Esperar rebote a zona mejor para short |
| Avoid | No short |

**Regla de densidad:** headline de 1–2 líneas; no muro de texto; no badge de 3 palabras sin headline.

### 4.9 Evidencias (motivos)

Códigos de path → i18n `dash.v2.motive.*`:

| Código | ES (resumen) |
| --- | --- |
| `extension` | Precio extendido vs media |
| `far_from_support` | Lejos del soporte / zona |
| `far_from_resistance` | Lejos de la resistencia / zona |
| `rsi_rich` | RSI elevado |
| `rsi_washed` | RSI deprimido |
| `above_value` | Por encima del valor de referencia |
| `below_value` | Descuento vs valor de referencia |
| `regime_risk` | Contexto de mercado adverso |
| `earnings_soon` | Earnings cercanos |
| `trend_against` | Tendencia en contra del setup |
| `weak_forecast` | Forecast débil para el lado |
| `near_zone` | Cerca de la zona preferida |
| `in_zone` | Dentro de la zona preferida |

Fallbacks de presentación:

- Si no hay supports y stance ≠ Avoid → “Score compuesto {score}”.  
- Si WaitZone sin cautions → “Timing aún no alinea con el valor”.

**Tope visual:** 3 bullets en la card.

---

## 5. Motor de decisión (lógica a verificar conceptualmente)

### 5.1 Pipeline

```text
Feed / screener state
  → score V3 (long o short) ± régimen
  → price_path::estimate_price_path (Rust)
  → CompactPricePath en OpportunityRow
  → buildConditionalPlan (TS)
  → rankDashboardV2
  → UI cards + counts
```

### 5.2 Reglas de stance (`deriveStance`) — long y short comparten reglas; el path ya viene side-aware

| Condición | Stance |
| --- | --- |
| `decision === Avoid` o `setup_label === StrongAvoid` | **Avoid** |
| `decision === Act` y (in_zone o near_zone) y riesgos ≤ 1 | **ActNow** |
| `decision === Act` y (far / extension / rsi_rich o riesgos ≥ 2) | **WaitZone** |
| `decision === Act` y p20 ≥ 60 y riesgos ≤ 1 | **ScaleIn** |
| `decision === Act` y setup positivo | **ActNow** (fallback) |
| `decision === Act` otros | **ScaleIn** |
| `decision === Watch` y (setup positivo o composite ≥ 15) y in/near zone | **ScaleIn** |
| `decision === Watch` y setup/composite atractivo | **WaitZone** |
| `composite < 0` | **Avoid** |
| Resto | **WaitZone** |

**Far** = códigos `far_from_support` | `far_from_resistance` | `extension` | `rsi_rich` en `risk_codes`.

### 5.3 Urgencia (orden de cards)

Aprox.:

- Base = `composite_score`  
- +80 ActNow, +40 ScaleIn, +10 WaitZone, −50 Avoid  
- +50 in_zone, +25 near_zone  
- + p20/4 en Wait/Scale  
- +8 zona high conf, −5 low conf  

Luego ranking prioriza ActNow → ScaleIn → WaitZone.

### 5.4 Price Path Estimator (backend)

**Archivo:** `apps/windows/src-tauri/src/price_path.rs`.

#### Zona

1. Anclas según lado:  
   - **Long (bajo precio):** soportes, BB lower, EMA50/200 bajo precio, banda ATR, intrinsic/DCF/analyst low si están por debajo, fib-like 52w.  
   - **Short (sobre precio):** resistencias, BB upper, EMAs arriba, ATR arriba, valor/analyst high arriba, fib-like.  
2. Clustering por radio ~0.5·ATR; se elige cluster de mayor peso.  
3. Ancho de zona clamp ~0.3–1.5·ATR.  
4. Confianza: High / Med / Low según cantidad de anclas estructurales vs solo ATR.

#### Timing (no “esperá 3 semanas” suelto)

| Campo | Significado |
| --- | --- |
| `p_touch_5d` / `20d` / `60d` | % estimado de tocar la zona en N sesiones |
| `expected_sessions` | Sesiones esperadas (mediana/híbrido) |
| `method` | `hybrid` \| `atr_distance` \| `empirical_touches` \| `unavailable` |

UI v1 muestra especialmente **p20** y usa `sessions` en headline Wait con zona.

#### Invalidación

- Long: cerca de resistencia o +ATR stretch.  
- Short: cerca de soporte o −ATR.  
- `session_budget` derivado (no siempre visible en UI v1; sí `invalidation` precio).

#### Compact payload en fila

```text
zone_low_cents, zone_high_cents, zone_confidence,
p_touch_20d, expected_sessions, invalidation_cents,
risk_codes[], support_codes[], timing_method, side
```

---

## 6. Matriz de modelos en Dashboard 2.0

| Combo | Modelo | Régimen scoring | Path side | Uso esperado en regresión |
| --- | --- | --- | --- | --- |
| A | `aggressive_v3` | OFF | long | Baseline long sin 4º bucket |
| B | `aggressive_v3` | ON | long | Long + contexto; posibles `regime_risk` / cambios de composite |
| C | `short_v3` | OFF | short | Zonas arriba del precio; copy short |
| D | `short_v3` | ON | short | Short + contexto |

**Al cambiar de combo:** esperar re-fetch de filas; headlines y stances pueden reordenarse.

**Efecto en Screener:** el mismo modelo queda activo al navegar.

---

## 7. Casos de prueba de regresión manual

Convención de IDs: `R-XXX`.

### 7.1 Navegación y persistencia

#### R-001 — Entrar al Dashboard

| | |
| --- | --- |
| **Pre** | App arrancada, feed puede estar cargando |
| **Pasos** | Sidebar → Dashboard |
| **Esperado** | Se muestra Clásico o 2.0 según `ds_dashboard_edition`. Sin crash. |
| **Fallo si** | Vista en blanco permanente sin loading; error no recuperable |

#### R-002 — Toggle Clásico → 2.0

| | |
| --- | --- |
| **Pre** | Dashboard en Clásico |
| **Pasos** | Click **2.0** |
| **Esperado** | UI 2.0 (título Decisiones, controles modelo, planes). `localStorage.ds_dashboard_edition === "v2"`. |
| **Fallo si** | Sigue el grid de gainers/losers como hero; no hay planes/resumen |

#### R-003 — Toggle 2.0 → Clásico

| | |
| --- | --- |
| **Pre** | Dashboard 2.0 |
| **Pasos** | Click **Clásico** |
| **Esperado** | Header “Buen día”, oportunidades, gainers/losers/alerts. Edición = legacy persistida. |
| **Fallo si** | Se pierden secciones clásicas o no vuelve el toggle |

#### R-004 — Persistencia de edición tras reload

| | |
| --- | --- |
| **Pre** | Poner 2.0 |
| **Pasos** | Cerrar y reabrir app (o reload webview) → Dashboard |
| **Esperado** | Sigue en 2.0 |
| **Fallo si** | Vuelve siempre a Clásico |

#### R-005 — Migración V2 → V3 al entrar 2.0

| | |
| --- | --- |
| **Pre** | En Screener, seleccionar **Long V2**; volver a Dashboard Clásico |
| **Pasos** | Click **2.0** |
| **Esperado** | Modelo activo pasa a Long V3 (botón Long V3 activo). No hay control V2 en 2.0. |
| **Fallo si** | Sigue en V2; o 2.0 se rompe |

#### R-006 — Modelo global compartido con Screener

| | |
| --- | --- |
| **Pre** | Dashboard 2.0 |
| **Pasos** | Elegir **Short** → ir a Screener |
| **Esperado** | Screener en modo Short (banner short / labels short). |
| **Fallo si** | Screener quedó en Long sin el cambio |

### 7.2 Dashboard Clásico

#### R-010 — Oportunidades top 6

| | |
| --- | --- |
| **Pre** | Feed cargado, hay Act/Buy en universo |
| **Pasos** | Observar sección oportunidades |
| **Esperado** | ≤6 cards; ordenadas por score; spark/precio/% si hay datos |
| **Fallo si** | Más de 6; cards sin símbolo; crash |

#### R-011 — Ver todas

| | |
| --- | --- |
| **Pasos** | Click “Ver todas →” |
| **Esperado** | Navega a Screener |
| **Fallo si** | No cambia de vista |

#### R-012 — Gainers / Losers

| | |
| --- | --- |
| **Pre** | Varios `daily_change_bps` |
| **Esperado** | ≤5 por lista; % coherente en signo/color; click abre símbolo |
| **Fallo si** | Listas invertidas; NaN% |

#### R-013 — Alertas

| | |
| --- | --- |
| **Esperado** | Lista o empty; click abre símbolo; link Advisor funciona |
| **Fallo si** | Error al montar panel |

### 7.3 Dashboard 2.0 — controles y resumen

#### R-020 — Cuatro combos de modelo×contexto

| | |
| --- | --- |
| **Pasos** | Probar A,B,C,D de la matriz §6; esperar refresh entre cada uno |
| **Esperado** | Botones reflejan estado; counts y cards se actualizan; sin error de consola bloqueante |
| **Fallo si** | Toggle no hace nada; filas no cambian nunca entre long/short |

#### R-021 — Counts con feed parcial (no bloquear)

| | |
| --- | --- |
| **Pre** | Feed con filas visibles pero `loaded < total` (p.ej. un símbolo stuck) |
| **Esperado** | Dos líneas de counts (Mercado y Cripto) visibles; hint de feed parcial; **no** loading eterno |
| **Fallo si** | Resumen queda en “Cargando… (571/572)” sin counts mientras ya hay plan cards |

#### R-021b — Counts por clase de activo

| | |
| --- | --- |
| **Pre** | Universo con stocks y cryptos |
| **Esperado** | Counts de mercado no incluyen cripto y viceversa |
| **Fallo si** | Una sola línea mezclada; o counts de mercado inflados por BTC/SOL |

#### R-022 — Loading state

| | |
| --- | --- |
| **Pre** | Arranque sin filas aún |
| **Esperado** | Resumen muestra loading solo hasta la primera tanda de rows |
| **Fallo si** | Empty calm prematuro con 0 rows fingiendo “nada forzado” |

#### R-023 — Empty calm

| | |
| --- | --- |
| **Pre** | Universo sin stances accionables (raro en vivo) o mock |
| **Esperado** | Mensaje: no hay acción forzada |
| **Fallo si** | Cards basura / placeholders rotos |

### 7.4 Plan cards y densidad

#### R-030 — Estructura mínima de card

| | |
| --- | --- |
| **Pre** | Al menos 1 plan en lista |
| **Esperado** | Símbolo + badge stance + headline no vacío + precio + Σ |
| **Fallo si** | Headline vacío; solo 3 palabras sin frase; muro > ~4 líneas de prose |

#### R-031 — Wait con zona

| | |
| --- | --- |
| **Pre** | Buscar card **Esperar** con banda de precio visible |
| **Esperado** | Headline menciona zona y p20 y/o invalidación/sesiones; borde ámbar; ≤3 bullets |
| **Fallo si** | Dice “esperá 3 semanas” sin zona ni p20; o `$` vacío tipo `$—` |

#### R-032 — ActNow en/near zona

| | |
| --- | --- |
| **Pre** | Card **Actuar** si existe |
| **Esperado** | Badge Actuar; copy de entrada viable; no pide esperar pullback material sin motivo |
| **Fallo si** | Actuar con copy de Avoid |

#### R-033 — Short copy

| | |
| --- | --- |
| **Pre** | Modelo Short |
| **Esperado** | Headlines/badges no invitan a “comprar”; hablan de short / tesis bajista |
| **Fallo si** | Aparece “buen momento de compra” long en modo short |

#### R-034 — Topes por sección

| | |
| --- | --- |
| **Esperado** | ≤8 cards en Planes de mercado; ≤4 en Planes cripto; cripto no aparece en la sección de mercado |
| **Fallo si** | Cripto en “Planes de mercado”; o decenas de cards mezcladas |

#### R-034b — Mercado primero

| | |
| --- | --- |
| **Esperado** | Sección mercado está **arriba** de cripto |
| **Fallo si** | Cripto es el hero y acciones quedan abajo o mezcladas |

#### R-035 — Click / teclado a Screener

| | |
| --- | --- |
| **Pasos** | Click card; volver; focus card + Enter |
| **Esperado** | Screener con ese símbolo seleccionado |
| **Fallo si** | No navega; símbolo incorrecto |

#### R-036 — Evidencias ≤ 3

| | |
| --- | --- |
| **Esperado** | Como máximo 3 ítems en la lista de la card |
| **Fallo si** | Lista larga de factores internos (`Quality`, IDs de motor) |

#### R-037 — p20 rango

| | |
| --- | --- |
| **Esperado** | Si se muestra p20, es entero 0–100 con sufijo `%` |
| **Fallo si** | p20 > 100, negativo, o `NaN` |

#### R-038 — Zona long vs short

| | |
| --- | --- |
| **Pre** | Mismo universo, Long V3 luego Short |
| **Esperado** | En long, zonas de wait suelen estar **por debajo** del precio actual cuando hay extensión; en short, **por encima** (rebote). No siempre en todos los símbolos, pero el patrón debe aparecer en varios Wait. |
| **Fallo si** | Todas las zonas short idénticas a long sin rotar lado |

### 7.5 Price path y datos

#### R-040 — Presencia de path en filas

| | |
| --- | --- |
| **Pre** | Devtools / log opcional, o inferencia por UI (zona/p20 en muchas cards) |
| **Esperado** | Mayoria de equities con precio > 0 muestran path usable (zona o al menos timing) |
| **Fallo si** | Ninguna card tiene zona ni p20 con mercado cargado |

#### R-041 — Confianza de zona

| | |
| --- | --- |
| **Esperado** | Si hay zona, conf. es baja/media/alta (localizado) |
| **Fallo si** | String crudo `low` sin traducir (aceptable solo si i18n key rota → FAIL) |

#### R-042 — Contexto ON y regime_risk

| | |
| --- | --- |
| **Pre** | Contexto ON; régimen disponible |
| **Esperado** | Algunos símbolos pueden mostrar evidencia “Contexto de mercado adverso”; composites pueden diferir vs OFF |
| **Fallo si** | ON/OFF no cambian scores ni textos en ningún caso con régimen sano |

#### R-043 — Sin inventar precios

| | |
| --- | --- |
| **Esperado** | Si no hay zona, headline sin zona no inventa `$0.00–$0.00` |
| **Fallo si** | Zonas a cero o absurdas (p.ej. high < low) |

### 7.6 Clásico vs 2.0 (no regresión cruzada)

#### R-050 — Clásico intacto

| | |
| --- | --- |
| **Pasos** | Tras usar 2.0, volver a Clásico |
| **Esperado** | Gainers, losers, alerts y top ops siguen funcionando |
| **Fallo si** | Secciones clásicas rotas por el feature 2.0 |

#### R-051 — RegimeBanner en ambas ediciones

| | |
| --- | --- |
| **Esperado** | Banner presente en Clásico y 2.0 cuando hay datos |
| **Fallo si** | Solo en una y crashea en la otra |

### 7.7 i18n

#### R-060 — ES / EN

| | |
| --- | --- |
| **Pre** | Cambiar idioma de la app (settings / control i18n existente) |
| **Esperado** | Títulos, badges, motives y headlines cambian de idioma; no quedan keys `dash.v2.*` crudas |
| **Fallo si** | Keys sin traducir visibles |

### 7.8 Performance / estabilidad

#### R-070 — Refresh periódico

| | |
| --- | --- |
| **Pre** | Dashboard 2.0 abierto 2–3 minutos con poll de oportunidades |
| **Esperado** | UI se actualiza sin memory leak obvio; sin freeze al re-rankear |
| **Fallo si** | UI se cuelga al refrescar |

#### R-071 — Universo grande

| | |
| --- | --- |
| **Pre** | Perfil tipo S&P 500 cargado |
| **Esperado** | get_opportunities con path no vuelve la app inutilizable (> segundos por poll de forma sostenida) |
| **Fallo si** | Cada refresh tarda de forma aguda e inusable |

---

## 8. Checklist rápido (smoke 10 minutos)

Usar cuando no hay tiempo para la batería completa.

| # | Check | PASS? |
| --- | --- | --- |
| 1 | Dashboard abre | |
| 2 | Toggle Clásico ↔ 2.0 y persiste | |
| 3 | 2.0: Long V3 / Short / Contexto conmutan | |
| 4 | Counts Mercado+Cripto visibles aunque feed parcial | |
| 4b | Planes de mercado arriba, cripto abajo (sin mezclar) | |
| 5 | ≥1 card con headline + precio + Σ | |
| 6 | Alguna card Wait con zona y p20 (si datos lo permiten) | |
| 7 | Click card → Screener al símbolo | |
| 8 | Short no usa copy de compra long | |
| 9 | Entrar 2.0 desde V2 fuerza Long V3 | |
| 10 | Clásico sigue con ops + movers + alerts | |

---

## 9. Datos de referencia para el tester

### 9.1 Claves `localStorage`

| Key | Valores |
| --- | --- |
| `ds_view_mode` | `dashboard` \| `screener` \| … |
| `ds_dashboard_edition` | `legacy` \| `v2` |
| `ds_scoring_model` | `aggressive_v2` \| `aggressive_v3` \| `short_v3` |
| `ds_regime_scoring` | `1` \| `0` |
| `ds_display_name` | string opcional (saludo) |

### 9.2 Archivos de implementación (trazabilidad)

| Área | Path |
| --- | --- |
| Shell app / edición | `apps/windows/src/App.tsx` |
| Dashboard Clásico | `apps/windows/src/components/DashboardPanel.tsx` |
| Dashboard 2.0 | `apps/windows/src/components/DashboardV2Panel.tsx` |
| ConditionalPlan | `apps/windows/src/conditionalPlan.ts` |
| Ranking | `apps/windows/src/dashboardV2Ranking.ts` |
| Tipos path | `apps/windows/src/api.ts` (`CompactPricePath`) |
| i18n | `apps/windows/src/i18n.tsx` (`dash.v2.*`, `dash.edition.*`) |
| Estilos | `apps/windows/src/App.css` (`.dash-v2-*`) |
| Estimador | `apps/windows/src-tauri/src/price_path.rs` |
| Wire filas | `apps/windows/src-tauri/src/commands.rs` (`OpportunityRow.price_path`) |
| Tests auto | `apps/windows/tests/conditionalPlan.test.ts`; `cargo test price_path` |

### 9.3 Automatizado ya existente (no reemplaza regresión visual)

```text
cd apps/windows && npm test
cd apps/windows/src-tauri && cargo test price_path
```

---

## 10. Plantilla de reporte de regresión

```text
Fecha:
Build / commit:
Tester:
Idioma UI: ES | EN
Universo / perfil:
Feed: loaded X / total Y

Smoke §8: PASS / FAIL
Casos ejecutados: R-…
Fallos:
  - ID:
    Síntoma:
    Modelo/contexto:
    Símbolo ejemplo:
    Esperado vs actual:
    Screenshot:

Notas / BLOCKED:
```

---

## 11. Glosario

| Término | Definición operativa |
| --- | --- |
| **Stance** | ActNow / ScaleIn / WaitZone / Avoid — verbo del plan |
| **Zona** | Banda de precio preferida por confluencia de anclas |
| **p20** | Prob. estimada de tocar la zona en ~20 sesiones de path |
| **Invalidación** | Nivel de precio que rompe el plan de espera/entrada |
| **Path** | Estimación multi-ancla + timing; no es el score V3 |
| **Composite Σ** | Score del modelo V3 (long o short) |
| **Contexto de mercado** | Régimen global; toggle incluye fit en score cuando ON |
| **Densidad media** | Headline + ≤3 evidencias; ni muro ni badge vacío |

---

## 12. Criterios de salida de regresión (release checklist)

Marcar listo para merge/release de esta feature cuando:

- [ ] Smoke §8 en PASS  
- [ ] R-002, R-003, R-004, R-005, R-006 PASS  
- [ ] R-020 (4 combos) PASS  
- [ ] R-030, R-031, R-033, R-034, R-035 PASS  
- [ ] R-050 Clásico intacto PASS  
- [ ] R-060 i18n sin keys crudas PASS  
- [ ] Sin crash al poll con universo completo  
- [ ] Tests automatizados verdes (`npm test`, `cargo test price_path`)

---

*Fin del documento. Mantener alineado con el código si cambian stances, templates o el contrato `CompactPricePath`.*

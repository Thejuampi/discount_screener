## Correcciones editoriales centrales

| Texto actual | Texto revisado | Cambio |
|---|---|---|
| “Mide si el perfil del activo…” | Se mueve a una ayuda contextual | La tarjeta debe interpretar este activo, no explicar internamente el modelo. |
| “calidad financiera favorece la tesis long en el entorno actual” | “Calidad financiera sólida: FCF positivo de $8.7B y ROE de 25.9%.” | Sustituye una conclusión abstracta por evidencia concreta. |
| “valoración relativa favorece…” | “Valuación atractiva: Forward P/E de 8.8x.” | El usuario entiende inmediatamente qué se evaluó. |
| “extensión del precio favorece…” | “El precio no luce extendido: RSI 42 y recorrido moderado frente al máximo anual.” | Traduce la variable técnica a una lectura natural. |
| “En contra” | “Desfavorable” | Mantiene la simetría Favorable / Neutral / Desfavorable. |

# Plan completo de implementación

## 1. Contrato comunicacional de la tarjeta

La tarjeta responderá, siempre en este orden, cuatro preguntas:

1. ¿Cuál es el resultado de esta dimensión?
2. ¿Qué significa para la posición evaluada?
3. ¿Qué evidencias explican el resultado?
4. ¿Qué factores participaron?

Estructura final:

```text
CONTEXTO DE MERCADO  ⓘ          +26  [Favorable]

El entorno actual favorece activos con este perfil.

• Calidad financiera sólida: FCF positivo de $8.7B y ROE de 25.9%.
• Valuación atractiva: Forward P/E de 8.8x.
• Sector de crecimiento: Tecnología acompaña el entorno actual.

[Calidad +] [Valor +] [Sector +]
```

Reglas:

- Una sola oración de interpretación general.
- Máximo de tres evidencias.
- Las evidencias contienen hechos, no repiten la conclusión general.
- Los chips resumen; no sustituyen la explicación.
- Nunca mostrar identificadores internos.
- Nunca presentar el contexto como predicción del mercado.
- Nunca confundir el score de contexto `+26` con su impacto sobre el score final.

## 2. Resumen general según posición y clasificación

### Long V3

| Clasificación | Español | English |
|---|---|---|
| Favorable | El entorno actual favorece activos con este perfil. | The current environment favors assets with this profile. |
| Neutral | El entorno actual no aporta una ventaja clara a este activo. | The current environment does not give this asset a clear advantage. |
| Desfavorable | Las condiciones actuales juegan en contra de este perfil. | Current conditions work against this asset profile. |

### Short V3

| Clasificación | Español | English |
|---|---|---|
| A favor del short | El entorno actual respalda la tesis bajista para este activo. | The current environment supports the bearish thesis for this asset. |
| Neutral para el short | El entorno actual no aporta una ventaja clara a la tesis bajista. | The current environment gives the bearish thesis no clear advantage. |
| En contra del short | Las condiciones actuales debilitan la tesis bajista. | Current conditions weaken the bearish thesis. |

La clasificación será explícitamente dependiente del modelo. Un `+26` en Short nunca aparecerá simplemente como “Favorable”, porque podría interpretarse como favorable para comprar.

## 3. Evidencias específicas para cada factor

Cada causa se convertirá en una evidencia concreta usando los datos disponibles en `SymbolDetail`.

| Factor interno | Chip visible | Evidencia preferida |
|---|---|---|
| `Quality` | Calidad | FCF, ROE y D/E; máximo dos métricas |
| `LowBeta` | Beta | Beta numérica y sensibilidad resultante |
| `Value` | Valor | Forward P/E |
| `OversoldQual` | Calidad + sobreventa | RSI, posición anual y una métrica de calidad |
| `Extension` | Extensión | RSI, distancia a EMA50 y posición en rango anual |
| `Trend` | Tendencia | Relación del precio con EMA20, EMA50 y EMA200 |
| `Defensive` | Sector defensivo | Nombre real del sector |
| `Growth` | Sector de crecimiento | Nombre real del sector |
| `Liquidity` | Liquidez | Capitalización y volumen relativo |
| `RegimeFit` | Encaje general | Fallback cuando no existe una causa individual dominante |
| `RegimeNeutral` | Sin ventaja | Ausencia de un factor contextual dominante |

### Redacción positiva y negativa

**Calidad**

- Positiva: “Calidad financiera sólida: FCF positivo de {fcf} y ROE de {roe}.”
- Negativa: “Calidad financiera débil: FCF de {fcf} y D/E de {de}.”

**Beta**

- Positiva long: “Beta de {beta}: menor sensibilidad a cambios del mercado.”
- Negativa long: “Beta de {beta}: mayor sensibilidad a cambios del mercado.”
- En Short, la evidencia será factual y el chip indicará si apoya o contradice el short.

**Valoración**

- Positiva: “Valuación atractiva: Forward P/E de {pe}x.”
- Negativa: “Valuación exigente: Forward P/E de {pe}x.”

**Calidad con sobreventa**

- Positiva: “Precio castigado con fundamentos sólidos: RSI {rsi} y FCF positivo de {fcf}.”
- Negativa: “La caída del precio no está acompañada por suficiente calidad financiera.”

**Extensión**

- A favor long: “El precio no luce extendido: RSI {rsi} y recorrido moderado frente al máximo anual.”
- En contra long: “El precio luce extendido: RSI {rsi} y cerca del máximo anual.”
- A favor short: “La extensión del precio aumenta su vulnerabilidad: RSI {rsi}.”
- En contra short: “El precio no muestra suficiente extensión para fortalecer el short.”

**Tendencia**

- Alcista: “Tendencia alcista: precio por encima de EMA50 y EMA200.”
- Bajista: “Tendencia débil: precio por debajo de EMA50 y EMA200.”
- La orientación long/short determinará si se presenta como apoyo o riesgo.

**Sector**

- Defensivo: “Sector defensivo: {sector} aporta estabilidad en este entorno.”
- Crecimiento long: “Sector de crecimiento: {sector} acompaña el entorno actual.”
- Crecimiento short: “Sector de crecimiento: {sector} aporta sensibilidad a la tesis bajista.”

**Liquidez**

- Positiva: “Buena liquidez: capitalización de {marketCap} y volumen relativo de {volume}x.”
- Negativa: “Liquidez limitada: capitalización de {marketCap} y volumen relativo de {volume}x.”

### Datos incompletos

- Mostrar un máximo de dos métricas por evidencia.
- Omitir métricas ausentes sin dejar puntuación defectuosa.
- Si ninguna métrica está disponible, usar una frase cualitativa específica del factor.
- Nunca mostrar `Quality`, `LowBeta`, `OversoldQual` ni otro token interno como fallback.
- Nunca inventar un valor ausente.

## 4. Selección real de las tres causas principales

Actualmente se conservan las primeras tres señales generadas, no necesariamente las más importantes.

Se modificará la generación para:

- calcular la contribución ponderada de cada factor;
- ordenar por magnitud absoluta;
- conservar las tres causas con mayor contribución;
- mantener su efecto orientado a long o short;
- usar `RegimeFit` solamente cuando ninguna causa individual supere el umbral;
- usar `RegimeNeutral` solamente cuando el resultado sea realmente neutral.

La magnitud interna servirá para ordenar, pero no se mostrará al usuario para evitar confundirla con puntos del score.

## 5. Modelo de datos tipado

Se reemplazará la dependencia interna de cadenas como `"+Quality"` por causas tipadas:

```text
RegimeCause
- factor: Quality | LowBeta | Value | OversoldQual | Extension |
          Trend | Defensive | Growth | Liquidity | GeneralFit
- effect: Support | Risk | Neutral
- contribution_bps: entero
```

Decisiones:

- El backend emitirá `regime_causes`.
- `regime_signals` seguirá aceptándose como payload heredado.
- El frontend preferirá `regime_causes`.
- Si sólo recibe `regime_signals`, los normalizará en la frontera de presentación.
- Una señal desconocida no se imprimirá literalmente; se convertirá en “Otro factor contextual” y se registrará para diagnóstico.
- No cambia el cálculo del score, sus pesos ni sus umbrales.

## 6. Estados sin score

Se agregará una razón tipada para `Unavailable`:

```text
MarketReadingUnavailable
InsufficientAssetData
Unknown
```

Copy definitivo:

| Estado | Español | English |
|---|---|---|
| Desactivado | Contexto desactivado. El score final usa sólo fundamentales, técnico y pronóstico. | Market context is off. The final score uses fundamentals, technicals, and forecast only. |
| Sin lectura de mercado | Todavía no hay una lectura confiable del entorno de mercado. | A reliable reading of the market environment is not available yet. |
| Datos insuficientes | No hay suficientes datos del activo para evaluar su encaje con el mercado. | There is not enough asset data to evaluate its market fit. |
| Razón desconocida | El contexto de mercado no está disponible en este momento. | Market context is currently unavailable. |
| No aplica | La tarjeta no se renderiza. | The card is not rendered. |

Los estados desactivado y no disponible:

- no mostrarán bullets ni chips;
- no usarán colores favorables o adversos;
- conservarán la tarjeta visible para acciones V3;
- explicarán claramente que el score final sigue usando tres dimensiones.

## 7. Encabezado y ayuda contextual

El encabezado tendrá tres elementos:

- título;
- botón de información;
- bloque indivisible con score y clasificación.

Copy de la ayuda:

**ES**

> Evalúa cuánto encajan la calidad, valuación, beta, sector y comportamiento del precio del activo con el entorno actual. El valor va de −100 a +100 y no predice la dirección del mercado. Su efecto sobre el score final se muestra en el resumen superior.

**EN**

> Evaluates how well the asset’s quality, valuation, beta, sector, and price behavior fit the current environment. The value ranges from −100 to +100 and does not predict market direction. Its effect on the final score appears in the summary above.

El tooltip:

- aparecerá con hover y foco de teclado;
- tendrá `aria-describedby`;
- se cerrará con Escape;
- no dependerá del atributo nativo `title`;
- será legible en la vista angosta.

## 8. Score, clasificación y chips

El resultado se renderizará como:

```text
+26 [Favorable]
```

No como una única cadena `" +26 · Favorable "`.

CSS:

- score y píldora dentro de un contenedor `white-space: nowrap`;
- el título podrá ocupar dos líneas;
- la clasificación no se separará del score;
- la píldora usará color, borde y texto;
- no se dependerá exclusivamente del color.

Chips:

```text
[Calidad +] [Valor +] [Extensión −]
```

Accesibilidad:

- `aria-label="Calidad, a favor"`
- `aria-label="Extensión, en contra"`
- Short: `aria-label="Extensión, a favor del short"`

## 9. Separación de responsabilidades

### Rust

`apps/windows/src-tauri/src/regime/regime_fit.rs`

- Crear causas tipadas.
- Conservar contribución ponderada.
- Ordenar las causas.
- Generar fallback general o neutral.
- No generar copy.

`apps/windows/src-tauri/src/commands.rs`

- Exponer `regime_causes`.
- Exponer `regime_unavailable_reason`.
- Mantener compatibilidad con los campos anteriores.

### TypeScript

`apps/windows/src/api.ts`

- Declarar `RegimeCauseFactor`, `RegimeCauseEffect`, `RegimeCause` y `RegimeUnavailableReason`.

`apps/windows/src/regimePresentation.ts`

- Normalizar payload nuevo y heredado.
- Resolver side, clasificación, estado e impacto.
- Producir un view model sin strings internas.

Nuevo `apps/windows/src/marketContextNarrative.ts`

- Seleccionar métricas.
- Construir evidencias.
- Resolver summary, chips y textos accesibles.
- No contener JSX.

`apps/windows/src/scoringPresentationMessages.ts`

- Contener mensajes dependientes de long/short.

`apps/windows/src/i18n.tsx`

- Contener etiquetas, estados, tooltip y plantillas ES/EN.

`apps/windows/src/components/DetailPanel.tsx`

- Render pasivo del view model.
- Sin reglas financieras ni selección de copy dentro del JSX.

`apps/windows/src/App.css`

- Resultado indivisible.
- Píldora de clasificación.
- Tooltip accesible.
- Chips de contexto.
- Mantener 2×2 y una columna bajo 720 px.

## 10. Pruebas obligatorias

### Rust

- Ordena causas por contribución absoluta.
- Limita la salida visible a tres.
- Conserva correctamente Support/Risk en long.
- Conserva correctamente Support/Risk en short.
- Genera GeneralFit cuando no hay causas dominantes.
- Genera Neutral cuando corresponde.
- Diferencia falta de política y falta de datos del activo.
- No altera el score existente.

### TypeScript

- Normaliza todos los identificadores heredados.
- Nunca deja escapar tokens internos.
- Produce copy exacto ES/EN para los once factores.
- Cubre Support, Risk y Neutral.
- Cubre long y short.
- Selecciona como máximo dos métricas por bullet.
- Omite métricas nulas correctamente.
- Genera fallback específico cuando faltan métricas.
- Diferencia score de contexto e impacto final.
- Mantiene los límites `+15` y `−15`.
- Renderiza cero como score incluido.
- Cubre Disabled, las tres razones de Unavailable y NotApplicable.
- Verifica los textos y etiquetas accesibles.

### UI

Comprobar:

- `+26` y la píldora nunca se separan;
- la tarjeta mantiene altura y ritmo visual coherentes;
- no hay overflow en español ni inglés;
- funciona en 2×2 y una columna;
- el tooltip funciona con mouse y teclado;
- los estados degradados no parecen resultados neutrales;
- los chips envuelven correctamente.

### Mutaciones

Las pruebas deberán fallar al introducir estas mutaciones:

- invertir Support/Risk;
- tratar `0` como no disponible;
- cambiar los límites `±15`;
- seleccionar las primeras causas en lugar de las más importantes;
- permitir cuatro causas;
- mostrar un token interno;
- confundir el score de contexto con el impacto final;
- usar copy long en Short.

## 11. Documentación

Actualizar:

- documentación de scoring de Windows;
- explicación del cuarto componente;
- diferencia entre score de contexto e impacto final;
- comportamiento long/short;
- estados desactivado y no disponible;
- aclaración explícita de que no es una predicción direccional.

## Criterio de aceptación final

Para el caso de la captura, la tarjeta debe comunicar algo equivalente a:

> **+26 · Favorable**  
> El entorno actual favorece activos con este perfil.
>
> - Calidad financiera sólida: FCF positivo de $8.7B y ROE de 25.9%.
> - Valuación atractiva: Forward P/E de 8.8x.
> - Sector de crecimiento: Tecnología acompaña el entorno actual.
>
> `Calidad +` `Valor +` `Sector +`

El usuario debe poder comprender qué significa el resultado, por qué se obtuvo y cómo afecta la tesis sin conocer términos como `RegimeFit`, “policy”, “bucket” o “tesis long”.
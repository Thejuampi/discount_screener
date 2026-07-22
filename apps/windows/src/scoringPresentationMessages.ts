export type ScoringLang = "es" | "en";

export const SCORING_PRESENTATION_MESSAGES: Record<string, Record<ScoringLang, string>> = {
  "presentation.evidence.raw": { es: "{fact}", en: "{fact}" },
  "presentation.short.banner.tag": {
    es: "MESA SHORT",
    en: "SHORT DESK",
  },
  "presentation.short.banner.text": {
    es: "Ranking para apostar a la baja · Act = candidato short · Score alto = mayor evidencia bajista",
    en: "Ranking for bearish positions · Act = short candidate · High score = stronger bearish evidence",
  },
  "presentation.short.dashboard.opportunities": { es: "Principales candidatos short", en: "Top short candidates" },
  "presentation.short.dashboard.empty": { es: "Sin candidatos short calificados todavía", en: "No qualified short candidates yet" },
  "presentation.short.advisor.opportunities": { es: "Candidatos short que no tenés", en: "Short candidates you do not own" },
  "presentation.short.advisor.empty": { es: "No hay candidatos short calificados", en: "No qualified short candidates" },
  "presentation.short.advisor.noSizing": {
    es: "El sizing de compra está desactivado en Short; abrí el detalle para revisar la tesis bajista y sus riesgos.",
    en: "Buy sizing is disabled in Short mode; open the detail to review the bearish thesis and its risks.",
  },
  "presentation.short.advisor.action": { es: "Revisar riesgo short", en: "Review short risk" },
  "presentation.short.advisor.longActionsPaused": {
    es: "Las acciones de compra/agregado están pausadas mientras el modelo Short está activo.",
    en: "Buy/add portfolio actions are paused while the Short model is active.",
  },
  "presentation.short.analyst.note": {
    es: "Opinión externa: recomendaciones alcistas y targets con upside son evidencia contraria y riesgo para el short.",
    en: "External opinion: bullish recommendations and upside targets are contrary evidence and risks to the short.",
  },
  "presentation.short.analyst.coverage": {
    es: "Consenso externo de {n} analistas: “{rec}”. Una opinión alcista es riesgo contra el short; no es la decisión de Vantage.",
    en: "External consensus from {n} analysts: “{rec}”. A bullish opinion is a risk to the short; it is not Vantage's decision.",
  },
  "presentation.short.evidence.support": {
    es: "A favor del short: {fact}",
    en: "Supports the short: {fact}",
  },
  "presentation.short.evidence.risk": {
    es: "Riesgo contra el short: {fact}",
    en: "Risk to the short: {fact}",
  },
  "presentation.short.evidence.neutral": {
    es: "Evidencia mixta: {fact}",
    en: "Mixed evidence: {fact}",
  },
  "presentation.short.crypto.title": { es: "₿ Ciclo cripto — lente short", en: "₿ Crypto cycle — short lens" },
  "presentation.short.crypto.rawScore": { es: "Score de ciclo long", en: "Long-cycle score" },
  "presentation.short.crypto.risk": { es: "Riesgo contra el short", en: "Risk to the short" },
  "presentation.short.crypto.support": { es: "Apoya el short", en: "Supports the short" },
  "presentation.short.crypto.neutral": { es: "Sin ventaja de ciclo", en: "No cycle edge" },
  "presentation.short.crypto.risk.detail": { es: "El modelo de ciclo es alcista y advierte riesgo de rebote o squeeze contra la posición short.", en: "The cycle model is bullish and warns of rebound or squeeze risk against the short position." },
  "presentation.short.crypto.support.detail": { es: "El modelo de ciclo es débil y aporta evidencia bajista a favor del short; no garantiza una caída.", en: "The cycle model is weak and adds bearish evidence supporting the short; it does not guarantee downside." },
  "presentation.short.crypto.neutral.detail": { es: "El ciclo no ofrece evidencia direccional suficiente para la tesis short.", en: "The cycle does not provide enough directional evidence for the short thesis." },
  "presentation.short.crypto.technical": { es: "Técnico (invertido)", en: "Technical (inverted)" },
  "presentation.short.crypto.accumulation": { es: "Acumulación (invertida)", en: "Accumulation (inverted)" },
  "presentation.short.crypto.halving": { es: "Halving (invertido)", en: "Halving (inverted)" },
  "presentation.short.crypto.sentiment": { es: "Sentimiento (invertido)", en: "Sentiment (inverted)" },
  "presentation.short.crypto.componentHint": { es: "Valor positivo = riesgo alcista en el modelo long; la vista short invierte el color para mostrar su efecto sobre la tesis bajista.", en: "Positive raw value = bullish risk in the long model; the short view inverts color to show its effect on the bearish thesis." },
  "presentation.short.crypto.cycleDay": { es: "Días desde halving", en: "Days since halving" },
  "presentation.short.crypto.drawdown": { es: "Drawdown desde ATH", en: "Drawdown from ATH" },
  "presentation.short.crypto.fearGreed": { es: "Fear & Greed", en: "Fear & Greed" },
  "presentation.short.crypto.disclaimer": { es: "⚠ Evidencia histórica de ciclo, no target ni garantía. Un drawdown profundo o miedo extremo puede aumentar el riesgo de rebote contra el short.", en: "⚠ Historical cycle evidence, not a target or guarantee. A deep drawdown or extreme fear can increase rebound risk against the short." },
  "setup.short.StrongBuy": { es: "Short fuerte", en: "Strong Short" },
  "setup.short.Buy": { es: "Short", en: "Short" },
  "setup.short.Accumulate": { es: "Short tentativo", en: "Mild Short" },
  "setup.short.Watch": { es: "Observar", en: "Watch" },
  "setup.short.Hold": { es: "Sin señal", en: "No signal" },
  "setup.short.Avoid": { es: "Evitar short", en: "Avoid Short" },
  "setup.short.StrongAvoid": { es: "No abrir short", en: "Do Not Short" },
  "setup.short.StrongBuy.desc": {
    es: "Setup short fuerte: forecast débil, valuación cara y/o técnicos bajistas alineados",
    en: "Strong short setup: weak forecast, rich valuation and/or bearish technicals aligned",
  },
  "setup.short.Buy.desc": {
    es: "Candidato short: la mayoría de los factores favorecen una baja",
    en: "Short candidate: most factors favor downside",
  },
  "setup.short.Accumulate.desc": {
    es: "Short tentativo: todavía falta confirmación",
    en: "Tentative short: confirmation is still missing",
  },
  "setup.short.Watch.desc": {
    es: "En radar short: no actuar todavía",
    en: "On the short radar: do not act yet",
  },
  "setup.short.Hold.desc": {
    es: "Sin sesgo short claro",
    en: "No clear short bias",
  },
  "setup.short.Avoid.desc": {
    es: "Poca ventaja para un short: factores mixtos o alcistas",
    en: "Little edge for a short: mixed or bullish factors",
  },
  "setup.short.StrongAvoid.desc": {
    es: "No abrir short: estructura alcista fuerte o downside limitado",
    en: "Do not short: strong bullish structure or limited downside",
  },
  "presentation.long.summary.avoid": {
    es: "{name} no cumple los criterios para una posición long (score {cs}/110). Conviene esperar una mejor oportunidad de entrada.",
    en: "{name} does not meet the criteria for a long position (score {cs}/110). Wait for a better entry opportunity.",
  },
  "presentation.long.summary.act.technical": {
    es: "{name} muestra una configuración técnica alcista accionable (score {cs}/110). Este activo se evalúa sólo con señales de precio y volumen.",
    en: "{name} shows an actionable bullish technical setup (score {cs}/110). This asset is evaluated only through price and volume signals.",
  },
  "presentation.long.summary.watch.technical": {
    es: "{name} muestra señales técnicas mixtas (score {cs}/110). Esperar confirmación alcista.",
    en: "{name} shows mixed technical signals (score {cs}/110). Wait for bullish confirmation.",
  },
  "presentation.long.summary.avoid.technical": {
    es: "{name} no presenta una configuración técnica alcista (score {cs}/110).",
    en: "{name} does not show a bullish technical setup (score {cs}/110).",
  },
  "presentation.long.reason.technical.insufficient": { es: "Datos técnicos insuficientes para validar una posición long.", en: "Insufficient technical data to validate a long position." },
  "presentation.long.gap.upside": {
    es: "{gap}% de upside hasta el target de analistas",
    en: "{gap}% upside to the analyst target",
  },
  "presentation.long.gap.upside.compact": { es: "{gap}% upside", en: "{gap}% upside" },
  "presentation.long.gap.aboveTarget": {
    es: "{gap}% por encima del target de analistas",
    en: "{gap}% above the analyst target",
  },
  "presentation.long.gap.aboveTarget.compact": { es: "{gap}% sobre target", en: "{gap}% above target" },
  "presentation.long.gap.noTarget": {
    es: "Sin target de analistas usable",
    en: "No usable analyst target",
  },
  "presentation.long.gap.noTarget.compact": { es: "Sin target", en: "No target" },
  "presentation.long.gap.flat": { es: "Precio alineado con el target de analistas", en: "Price aligned with the analyst target" },
  "presentation.long.gap.flat.compact": { es: "En target", en: "At target" },
  "presentation.short.analysis.act": {
    es: "Candidato para posición short",
    en: "Short-position candidate",
  },
  "presentation.short.analysis.watch": {
    es: "Esperar confirmación bajista",
    en: "Wait for bearish confirmation",
  },
  "presentation.short.analysis.avoid": {
    es: "No abrir short",
    en: "Do not short",
  },
  "presentation.short.summary.act": {
    es: "{name} presenta una tesis short de alta convicción (score {cs}/110): las debilidades detectadas favorecen una apuesta en contra con sesgo bajista.",
    en: "{name} presents a high-conviction short thesis (score {cs}/110): the detected weaknesses favor a bearish position against the stock.",
  },
  "presentation.short.summary.watch": {
    es: "{name} muestra una tesis short mixta (score {cs}/110). Falta confirmación bajista suficiente para abrir la posición.",
    en: "{name} shows a mixed short thesis (score {cs}/110). There is not enough bearish confirmation to open the position.",
  },
  "presentation.short.summary.avoid": {
    es: "{name} no justifica apostar en contra (score {cs}/110). Sus fortalezas o el downside limitado vuelven riesgoso abrir un short.",
    en: "{name} does not justify betting against it (score {cs}/110). Its strengths or limited downside make opening a short too risky.",
  },
  "presentation.short.summary.targetSupport": {
    es: "El precio está {gap}% por encima del target externo: es una referencia de downside que apoya la tesis short.",
    en: "Price is {gap}% above the external target: this is a downside reference that supports the short thesis.",
  },
  "presentation.short.summary.targetRisk": {
    es: "El target externo implica {gap}% de upside: contradice la tesis short y debe tratarse como riesgo, no como beneficio esperado.",
    en: "The external target implies {gap}% upside: this contradicts the short thesis and is a risk, not expected profit.",
  },
  "presentation.short.summary.noTarget": {
    es: "No hay ancla de analistas para estimar downside; la tesis depende de las señales disponibles y exige controles de riesgo más estrictos.",
    en: "There is no analyst anchor for estimating downside; the thesis relies on available signals and requires tighter risk controls.",
  },
  "presentation.short.summary.lowConfidence": {
    es: "La confianza de valuación es baja; aumenta el riesgo de que la aparente sobrevaluación no sea confiable.",
    en: "Valuation confidence is low; this increases the risk that the apparent overvaluation is unreliable.",
  },
  "presentation.short.summary.act.technical": {
    es: "{name} muestra una configuración técnica bajista accionable (score {cs}/110). Este activo se evalúa sólo con señales de precio y volumen.",
    en: "{name} shows an actionable bearish technical setup (score {cs}/110). This asset is evaluated only through price and volume signals.",
  },
  "presentation.short.summary.watch.technical": {
    es: "{name} muestra señales técnicas mixtas para un short (score {cs}/110). Esperar confirmación bajista.",
    en: "{name} shows mixed technical signals for a short (score {cs}/110). Wait for bearish confirmation.",
  },
  "presentation.short.summary.avoid.technical": {
    es: "{name} no presenta una configuración técnica bajista (score {cs}/110). No abrir short.",
    en: "{name} does not show a bearish technical setup (score {cs}/110). Do not short.",
  },
  "presentation.short.reason.act": {
    es: "El score invertido es alto ({cs}): debilidad fundamental, forecast desfavorable y/o estructura técnica bajista se alinean para una posición short.",
    en: "The inverted score is high ({cs}): fundamental weakness, an unfavorable forecast and/or bearish technical structure align for a short position.",
  },
  "presentation.short.reason.watch": {
    es: "El score invertido es moderado ({cs}). Hay señales bajistas, pero todavía no alcanzan para validar una entrada short.",
    en: "The inverted score is moderate ({cs}). Bearish evidence exists, but it is not strong enough to validate a short entry.",
  },
  "presentation.short.reason.avoid": {
    es: "El score invertido es bajo ({cs}). La evidencia no ofrece ventaja para apostar en contra; no abrir short.",
    en: "The inverted score is low ({cs}). The evidence offers no edge for betting against the stock; do not short.",
  },
  "presentation.short.reason.act.technical": {
    es: "Este activo se evalúa por señales técnicas. El score ({ts}) muestra una configuración bajista que puede sostener una posición short.",
    en: "This asset is evaluated through technical signals. Its score ({ts}) shows a bearish technical setup that may support a short position.",
  },
  "presentation.short.reason.watch.technical": {
    es: "Las señales técnicas bajistas son moderadas ({ts}); esperar confirmación antes de considerar un short.",
    en: "Bearish technical signals are moderate ({ts}); wait for confirmation before considering a short.",
  },
  "presentation.short.reason.avoid.technical": {
    es: "Las señales técnicas ({ts}) no respaldan una posición short; no apostar en contra de este activo.",
    en: "Technical signals ({ts}) do not support a short position; do not bet against this asset.",
  },
  "presentation.short.reason.technical.insufficient": {
    es: "Datos técnicos insuficientes: no abrir short hasta contar con evidencia verificable.",
    en: "Insufficient technical data: do not short until verifiable evidence is available.",
  },
  "presentation.short.gap.support": {
    es: "{gap}% de downside hasta el target externo · apoya la tesis short",
    en: "{gap}% downside to the external target · supports the short thesis",
  },
  "presentation.short.gap.support.compact": { es: "{gap}% downside", en: "{gap}% downside" },
  "presentation.short.gap.risk": {
    es: "{gap}% de upside hasta el target externo · riesgo para la tesis short",
    en: "{gap}% upside to the external target · risk to the short thesis",
  },
  "presentation.short.gap.risk.compact": { es: "{gap}% upside · riesgo", en: "{gap}% upside · risk" },
  "presentation.short.gap.flat": {
    es: "Precio alineado con el target externo · sin ventaja clara",
    en: "Price aligned with the external target · no clear edge",
  },
  "presentation.short.gap.flat.compact": { es: "Sin ventaja", en: "No edge" },
  "presentation.short.gap.noTarget": {
    es: "Sin ancla de analistas para estimar downside",
    en: "No analyst anchor for estimating downside",
  },
  "presentation.short.gap.noTarget.compact": { es: "Sin target", en: "No target" },
  "presentation.short.risk": {
    es: "Riesgo short: las pérdidas pueden superar el capital inicial. Verificá disponibilidad de préstamo, costo, squeeze, stop y fecha de resultados antes de operar.",
    en: "Short risk: losses can exceed the initial capital. Verify borrow availability, cost, squeeze risk, stop, and earnings date before trading.",
  },
  "presentation.short.analystTarget": {
    es: "Target externo (analistas)",
    en: "External target (analysts)",
  },
  "presentation.short.gapLabel": {
    es: "referencia short",
    en: "short reference",
  },
  "presentation.short.col.target": {
    es: "Target externo",
    en: "External target",
  },
  "presentation.short.col.gap": {
    es: "Vs. target",
    en: "Vs. target",
  },
  "presentation.short.col.setup.tooltip": {
    es: "Señal short unificada. Un score alto indica mayor evidencia para apostar a la baja; no es una recomendación de compra.",
    en: "Unified short signal. A high score means stronger evidence for betting on downside; it is not a buy recommendation.",
  },
  "presentation.short.col.trio.tooltip": {
    es: "Debilidad fundamental · Señal técnica bajista · Forecast desfavorable (scores positivos favorecen el short)",
    en: "Fundamental weakness · Bearish technical signal · Unfavorable forecast (positive scores favor the short)",
  },
  "presentation.short.bucket.fundamentals": {
    es: "Debilidad / valuación",
    en: "Weakness / valuation",
  },
  "presentation.short.bucket.technical": {
    es: "Técnico bajista",
    en: "Bearish technicals",
  },
  "presentation.short.bucket.forecast": {
    es: "Forecast desfavorable",
    en: "Unfavorable forecast",
  },
  "presentation.short.tech.supporting": {
    es: "A favor del short",
    en: "Supports the short",
  },
  "presentation.short.tech.risks": {
    es: "Riesgos contra el short",
    en: "Risks to the short",
  },
  "presentation.short.tech.noSupporting": {
    es: "Sin señales bajistas relevantes",
    en: "No relevant bearish signals",
  },
  "presentation.short.tech.noRisks": {
    es: "Sin señales alcistas relevantes",
    en: "No relevant bullish signals",
  },
  "presentation.short.tech.action.strongBull": {
    es: "La estructura alcista contradice el short y eleva el riesgo de squeeze. No abrir una posición bajista sin una reversión confirmada.",
    en: "The bullish structure contradicts the short and raises short-squeeze risk. Do not open a bearish position without a confirmed reversal.",
  },
  "presentation.short.tech.action.mildBull": {
    es: "El sesgo alcista es un riesgo para el short. Esperar pérdida de momentum o ruptura bajista antes de considerar la posición.",
    en: "The bullish bias is a risk to the short. Wait for momentum loss or a bearish break before considering the position.",
  },
  "presentation.short.tech.action.neutral": {
    es: "No hay ventaja técnica clara para un short. Esperar una estructura bajista definida.",
    en: "There is no clear technical edge for a short. Wait for a defined bearish structure.",
  },
  "presentation.short.tech.action.mildBear": {
    es: "La debilidad técnica apoya parcialmente un short, pero requiere confirmación y un stop explícito.",
    en: "Technical weakness partially supports a short setup, but it requires confirmation and an explicit stop.",
  },
  "presentation.short.tech.action.strongBear": {
    es: "La alineación bajista apoya un setup short. Confirmar préstamo, riesgo de squeeze y stop antes de operar.",
    en: "Bearish alignment supports a short setup. Confirm borrow, short-squeeze risk, and stop before trading.",
  },
  "presentation.short.tech.action.insufficient": {
    es: "Datos técnicos insuficientes: no abrir un short hasta contar con evidencia verificable.",
    en: "Insufficient technical data: do not open a short until verifiable evidence is available.",
  },
  "presentation.short.empty.loading": {
    es: "Cargando candidatos para posiciones short…",
    en: "Loading short-position candidates…",
  },
  "presentation.short.empty.loading.hint": {
    es: "Los resultados aparecen a medida que se calculan las señales invertidas; datos faltantes permanecen explícitos.",
    en: "Results appear as inverted signals are calculated; missing data remains explicit.",
  },
  "presentation.short.empty.nomatch": {
    es: "Ningún candidato short coincide con los filtros actuales",
    en: "No short candidate matches the current filters",
  },
  "presentation.short.empty.nomatch.hint": {
    es: "Ajustá los filtros o cambiá el universo; no se fabrican candidatos cuando falta evidencia.",
    en: "Adjust filters or change universe; candidates are not fabricated when evidence is missing.",
  },
  "presentation.short.insiderBuys": {
    es: "{n} compras de insiders en 90 días — riesgo contrario para la tesis short.",
    en: "{n} insider buys in 90 days — contrary risk to the short thesis.",
  },
  "presentation.short.insiderBuy": {
    es: "{n} compra de insider en 90 días — evidencia contraria al short.",
    en: "{n} insider buy in 90 days — evidence against the short.",
  },
  "presentation.short.insiderSells": {
    es: "{n} ventas de insiders sin compras compensatorias — evidencia que apoya la tesis short.",
    en: "{n} insider sells without offsetting buys — evidence supporting the short thesis.",
  },
  "presentation.insider.inactive": {
    es: "Sin formularios Form 4 en los últimos 90 días (insiders inactivos)",
    en: "No Form 4 filings in the last 90 days (insiders inactive)",
  },
  "presentation.insider.mixed": {
    es: "Actividad de insiders mixta",
    en: "Mixed insider activity",
  },
  "presentation.insider.neutral": {
    es: "neutral",
    en: "neutral",
  },
  "presentation.insider.netShares": {
    es: "{n} acciones",
    en: "{n} shares",
  },
  "presentation.insider.activity": {
    es: "90d: {buys} compras · {sells} ventas · neto {net}",
    en: "90d: {buys} buys · {sells} sells · net {net}",
  },
};

export function translateScoringMessage(
  key: string,
  lang: ScoringLang,
  vars?: Record<string, string | number>,
): string {
  let message = SCORING_PRESENTATION_MESSAGES[key]?.[lang] ?? key;
  for (const [name, value] of Object.entries(vars ?? {})) {
    message = message.replaceAll(`{${name}}`, String(value));
  }
  return message;
}

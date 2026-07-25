/** Pure ES/EN dictionary for market-context card copy (safe for Node tests). */
export type MarketContextLang = "es" | "en";

export const MARKET_CONTEXT_MESSAGES: Record<string, Record<MarketContextLang, string>> = {
  "analysis.marketContext.title": { es: "Contexto de mercado", en: "Market context" },
  "analysis.marketContext.tooltip": {
    es: "Evalúa cuánto encajan la calidad, valuación, beta, sector y comportamiento del precio del activo con el entorno actual. El valor va de −100 a +100 y no predice la dirección del mercado. Su efecto sobre el score final se muestra en el resumen superior.",
    en: "Evaluates how well the asset’s quality, valuation, beta, sector, and price behavior fit the current environment. The value ranges from −100 to +100 and does not predict market direction. Its effect on the final score appears in the summary above.",
  },
  "analysis.marketContext.infoAria": {
    es: "Qué significa el contexto de mercado",
    en: "What market context means",
  },
  "analysis.marketContext.status.included": { es: "Incluido", en: "Included" },
  "analysis.marketContext.status.disabled": {
    es: "Contexto desactivado. El score final usa sólo fundamentales, técnico y pronóstico.",
    en: "Market context is off. The final score uses fundamentals, technicals, and forecast only.",
  },
  "analysis.marketContext.status.unavailable": {
    es: "El contexto de mercado no está disponible en este momento.",
    en: "Market context is currently unavailable.",
  },
  "analysis.marketContext.status.unavailable.marketReading": {
    es: "Todavía no hay una lectura confiable del entorno de mercado.",
    en: "A reliable reading of the market environment is not available yet.",
  },
  "analysis.marketContext.status.unavailable.insufficientData": {
    es: "No hay suficientes datos del activo para evaluar su encaje con el mercado.",
    en: "There is not enough asset data to evaluate its market fit.",
  },
  "analysis.marketContext.status.unavailable.unknown": {
    es: "El contexto de mercado no está disponible en este momento.",
    en: "Market context is currently unavailable.",
  },
  "analysis.marketContext.status.notApplicable": { es: "No aplica", en: "Not applicable" },
  "analysis.marketContext.bucket.favorable": { es: "Favorable", en: "Favorable" },
  "analysis.marketContext.bucket.neutral": { es: "Neutral", en: "Neutral" },
  "analysis.marketContext.bucket.adverse": { es: "Desfavorable", en: "Unfavorable" },
  "analysis.marketContext.bucket.short.favorable": { es: "A favor del short", en: "Supports the short" },
  "analysis.marketContext.bucket.short.neutral": { es: "Neutral para el short", en: "Neutral for the short" },
  "analysis.marketContext.bucket.short.adverse": { es: "En contra del short", en: "Against the short" },
  "analysis.marketContext.summary.long.favorable": {
    es: "El entorno actual favorece activos con este perfil.",
    en: "The current environment favors assets with this profile.",
  },
  "analysis.marketContext.summary.long.neutral": {
    es: "El entorno actual no aporta una ventaja clara a este activo.",
    en: "The current environment does not give this asset a clear advantage.",
  },
  "analysis.marketContext.summary.long.adverse": {
    es: "Las condiciones actuales juegan en contra de este perfil.",
    en: "Current conditions work against this asset profile.",
  },
  "analysis.marketContext.summary.short.favorable": {
    es: "El entorno actual respalda la tesis bajista para este activo.",
    en: "The current environment supports the bearish thesis for this asset.",
  },
  "analysis.marketContext.summary.short.neutral": {
    es: "El entorno actual no aporta una ventaja clara a la tesis bajista.",
    en: "The current environment gives the bearish thesis no clear advantage.",
  },
  "analysis.marketContext.summary.short.adverse": {
    es: "Las condiciones actuales debilitan la tesis bajista.",
    en: "Current conditions weaken the bearish thesis.",
  },
  "analysis.marketContext.chip.Quality": { es: "Calidad", en: "Quality" },
  "analysis.marketContext.chip.LowBeta": { es: "Beta", en: "Beta" },
  "analysis.marketContext.chip.Value": { es: "Valor", en: "Value" },
  "analysis.marketContext.chip.OversoldQual": { es: "Calidad + sobreventa", en: "Oversold quality" },
  "analysis.marketContext.chip.Extension": { es: "Extensión", en: "Extension" },
  "analysis.marketContext.chip.Trend": { es: "Tendencia", en: "Trend" },
  "analysis.marketContext.chip.Defensive": { es: "Sector defensivo", en: "Defensive sector" },
  "analysis.marketContext.chip.Growth": { es: "Sector de crecimiento", en: "Growth sector" },
  "analysis.marketContext.chip.Liquidity": { es: "Liquidez", en: "Liquidity" },
  "analysis.marketContext.chip.GeneralFit": { es: "Encaje general", en: "Overall fit" },
  "analysis.marketContext.chip.Neutral": { es: "Sin ventaja", en: "No edge" },
  "analysis.marketContext.chip.Other": { es: "Otro factor contextual", en: "Other contextual factor" },
  "analysis.marketContext.chip.aria.support": { es: "{label}, a favor", en: "{label}, supportive" },
  "analysis.marketContext.chip.aria.risk": { es: "{label}, en contra", en: "{label}, against" },
  "analysis.marketContext.chip.aria.neutral": { es: "{label}, neutral", en: "{label}, neutral" },
  "analysis.marketContext.chip.aria.short.support": { es: "{label}, a favor del short", en: "{label}, supports the short" },
  "analysis.marketContext.chip.aria.short.risk": { es: "{label}, en contra del short", en: "{label}, against the short" },
  "analysis.marketContext.factsJoin": { es: " y ", en: " and " },
  "analysis.marketContext.metric.fcfPositive": { es: "FCF positivo de {fcf}", en: "positive FCF of {fcf}" },
  "analysis.marketContext.metric.fcf": { es: "FCF de {fcf}", en: "FCF of {fcf}" },
  "analysis.marketContext.metric.roe": { es: "ROE de {roe}", en: "ROE of {roe}" },
  "analysis.marketContext.metric.de": { es: "D/E de {de}", en: "D/E of {de}" },
  "analysis.marketContext.metric.rsi": { es: "RSI {rsi}", en: "RSI {rsi}" },
  "analysis.marketContext.metric.pos52": { es: "{pos}% del rango anual", en: "{pos}% of the yearly range" },
  "analysis.marketContext.metric.marketCap": { es: "capitalización de {cap}", en: "market cap of {cap}" },
  "analysis.marketContext.metric.volume": { es: "volumen relativo de {volume}x", en: "relative volume of {volume}x" },
  "analysis.marketContext.metric.sectorUnknown": { es: "sector no identificado", en: "unidentified sector" },
  "analysis.marketContext.evidence.Quality.support": {
    es: "Calidad financiera sólida: {facts}.",
    en: "Solid financial quality: {facts}.",
  },
  "analysis.marketContext.evidence.Quality.risk": {
    es: "Calidad financiera débil: {facts}.",
    en: "Weak financial quality: {facts}.",
  },
  "analysis.marketContext.evidence.Quality.support.fallback": {
    es: "Calidad financiera sólida respecto del entorno actual.",
    en: "Solid financial quality relative to the current environment.",
  },
  "analysis.marketContext.evidence.Quality.risk.fallback": {
    es: "Calidad financiera débil respecto del entorno actual.",
    en: "Weak financial quality relative to the current environment.",
  },
  "analysis.marketContext.evidence.LowBeta.support": {
    es: "Beta de {beta}: menor sensibilidad a cambios del mercado.",
    en: "Beta of {beta}: lower sensitivity to market moves.",
  },
  "analysis.marketContext.evidence.LowBeta.risk": {
    es: "Beta de {beta}: mayor sensibilidad a cambios del mercado.",
    en: "Beta of {beta}: higher sensitivity to market moves.",
  },
  "analysis.marketContext.evidence.LowBeta.support.fallback": {
    es: "Perfil de beta más defensivo en el entorno actual.",
    en: "More defensive beta profile in the current environment.",
  },
  "analysis.marketContext.evidence.LowBeta.risk.fallback": {
    es: "Perfil de beta más sensible en el entorno actual.",
    en: "More sensitive beta profile in the current environment.",
  },
  "analysis.marketContext.evidence.Value.support": {
    es: "Valuación atractiva: Forward P/E de {pe}x.",
    en: "Attractive valuation: forward P/E of {pe}x.",
  },
  "analysis.marketContext.evidence.Value.risk": {
    es: "Valuación exigente: Forward P/E de {pe}x.",
    en: "Demanding valuation: forward P/E of {pe}x.",
  },
  "analysis.marketContext.evidence.Value.support.fallback": {
    es: "Valuación atractiva en el entorno actual.",
    en: "Attractive valuation in the current environment.",
  },
  "analysis.marketContext.evidence.Value.risk.fallback": {
    es: "Valuación exigente en el entorno actual.",
    en: "Demanding valuation in the current environment.",
  },
  "analysis.marketContext.evidence.OversoldQual.support": {
    es: "Precio castigado con fundamentos sólidos: {facts}.",
    en: "Beaten-down price with solid fundamentals: {facts}.",
  },
  "analysis.marketContext.evidence.OversoldQual.risk": {
    es: "La caída del precio no está acompañada por suficiente calidad financiera.",
    en: "The price decline is not backed by enough financial quality.",
  },
  "analysis.marketContext.evidence.OversoldQual.support.fallback": {
    es: "Precio castigado con fundamentos sólidos.",
    en: "Beaten-down price with solid fundamentals.",
  },
  "analysis.marketContext.evidence.OversoldQual.risk.fallback": {
    es: "La caída del precio no está acompañada por suficiente calidad financiera.",
    en: "The price decline is not backed by enough financial quality.",
  },
  "analysis.marketContext.evidence.Extension.long.support": {
    es: "El precio no luce extendido: {facts}.",
    en: "Price does not look extended: {facts}.",
  },
  "analysis.marketContext.evidence.Extension.long.risk": {
    es: "El precio luce extendido: {facts}.",
    en: "Price looks extended: {facts}.",
  },
  "analysis.marketContext.evidence.Extension.long.support.fallback": {
    es: "El precio no luce extendido frente al entorno actual.",
    en: "Price does not look extended versus the current environment.",
  },
  "analysis.marketContext.evidence.Extension.long.risk.fallback": {
    es: "El precio luce extendido frente al entorno actual.",
    en: "Price looks extended versus the current environment.",
  },
  "analysis.marketContext.evidence.Extension.short.support": {
    es: "La extensión del precio aumenta su vulnerabilidad: {facts}.",
    en: "Price extension increases vulnerability: {facts}.",
  },
  "analysis.marketContext.evidence.Extension.short.risk": {
    es: "El precio no muestra suficiente extensión para fortalecer el short: {facts}.",
    en: "Price does not show enough extension to strengthen the short: {facts}.",
  },
  "analysis.marketContext.evidence.Extension.short.support.fallback": {
    es: "La extensión del precio aumenta su vulnerabilidad.",
    en: "Price extension increases vulnerability.",
  },
  "analysis.marketContext.evidence.Extension.short.risk.fallback": {
    es: "El precio no muestra suficiente extensión para fortalecer el short.",
    en: "Price does not show enough extension to strengthen the short.",
  },
  "analysis.marketContext.evidence.Trend.bullish": {
    es: "Tendencia alcista: precio por encima de EMA50 y EMA200.",
    en: "Uptrend: price above the 50- and 200-day EMAs.",
  },
  "analysis.marketContext.evidence.Trend.bearish": {
    es: "Tendencia débil: precio por debajo de EMA50 y EMA200.",
    en: "Weak trend: price below the 50- and 200-day EMAs.",
  },
  "analysis.marketContext.evidence.Trend.support.fallback": {
    es: "La tendencia del precio acompaña el entorno actual.",
    en: "Price trend aligns with the current environment.",
  },
  "analysis.marketContext.evidence.Trend.risk.fallback": {
    es: "La tendencia del precio no acompaña el entorno actual.",
    en: "Price trend does not align with the current environment.",
  },
  "analysis.marketContext.evidence.Defensive": {
    es: "Sector defensivo: {sector} aporta estabilidad en este entorno.",
    en: "Defensive sector: {sector} adds stability in this environment.",
  },
  "analysis.marketContext.evidence.Growth.long": {
    es: "Sector de crecimiento: {sector} acompaña el entorno actual.",
    en: "Growth sector: {sector} fits the current environment.",
  },
  "analysis.marketContext.evidence.Growth.short": {
    es: "Sector de crecimiento: {sector} aporta sensibilidad a la tesis bajista.",
    en: "Growth sector: {sector} adds sensitivity to the bearish thesis.",
  },
  "analysis.marketContext.evidence.Liquidity.support": {
    es: "Buena liquidez: {facts}.",
    en: "Solid liquidity: {facts}.",
  },
  "analysis.marketContext.evidence.Liquidity.risk": {
    es: "Liquidez limitada: {facts}.",
    en: "Limited liquidity: {facts}.",
  },
  "analysis.marketContext.evidence.Liquidity.support.fallback": {
    es: "Buena liquidez para operar en el entorno actual.",
    en: "Solid liquidity for trading in the current environment.",
  },
  "analysis.marketContext.evidence.Liquidity.risk.fallback": {
    es: "Liquidez limitada en el entorno actual.",
    en: "Limited liquidity in the current environment.",
  },
  "analysis.marketContext.evidence.GeneralFit.support": {
    es: "El perfil del activo encaja en conjunto con el entorno actual.",
    en: "Overall, the asset profile fits the current environment.",
  },
  "analysis.marketContext.evidence.GeneralFit.risk": {
    es: "El perfil del activo no encaja bien con el entorno actual.",
    en: "Overall, the asset profile does not fit the current environment well.",
  },
  "analysis.marketContext.evidence.Neutral": {
    es: "Ningún factor contextual domina el resultado.",
    en: "No single contextual factor dominates the result.",
  },
  "analysis.marketContext.evidence.Other": {
    es: "Otro factor contextual influye en el resultado.",
    en: "Another contextual factor influences the result.",
  },
};

export function translateMarketContextMessage(
  key: string,
  lang: MarketContextLang,
  vars?: Record<string, string | number>,
): string {
  const entry = MARKET_CONTEXT_MESSAGES[key];
  if (!entry) return key;
  let text = entry[lang] ?? entry.es ?? key;
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.replaceAll(`{${k}}`, String(v));
    }
  }
  return text;
}

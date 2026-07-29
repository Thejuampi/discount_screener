import type { Lang } from "./i18n";

export function formatProviderDate(epochSeconds: number, lang: Lang): string {
  return new Intl.DateTimeFormat(lang === "es" ? "es-AR" : "en-US", {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    timeZone: "UTC",
  }).format(new Date(epochSeconds * 1000));
}

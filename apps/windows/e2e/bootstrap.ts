import "../src/index.css";

declare global {
  interface Window {
    __startVantageE2e?: () => Promise<void>;
  }
}

let started = false;

window.__startVantageE2e = async () => {
  if (started) return;
  started = true;

  const [
    { createElement },
    { createRoot },
    { default: App },
    { LanguageProvider },
    { ThemeProvider },
  ] = await Promise.all([
    import("react"),
    import("react-dom/client"),
    import("../src/App.tsx"),
    import("../src/i18n.tsx"),
    import("../src/theme.tsx"),
  ]);

  const root = document.getElementById("root");
  if (root == null) throw new Error("E2E root element is missing");

  createRoot(root).render(
    createElement(
      ThemeProvider,
      null,
      createElement(LanguageProvider, null, createElement(App)),
    ),
  );
};

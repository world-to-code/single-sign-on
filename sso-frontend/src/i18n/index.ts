import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { getLocale, type Locale } from "@/lib/prefs";
import { en } from "./en";
import { ko } from "./ko";

// Prefer Korean: a persisted choice wins, else the browser hint, else Korean-by-default via the
// navigator check below. Detection runs before init so there is no flash of the wrong language.
const initialLng: Locale = getLocale() ?? (navigator.language.startsWith("ko") ? "ko" : "en");

// Resources are bundled (not lazy-loaded) so the first paint is already translated — no flash.
void i18n.use(initReactI18next).init({
  resources: { en, ko },
  lng: initialLng,
  fallbackLng: "en",
  defaultNS: "common",
  ns: ["common", "nav", "auth", "console", "errors", "states", "validation", "marketing"],
  interpolation: { escapeValue: false }, // React already escapes; double-escaping mangles copy
});

// The CSS `:root[lang="ko"|"en"]` typography rules (index.css) depend on this attribute at runtime.
i18n.on("languageChanged", (lng) => {
  document.documentElement.lang = lng;
});
document.documentElement.lang = i18n.language;

export default i18n;

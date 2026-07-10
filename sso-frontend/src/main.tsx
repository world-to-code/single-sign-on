import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import { ConfirmProvider } from "./components/ConfirmProvider";
import { ToastProvider } from "./components/ToastProvider";
import "./index.css";

// React.StrictMode is intentionally NOT used: in development it double-invokes every effect, which
// fires every data fetch (and side effect) twice — the duplicate-request noise. Production builds
// never double-invoke, so this only affects dev. Effects are still written to be idempotent (e.g. the
// admin OIDC flow in AdminPortal guards against re-entry).
ReactDOM.createRoot(document.getElementById("root")!).render(
  <BrowserRouter>
    <ToastProvider>
      <ConfirmProvider>
        <App />
      </ConfirmProvider>
    </ToastProvider>
  </BrowserRouter>,
);

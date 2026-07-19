# CLAUDE.md — sso-frontend

The IdP's all-React SPA (login, MFA, admin console, user portal). Read the root `../CLAUDE.md`
first (git, engineering defaults). This file covers frontend-specific rules; they OVERRIDE defaults.

## Stack

React 18 + **TypeScript (strict)** · Vite · React Router · Tailwind CSS v3 with a shadcn/ui-style
component layer (Radix primitives + `class-variance-authority` + `clsx`/`tailwind-merge`) ·
`lucide-react` icons. Import via the **`@/` alias** (→ `src/`), never long relative chains.

Run from `sso-frontend/`: `npm run dev` (Vite `:5173`, proxies API to backend `:9000`) ·
`npm test` (**Vitest + jsdom**, `npm run test:watch` while iterating) · `npm run build`
(**`tsc && vite build`** — type-check must pass; emits into the backend's `static/`).
**`npm test` and `npm run build` are BOTH the gate: both must be green** (CI runs both).

## Structure (keep to it)

```
src/
  pages/              route-level screens (one folder-worthy feature each)
  components/
    ui/               shadcn-style primitives (button, card, input, dialog, table, …) — compose, don't reinvent
    layout/           AppShell, AuthLayout, nav, topbar
    auth/             factor inputs, choosers (OtpInput, FactorChooser, …)
    form/             shared form fields (Field, Toggle, …)
    <Shared>.tsx      cross-page building blocks (Brand, PageHeader, states, ConfirmProvider, …)
  hooks/              reusable logic (useApiData, useEditorForm, useDeleteConfirm, useFactorVerification, …)
  lib/                utils (cn, tokens), api client, webauthn helpers
```

## Rules (best practice)

- **Componentize first.** Prefer many small, single-purpose, reusable components over large ones.
  Extract any UI repeated ≥2× into a shared component; one component per file; PascalCase names/files.
  Split presentation (dumb, props-in) from container/logic where it clarifies.
- **Reuse before writing.** Check `components/ui`, `components/*`, and `hooks/` first — a primitive,
  layout, provider, or hook (data fetching, edit dialogs, delete-confirm, factor flow) probably
  already exists. Don't duplicate fetch logic or dialog boilerplate.
- **TypeScript strict, no `any`.** Type every prop (an explicit `type`/`interface`), hook return,
  and API shape. Model state with precise unions (e.g. `next: "IDENTIFY" | "FACTOR" | "DONE"`).
- **Hooks for shared logic**; custom hooks start with `use`, contain no JSX. Keep state local; lift
  only when shared; use context for cross-cutting concerns (confirm dialogs, step-up), not prop-drilling.
- **Styling via Tailwind + the `ui` design tokens only — no inline `style`, no ad-hoc hex colors.**
  Use `cn()` to compose classes. Respect the light/dark theme tokens.
- **Centralize API calls** in the `lib` api/auth client; components call typed functions, never raw
  `fetch` scattered around. Always render loading / error / empty states (reuse `states`/`DataList`).
- **Accessibility:** semantic HTML, labelled inputs, keyboard-navigable dialogs/menus (Radix gives
  this — keep it), meaningful `aria-*`.
- **No dead code:** remove unused components, props, imports, and exports.
- Dev-proxy gotcha: `vite.config.ts` proxies the backend paths (`/api`, `/oauth2`, `/saml2`, `/scim`,
  `/userinfo`, `/.well-known`, `/connect`, `/actuator`, `/webauthn`, `/login/webauthn`) to `:9000`.
  Proxy `/login/webauthn` only — NOT all of `/login` (it's a client-side SPA route). When you add a
  backend path the SPA calls, add it here and restart `npm run dev`.

## Commits

Conventional Commits with scope `frontend` (see `../docs/commit-convention.md`),
e.g. `feat(frontend): add passkey management page`.

# DESIGN.md — Mini SSO admin portal

The design system for the IdP's React SPA: login, MFA, admin console, user portal.

This file is **normative**. When a screen and this document disagree, the document wins and the
screen is a bug. Read `CLAUDE.md` first for engineering rules (componentize, TS strict, `@/` alias,
no dead code); this file governs what things *look like* and *how they behave*, including the
moments the happy path does not cover.

Every pattern here exists in a working prototype and was verified in a browser at all seven target
viewports, in both languages, in both themes. Do not add a pattern to this file that you have not
built and looked at.

---

## 1. What this product is

An identity provider is not a content site. Two facts drive every decision below.

**The reader is an administrator making a consequential change.** Turning off a factor, widening a
CIDR, deleting an organization — each has blast radius. The interface's job is not delight; it is
*legibility*. Nobody should press Save without knowing what is currently true.

**The reader is Korean first.** Hangul is a square-block script. It suffocates at Latin leading and
breaks under negative tracking. So the vertical rhythm is built for 한글, and Latin is fitted into
it — never the reverse.

### Anti-patterns: what makes an interface look generated

These are banned. They are how the console looked before, and they are what "AI-generated" means
visually.

| Banned | Why | Instead |
|---|---|---|
| `--primary: 243 75% 59%` (shadcn indigo default) | Inherited, not chosen | The accent in §2 |
| Icon inside a tinted rounded square, as a stat tile | The single strongest "template" tell | Mono eyebrow + large tabular figure + delta |
| Coloured accent bar or rail on a rounded card | Decoration encoding nothing | A tinted **border**, only when it means something (tenant scope) |
| Purple→blue gradient anything | — | Flat fills |
| Emoji as a section marker | — | Nothing, or a 1.6px stroke icon |
| A page title in the topbar **and** in the page | Duplicate expression | The page's `<h1>`, once |
| `rounded-lg` on everything | Unconsidered | The radius scale in §2 |

---

## 2. Tokens

Define as CSS custom properties on `:root` in `src/index.css`. Redefine **only the tokens** under
`@media (prefers-color-scheme: dark)`, then again under `:root[data-theme="dark"]` and
`:root[data-theme="light"]` so an explicit toggle beats the OS in both directions. Style components
through tokens — never inside the media query.

> **Implementation note (how these actually ship).** The hexes below are the source of truth for the
> *rendered colour*, but they are stored in `src/index.css` as **HSL channel triples**
> (`--accent: 183 76% 25%`), consumed as `hsl(var(--x) / <alpha-value>)`. This is not cosmetic: the
> codebase uses Tailwind alpha modifiers (`bg-primary/10`, `border-destructive/40`) in 50+ places,
> and those only resolve against a channel triple. For the same reason `--accent-soft` / `--accent-line`
> are **precomputed** triples (accent at 8% / 26% over surface), not `color-mix()` — `color-mix` can't
> take a bare triple. And because `text-primary` already meant "accent" across the code, petrol lives
> in the existing `--primary` alias rather than a new `--accent` token.

### Colour

The neutral scale is warm-biased. A pure mid-grey reads as unconsidered; this one reads as chosen.

```
--bg        #F4F4F2   page ground
--surface   #FFFFFF   cards, sidebar, inputs on focus
--sunken    #FAFAF8   input rest, table row hover, preview panels
--line      #E5E5E2   borders
--line-soft #EFEFEC   internal dividers, quiet button fill
--faint     #A8A8A3   placeholders, disabled, tertiary
--muted     #6E6E69   secondary text, descriptions
--ink-2     #3A3A37   labels
--ink       #1B1B19   primary text, primary button fill
```

**Exactly one accent.** Petrol, deliberately far from indigo.

```
--accent      #0F6A6E    dark: #4FBDB4
--accent-ink  #FFFFFF    dark: #08201F
--accent-soft color-mix(in srgb, var(--accent) 8%, var(--surface))
--accent-line color-mix(in srgb, var(--accent) 26%, transparent)
```

The accent is for: links, focus rings, the active nav item, chart strokes, selected chips, the
active tab underline. **It is not for primary buttons** — those are ink black. An accent-filled
primary button is what the indigo default looked like, and near-black CTAs are what Korean products
actually ship.

**Semantic colour is separate from the accent and never substitutes for it.** Swap the accent and
"실패" must still be red.

```
--allow #2E7D4F   dark: #63B283    success, allow rules, verified
--deny  #C0392B   dark: #E1685C    failure, destructive, validation errors
--warn  #A87413   dark: #D2A748    privilege, elevation, expiry, stale data
```

`--warn` carries one extra meaning in this product: **amber means "this costs you a re-auth."**
Elevation lifetime rows, `PRIVILEGED` command tags, step-up modals. Never decorative.

Dark ground is warm charcoal `#141412`, not `#000` and not blue-black. Surfaces `#1C1C1A`.

### Type

CSP forbids font CDNs. Self-host, do not `<link>` a webfont URL.

```
body / UI   "Pretendard Variable", Pretendard, -apple-system, "Apple SD Gothic Neo",
            "Malgun Gothic", "Noto Sans KR", system-ui, sans-serif
data / IDs  ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace
```

There is no display face. Character comes from weight, size and tracking.

| Role | Size | Weight | Tracking |
|---|---|---|---|
| Page `<h1>` | 26px | 700 | `-0.022em` (ko: `-0.03em`) |
| Card `<h2>` | 16px | 700 | — |
| Section `<h3>` | 15px | 700 | — |
| Body | 14px | 400 | — |
| Setting label | 15px | 600 | — |
| Description | 13px | 400 | — |
| Nav item | 13.5px | 600 | — |
| Table header | 12px | 600 | — |
| KPI figure | 32px | 700 | `-0.035em` |
| Nav group heading | 11px | 700 | `0.04em` |

**Korean-first typography rules.** Enforce with `:root[lang="ko"]` / `:root[lang="en"]`:

- Line-height **1.65 for `ko`**, 1.55 for `en`.
- Headings take `-0.03em` in Korean, `-0.022em` in Latin. Never tighter — Hangul jamo collide.
- **Uppercase is meaningless in Hangul.** `text-transform: uppercase` and wide letter-spacing on
  eyebrows apply under `:root[lang="en"]` only.
- `text-wrap: balance` on every heading.
- `font-variant-numeric: tabular-nums` wherever digits align in a column.

### Space, radius, elevation

An 8px rhythm. Three variables carry all page spacing so a breakpoint can retune the whole console
by changing three numbers.

```
--page 28px   page gutter        --gap 20px   between cards      --pad 24px   inside cards
```

```
--r-card 18px    cards, modals(20px), auth card(22px)
--r-ctl  12px    inputs, buttons, selects
         999px   chips, badges(7px), pills, toasts(14px)
```

Two shadows only. Both are near-invisible in light and do the lifting in dark.

```
--shadow      resting cards
--shadow-lift modals, drawers, toasts, the save bar
```

*(As built, only `--shadow` exists as a token; the lifted surfaces use Tailwind's `shadow-lg`. If a
distinct `--shadow-lift` is wanted, add it — nothing depends on its absence.)*

Density is **roomy**: setting rows are 18px vertical padding with a description line, not 32px
data rows. Complex settings are made legible by explanation, not by compression.

### Motion

```
--ease cubic-bezier(.2, .8, .2, 1)
```

- Hover / focus: 120–160ms.
- Page and panel entry: 260–340ms, `translateY(6–8px)` + fade. First paint only.
- Modal entry: 200ms on `transform`, `cubic-bezier(.2,.9,.3,1.1)` — it should *snap*.
- Drawer, sidebar collapse: 260–280ms.
- Save bar rise, toast entry: 280–340ms.

Nothing ambient. No floating glows, no gradient meshes, no looping animation. Motion appears where
it explains causality — a policy chip toggling redraws the sign-in preview — and nowhere else.

`@media (prefers-reduced-motion: reduce)` disables every animation and transition. Not optional.

---

## 3. Layout and chrome

```
┌────────────┬───────────────────────────────────────────────┐
│ org picker │ breadcrumb (section only)          ⌘K search  │  topbar, 60px
│            ├───────────────────────────────────────────────┤
│ nav groups │  <h1>  page title            [primary action] │
│  · icon    │  description                                  │
│  · label   │                                               │
│            │  cards …                                      │
│            │                                               │
│ ┌────────┐ │                                               │
│ │ account│ │                                               │
│ │ 한/영 ☾ ⏻│ │                                              │
│ └────────┘ │                                               │
└────────────┴───────────────────────────────────────────────┘
   248px          main, max-width 1400px, margin-inline auto
```

**The topbar carries a breadcrumb and search. Nothing else.** It must never render the page title —
that is the `<h1>`'s job and duplicating it was the console's worst redundancy. It must never hold a
second copy of a control that exists in the sidebar.

**Identity lives in the sidebar foot**: the account button, the language toggle, the theme toggle,
sign out. These are session-scoped concerns and belong with the session's identity, not floating
above the content.

**The sidebar collapses** to a 68px icon rail via a handle on its right edge. Collapsed, every label
becomes a tooltip. Icons must be **individually distinguishable** — an icon-only rail with three
identical glyphs is unusable. One glyph per nav item, no reuse.

**Scope is a breadcrumb pill**, not a banner. When a platform admin has drilled into a tenant, the
breadcrumb leads with `● 관리 중 · Acme` in accent. A separate full-width banner restating the same
tenant is duplication.

### Breakpoints

Verified at 1920×1080, 1366×768, 1440×900, 2560×1080, 1024×768, 768×1024, 800×1280.

| Width | Behaviour |
|---|---|
| ≥1600px | `--page: 36px`, `--gap: 24px`. **`main` caps at 1400px and centres** — an ultrawide must not tear a table across 2500px |
| 1281–1599 | Full 248px sidebar |
| ≤1280px | Sidebar auto-collapses to the 68px icon rail (tablet landscape, 1024×768) |
| ≤1180px | The policy editor drops its side-by-side preview to one column |
| ≤1100px | `--page: 20px`; `.grid2` → one column |
| ≤1000px | Form label column folds above its controls |
| ≤900px | Sidebar becomes an overlay **drawer** + scrim, hamburger appears (768×1024, 800×1280) |
| ≤620px | Setting rows stack; modal footers reverse to column |
| any `max-height: 820px` | `--page: 20px`, `--gap: 16px`, `h1` → 23px, chart → 168px. This is 1366×768 |

Two hard rules:

- **The page body never scrolls horizontally.** Wide content — tables, charts, the topology —
  scrolls inside its own `overflow-x: auto` container.
- **Never `grid-template-columns: 1fr` on the app shell.** `1fr` is `minmax(auto, 1fr)`; the track
  floors at min-content and a wide policy table pushes the entire grid past the viewport. Always
  `minmax(0, 1fr)`. This was a real bug at 768×1024.

---

## 4. Components

Live in `components/ui/` (primitives), `components/` (shared), `components/layout/`. Reuse before
writing. Every one below exists.

### Buttons

| Variant | Fill | Use |
|---|---|---|
| `primary` | `--ink`, text `--bg` | The one action the page exists for |
| `quiet` | `--line-soft` | Cancel, secondary, row actions |
| `danger-text` | text `--deny`, no fill | Delete. **Never a filled red block** in a normal page |
| `icon` | 32px square, ghost | Row edit / delete / reorder |

Height 44px (`sm`: 36px). `:active` → `scale(.985)`. Buttons rendered as `<a>` need
`text-decoration: none` — an underlined "취소" is a bug.

### Badges and chips

`badge` (24px, radius 7px) states a fact: `사용` / `중지` / `시스템` / `기본 제공` / `적용 중`.
Tones `ok` `bad` `warn` `soft`(accent). `permchip` is a bordered mono code chip for permissions and
grant types. `chip` is a 38px toggleable pill for factor selection — pressed state takes the accent
border, accent text, `--accent-soft` fill, and reveals a filled dot.

**State is encoded in form as well as colour.** A result chip is a dot *plus* a word: `● 성공`,
`○ 실패` (hollow ring). It must read without hue.

### Setting row — the workhorse

Every complex setting is three parts:

```
label                                                       current value
one muted sentence saying what happens, in the user's terms
```

The current value is right-aligned. **The page must be readable without opening a single control.**
The sentence describes consequence, not mechanism: "이 시간 동안 아무 활동이 없으면 다시 로그인해야
합니다", never "sets idleTimeoutMinutes".

A value inherited from the platform tier renders read-only with an `플랫폼에서 상속됨` marker in
`--faint`. Ownership must be visible.

### Form section

A 15rem label column (title + description) beside a controls column. Folds to one column ≤1000px.
This is the shape of every create/edit screen.

### Editor screen

`back crumb → <h1> → tabs → panels → sticky action bar`. Tabs actually switch panels
(`data-panel`). The action bar holds Cancel + the submit verb, and a short note — the note must not
restate the page description.

### Save bar

An edit made to an existing object raises a pill from the bottom naming **the diff, not the intent**:
`유휴 제한 시간 30분 → 15분`. Disabled and reading "저장할 변경사항 없음" until a diff exists.

### Signature: the live sign-in preview

The authentication-policy editor renders, beside the form, the sign-in the policy produces:

```
○ 이메일 입력      사용자를 확인하고 정책을 고릅니다.
│
○ 1단계            다음 중 하나   [비밀번호] [패스키]
│
○ 2단계            다음 중 하나   [인증 앱] [이메일 코드]
│
○ 로그인 완료      세션 8시간 · 유휴 30분 후 만료
```

Toggling a factor chip redraws it immediately. Emptying step 2 collapses it to `2단계 없음`.
Emptying step 1 replaces the flow with **`로그인 불가` — "1단계 수단이 하나도 없어 아무도 로그인할
수 없습니다."** This is the answer to "how do we make complex settings understandable": show the
consequence, not the schema.

---

## 5. Loading, empty, failure

Never render a spinner where the shape is known. Render the shape.

| State | Pattern |
|---|---|
| Loading | Skeleton rows matching the real row height, shimmer 1.4s (off under reduced-motion) |
| Empty | Centred title + one hint sentence. An empty screen is an invitation to act: "첫 사용자를 만드세요." |
| Partial / stale | An amber `stale` strip above the panel: "10분 전 데이터입니다." + `다시 시도`. The data still renders |
| Failure | A `failure` panel replacing the panel's body |

### The four failures

Each is a distinct panel: icon, title, one sentence of what to do, actions, and — for the two that
are the server's fault — a trace ID. The backend stamps every `ProblemDetail` with a `traceId`
(alongside `code` and `instance`); `ApiError` in `src/api.ts` captures it and the panel shows it.

| Kind | Title | Retry? | Trace ID? |
|---|---|---|---|
| Network | 서버에 연결하지 못했습니다 | yes | yes |
| 403 | 이 화면을 볼 권한이 없습니다 | **no** | no |
| 404 | 찾을 수 없습니다 | **no** | no |
| 5xx | 요청을 처리하지 못했습니다 | yes | yes |

Three rules, and they are security requirements, not style:

1. **An error never apologises and is never vague.** It says what happened and what to do next.
   "죄송합니다, 오류가 발생했습니다" is banned.
2. **A failure discloses nothing the caller may not know.** 403 says "you do not have permission" —
   never "the object exists but you cannot see it". A wrong password and an unknown username produce
   the same message. Never echo an existence check into the UI.
3. **Retry only what is retryable.** A 403 has no retry button; offering one teaches people to
   hammer a wall.

### Toasts

Bottom-centre, capped at 520px. **A toast is made of the same material as a card** — `--surface`
ground, 1px `--line` border, `--shadow-lift`. It is never an inverted ink pill; that reads as a
foreign component dropped into the product.

**Status is said once**, by the pip: a filled `--allow` dot for success, a hollow `--deny` ring for
failure, a filled `--warn` dot for warning. It reads without hue, so it needs no help. A coloured
rail down the left edge on top of that is the same fact stated twice — the card-with-an-accent-rail
pattern §1 bans, wearing a different hat. **The accent never appears in a toast.**

Anatomy, and the row is vertically centred — the action and close buttons sit on the toast's centre
line, never riding up against the title:

```
  ●  저장하지 못했습니다                       [다시 시도]  ✕
     변경 내용은 그대로 있습니다.
▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔                                     ← time remaining
```

**Everything dismisses itself.** A toast that lingers forever trains people to ignore the corner.

| Tone | Life | `role` |
|---|---|---|
| success | 4.5s | `status` |
| warning | 7s | `status` |
| error | 9s | `alert` |

An error lives longer because it asks for a decision, not because it is permanent.

A 2px bar drains left-to-right showing the time left — a toast that vanishes without warning reads
as a glitch. **Hovering or focusing the toast pauses both the bar and the timer**, so it can never
disappear from under a pointer reaching for its button. Under `prefers-reduced-motion` the bar is
static.

Newest toast sits **lowest**, nearest the eye; older ones push upward.

Two implementation traps, both found by testing:

- Removal must never depend on `animationend` alone. If the exit animation is suppressed or
  interrupted the event never fires and the toast is stranded on screen forever. Pair it with a
  fallback `setTimeout`.
- The pause timer must mirror the CSS bar's duration, or what the person sees is a lie.

An error toast after a failed save reassures: "변경 내용은 그대로 있습니다."

### Optimistic concurrency

A 409 renders an amber alert, not a toast: "다른 관리자가 먼저 수정했습니다" + `최신 내용 불러오기`.
Never silently clobber.

---

## 6. Validation

Validate on blur and on submit — never on every keystroke while the field is still being typed into.

**The field.** `aria-invalid="true"`, `aria-describedby` pointing at the message, `--deny` border, a
4%-tint background, and a `role="alert"` message beneath with an icon.

The message says what is wrong **and how to fix it**:

| Bad | Good |
|---|---|
| 잘못된 형식 | `CIDR 형식이 아닙니다. 예: 203.0.113.0/24` |
| 유효하지 않음 | `http:// 주소는 localhost에서만 허용됩니다. https를 사용하세요.` |
| 오류 | `이미 사용 중인 클라이언트 ID입니다. 다른 값을 입력하세요.` |

An async check that *passed* also speaks: green border + `사용할 수 있습니다.` Silence after a
server round-trip reads as breakage.

**The form.** On a failed submit, an `alert-error` sits at the top of the card: the count, and a
list of anchor links to each bad field. Focus moves to the first invalid control.

Field error replaces the hint text — never stack both.

---

## 7. Interruptions: re-auth, step-up, destruction

A modal that appears without a stated reason trains administrators to click through security
prompts. Every interruption states **why it appeared** and **what it unlocks**.

Shared anatomy:

```
badge (권한 승격 필요 / 본인 확인 / 되돌릴 수 없음)
<h2>   what is being asked
<p>    why it is being asked, right now, of you
┌─ modal-why ─────────────────┐
│ 요청한 작업   세션 정책 수정 │   ← what triggered it
│ 확인 후 유효시간      5분    │   ← what you get for complying
└─────────────────────────────┘
factor chooser
[취소]
```

The factor chooser lists **only the factors the session policy allows for this purpose**, each with
a one-line explanation. A factor the person has not enrolled renders disabled with `등록되지 않음` —
visible so they know why it is not offered, never silently dropped.

> **Trap, and it bit us in production.** A *proactive* step-up must be constrained to the session
> policy's `reauthFactors`. A `SessionPolicy`'s factors are not an `AuthPolicy`'s login factors. If
> the modal offers password because the login policy allows it, `ReauthService` rejects it with 400
> and the admin loops forever. Read the session policy, not the auth policy.

### The four

| | Badge | Dismissable? | Notes |
|---|---|---|---|
| **Re-auth** | `본인 확인` (amber) | Esc + backdrop | Session is valid; this *action* is sensitive. States minutes since last auth |
| **Step-up / elevation** | `권한 승격 필요` (amber) | **No** — cancel button only | Entering the admin console. You elevate or you leave |
| **Step-up denied** | (red) | **No** | Shows attempts remaining. Says nothing about *why* it failed |
| **Destructive** | `되돌릴 수 없음` (red) | Esc + backdrop | Type-to-confirm |

**Destructive confirmation** enumerates the blast radius before asking: "사용자 418명, 애플리케이션
6개, 모든 정책과 감사 로그가 함께 삭제됩니다." For an irreversible action the confirm button stays
disabled until the exact slug is typed. It is never the autofocused element.

**Session expiry** shows a live countdown, because a static "60초" is a lie the moment it renders.
`계속 사용` / `지금 로그아웃`.

### Modal mechanics (all four)

`role="dialog"`, `aria-modal="true"`. Focus moves inside on open and **returns to the trigger on
close**. Tab cycles within. Esc closes — *unless* `dismissable: false`. Backdrop click closes —
*unless* `dismissable: false`. Exactly one modal open at a time.

---

## 8. The OIDC authorization flow

The end-user surface, and the only part of this product a person outside the organization ever sees:

```
organization → identify → factor(s) → [enroll] → consent → redirect
```

A centred `authpanel` on `--bg`, brand above, `Mini SSO가 보호하는 로그인` below. A four-step
`stepper` names the steps — `조직 · 로그인 · 인증 · 권한`. It is never `01 / 02 / 03`: the labels
carry information the numbers do not.

Every factor screen offers **다른 방법으로 인증** listing the other factors the policy allows, each
with one line of explanation. A failed factor shows the error on the field and the attempts
remaining — never *why* it failed.

**Context chips share a row, a height, and an optical weight.** The organization chip and a reason
badge (`앱 정책`) sit in a `.chiprow` flex row at `align-items: center`, both 32px tall. Never place
two inline pills side by side and let them baseline-align — the taller one drags the shorter one off
centre and they read as two unrelated components that happen to be adjacent. Match optical weight,
not pixel size: a filled avatar tile (22px) next to a 1.6px stroke glyph (15px) reads as equal; the
same number on both makes the glyph loom.

Screens: `조직 선택 · 로그인 · 비밀번호 · 인증 앱 · 이메일 코드 · 패스키 · 인증 앱 등록 · 추가 인증
· 동의 · 재동의 · 접근 거부 · 잘못된 요청 · 계정 잠금`.

### The consent screen

`/oauth2/consent`, rendered by `OidcConsentController` into
`sso-backend/src/main/resources/templates/consent.html`.

> **It is not React.** It is Thymeleaf under a CSP that forbids inline script. The whole interaction
> is checkboxes and two `<form method="post" action="/oauth2/authorize">` elements. **No JavaScript
> may be required for any part of it.** The input contract is fixed and must survive any redesign:
> checkbox `name="scope"`, hidden `client_id`, hidden `state`, optional `user_code`. A live verifier
> (`scripts/oidc_authcode_flow.py`) regexes the page for `name="state"` *before* `value=`, so keep
> that attribute order. **Do not add a CSRF field** — `/oauth2/authorize` is CSRF-exempt by design
> (the OAuth `state` is the protection); Spring's data-value processor injects a hidden `_csrf`
> anyway, which is harmless and pre-existing.
>
> That template carries its **own copy of the design tokens**. Changing `src/index.css` does not
> touch it. Any token change in §2 must be mirrored there, or this one screen stays indigo while the
> rest of the product moves on. (As of this writing, done — the redesign mirrors the §2 triples.)

Four rules, in order of importance:

1. **Name the application and where it will send you.** Phishing works by hiding the destination. A
   consent screen that omits the redirect host is complicit in it. Show `grafana.acme.io` in `--ink`
   bold, and mark the client `타사 애플리케이션` or `조직에서 등록한 애플리케이션`.
2. **`openid` is never a checkbox.** `ConsentModelService` drops it and the authorization server
   re-adds it. A checkbox the person can untick to no effect is a lie. Render it as a locked row
   with a muted tick and `해제할 수 없습니다`. An *unticked-looking* box is equally wrong — it reads
   as "not granted".
3. **Say what the application cannot do.** Consent is approved reflexively; the reassurance block is
   what makes anyone read at all: 비밀번호를 볼 수 없습니다 · 선택하지 않은 권한은 받지 못합니다 ·
   언제든 취소할 수 있습니다.
4. **An already-granted scope is context, not a decision.** Show it in a muted row with a tick.
   Never re-ask it. On re-consent, lead with `이 애플리케이션이 새로운 권한을 요청합니다` and let the
   new items be the only checkboxes.

Optional scopes (`offline_access`) default to **unchecked**. A scope the person did not ask for is
not a default.

Cancel and Allow are two submits on the form. Cancel is `btn-quiet` and sits left; Allow is
`btn-primary` (ink). Allow is never the only visible affordance, and never pre-focused.

Terminal outcomes get their own panel, never a redirect: `접근을 허용하지 않았습니다` (the app got
nothing), `이 로그인 요청을 처리할 수 없습니다` (unregistered redirect — and **we do not send you
back to the address it asked for**), `계정이 잠겼습니다`.

### Korean particles

Never interpolate a name in front of a particle that varies by final consonant. `{app}이(가)`,
`{app}은(는)`, `{app}(으)로` all leak the template into the UI. Rewrite so the particle is fixed:

| Bad | Good |
|---|---|
| `{app}이(가) 계정 접근을 요청합니다` | `{app}에서 계정 접근을 요청합니다` |
| `{app}(으)로 돌아가기` | `{app} 화면으로 돌아가기` |
| `{email}(으)로 보낸 코드` | `{email} 주소로 보낸 코드` |

---

## 9. Accessibility

Semantic HTML. Radix primitives give keyboard-navigable dialogs and menus — keep them.

- Visible `:focus-visible` on **everything** interactive: 2px `--accent`, 2px offset.
- Labelled inputs; `aria-invalid` + `aria-describedby` on errors; `role="alert"` on messages.
- Toggles are `role="switch"` with `aria-checked`, and answer Space and Enter.
- Toggleable chips and cards use `aria-pressed`. Tabs use `role="tab"` + `aria-selected`.
- Nav marks the current item with `aria-current="page"`.
- Colour is never the only signal — pair it with a shape, a word, or an icon.
- The command palette announces nothing it cannot execute.

---

## 10. Language

Both languages are first-class. Ship `ko` and `en` with **byte-identical key sets** — a key present
in one dictionary and absent in the other is a build failure, not a runtime fallback.

Interpolate with `{name}` placeholders. Never concatenate sentence fragments; Korean word order is
not English word order.

Write from the reader's side of the screen. Name things by what people control, not how the system
is built. Active voice. A control says exactly what happens: `변경사항 저장` → toast `저장했습니다`.
The same verb survives the whole flow.

Korean UI register: 간결한 명사형·평서체. `삭제` not `삭제하시겠습니까?`. No 존댓말 종결 on buttons.
Descriptions may use `~합니다` / `~하세요`.

Translated strings must respect §2's typography rules — an eyebrow that is uppercase in English is
not uppercase in Korean.

---

## 11. Applying this

Where it lands in the codebase:

| Concern | File |
|---|---|
| Tokens, both themes | `src/index.css` |
| Radius, colour aliases | `tailwind.config.js` |
| Button / badge / card / input variants | `src/components/ui/*` |
| Shell, topbar, sidebar, collapse, drawer | `src/components/layout/AppShell.tsx` |
| Nav model + icons | `src/components/layout/nav.ts` |
| Page title (the only one) | `src/components/PageHeader.tsx` |
| Loading / empty / error / DataList | `src/components/states.tsx` |
| Modals: confirm, re-auth, step-up | `src/components/ConfirmProvider.tsx`, `StepUpProvider.tsx` |
| Live sign-in preview | `src/pages/AuthPolicyDetail.tsx` |
| Charts | `src/components/charts/*` |

Known deletions this document requires:

1. `AppShell.tsx` — remove the topbar `<h1>{titleFor(pathname)}</h1>`. It duplicates every
   `PageHeader`.
2. `AppShell.tsx` — remove the second "Back to portal" (it exists in both the sidebar and topbar).
3. `AppShell.tsx` — the avatar's "Administrator / Member" label reads a `canEnterAdmin` boolean, not
   a role. Drop it or say something true.
4. `MetricTile.tsx` — the tinted-square icon tile. Replace with eyebrow + tabular figure + delta.
5. `index.css` — `--primary: 243 75% 59%`.

`npm run build` (`tsc && vite build`) is the gate. A design change that does not typecheck is not a
design change.

**Verify visually.** Chrome headless renders this app; a screenshot at 1366×768 and 768×1024 costs
seconds and catches what a green build cannot. Two cautions learned the hard way: a headless
screenshot captures CSS transitions **mid-flight**, so disable transitions before judging a frame;
and rewriting `document.body.innerHTML` destroys a `<style>` that lives in the body.

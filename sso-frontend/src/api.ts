import { clearAdminUnlock, getAdminToken } from "@/adminPortal";
import i18n from "@/i18n";

/** One page of a larger admin list — mirrors the backend `shared.Page` record. */
export interface Page<T> {
  total: number;
  page: number;
  size: number;
  items: T[];
}

/** Thrown for non-2xx responses so callers can branch on status (e.g. 401/403). */
export class ApiError extends Error {
  constructor(
    public status: number,
    message?: string,
    /** RFC 7807 `code` — the backend's stable error code (e.g. "invalid_cidr"). */
    public code?: string,
    /** RFC 7807 `traceId` — 12 hex chars the operator can grep server logs by (server/network failures only). */
    public traceId?: string,
  ) {
    super(message ?? `HTTP ${status}`);
  }
}

/**
 * Thrown when the user dismisses a step-up prompt (Cancel / X). It is NOT a failure: the pending action
 * is simply abandoned, with no side effects and no error surfaced ({@link errorMessage} maps it to "").
 */
export class StepUpCancelledError extends Error {
  constructor() {
    super("Step-up re-authentication was cancelled");
  }
}

/** Human-friendly copy for a caught request error, mapping known statuses to plain language. */
export function errorMessage(e: unknown): string {
  if (e instanceof StepUpCancelledError) {
    return ""; // cancelled step-up: nothing went wrong, so show no message
  }
  if (e instanceof ApiError) {
    // The server's ProblemDetail `detail` (when parse() captured one) is more precise than a status
    // line for input/conflict errors — e.g. "invalid CIDR: x" or "zone is referenced by a policy".
    const detail = e.message.startsWith("HTTP ") ? null : e.message;
    switch (e.status) {
      // The backend localizes these `detail` strings by Accept-Language, so prefer them; the rest are our
      // copy. A bare "Forbidden" hides the one thing a 403 usually needs to say — WHY, and what to do next.
      case 400: return detail ?? i18n.t("badRequest", { ns: "errors" });
      case 401: return i18n.t("unauthorized", { ns: "errors" });
      case 403: return detail ?? i18n.t("forbidden", { ns: "errors" });
      case 404: return i18n.t("notFound", { ns: "errors" });
      case 409: return detail ?? i18n.t("conflict", { ns: "errors" });
      default: return i18n.t("failed", { ns: "errors", status: e.status });
    }
  }
  return e instanceof Error ? e.message : String(e);
}

function csrfHeader(): Record<string, string> {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? { "X-XSRF-TOKEN": decodeURIComponent(match[1]) } : {};
}

/** Attach the admin elevation bearer (RFC 9470 proof) to admin API requests. */
function adminAuthHeader(path: string): Record<string, string> {
  if (path.startsWith("/api/admin")) {
    const token = getAdminToken();
    if (token) {
      return { Authorization: `Bearer ${token}` };
    }
  }
  return {};
}

/**
 * Drill-in: a platform super-admin scoping admin requests to one tenant. The drill-in store registers a
 * getter; when set, admin requests carry X-Org-Context so the backend scopes RLS to that org. Server-side
 * the header is honored only for a super-admin (a tenant admin is refused), so this is a UI convenience.
 */
let drillInOrgId: () => string | null = () => null;
export function registerDrillInOrgId(getter: () => string | null): void {
  drillInOrgId = getter;
}
function orgContextHeader(path: string): Record<string, string> {
  if (path.startsWith("/api/admin")) {
    const id = drillInOrgId();
    if (id) {
      return { "X-Org-Context": id };
    }
  }
  return {};
}

/**
 * The admin elevation gate (RFC 9470) rejects a missing/stale/foreign bearer with a 401 whose
 * WWW-Authenticate challenge names `insufficient_user_authentication`. When that happens, drop the
 * stale admin unlock and bounce to /admin so AdminGuard restarts the step-up + OIDC flow. Returns
 * true if it handled (and is navigating away) — callers should stop. Guards against loops: the full
 * navigation discards this JS context, and we only react to the specific elevation challenge.
 */
function handleElevationChallenge(path: string, res: Response): boolean {
  if (!path.startsWith("/api/admin") || res.status !== 401) {
    return false;
  }
  const challenge = res.headers.get("WWW-Authenticate") ?? "";
  if (!challenge.includes("insufficient_user_authentication")) {
    return false;
  }
  clearAdminUnlock();
  window.location.assign("/admin");
  return true;
}

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    // The backend answers RFC 7807 ProblemDetail — carry its `detail` so errorMessage() can show
    // the precise reason (e.g. "invalid CIDR: x") instead of a generic status line.
    const body = await res.text().catch(() => "");
    let detail: string | undefined;
    let code: string | undefined;
    let traceId: string | undefined;
    try {
      const problem = body ? (JSON.parse(body) as { detail?: string; code?: string; traceId?: string }) : {};
      detail = problem.detail;
      code = problem.code;
      traceId = problem.traceId;
    } catch {
      detail = undefined;
    }
    throw new ApiError(res.status, detail, code, traceId);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function apiGet<T>(path: string, retried = false): Promise<T> {
  lastActivityAt = Date.now(); // a read IS activity — resets the inactivity clocks the timers watch
  const res = await fetch(path, {
    credentials: "include",
    headers: { "Accept-Language": i18n.language, ...adminAuthHeader(path), ...orgContextHeader(path) },
  });
  // The mandatory session re-auth gates READS too, so a GET can be answered with a step-up challenge — prompt
  // the modal and retry, rather than surfacing it as a bare "unauthorized" (checked BEFORE the elevation bounce).
  if (await resolveStepUp(res, retried)) {
    return apiGet<T>(path, true); // retry once after re-authentication
  }
  if (handleElevationChallenge(path, res)) {
    return new Promise<T>(() => {}); // navigating away; never resolves
  }
  return parse<T>(res);
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return send<T>("POST", path, body);
}

export async function apiPut<T>(path: string, body?: unknown): Promise<T> {
  return send<T>("PUT", path, body);
}

export async function apiDelete(path: string): Promise<void> {
  return send<void>("DELETE", path);
}

/**
 * Step-up re-auth bridge: the StepUpProvider registers a handler that prompts the user for a fresh
 * factor. When a mutating request is rejected with X-Step-Up-Required, we run it and retry once.
 */
/**
 * Why a step-up prompt is shown — drives the modal copy and factor choice. "elevation" is the admin-console
 * entry: the minted token must assert acr=mfa, so a single-factor session must present a NEW factor (the
 * modal excludes factors the session already holds), unlike a plain freshness re-auth.
 */
export type StepUpReason = "action" | "session" | "elevation";

/** The modal prompts for a fresh factor; `factors` (when known) limits the offered methods to those the policy allows. */
export type StepUpHandler = (reason: StepUpReason, factors?: string[]) => Promise<boolean>;

let stepUpHandler: StepUpHandler | null = null;
const stepUpWaiters: Array<() => void> = [];
export function registerStepUpHandler(handler: StepUpHandler | null): void {
  stepUpHandler = handler;
  if (handler) {
    stepUpWaiters.splice(0).forEach((notify) => notify()); // release anyone who asked before we registered
  }
}
/**
 * Invoke the registered step-up modal. If the handler is not registered YET, WAIT for it rather than
 * resolving false — the StepUpProvider is a parent whose effect runs AFTER a child route's effect
 * (React runs child effects first), so AdminGuard on a fresh /admin load would otherwise see no
 * handler, get false, and bounce the user out. Times out to false if no modal ever appears.
 */
export function triggerStepUp(reason: StepUpReason = "session", factors?: string[]): Promise<boolean> {
  if (stepUpHandler) {
    return stepUpHandler(reason, factors);
  }
  return new Promise<boolean>((resolve) => {
    let settled = false;
    const run = () => {
      if (settled) return;
      settled = true;
      resolve(stepUpHandler ? stepUpHandler(reason, factors) : false);
    };
    stepUpWaiters.push(run);
    setTimeout(run, 3000);
  });
}

/**
 * If {@code res} is a step-up challenge (401 + {@code X-Step-Up-Required}) and a handler is registered, run the
 * re-auth modal. Returns true when the caller should RETRY the request once (re-auth succeeded); throws
 * {@link StepUpCancelledError} when the user declined a cancelable (action) step-up. Returns false to fall
 * through to normal handling — and in that case the response body is left UNREAD, so the caller can still parse
 * it. Shared by every verb, so a step-up challenge on a GET (the mandatory session re-auth gates reads too)
 * prompts the modal exactly as it does on a mutation, instead of surfacing a bare 401.
 */
async function resolveStepUp(res: Response, retried: boolean): Promise<boolean> {
  if (res.status !== 401 || retried || !stepUpHandler || res.headers.get("X-Step-Up-Required") !== "true") {
    return false;
  }
  const challenge = await res.json().catch(() => ({} as { factors?: string[]; mandatory?: boolean }));
  const factors = Array.isArray(challenge.factors) ? challenge.factors : undefined;
  // `mandatory` = the session's periodic re-auth is overdue (server-enforced, non-cancelable); otherwise a
  // sensitive action the user may still decline.
  if (await stepUpHandler(challenge.mandatory ? "session" : "action", factors)) {
    return true;
  }
  throw new StepUpCancelledError();
}

/** Wall-clock of the last API request — the client re-auth/idle timers measure inactivity from here. */
let lastActivityAt = Date.now();
export function lastActivityMillis(): number {
  return lastActivityAt;
}
/** Reset the inactivity clock without a request — "Stay signed in" on the idle countdown pairs it with a real ping. */
export function markActivity(): void {
  lastActivityAt = Date.now();
}

async function send<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
  lastActivityAt = Date.now(); // a request IS activity — resets the inactivity clocks the timers watch
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers: {
      "Content-Type": "application/json", "Accept-Language": i18n.language,
      ...csrfHeader(), ...adminAuthHeader(path), ...orgContextHeader(path),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (await resolveStepUp(res, retried)) {
    return send<T>(method, path, body, true); // retry once after re-authentication
  }
  if (handleElevationChallenge(path, res)) {
    return new Promise<T>(() => {}); // navigating away; never resolves
  }
  return parse<T>(res);
}

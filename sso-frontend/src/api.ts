import { clearAdminUnlock, getAdminToken } from "@/adminPortal";

/** One page of a larger admin list — mirrors the backend `shared.Page` record. */
export interface Page<T> {
  total: number;
  page: number;
  size: number;
  items: T[];
}

/** Thrown for non-2xx responses so callers can branch on status (e.g. 401/403). */
export class ApiError extends Error {
  constructor(public status: number, message?: string) {
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
      case 400: return detail ?? "Invalid input — please check the form.";
      case 401: return "Re-authentication required — please retry.";
      case 403: return "You don't have permission for this action.";
      case 404: return "Not found — it may have been removed.";
      case 409: return detail ?? "Conflict — the change wasn't applied.";
      default: return `Request failed (${e.status}).`;
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
    try {
      detail = body ? (JSON.parse(body) as { detail?: string }).detail : undefined;
    } catch {
      detail = undefined;
    }
    throw new ApiError(res.status, detail);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(path, {
    credentials: "include",
    headers: { ...adminAuthHeader(path), ...orgContextHeader(path) },
  });
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

/** Wall-clock of the last API request — the client re-auth/idle timers measure inactivity from here. */
let lastActivityAt = Date.now();
export function lastActivityMillis(): number {
  return lastActivityAt;
}

async function send<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
  lastActivityAt = Date.now(); // a request IS activity — resets the inactivity clocks the timers watch
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers: {
      "Content-Type": "application/json", ...csrfHeader(), ...adminAuthHeader(path), ...orgContextHeader(path),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401 && res.headers.get("X-Step-Up-Required") === "true" && !retried && stepUpHandler) {
    const challenge = await res.json().catch(() => ({} as { factors?: string[]; mandatory?: boolean }));
    const factors = Array.isArray(challenge.factors) ? challenge.factors : undefined;
    // `mandatory` = the session's periodic re-auth is overdue (server-enforced, non-cancelable);
    // otherwise a sensitive action triggered a step-up the user may still decline.
    const ok = await stepUpHandler(challenge.mandatory ? "session" : "action", factors);
    if (ok) {
      return send<T>(method, path, body, true); // retry once after re-authentication
    }
    // A declined action: abandon it with no side effects — NOT a logout, NOT a surfaced error.
    throw new StepUpCancelledError();
  }
  if (handleElevationChallenge(path, res)) {
    return new Promise<T>(() => {}); // navigating away; never resolves
  }
  return parse<T>(res);
}

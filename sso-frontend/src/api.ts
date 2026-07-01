import { clearAdminUnlock, getAdminToken } from "@/adminPortal";

/** Thrown for non-2xx responses so callers can branch on status (e.g. 401/403). */
export class ApiError extends Error {
  constructor(public status: number, message?: string) {
    super(message ?? `HTTP ${status}`);
  }
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
    throw new ApiError(res.status);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(path, {
    credentials: "include",
    headers: { ...adminAuthHeader(path) },
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
/** Why a step-up prompt is shown — drives the modal copy. */
export type StepUpReason = "action" | "session";

let stepUpHandler: ((reason: StepUpReason) => Promise<boolean>) | null = null;
const stepUpWaiters: Array<() => void> = [];
export function registerStepUpHandler(handler: ((reason: StepUpReason) => Promise<boolean>) | null): void {
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
export function triggerStepUp(reason: StepUpReason = "session"): Promise<boolean> {
  if (stepUpHandler) {
    return stepUpHandler(reason);
  }
  return new Promise<boolean>((resolve) => {
    let settled = false;
    const run = () => {
      if (settled) return;
      settled = true;
      resolve(stepUpHandler ? stepUpHandler(reason) : false);
    };
    stepUpWaiters.push(run);
    setTimeout(run, 3000);
  });
}

async function send<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers: { "Content-Type": "application/json", ...csrfHeader(), ...adminAuthHeader(path) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401 && res.headers.get("X-Step-Up-Required") === "true" && !retried && stepUpHandler) {
    const ok = await stepUpHandler("action"); // a sensitive request triggered this
    if (ok) {
      return send<T>(method, path, body, true); // retry once after re-authentication
    }
  }
  if (handleElevationChallenge(path, res)) {
    return new Promise<T>(() => {}); // navigating away; never resolves
  }
  return parse<T>(res);
}

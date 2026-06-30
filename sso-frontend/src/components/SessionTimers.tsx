import { useEffect, useRef } from "react";
import { triggerStepUp } from "@/api";
import { logout } from "@/auth";
import { getSessionConfig } from "@/portal";

/**
 * Client-side enforcement of the session policy timers (mounted while signed in):
 *  - idle timeout: after N minutes of no user activity, the session is ended and the user returns to login;
 *  - re-auth interval: every N minutes a central re-auth modal is shown requiring the configured factor;
 *    cancelling it signs the user out.
 */
export function SessionTimers() {
  const idle = useRef<number | undefined>(undefined);
  const reauth = useRef<number | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    const events = ["mousemove", "mousedown", "keydown", "scroll", "touchstart"];
    let idleMs = 0;

    async function signOut() {
      try { await logout(); } catch { /* ignore */ }
      window.location.href = "/login";
    }
    function resetIdle() {
      window.clearTimeout(idle.current);
      if (idleMs > 0) idle.current = window.setTimeout(signOut, idleMs);
    }
    function scheduleReauth(ms: number) {
      window.clearTimeout(reauth.current);
      reauth.current = window.setTimeout(async () => {
        const ok = await triggerStepUp("session");
        if (cancelled) return;
        if (ok) scheduleReauth(ms);   // re-armed after a successful re-authentication
        else signOut();                // declined -> sign out
      }, ms);
    }

    getSessionConfig().then((cfg) => {
      if (cancelled) return;
      idleMs = cfg.idleTimeoutMinutes * 60_000;
      events.forEach((e) => window.addEventListener(e, resetIdle, { passive: true }));
      resetIdle();
      if (cfg.reauthIntervalMinutes > 0) scheduleReauth(cfg.reauthIntervalMinutes * 60_000);
    }).catch(() => undefined);

    return () => {
      cancelled = true;
      events.forEach((e) => window.removeEventListener(e, resetIdle));
      window.clearTimeout(idle.current);
      window.clearTimeout(reauth.current);
    };
  }, []);

  return null;
}

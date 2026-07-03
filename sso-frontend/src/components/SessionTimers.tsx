import { useEffect, useRef } from "react";
import { lastActivityMillis, triggerStepUp } from "@/api";
import { logout } from "@/auth";
import { getSessionConfig } from "@/portal";

/**
 * Client-side enforcement of the session policy timers (mounted while signed in). Both measure inactivity
 * as time since the last API REQUEST (matching the server, which resets its clocks on each request):
 *  - idle timeout: after N minutes with no request the session is ended and the user returns to login;
 *  - re-auth interval: after N minutes with no request the MANDATORY re-auth modal appears on its own —
 *    no request needed. This is only the proactive nudge; the server (SessionIntegrityFilter) also refuses
 *    protected requests until the re-auth is done, so the modal cannot be bypassed by dismissing it.
 */
export function SessionTimers() {
  const ticker = useRef<number | undefined>(undefined);
  const prompting = useRef(false); // a re-auth modal is already open — don't stack another

  useEffect(() => {
    let cancelled = false;

    async function signOut() {
      try { await logout(); } catch { /* ignore */ }
      window.location.href = "/login";
    }

    getSessionConfig().then((cfg) => {
      if (cancelled) return;
      const idleMs = cfg.idleTimeoutMinutes * 60_000;
      const reauthMs = cfg.reauthIntervalMinutes * 60_000;

      async function promptReauth() {
        if (cancelled || prompting.current) return;
        prompting.current = true;
        try {
          await triggerStepUp("session", cfg.reauthFactors); // mandatory modal, in place, with the allowed factors
        } finally {
          prompting.current = false;
        }
      }

      function tick() {
        if (cancelled) return;
        const idleFor = Date.now() - lastActivityMillis();
        if (idleMs > 0 && idleFor >= idleMs) {
          void signOut();
          return;
        }
        if (reauthMs > 0 && idleFor >= reauthMs) {
          void promptReauth(); // once past the interval with no request, prompt (guards against re-entry)
        }
        ticker.current = window.setTimeout(tick, 1_000);
      }
      tick();
    }).catch(() => undefined);

    return () => {
      cancelled = true;
      window.clearTimeout(ticker.current);
    };
  }, []);

  return null;
}

import { useEffect, useRef, useState } from "react";
import { Clock } from "lucide-react";
import { lastActivityMillis, markActivity, triggerStepUp } from "@/api";
import { logout } from "@/auth";
import { getSessionConfig } from "@/portal";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";

/** The idle countdown appears this long before the session is dropped, so the reader can act. */
const WARN_MS = 60_000;

async function signOut() {
  try { await logout(); } catch { /* ignore */ }
  window.location.href = "/login";
}

/**
 * Client-side enforcement of the session policy timers (mounted while signed in). Both measure inactivity
 * as time since the last API REQUEST (matching the server, which resets its clocks on each request):
 *  - idle timeout: a live countdown modal appears in the final minute; if it lapses the session ends;
 *  - re-auth interval: after N minutes with no request the MANDATORY re-auth modal appears on its own —
 *    no request needed. This is only the proactive nudge; the server (SessionIntegrityFilter) also refuses
 *    protected requests until the re-auth is done, so the modal cannot be bypassed by dismissing it.
 */
export function SessionTimers() {
  const ticker = useRef<number | undefined>(undefined);
  const prompting = useRef(false); // a re-auth modal is already open — don't stack another
  // Seconds left before idle sign-out, or null when outside the warning window. A LIVE value — a static
  // "60 seconds" would be a lie the moment it rendered (DESIGN.md §7).
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;

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
        if (idleMs > 0) {
          const remaining = idleMs - idleFor;
          if (remaining <= 0) {
            setSecondsLeft(null);
            void signOut();
            return;
          }
          setSecondsLeft(remaining <= WARN_MS ? Math.ceil(remaining / 1000) : null);
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

  function stay() {
    markActivity();                 // reset the client clock immediately
    void getSessionConfig();        // and ping the server so its idle clock resets too
    setSecondsLeft(null);
  }

  return (
    <Dialog open={secondsLeft !== null} onOpenChange={() => { /* explicit choice only — not dismissable */ }}>
      <DialogContent
        className="max-w-sm"
        hideClose
        onEscapeKeyDown={(e) => e.preventDefault()}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <div className="flex items-start gap-3">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-warn/10 text-warn">
              <Clock className="size-5" />
            </span>
            <div className="space-y-1">
              <DialogTitle>Still there?</DialogTitle>
              <DialogDescription>
                You'll be signed out in <span className="font-semibold text-ink" data-idle-countdown>{secondsLeft ?? 0}</span>{" "}
                seconds due to inactivity.
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="outline" onClick={() => void signOut()}>Sign out now</Button>
          <Button onClick={stay}>Stay signed in</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

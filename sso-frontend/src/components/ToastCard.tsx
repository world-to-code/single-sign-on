import { useCallback, useEffect, useRef, useState } from "react";
import type { AnimationEvent } from "react";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ToastItem, ToastTone } from "./ToastProvider";

/** Lifetimes per DESIGN.md §5. The JS timer here MUST match the CSS `.toast-drain[data-life]` duration. */
const LIFE_MS: Record<ToastTone, number> = { success: 4500, warning: 7000, error: 9000 };

function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(
    () => typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  );
  useEffect(() => {
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setReduced(mq.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);
  return reduced;
}

/** The pip is the ONLY status signal (DESIGN.md §5): filled allow/warn dot, hollow deny ring for errors. */
function Pip({ tone }: { tone: ToastTone }) {
  if (tone === "error") return <span className="size-2.5 rounded-full border-2 border-deny" />;
  return <span className={cn("size-2.5 rounded-full", tone === "success" ? "bg-allow" : "bg-warn")} />;
}

/**
 * A single toast. Card material only (never an inverted ink pill), status said once by the pip, no
 * coloured rail, and the accent never appears (DESIGN.md §5). Hover/focus pauses BOTH the drain bar
 * (CSS, via data-paused) and the JS dismissal timer, so a toast can't vanish under a reaching pointer.
 * Removal never depends on animationend alone — a fallback timer covers a suppressed exit animation.
 */
export function ToastCard({ toast, onDismiss }: { toast: ToastItem; onDismiss: (id: number) => void }) {
  const reducedMotion = usePrefersReducedMotion();
  const life = LIFE_MS[toast.tone];
  const [paused, setPaused] = useState(false);
  const [exiting, setExiting] = useState(false);
  const remaining = useRef(life);
  const startedAt = useRef(Date.now());
  const rootRef = useRef<HTMLDivElement>(null);

  const finalize = useCallback(() => onDismiss(toast.id), [onDismiss, toast.id]);

  const dismiss = useCallback(() => {
    if (reducedMotion) {
      finalize();
      return;
    }
    setExiting(true);
  }, [reducedMotion, finalize]);

  // Life timer. Banks elapsed time on pause so resume continues the same countdown the bar shows.
  useEffect(() => {
    if (paused || exiting) return;
    startedAt.current = Date.now();
    const timer = window.setTimeout(dismiss, remaining.current);
    return () => {
      window.clearTimeout(timer);
      if (!exiting) remaining.current -= Date.now() - startedAt.current;
    };
  }, [paused, exiting, dismiss]);

  // Fallback removal — if the exit animation is suppressed/interrupted, animationend never fires.
  useEffect(() => {
    if (!exiting) return;
    const fallback = window.setTimeout(finalize, 400);
    return () => window.clearTimeout(fallback);
  }, [exiting, finalize]);

  // Native listeners (not React synthetic) so the pause is deterministic and directly testable.
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const pause = () => setPaused(true);
    const resume = () => setPaused(false);
    el.addEventListener("mouseenter", pause);
    el.addEventListener("mouseleave", resume);
    el.addEventListener("focusin", pause);
    el.addEventListener("focusout", resume);
    return () => {
      el.removeEventListener("mouseenter", pause);
      el.removeEventListener("mouseleave", resume);
      el.removeEventListener("focusin", pause);
      el.removeEventListener("focusout", resume);
    };
  }, []);

  const onAnimationEnd = (e: AnimationEvent<HTMLDivElement>) => {
    if (exiting && e.animationName === "toast-exit") finalize();
  };

  return (
    <div
      ref={rootRef}
      data-toast
      role={toast.tone === "error" ? "alert" : "status"}
      onAnimationEnd={onAnimationEnd}
      className={cn(
        "pointer-events-auto relative w-full max-w-[520px] overflow-hidden rounded-[14px] border border-line bg-surface shadow-lg",
        exiting ? "toast-exit" : "toast-enter",
      )}
    >
      <div className="flex items-center gap-3 px-4 py-3">
        <span className="flex size-5 shrink-0 items-center justify-center">
          <Pip tone={toast.tone} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-ink">{toast.title}</p>
          {toast.description && <p className="mt-0.5 text-[13px] text-muted-foreground">{toast.description}</p>}
        </div>
        {toast.action && (
          <button
            type="button"
            data-toast-action
            onClick={() => {
              toast.action?.onClick();
              dismiss();
            }}
            className="shrink-0 rounded-md bg-line-soft px-2.5 py-1.5 text-xs font-medium text-ink transition-colors hover:bg-line-soft/70"
          >
            {toast.action.label}
          </button>
        )}
        <button
          type="button"
          aria-label="Dismiss"
          onClick={dismiss}
          className="shrink-0 rounded-md p-1 text-faint transition-colors hover:text-ink"
        >
          <X className="size-4" />
        </button>
      </div>
      {reducedMotion ? (
        <span data-toast-bar className="absolute inset-x-0 bottom-0 h-0.5 bg-line" />
      ) : (
        <span
          data-toast-bar
          data-life={toast.tone}
          data-paused={paused}
          className="toast-drain absolute inset-x-0 bottom-0 h-0.5 bg-line"
        />
      )}
    </div>
  );
}

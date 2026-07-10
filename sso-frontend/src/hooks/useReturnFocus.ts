import { useCallback, useRef } from "react";

/**
 * Restores keyboard focus to whatever opened a dialog.
 *
 * Radix normally does this for you, but only when the dialog was opened from a `DialogTrigger`. The
 * confirm and step-up dialogs open programmatically — from a promise-returning hook and from the API
 * layer's step-up challenge — so Radix has no trigger to return to and drops focus on `<body>`. That
 * strands a keyboard user at the top of the page after every prompt.
 *
 * Capture on open, restore on close. The restore is deferred a frame because Radix moves focus itself
 * while unmounting, and the last write wins.
 */
export function useReturnFocus() {
  const origin = useRef<HTMLElement | null>(null);

  const capture = useCallback(() => {
    origin.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }, []);

  const restore = useCallback(() => {
    const el = origin.current;
    origin.current = null;
    // isConnected: the trigger may have been unmounted by the very action the dialog confirmed.
    if (el?.isConnected) requestAnimationFrame(() => el.focus());
  }, []);

  return { capture, restore };
}

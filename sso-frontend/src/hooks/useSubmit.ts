import { useCallback, useState } from "react";
import { errorMessage } from "@/api";

/**
 * The busy/error bookkeeping around a single write.
 *
 * <p>Extracted after the third copy. BrandingEditor, ScreenCopyEditor and EmailTemplateEditor each carried
 * the same `setBusy(true)` / `try` / `catch → errorMessage` / `finally → setBusy(false)` block, and they had
 * already drifted — only one of them refreshed the shell after a write. Two copies is a judgement call;
 * three, with a divergence, is not.
 *
 * <p>It stays a hook rather than a shared `<Editor>` component on purpose. The three editors have different
 * resources, endpoints and revert semantics, and a component wrapping them would be an abstraction over a
 * coincidence. What they genuinely share is this: while a write is in flight the form is disabled, and when
 * it fails the reason belongs on screen rather than in the console.
 *
 * <p>`run` resolves to whether the work succeeded, so the caller can decide what happens next — a toast, a
 * reload, a refresh — instead of this hook guessing. It never rethrows: an editor that let the rejection
 * escape would leave `busy` true and the form permanently disabled, which is the failure a `finally` exists
 * to prevent and the one nobody notices in review.
 */
export function useSubmit(): {
  busy: boolean;
  error: string | null;
  setError: (message: string | null) => void;
  run: (work: () => Promise<unknown>) => Promise<boolean>;
} {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async (work: () => Promise<unknown>): Promise<boolean> => {
    setBusy(true);
    setError(null); // a retry showing the previous failure reads as "it failed again"
    try {
      await work();
      return true;
    } catch (e) {
      setError(errorMessage(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  return { busy, error, setError, run };
}

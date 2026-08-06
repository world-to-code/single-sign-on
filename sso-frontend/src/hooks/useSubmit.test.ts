import { describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useSubmit } from "./useSubmit";

/**
 * The same twelve lines were written three times — BrandingEditor, ScreenCopyEditor, EmailTemplateEditor —
 * and had already drifted: only one of them refreshed the shell after a write. Three copies is where the
 * duplication stops being cheaper than the abstraction, and the drift is the evidence.
 *
 * <p>What the hook owes its callers is narrow and each part is asserted here: `busy` is true only while the
 * work runs, a failure becomes a MESSAGE rather than an unhandled rejection, a new attempt clears the
 * previous error, and `busy` is released on both paths — a stuck spinner is the failure a `finally` exists
 * to prevent and the one nobody notices in review.
 */
describe("useSubmit", () => {
  it("is idle with no error before anything runs", () => {
    const { result } = renderHook(() => useSubmit());

    expect(result.current.busy).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it("reports busy while the work runs and releases it on success", async () => {
    let finish: () => void = () => {};
    const work = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    const { result } = renderHook(() => useSubmit());

    act(() => { void result.current.run(work); });
    await waitFor(() => expect(result.current.busy).toBe(true));

    await act(async () => { finish(); });
    expect(result.current.busy).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it("turns a failure into a message and still releases busy", async () => {
    const { result } = renderHook(() => useSubmit());

    await act(async () => {
      await result.current.run(() => Promise.reject(new Error("nope")));
    });

    expect(result.current.error).toBeTruthy();
    expect(result.current.busy).toBe(false);
  });

  /** A retry that still showed the previous failure would read as "it failed again". */
  it("clears the previous error when a new attempt starts", async () => {
    const { result } = renderHook(() => useSubmit());
    await act(async () => {
      await result.current.run(() => Promise.reject(new Error("nope")));
    });
    expect(result.current.error).toBeTruthy();

    await act(async () => { await result.current.run(() => Promise.resolve()); });

    expect(result.current.error).toBeNull();
  });

  /** The caller decides what happens next, so it has to be told which way the attempt went. */
  it("tells the caller whether the work succeeded", async () => {
    const { result } = renderHook(() => useSubmit());
    let ok: boolean | undefined;
    let failed: boolean | undefined;

    await act(async () => { ok = await result.current.run(() => Promise.resolve()); });
    await act(async () => { failed = await result.current.run(() => Promise.reject(new Error("nope"))); });

    expect(ok).toBe(true);
    expect(failed).toBe(false);
  });
});

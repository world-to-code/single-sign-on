import { createContext, useCallback, useContext, useRef, useState } from "react";
import type { ReactNode } from "react";
import { ToastCard } from "./ToastCard";

export type ToastTone = "success" | "warning" | "error";

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastOptions {
  tone: ToastTone;
  title: string;
  description?: string;
  action?: ToastAction;
}

export interface ToastItem extends ToastOptions {
  id: number;
}

type ToastFn = (opts: ToastOptions) => void;
const ToastContext = createContext<ToastFn | null>(null);

/**
 * Imperative toast API — `useToast()(opts)` — mirroring ConfirmProvider's shape. Toasts stack at the
 * bottom centre; the newest sits LOWEST (rendered last in a bottom-anchored column, DESIGN.md §5).
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const nextId = useRef(1);

  const toast = useCallback<ToastFn>((opts) => {
    setToasts((prev) => [...prev, { ...opts, id: nextId.current++ }]);
  }, []);

  const dismiss = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={toast}>
      {children}
      <div
        data-toast-viewport
        className="pointer-events-none fixed inset-x-0 bottom-0 z-[100] flex flex-col items-center gap-2 p-4"
      >
        {toasts.map((t) => (
          <ToastCard key={t.id} toast={t} onDismiss={dismiss} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastFn {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within ToastProvider");
  return ctx;
}

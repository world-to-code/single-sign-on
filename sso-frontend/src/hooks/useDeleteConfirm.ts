import { useCallback } from "react";
import { apiDelete } from "@/api";
import { useConfirm } from "@/components/ConfirmProvider";

export interface DeleteRequest {
  title: string;
  description: string;
  confirmText?: string;
  /** apiDelete this path... */
  path?: string;
  /** ...or run a custom deletion (e.g. a typed helper). */
  run?: () => Promise<void>;
  onDeleted: () => void;
}

/**
 * Returns a `confirmDelete(request)` that shows the shared destructive confirm dialog and, on
 * confirmation, deletes (via `path` → apiDelete, or a custom `run`) and calls `onDeleted` to reload.
 */
export function useDeleteConfirm() {
  const confirm = useConfirm();
  return useCallback(async (request: DeleteRequest) => {
    const ok = await confirm({
      title: request.title,
      description: request.description,
      confirmText: request.confirmText ?? "Delete",
      variant: "destructive",
    });
    if (!ok) return;
    if (request.path) await apiDelete(request.path);
    else if (request.run) await request.run();
    request.onDeleted();
  }, [confirm]);
}

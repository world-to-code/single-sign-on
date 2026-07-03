import { useState } from "react";
import type { Dispatch, FormEvent, SetStateAction } from "react";
import { errorMessage } from "@/api";

export interface EditorForm<E> {
  editor: E;
  set: (patch: Partial<E>) => void;
  setEditor: Dispatch<SetStateAction<E>>;
  open: boolean;
  setOpen: Dispatch<SetStateAction<boolean>>;
  error: string | null;
  setError: Dispatch<SetStateAction<string | null>>;
  openCreate: () => void;
  openEdit: (editor: E) => void;
  save: (event: FormEvent) => Promise<void>;
}

/**
 * Create/edit dialog state shared by the CRUD pages: holds the editor draft + dialog open state,
 * and runs `create` (POST) or `update` (PUT, when the draft has an id) from `toRequest(editor)`,
 * then clears the draft, closes the dialog and calls `onSaved` to reload. Each page supplies its
 * own blank draft, request-body mapping and endpoints so the exact wire shape is preserved.
 */
export function useEditorForm<E extends { id: string | null }>(opts: {
  blank: E;
  toRequest: (editor: E) => unknown;
  create: (body: unknown) => Promise<unknown>;
  update: (id: string, body: unknown) => Promise<unknown>;
  onSaved: () => void;
}): EditorForm<E> {
  const { blank, toRequest, create, update, onSaved } = opts;
  const [editor, setEditor] = useState<E>(blank);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const set = (patch: Partial<E>) => setEditor((e) => ({ ...e, ...patch }));
  const openCreate = () => { setError(null); setEditor(blank); setOpen(true); };
  const openEdit = (next: E) => { setError(null); setEditor(next); setOpen(true); };

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      const body = toRequest(editor);
      if (editor.id) await update(editor.id, body);
      else await create(body);
      setEditor(blank);
      setOpen(false);
      onSaved();
    } catch (e) {
      // A cancelled step-up maps to "" (no message): the dialog stays open with the draft intact.
      setError(errorMessage(e));
    }
  };

  return { editor, set, setEditor, open, setOpen, error, setError, openCreate, openEdit, save };
}

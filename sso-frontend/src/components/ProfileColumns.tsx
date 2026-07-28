import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { errorMessage } from "@/api";
import {
  getProfileAttributes, saveProfileAttributes, type ProfileColumn,
} from "@/profileAttributes";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * The person as their profile describes them — the first thing the detail page shows, because "who is this"
 * is the question an administrator opened it to answer, and a generic key/value list at the bottom of the
 * page was not it.
 *
 * The profile's own order is honoured: base columns (username, email, display name, …) first, then the
 * tenant's own in `sortOrder`. Columns the form cannot write — a base field, whose edit dialog owns it, and a
 * directory-owned one, whose connector does — are shown read-only rather than hidden, since "what does this
 * person have" includes the parts this screen is not allowed to change.
 */
export function ProfileColumns({ userId, profileId, onSaved }:
  { userId: string; profileId: string | null; onSaved?: () => void }) {
  const { t } = useTranslation("console");
  const [columns, setColumns] = useState<ProfileColumn[]>([]);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    getProfileAttributes(userId)
      .then((data) => {
        setColumns(data.columns);
        setDraft(Object.fromEntries(data.columns.map((c) => [c.key, c.values.join(", ")])));
      })
      .catch((e) => setError(errorMessage(e)));
  };

  // Re-read on a profile change too: the columns ARE the profile, so the old list would describe a schema this
  // person no longer has — including columns the move deleted.
  useEffect(load, [userId, profileId]);

  const save = () => {
    setBusy(true);
    setError(null);
    // Only the writable columns travel, and every one of them does even when blank — see the note on
    // saveProfileAttributes: a blank is how the form says "unset this one".
    const values = Object.fromEntries(columns
      .filter((c) => c.editable)
      .map((c) => [c.key, splitValues(draft[c.key] ?? "", c.multiValued)]));
    saveProfileAttributes(userId, values)
      .then((data) => {
        setColumns(data.columns);
        setEditing(false);
        onSaved?.();
      })
      .catch((e) => setError(errorMessage(e)))
      .finally(() => setBusy(false));
  };

  const cancel = () => {
    setDraft(Object.fromEntries(columns.map((c) => [c.key, c.values.join(", ")])));
    setEditing(false);
    setError(null);
  };

  return (
    <div className="space-y-4">
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

      <div className="flex justify-end gap-2">
        {editing ? (
          <>
            <Button variant="outline" size="sm" onClick={cancel} disabled={busy}>{t("cancel")}</Button>
            <Button size="sm" onClick={save} disabled={busy}>{t("save")}</Button>
          </>
        ) : (
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}>{t("userDetailEdit")}</Button>
        )}
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        {columns.map((column) => (
          <div key={column.key} className="space-y-1.5">
            <Label htmlFor={`col-${column.key}`} className="flex items-center gap-2">
              {column.displayName}
              {column.required && <Badge variant="muted">{t("profileColumnRequired")}</Badge>}
              {!column.editable && <Badge variant="muted">{t("profileColumnReadOnly")}</Badge>}
            </Label>
            {editing && column.editable ? (
              <Editor column={column} value={draft[column.key] ?? ""}
                      onChange={(v) => setDraft({ ...draft, [column.key]: v })} />
            ) : (
              <p className="text-sm">{column.values.join(", ") || <Unset />}</p>
            )}
            {column.description && <p className="text-xs text-muted-foreground">{column.description}</p>}
          </div>
        ))}
      </div>
    </div>
  );
}

function Unset() {
  const { t } = useTranslation("console");
  return <span className="text-muted-foreground">{t("profileColumnUnset")}</span>;
}

function Editor({ column, value, onChange }:
  { column: ProfileColumn; value: string; onChange: (value: string) => void }) {
  const { t } = useTranslation("console");
  const id = `col-${column.key}`;

  if (column.dataType === "ENUM" && !column.multiValued) {
    return (
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm">
        <option value="">{t("profileColumnUnset")}</option>
        {column.enumValues.map((v) => <option key={v} value={v}>{v}</option>)}
      </select>
    );
  }
  // A multi-valued column is comma-separated: the type system already says several values are legal, and a
  // row-per-value widget would cost more than it buys on a form this dense.
  const type = column.multiValued ? "text"
    : column.dataType === "DATE" ? "date"
    : column.dataType === "INTEGER" ? "number" : "text";
  return (
    <Input id={id} type={type} value={value} onChange={(e) => onChange(e.target.value)}
           placeholder={column.multiValued ? t("profileColumnMultiHint") : undefined} />
  );
}

/** Comma-separated for a multi-valued column; a single-valued one keeps its commas. */
function splitValues(raw: string, multiValued: boolean): string[] {
  if (!multiValued) {
    return raw.trim() ? [raw.trim()] : [];
  }
  return raw.split(",").map((v) => v.trim()).filter((v) => v !== "");
}

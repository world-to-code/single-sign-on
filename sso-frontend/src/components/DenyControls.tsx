import { useState } from "react";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { errorMessage } from "@/api";
import { createDeny, liftDeny, type DenyRow, type DenySubjectKind } from "@/denies";

type Props = {
  kind: DenySubjectKind;
  subjectId: string;
  /** Permissions the subject holds/grants — the ones an admin can withhold with a deny. */
  candidates: string[];
  denies: DenyRow[];
  onChanged: () => void;
};

/**
 * Author and lift denies on one subject (a user, role, group or org). The server owns the authorization (a
 * caller lacking the permission gets a 403 surfaced here) and the last-admin guard, so this stays a thin
 * form + list.
 */
export function DenyControls({ kind, subjectId, candidates, denies, onChanged }: Props) {
  const { t } = useTranslation("console");
  const deniedPatterns = new Set(denies.map((d) => d.pattern));
  const options = candidates.filter((p) => !deniedPatterns.has(p));
  const [pattern, setPattern] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-4 space-y-2 border-t border-border pt-3">
      <p className="text-xs font-medium text-muted-foreground">{t("userDetailDenyManage")}</p>
      {denies.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {denies.map((d) => (
            <Badge key={d.id} variant="destructive" className="gap-1 font-mono text-xs">
              {d.pattern}
              <button type="button" disabled={busy} aria-label={`${t("userDetailDenyLift")} ${d.pattern}`}
                      onClick={() => run(() => liftDeny(d.id, kind))} className="hover:opacity-70">
                <X className="size-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}
      <div className="flex gap-2">
        <Select aria-label={t("userDetailDenyPick")} value={pattern} disabled={busy || options.length === 0}
                onChange={(e) => setPattern(e.target.value)} className="max-w-xs">
          <option value="">{t("userDetailDenyPick")}</option>
          {options.map((p) => <option key={p} value={p}>{p}</option>)}
        </Select>
        <Button type="button" variant="destructive" size="sm" disabled={busy || !pattern}
                onClick={() => run(async () => { await createDeny(kind, subjectId, pattern); setPattern(""); })}>
          {t("userDetailDenyAdd")}
        </Button>
      </div>
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

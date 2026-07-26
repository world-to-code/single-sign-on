import { useState } from "react";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { errorMessage } from "@/api";
import { createDeny, liftDeny, type UserDenyRow } from "@/denies";

type Props = {
  userId: string;
  /** Permissions the user effectively holds — the ones an admin can withhold with a deny. */
  candidates: string[];
  userDenies: UserDenyRow[];
  onChanged: () => void;
};

/**
 * Author and lift USER-level denies for one user. The server owns the authorization (a caller lacking the
 * permission gets a 403 surfaced here) and the last-admin guard, so this stays a thin form + list.
 */
export function DenyControls({ userId, candidates, userDenies, onChanged }: Props) {
  const { t } = useTranslation("console");
  const denied = new Set(userDenies.map((d) => d.pattern));
  const options = candidates.filter((p) => !denied.has(p));
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
      {userDenies.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {userDenies.map((d) => (
            <Badge key={d.id} variant="destructive" className="gap-1 font-mono text-xs">
              {d.pattern}
              <button type="button" disabled={busy} aria-label={`${t("userDetailDenyLift")} ${d.pattern}`}
                      onClick={() => run(() => liftDeny(d.id, "USER"))} className="hover:opacity-70">
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
                onClick={() => run(async () => { await createDeny("USER", userId, pattern); setPattern(""); })}>
          {t("userDetailDenyAdd")}
        </Button>
      </div>
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

import { useState } from "react";
import { useTranslation } from "react-i18next";
import { groupByResource, type Permission } from "@/roles";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";

/**
 * Searchable checkbox grid for selecting catalog permissions, grouped by resource. A filter box narrows the
 * catalog by resource/action/name. Enabling a mutating action implies `read` (handled by the caller via
 * `togglePermission`); a hint communicates this. Read-only when `disabled`.
 */
export function PermissionPicker({ catalog, selected, onToggle, disabled }: {
  catalog: Permission[];
  selected: string[];
  onToggle: (perm: Permission) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation("console");
  const [filter, setFilter] = useState("");

  if (catalog.length === 0) {
    return <p className="text-sm text-muted-foreground">{t("permPickerEmpty")}</p>;
  }

  const q = filter.trim().toLowerCase();
  const matches = q
    ? catalog.filter((p) =>
        p.name.toLowerCase().includes(q) || p.resource.toLowerCase().includes(q) || p.action.toLowerCase().includes(q))
    : catalog;
  const groups = groupByResource(matches);

  return (
    <div className="space-y-3">
      <p className="text-xs text-muted-foreground">{t("permPickerReadHint")}</p>
      <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t("permPickerFilter")} />
      {groups.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("permPickerNoMatch")}</p>
      ) : (
        <div className="max-h-80 space-y-3 overflow-y-auto pr-1">
          {groups.map(([resource, perms]) => (
            <div key={resource} className="rounded-lg border p-3">
              <p className="mb-2 font-mono text-xs font-semibold text-foreground">{resource}</p>
              <div className="grid grid-cols-2 gap-1 sm:grid-cols-4">
                {perms.map((perm) => {
                  const checked = selected.includes(perm.name);
                  return (
                    <label
                      key={perm.name}
                      className={`flex items-center gap-2 rounded-md border p-2 text-sm transition-colors has-[:checked]:border-primary has-[:checked]:bg-accent ${
                        disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:bg-muted/60"
                      }`}
                    >
                      <Checkbox
                        className="size-4"
                        checked={checked}
                        disabled={disabled}
                        onCheckedChange={() => onToggle(perm)}
                      />
                      <span className="font-mono text-xs">{perm.action || perm.name}</span>
                    </label>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

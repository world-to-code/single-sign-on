import { groupByResource, type Permission } from "@/roles";
import { Checkbox } from "@/components/ui/checkbox";

/**
 * Checkbox grid for selecting catalog permissions, grouped by resource. Enabling a mutating action
 * implies `read` (handled by the caller via `togglePermission`); a hint communicates this. Read-only
 * when `disabled` (e.g. the auto-managed ROLE_ADMIN).
 */
export function PermissionPicker({ catalog, selected, onToggle, disabled }: {
  catalog: Permission[];
  selected: string[];
  onToggle: (perm: Permission) => void;
  disabled?: boolean;
}) {
  if (catalog.length === 0) {
    return <p className="text-sm text-muted-foreground">No permission catalog available.</p>;
  }
  return (
    <div className="space-y-4">
      <p className="text-xs text-muted-foreground">
        Selecting a <span className="font-medium">create</span>, <span className="font-medium">update</span> or{" "}
        <span className="font-medium">delete</span> permission automatically includes <span className="font-medium">read</span>.
      </p>
      <div className="max-h-80 space-y-3 overflow-y-auto pr-1">
        {groupByResource(catalog).map(([resource, perms]) => (
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
    </div>
  );
}

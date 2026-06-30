import { factorMeta } from "@/factors";
import { cn } from "@/lib/utils";

/** Method picker shown when a step allows more than one factor; renders nothing for a single option. */
export function FactorChooser({
  factors, value, onSelect, className,
}: {
  factors: string[];
  value: string;
  onSelect: (factor: string) => void;
  className?: string;
}) {
  if (factors.length <= 1) return null;
  return (
    <div className={cn("mb-5 grid gap-2", className)}>
      <p className="text-xs font-medium text-muted-foreground">Choose a method</p>
      <div className="grid gap-2">
        {factors.map((f) => {
          const { label, icon: Icon } = factorMeta(f);
          const active = f === value;
          return (
            <button
              key={f}
              type="button"
              onClick={() => onSelect(f)}
              className={cn(
                "flex items-center gap-3 rounded-lg border p-3 text-left text-sm transition-colors",
                active ? "border-primary bg-accent" : "hover:bg-muted",
              )}
            >
              <span className={cn(
                "flex size-8 items-center justify-center rounded-md",
                active ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground",
              )}>
                <Icon className="size-4" />
              </span>
              <span className="font-medium">{label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

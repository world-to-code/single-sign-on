import type { ReactNode } from "react";
import { Card, CardContent } from "@/components/ui/card";

/** A dashboard stat tile: an accent icon, an uppercase label, and a prominent value. */
export function MetricTile({ icon, label, value, hint }: {
  icon: ReactNode; label: string; value: string | number; hint?: string;
}) {
  return (
    <Card>
      <CardContent className="flex items-center gap-4 p-5">
        <div className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-accent text-primary">
          {icon}
        </div>
        <div className="min-w-0">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
          <p className="text-2xl font-semibold tabular-nums">{value}</p>
          {hint && <p className="truncate text-xs text-muted-foreground">{hint}</p>}
        </div>
      </CardContent>
    </Card>
  );
}

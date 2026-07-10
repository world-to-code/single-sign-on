import { Card, CardContent } from "@/components/ui/card";

/**
 * A dashboard stat: a small muted label, a large tabular figure, and an optional hint / delta line.
 * No tinted icon square (DESIGN.md §1 calls it the strongest "template" tell) — the figure carries it.
 */
export function Metric({ label, value, hint }: { label: string; value: string | number; hint?: string }) {
  return (
    <Card>
      <CardContent className="p-5">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
        <p className="mt-2 text-3xl font-bold tabular-nums tracking-[-0.035em]">{value}</p>
        {hint && <p className="mt-1 truncate text-xs text-muted-foreground">{hint}</p>}
      </CardContent>
    </Card>
  );
}

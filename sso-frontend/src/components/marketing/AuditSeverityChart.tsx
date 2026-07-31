import { cn } from "@/lib/utils";

/**
 * Audit events per day, split by triage severity.
 *
 * <p>A different question from the sign-in chart, so a different chart. Volume over time is a line; a
 * composition that matters at a glance — how much of today was routine and how much was refused — is a stacked
 * column. Reusing the line here said "we had a chart component" rather than anything about the audit trail.
 *
 * <p>The point the shape makes: the critical band is small and never absent. An audit trail whose warnings
 * vanish entirely is usually one that stopped recording.
 */

const WIDTH = 300;
const HEIGHT = 76;
const PAD_BOTTOM = 14;
const GAP = 7;

/** [info, warning, critical] per day. Sums vary; the mix is the readable part. */
const DAYS: readonly (readonly [number, number, number])[] = [
  [128, 14, 2],
  [143, 9, 1],
  [131, 21, 3],
  [156, 12, 1],
  [172, 18, 4],
  [64, 6, 1],
  [41, 3, 1],
];

const BANDS = [
  { key: "info", className: "fill-primary/35" },
  { key: "warning", className: "fill-amber-400/70" },
  { key: "critical", className: "fill-destructive/80" },
] as const;

export interface AuditSeverityChartProps {
  labels: readonly string[];
  legend: readonly [string, string, string];
  className?: string;
}

export function AuditSeverityChart({ labels, legend, className }: AuditSeverityChartProps) {
  const max = Math.max(...DAYS.map((d) => d[0] + d[1] + d[2]));
  const plotHeight = HEIGHT - PAD_BOTTOM;
  const columnWidth = (WIDTH - GAP * (DAYS.length - 1)) / DAYS.length;

  return (
    <div className={cn("space-y-2", className)}>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="w-full" role="img" aria-label={legend.join(", ")}>
        <line
          x1="0" x2={WIDTH} y1={plotHeight} y2={plotHeight}
          stroke="currentColor" strokeOpacity="0.15" strokeWidth="1"
        />
        {DAYS.map((day, i) => {
          const x = i * (columnWidth + GAP);
          let cursor = plotHeight;
          return (
            <g key={labels[i] + i}>
              {day.map((value, band) => {
                const h = (value / max) * (plotHeight - 4);
                cursor -= h;
                return (
                  <rect
                    key={BANDS[band].key}
                    x={x} y={cursor} width={columnWidth} height={h}
                    className={BANDS[band].className}
                  />
                );
              })}
              <text
                x={x + columnWidth / 2} y={HEIGHT - 3} textAnchor="middle"
                className="fill-current text-[8px] opacity-45"
              >
                {labels[i]}
              </text>
            </g>
          );
        })}
      </svg>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[10px] text-muted-foreground">
        {BANDS.map((band, i) => (
          <span key={band.key} className="inline-flex items-center gap-1.5">
            <span className={cn("size-2 rounded-[2px]", band.className.replace("fill-", "bg-"))} />
            {legend[i]}
          </span>
        ))}
      </div>
    </div>
  );
}

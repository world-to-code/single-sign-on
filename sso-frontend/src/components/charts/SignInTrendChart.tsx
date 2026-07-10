import type { SignInDay } from "@/metrics";

const W = 600;
const H = 200;
const PAD_L = 30;
const PAD_R = 12;
const PAD_T = 12;
const PAD_B = 22;

function shortDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/**
 * Hand-rolled, token-themed SVG line chart of a tenant's daily sign-ins: a filled success line and a
 * failure line over the window, with a faint gridline scale. No charting dependency — responsive via
 * viewBox; colors come from the theme's allow/deny tokens.
 */
export function SignInTrendChart({ days }: { days: SignInDay[] }) {
  const total = days.reduce((sum, d) => sum + d.successes + d.failures, 0);
  if (days.length === 0 || total === 0) {
    return (
      <div className="flex h-44 items-center justify-center text-sm text-muted-foreground">
        No sign-ins recorded in this period.
      </div>
    );
  }

  const max = Math.max(1, ...days.map((d) => Math.max(d.successes, d.failures)));
  const plotW = W - PAD_L - PAD_R;
  const plotH = H - PAD_T - PAD_B;
  const x = (i: number) => PAD_L + (days.length === 1 ? plotW / 2 : (i / (days.length - 1)) * plotW);
  const y = (v: number) => PAD_T + plotH - (v / max) * plotH;
  const line = (pick: (d: SignInDay) => number) => days.map((d, i) => `${x(i)},${y(pick(d))}`).join(" ");
  const successArea = `${x(0)},${PAD_T + plotH} ${line((d) => d.successes)} ${x(days.length - 1)},${PAD_T + plotH}`;
  const ticks = [...new Set([0, Math.round(max / 2), max])];

  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img" aria-label="Daily sign-in trend">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD_L} x2={W - PAD_R} y1={y(t)} y2={y(t)}
                  className="stroke-line" strokeWidth={1} opacity={0.6} />
            <text x={PAD_L - 5} y={y(t) + 3} textAnchor="end" fontSize={10} className="fill-muted-foreground">
              {t}
            </text>
          </g>
        ))}
        <polygon points={successArea} className="fill-allow" opacity={0.12} />
        <polyline points={line((d) => d.successes)} fill="none" className="stroke-allow"
                  strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        <polyline points={line((d) => d.failures)} fill="none" className="stroke-deny"
                  strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        <text x={PAD_L} y={H - 5} fontSize={10} className="fill-muted-foreground">{shortDate(days[0].day)}</text>
        <text x={W - PAD_R} y={H - 5} textAnchor="end" fontSize={10} className="fill-muted-foreground">
          {shortDate(days[days.length - 1].day)}
        </text>
      </svg>
      <div className="mt-2 flex items-center justify-center gap-5 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm bg-allow" /> Successful
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm bg-deny" /> Failed
        </span>
      </div>
    </div>
  );
}

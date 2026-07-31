import { cn } from "@/lib/utils";

/**
 * The sign-in volume chart used in the product mocks.
 *
 * <p>Drawn as an SVG rather than a row of divs. The previous version was a set of thick rounded bars with no
 * axis, no scale and no baseline, which reads as decoration — a viewer cannot tell 400 from 4,000, so it says
 * nothing about the product it is illustrating. Gridlines and a labelled axis are what make a chart legible
 * enough to be worth showing.
 *
 * <p>Deterministic sample data, deliberately: this is a screenshot of a console, not a live figure, and a
 * number that moves on reload would be a lie told for animation's sake.
 */

const WIDTH = 320;
const HEIGHT = 104;
const PAD_LEFT = 30;
const PAD_BOTTOM = 18;
const PAD_TOP = 8;

/** Sign-ins per day, Monday through Sunday. The weekend dip is what makes the shape read as real. */
const SERIES = [2840, 3120, 2960, 3380, 3907, 1420, 980];
const AXIS_MAX = 4000;

export interface SignInChartProps {
  labels: readonly string[];
  className?: string;
  /** Shorter variant for the tighter mock on the capability page. */
  compact?: boolean;
}

export function SignInChart({ labels, className, compact = false }: SignInChartProps) {
  const height = compact ? 76 : HEIGHT;
  const plotHeight = height - PAD_TOP - PAD_BOTTOM;
  const plotWidth = WIDTH - PAD_LEFT - 6;
  const step = plotWidth / (SERIES.length - 1);

  const x = (i: number) => PAD_LEFT + i * step;
  const y = (value: number) => PAD_TOP + plotHeight - (value / AXIS_MAX) * plotHeight;

  // A gentle cardinal spline. Straight segments look like a sketch; an over-curved line looks like a toy.
  const line = SERIES.map((value, i) => {
    if (i === 0) return `M ${x(0)} ${y(value)}`;
    const cx = (x(i - 1) + x(i)) / 2;
    return `C ${cx} ${y(SERIES[i - 1])} ${cx} ${y(value)} ${x(i)} ${y(value)}`;
  }).join(" ");
  const area = `${line} L ${x(SERIES.length - 1)} ${PAD_TOP + plotHeight} L ${x(0)} ${PAD_TOP + plotHeight} Z`;

  const ticks = [0, AXIS_MAX / 2, AXIS_MAX];
  const peak = SERIES.indexOf(Math.max(...SERIES));

  return (
    <svg
      viewBox={`0 0 ${WIDTH} ${height}`}
      className={cn("w-full", className)}
      role="img"
      aria-label={labels.join(", ")}
    >
      <defs>
        <linearGradient id="signInFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity="0.22" />
          <stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity="0" />
        </linearGradient>
      </defs>

      {ticks.map((tick) => (
        <g key={tick}>
          <line
            x1={PAD_LEFT} x2={WIDTH - 6} y1={y(tick)} y2={y(tick)}
            stroke="currentColor" strokeOpacity="0.12" strokeWidth="1"
            strokeDasharray={tick === 0 ? undefined : "3 3"}
          />
          <text
            x={PAD_LEFT - 8} y={y(tick) + 3} textAnchor="end"
            className="fill-current text-[8px] tabular-nums opacity-45"
          >
            {tick === 0 ? "0" : `${tick / 1000}k`}
          </text>
        </g>
      ))}

      <path d={area} fill="url(#signInFill)" />
      <path
        d={line} fill="none" stroke="hsl(var(--primary))"
        strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"
      />

      {/* The peak is marked rather than every point: seven dots would compete with the line itself. */}
      <circle cx={x(peak)} cy={y(SERIES[peak])} r="2.75" fill="hsl(var(--primary))" />
      <circle
        cx={x(peak)} cy={y(SERIES[peak])} r="5.5"
        fill="none" stroke="hsl(var(--primary))" strokeOpacity="0.3" strokeWidth="1"
      />

      {labels.map((label, i) => (
        <text
          key={label} x={x(i)} y={height - 4} textAnchor="middle"
          className="fill-current text-[8px] opacity-45"
        >
          {label}
        </text>
      ))}
    </svg>
  );
}

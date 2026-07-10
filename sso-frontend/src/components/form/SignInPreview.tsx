import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { factorMeta } from "@/factors";
import { getSessionConfig } from "@/portal";
import { cn } from "@/lib/utils";

/** A non-interactive factor chip, mirroring a chosen factor. Labels come verbatim from factorMeta (no casing). */
function FactorChip({ factor }: { factor: string }) {
  const meta = factorMeta(factor);
  const Icon = meta.icon;
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-accent-line bg-accent-soft px-2.5 py-1 text-xs font-medium text-primary">
      <Icon className="size-3.5" />
      {meta.label}
    </span>
  );
}

interface PreviewNode {
  title: string;
  detail?: string;
  chips?: string[];
}

function Timeline({ nodes }: { nodes: PreviewNode[] }) {
  return (
    <ol className="relative mt-3">
      {nodes.map((node, i) => (
        <li key={i} className="relative flex gap-3 pb-5 last:pb-0">
          {i < nodes.length - 1 && <span className="absolute left-[5px] top-4 h-[calc(100%-0.5rem)] w-px bg-line" />}
          <span className="mt-1 size-3 shrink-0 rounded-full border-2 border-accent-line bg-surface" />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <p className="text-sm font-semibold text-ink">{node.title}</p>
              {node.chips && (
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="text-xs text-muted-foreground">any one of</span>
                  {node.chips.map((f) => <FactorChip key={f} factor={f} />)}
                </div>
              )}
            </div>
            {node.detail && <p className="mt-0.5 text-sm text-muted-foreground">{node.detail}</p>}
          </div>
        </li>
      ))}
    </ol>
  );
}

function Panel({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-[18px] border border-line bg-sunken p-4">
      <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Sign-in preview</p>
      {children}
    </div>
  );
}

/**
 * The signature of the auth-policy editor (DESIGN.md §4): it draws the sign-in the policy produces and
 * redraws immediately as factor chips toggle. It reads `steps` directly from the editor — no new plumbing.
 * Empty step 1 => "Nobody can sign in"; an empty later step collapses to "No second step".
 */
export function SignInPreview({ steps }: { steps: string[][] }) {
  const [idleMinutes, setIdleMinutes] = useState<number | null>(null);
  useEffect(() => {
    getSessionConfig().then((cfg) => setIdleMinutes(cfg.idleTimeoutMinutes)).catch(() => undefined);
  }, []);

  // Empty step 1 is a dead policy: show the consequence, not the schema (DESIGN.md §4).
  if (steps.length === 0 || steps[0].length === 0) {
    return (
      <Panel>
        <div className={cn("mt-3 rounded-[12px] border border-deny/30 bg-deny/[0.05] p-4")}>
          <p className="text-sm font-semibold text-deny">Nobody can sign in</p>
          <p className="mt-1 text-sm text-muted-foreground">Step 1 has no factors. Turn at least one on.</p>
        </div>
      </Panel>
    );
  }

  const lifetime = idleMinutes !== null
    ? `Session active · expires after ${idleMinutes} min idle`
    : "Session established.";

  const nodes: PreviewNode[] = [{ title: "Enter email", detail: "We find the person and pick their policy." }];
  steps.forEach((step, i) => {
    if (step.length === 0) {
      nodes.push({ title: i === 1 ? "No second step" : `Step ${i + 1} — no factors` });
    } else {
      nodes.push({ title: `Step ${i + 1}`, chips: step });
    }
  });
  nodes.push({ title: "Signed in", detail: lifetime });

  return (
    <Panel>
      <Timeline nodes={nodes} />
    </Panel>
  );
}

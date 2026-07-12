import { ArrowDown, ChevronDown, ChevronUp, Plus, Trash2, X } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import { FACTORS, factorMeta } from "@/factors";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";

/**
 * Visual factor-chain builder: an ordered list of steps, each an "any one of" set of factors
 * (Okta-style sign-on). Reused by the auth-policy editor; controlled via `steps` / `onChange`.
 */
export function StepsBuilder({ steps, onChange }: { steps: string[][]; onChange: (s: string[][]) => void }) {
  const { t } = useTranslation("auth");
  const setStep = (i: number, factors: string[]) => onChange(steps.map((s, idx) => (idx === i ? factors : s)));
  const removeStep = (i: number) => onChange(steps.filter((_, idx) => idx !== i));
  const moveStep = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= steps.length) return;
    const next = [...steps];
    [next[i], next[j]] = [next[j], next[i]];
    onChange(next);
  };
  const addStep = () => {
    const used = steps.flat();
    onChange([...steps, [FACTORS.find((f) => !used.includes(f)) ?? FACTORS[1]]]);
  };

  return (
    <div className="space-y-2">
      <Label>{t("stepsLabel")} <span className="text-muted-foreground">{t("stepsLabelHint")}</span></Label>
      <div className="space-y-1">
        {steps.map((step, i) => {
          const remaining = FACTORS.filter((f) => !step.includes(f));
          return (
            <div key={i}>
              {i > 0 && (
                <div className="flex items-center gap-1.5 py-0.5 pl-3 text-xs font-medium text-muted-foreground">
                  <ArrowDown className="size-3" /> {t("stepsThen")}
                </div>
              )}
              <div className="rounded-lg border bg-card p-3">
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("stepsStep", { n: i + 1 })}</span>
                  <div className="flex items-center gap-0.5">
                    <Button type="button" variant="ghost" size="icon" className="size-7" aria-label={t("stepsMoveUp")} disabled={i === 0} onClick={() => moveStep(i, -1)}><ChevronUp className="size-4" /></Button>
                    <Button type="button" variant="ghost" size="icon" className="size-7" aria-label={t("stepsMoveDown")} disabled={i === steps.length - 1} onClick={() => moveStep(i, 1)}><ChevronDown className="size-4" /></Button>
                    <Button type="button" variant="ghost" size="icon" className="size-7 text-muted-foreground hover:text-destructive" aria-label={t("stepsRemoveStep")} disabled={steps.length === 1} onClick={() => removeStep(i)}><Trash2 className="size-4" /></Button>
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-1.5">
                  {step.length === 0 && <span className="text-xs text-destructive">{t("stepsPickFactor")}</span>}
                  {step.map((f, fi) => {
                    const meta = factorMeta(f);
                    const Icon = meta.icon;
                    return (
                      <span key={f} className="flex items-center gap-1.5">
                        {fi > 0 && <span className="text-xs font-medium text-muted-foreground">{t("stepsOr")}</span>}
                        <span className="inline-flex items-center gap-1.5 rounded-md border bg-background py-1 pl-2 pr-1 text-sm">
                          <Icon className="size-3.5 text-primary" />
                          {t(meta.label)}
                          <button type="button" aria-label={t("stepsRemoveFactor")} className="rounded text-muted-foreground hover:text-destructive" onClick={() => setStep(i, step.filter((x) => x !== f))}><X className="size-3.5" /></button>
                        </span>
                      </span>
                    );
                  })}
                  {remaining.length > 0 && (
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button type="button" variant="outline" size="sm" className="h-7 gap-1 border-dashed">
                          <Plus className="size-3.5" /> {step.length === 0 ? t("stepsAddFactor") : t("stepsOr")}
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="start">
                        {remaining.map((f) => {
                          const meta = factorMeta(f);
                          const Icon = meta.icon;
                          return (
                            <DropdownMenuItem key={f} onClick={() => setStep(i, [...step, f])}>
                              <Icon className="size-4" /> {t(meta.label)}
                            </DropdownMenuItem>
                          );
                        })}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
      <Button type="button" variant="outline" size="sm" className="w-full border-dashed" onClick={addStep}>
        <Plus className="size-4" /> {t("stepsAddStep")}
      </Button>
      <p className="text-xs text-muted-foreground">
        <Trans t={t} i18nKey="stepsHint" components={[<strong key="0" />, <strong key="1" />]} />
      </p>
    </div>
  );
}

import { useTranslation } from "react-i18next";
import { ArrowDown, Lock, ShieldCheck } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Section, CtaBand } from "@/components/marketing/MarketingLayout";

type MKey = keyof (typeof import("@/i18n/en/marketing"))["marketing"];

/** Security page ("/security"): the trust story told seriously — a dark zero-trust isolation flow and a
 *  threat -> mitigation controls table, not another card grid. */
export default function Security() {
  const { t } = useTranslation("marketing");
  return (
    <>
      <section className="relative overflow-hidden border-b">
        <div className="relative mx-auto max-w-3xl px-4 py-20 sm:px-6 sm:py-24">
          <Badge variant="muted" className="mb-5 inline-flex items-center gap-1.5">
            <ShieldCheck className="size-3.5" /> {t("securityHeroBadge")}
          </Badge>
          <h1 className="text-balance text-4xl font-bold tracking-tight sm:text-5xl">{t("securityHeroTitle")}</h1>
          <p className="mt-5 max-w-2xl text-pretty text-lg text-muted-foreground">{t("securityHeroBody")}</p>
        </div>
      </section>

      <Section tone="dark">
        <div className="grid gap-12 lg:grid-cols-2 lg:items-center">
          <div>
            <Badge variant="default" className="mb-4 bg-white/10 text-white hover:bg-white/10">{t("securityIsolationBadge")}</Badge>
            <h2 className="text-balance text-3xl font-semibold tracking-tight text-white sm:text-4xl">{t("securityIsolationTitle")}</h2>
            <p className="mt-3 text-band-fg/70">{t("securityIsolationBody")}</p>
            <div className="mt-6 inline-flex items-center gap-2 rounded-lg border border-white/10 bg-white/5 px-4 py-2.5 text-sm text-white">
              <Lock className="size-4 text-primary" /> {t("securityIsolationCallout")}
            </div>
          </div>
          <ol className="space-y-3">
            {ISOLATION_FLOW.map((step, i) => (
              <li key={step.label}>
                <div className="rounded-xl border border-white/10 bg-white/5 p-4">
                  <div className="flex items-center gap-3">
                    <span className="flex size-7 shrink-0 items-center justify-center rounded-md bg-primary text-xs font-semibold text-primary-foreground">{i + 1}</span>
                    <span className="font-medium text-white">{t(step.label)}</span>
                  </div>
                  <p className="mt-1.5 pl-10 text-sm text-band-fg/70">{t(step.detail)}</p>
                </div>
                {i < ISOLATION_FLOW.length - 1 && (
                  <div className="flex justify-center py-1"><ArrowDown className="size-4 text-band-fg/40" /></div>
                )}
              </li>
            ))}
          </ol>
        </div>
      </Section>

      <Section>
        <div className="mb-8 max-w-2xl">
          <Badge variant="muted" className="mb-3">{t("securityControlsBadge")}</Badge>
          <h2 className="text-balance text-3xl font-semibold tracking-tight sm:text-4xl">{t("securityControlsTitle")}</h2>
          <p className="mt-3 text-muted-foreground">{t("securityControlsBody")}</p>
        </div>
        <div className="overflow-hidden rounded-xl border">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/40 hover:bg-muted/40">
                <TableHead className="min-w-[11rem]">{t("securityColControl")}</TableHead>
                <TableHead className="min-w-[12rem]">{t("securityColThreat")}</TableHead>
                <TableHead>{t("securityColHow")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {CONTROLS.map((c) => (
                <TableRow key={c.control}>
                  <TableCell className="align-top font-medium">{t(c.control)}</TableCell>
                  <TableCell className="align-top text-muted-foreground">{t(c.threat)}</TableCell>
                  <TableCell className="align-top text-muted-foreground">{t(c.how)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Section>

      <Section tone="muted">
        <div className="grid gap-8 rounded-2xl border bg-card p-8 sm:grid-cols-3 sm:p-10">
          {ASSURANCES.map((a) => (
            <div key={a.title}>
              <a.icon className="size-5 text-primary" />
              <h3 className="mt-3 font-semibold">{t(a.title)}</h3>
              <p className="mt-1.5 text-sm text-muted-foreground">{t(a.body)}</p>
            </div>
          ))}
        </div>
      </Section>

      <CtaBand />
    </>
  );
}

const ISOLATION_FLOW: { label: MKey; detail: MKey }[] = [
  { label: "securityFlow1Label", detail: "securityFlow1Detail" },
  { label: "securityFlow2Label", detail: "securityFlow2Detail" },
  { label: "securityFlow3Label", detail: "securityFlow3Detail" },
  { label: "securityFlow4Label", detail: "securityFlow4Detail" },
];

const CONTROLS: { control: MKey; threat: MKey; how: MKey }[] = [
  { control: "securityCtrl1Control", threat: "securityCtrl1Threat", how: "securityCtrl1How" },
  { control: "securityCtrl2Control", threat: "securityCtrl2Threat", how: "securityCtrl2How" },
  { control: "securityCtrl3Control", threat: "securityCtrl3Threat", how: "securityCtrl3How" },
  { control: "securityCtrl4Control", threat: "securityCtrl4Threat", how: "securityCtrl4How" },
  { control: "securityCtrl5Control", threat: "securityCtrl5Threat", how: "securityCtrl5How" },
  { control: "securityCtrl6Control", threat: "securityCtrl6Threat", how: "securityCtrl6How" },
  { control: "securityCtrl7Control", threat: "securityCtrl7Threat", how: "securityCtrl7How" },
  { control: "securityCtrl8Control", threat: "securityCtrl8Threat", how: "securityCtrl8How" },
  { control: "securityCtrl9Control", threat: "securityCtrl9Threat", how: "securityCtrl9How" },
  { control: "securityCtrl10Control", threat: "securityCtrl10Threat", how: "securityCtrl10How" },
];

const ASSURANCES: { icon: typeof ShieldCheck; title: MKey; body: MKey }[] = [
  { icon: ShieldCheck, title: "securityAssure1Title", body: "securityAssure1Body" },
  { icon: Lock, title: "securityAssure2Title", body: "securityAssure2Body" },
  { icon: ArrowDown, title: "securityAssure3Title", body: "securityAssure3Body" },
];

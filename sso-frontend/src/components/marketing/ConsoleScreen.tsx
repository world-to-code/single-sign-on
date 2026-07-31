import { useTranslation } from "react-i18next";
import { Blocks, KeyRound, Plus, ScrollText, ShieldCheck, UsersRound, Users } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

/**
 * The admin console's user list, rebuilt in the page.
 *
 * <p>Not a captured PNG. The labels come from the CONSOLE's own translation bundle, so this cannot drift from
 * what the product actually says, it translates with the rest of the page, it stays sharp on any display, and
 * it carries no screenshot of anybody's real directory. A picture would go stale the first time a column was
 * renamed and nobody would notice.
 *
 * <p>The rows are invented and obviously so. Putting plausible-looking real names in a marketing mock is how
 * people end up shipping a customer's directory to their landing page.
 */

/**
 * Labels come from the NAV bundle, the same one the real sidebar renders from — and grouped under the same
 * headings, because a flat list of five items is not what the console looks like.
 */
const NAV = [
  { heading: "nav:sectionDirectory", items: [
    { icon: UsersRound, key: "nav:users", active: true },
    { icon: Users, key: "nav:groups", active: false },
  ] },
  { heading: "nav:sectionAccessSecurity", items: [
    { icon: Blocks, key: "nav:applications", active: false },
    { icon: ShieldCheck, key: "nav:roles", active: false },
  ] },
  { heading: "nav:sectionSystem", items: [
    { icon: ScrollText, key: "nav:auditLog", active: false },
  ] },
] as const;

const ROWS = [
  { user: "jordan", email: "jordan@acme.example", name: "Jordan Ellis", on: true, roles: ["ORG_ADMIN"] },
  { user: "priya", email: "priya@acme.example", name: "Priya Raman", on: true, roles: ["GROUP_ADMIN", "ROLE_USER"] },
  { user: "sam", email: "sam@acme.example", name: "Sam Okafor", on: true, roles: ["ROLE_USER"] },
  { user: "l.chen", email: "l.chen@acme.example", name: "Li Chen", on: false, roles: ["ROLE_USER"] },
] as const;

export function ConsoleScreen({ className }: { className?: string }) {
  const { t } = useTranslation(["marketing", "console", "nav"]);
  return (
    <div className={cn("overflow-hidden rounded-xl border bg-card shadow-2xl", className)}>
      <div className="flex items-center gap-2 border-b bg-muted/50 px-4 py-2.5">
        <span className="size-3 rounded-full bg-destructive/40" />
        <span className="size-3 rounded-full bg-amber-400/50" />
        <span className="size-3 rounded-full bg-success/50" />
        <span className="ml-3 rounded-md border bg-background px-2.5 py-1 font-mono text-xs text-muted-foreground">
          acme.svalinn.example/admin/users
        </span>
      </div>

      <div className="flex">
        <nav className="hidden w-48 shrink-0 border-r sm:block">
          <div className="flex items-center gap-2 border-b px-3 py-2.5">
            <span className="flex size-6 shrink-0 items-center justify-center rounded bg-ink text-bg">
              <span className="brand-mark size-3.5" />
            </span>
            <span className="truncate text-xs font-semibold tracking-tight">acme</span>
          </div>
          <div className="space-y-3 p-3">
            {NAV.map((group) => (
              <div key={group.heading}>
                <p className="mb-1 px-2.5 text-[10px] font-medium uppercase tracking-wider text-muted-foreground/70">
                  {t(group.heading)}
                </p>
                <ul className="space-y-0.5">
                  {group.items.map((item) => (
                    <li key={item.key}>
                      <span
                        className={cn(
                          "flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-xs",
                          item.active ? "bg-muted font-medium text-foreground" : "text-muted-foreground",
                        )}
                      >
                        <item.icon className="size-3.5" />
                        {t(item.key)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </nav>

        <div className="min-w-0 flex-1 p-5">
          <div className="mb-4 flex items-start justify-between gap-4">
            <div>
              <h3 className="text-base font-semibold tracking-tight">{t("console:usersTitle")}</h3>
              <p className="mt-0.5 text-xs text-muted-foreground">{t("console:usersDescription")}</p>
            </div>
            <span className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-primary px-2.5 py-1.5 text-xs font-medium text-primary-foreground">
              <Plus className="size-3.5" /> {t("console:usersNew")}
            </span>
          </div>

          <div className="overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-xs">
              <thead className="border-b bg-muted/40 text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">{t("console:usersColUsername")}</th>
                  <th className="hidden px-3 py-2 font-medium md:table-cell">{t("console:usersColEmail")}</th>
                  <th className="px-3 py-2 font-medium">{t("console:usersColStatus")}</th>
                  <th className="hidden px-3 py-2 font-medium lg:table-cell">{t("console:usersColRoles")}</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {ROWS.map((row) => (
                  <tr key={row.user}>
                    <td className="px-3 py-2 font-medium text-primary">{row.user}</td>
                    <td className="hidden px-3 py-2 text-muted-foreground md:table-cell">{row.email}</td>
                    <td className="px-3 py-2">
                      <Badge variant={row.on ? "success" : "muted"} className="text-[10px]">
                        {row.on ? t("console:usersStatusEnabled") : t("console:usersStatusDisabled")}
                      </Badge>
                    </td>
                    <td className="hidden px-3 py-2 lg:table-cell">
                      <span className="flex flex-wrap gap-1">
                        {row.roles.map((role) => (
                          <span key={role} className="rounded border bg-muted/60 px-1.5 py-0.5 font-mono text-[10px]">
                            {role}
                          </span>
                        ))}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="mt-3 flex items-center gap-1.5 text-[11px] text-muted-foreground">
            <KeyRound className="size-3" />
            {t("marketing:consoleScreenNote")}
          </p>
        </div>
      </div>
    </div>
  );
}

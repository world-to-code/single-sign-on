import { useEffect, useState } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  Activity, AppWindow, ArrowLeft, Fingerprint, KeyRound, Monitor, Pencil, Power, PowerOff, RotateCcw,
  ShieldCheck, Smartphone, Trash2,
} from "lucide-react";
import type { SessionView } from "@/auth";
import {
  getUser, getUserApplications, getUserDevices, getUserSessions, resetUserMfa,
  setUserEnabled, setUserPermissions, updateUser,
  type ActivityEntry, type UserApplication, type UserDetail as UserDetailData, type UserDevices,
  type UserSession,
} from "@/users";
import { errorMessage } from "@/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { ADMIN_ROLE, listPermissions, listRoles, togglePermission, type Permission, type Role } from "@/roles";
import { usePaginated } from "@/usePaginated";
import { Pagination } from "@/components/Pagination";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { PageHeader } from "@/components/PageHeader";
import { PermissionPicker } from "@/components/PermissionPicker";
import { useConfirm } from "@/components/ConfirmProvider";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";

type Tab = "overview" | "activity";

export default function UserDetail({ session }: { session: SessionView }) {
  const { t, i18n } = useTranslation("console");
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const confirm = useConfirm();
  const confirmDelete = useDeleteConfirm();
  const [user, setUser] = useState<UserDetailData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [allRoles, setAllRoles] = useState<Role[]>([]);
  const [catalog, setCatalog] = useState<Permission[]>([]);
  const [rolesOpen, setRolesOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [profile, setProfile] = useState({ displayName: "", email: "" });
  const [roleSel, setRoleSel] = useState<string[]>([]);
  const [permsOpen, setPermsOpen] = useState(false);
  const [permSel, setPermSel] = useState<string[]>([]);
  const [apps, setApps] = useState<UserApplication[]>([]);
  const [devices, setDevices] = useState<UserDevices | null>(null);
  const [sessions, setSessions] = useState<UserSession[]>([]);
  const [tab, setTab] = useState<Tab>("overview");
  const activityPage = usePaginated<ActivityEntry>(`/api/admin/users/${id}/activity`);

  function load() { getUser(id).then(setUser).catch((e) => setError(errorMessage(e))); }
  useEffect(load, [id]);
  useEffect(() => {
    getUserApplications(id).then(setApps).catch(() => undefined);
    getUserDevices(id).then(setDevices).catch(() => undefined);
    getUserSessions(id).then(setSessions).catch(() => undefined);
  }, [id]);
  useEffect(() => {
    listRoles().then(setAllRoles).catch(() => undefined);
    listPermissions().then(setCatalog).catch(() => undefined);
  }, []);

  const isSelf = !!user && user.username === session.username;
  // Can't revoke your own directly-held admin role (enforced server-side; reflected in the UI).
  const selfLockedAdmin = isSelf && !!user && user.roleAssignments.some((a) => a.direct && a.roleName === ADMIN_ROLE);

  async function toggleEnabled() {
    if (!user) return;
    await setUserEnabled(id, !user.enabled);
    load();
  }

  async function resetMfa() {
    if (!user) return;
    if (await confirm({ title: t("userDetailResetMfaTitle"), description: t("userDetailResetMfaDesc", { name: user.username }), confirmText: t("userDetailResetMfa") })) {
      await resetUserMfa(id);
      load();
    }
  }

  function remove() {
    if (!user) return;
    confirmDelete({
      title: t("userDetailDeleteTitle"),
      description: t("userDetailDeleteDesc", { name: user.username }),
      path: `/api/admin/users/${id}`,
      onDeleted: () => navigate("/admin/users"),
    });
  }

  function openRoles() {
    setRoleSel(user ? user.roleAssignments.filter((a) => a.direct).map((a) => a.roleName) : []);
    setRolesOpen(true);
  }
  function toggleRole(name: string) {
    if (selfLockedAdmin && name === ADMIN_ROLE) return; // cannot revoke your own admin role
    setRoleSel((sel) => (sel.includes(name) ? sel.filter((r) => r !== name) : [...sel, name]));
  }
  async function saveRoles() {
    if (!user) return;
    try {
      await updateUser(id, { displayName: user.displayName, email: user.email, enabled: user.enabled, roles: roleSel });
      setRolesOpen(false);
      load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  function openProfile() {
    if (!user) return;
    setProfile({ displayName: user.displayName ?? "", email: user.email });
    setProfileOpen(true);
  }
  async function saveProfile() {
    if (!user) return;
    try {
      await updateUser(id, {
        displayName: profile.displayName, email: profile.email,
        enabled: user.enabled,
        roles: user.roleAssignments.filter((a) => a.direct).map((a) => a.roleName),
      });
      setProfileOpen(false);
      load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  function openPerms() {
    setPermSel(user ? [...user.directPermissions] : []);
    setPermsOpen(true);
  }
  async function savePerms() {
    try {
      await setUserPermissions(id, permSel);
      setPermsOpen(false);
      load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <>
      <Link to="/admin/users" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t("userDetailBack")}
      </Link>
      <PageHeader
        title={user ? (user.displayName || user.username) : t("userDetailFallbackTitle")}
        description={user ? user.email : t("userDetailFallbackDesc")}
        actions={user && (
          <div className="flex items-center gap-1">
            <Button
              variant="outline" size="sm" onClick={toggleEnabled}
              disabled={isSelf && user.enabled}
              title={isSelf && user.enabled ? t("userDetailCannotDisableSelf") : undefined}
            >
              {user.enabled ? <PowerOff className="size-4" /> : <Power className="size-4" />}
              {user.enabled ? t("userDetailDisable") : t("userDetailEnable")}
            </Button>
            <Button
              variant="outline" size="sm" onClick={openProfile}
              disabled={user.externalId !== null}
              title={user.externalId !== null ? t("userDetailExternalProvisioned") : undefined}
            >
              <Pencil className="size-4" /> {t("userDetailEditProfile")}
            </Button>
            <Button variant="outline" size="sm" onClick={resetMfa}><RotateCcw className="size-4" /> {t("userDetailResetMfa")}</Button>
            <Button
              variant="outline" size="sm" className="text-destructive hover:text-destructive" onClick={remove}
              disabled={isSelf}
              title={isSelf ? t("userDetailCannotDeleteSelf") : undefined}
            >
              <Trash2 className="size-4" /> {t("userDetailDelete")}
            </Button>
          </div>
        )}
      />

      {error && <Alert variant="destructive" className="mb-4"><AlertDescription>{error}</AlertDescription></Alert>}

      {user && (
        <div className="mb-4 flex gap-1 border-b">
          {(["overview", "activity"] as Tab[]).map((tabKey) => (
            <button key={tabKey} onClick={() => setTab(tabKey)}
                    className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${tab === tabKey ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"}`}>
              {tabKey === "activity"
                ? (activityPage.total ? t("userDetailTabActivityCount", { count: activityPage.total }) : t("userDetailTabActivity"))
                : t("userDetailTabOverview")}
            </button>
          ))}
        </div>
      )}

      {user && tab === "overview" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>{t("userDetailProfile")}</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-4 sm:grid-cols-2">
              <Detail label={t("userDetailUsername")} value={user.username} mono />
              <Detail label={t("userDetailEmail")} value={user.email} />
              <Detail label={t("userDetailDisplayName")} value={user.displayName || "—"} />
              <Detail label={t("userDetailExternalId")} value={user.externalId || "—"} />
              <div className="space-y-1.5">
                <p className="text-xs font-medium text-muted-foreground">{t("userDetailStatus")}</p>
                <div className="flex flex-wrap gap-1">
                  <Badge variant={user.enabled ? "success" : "muted"}>{user.enabled ? t("userDetailStatusEnabled") : t("userDetailStatusDisabled")}</Badge>
                  <Badge variant={user.emailVerified ? "success" : "muted"}>{user.emailVerified ? t("userDetailEmailVerified") : t("userDetailEmailUnverified")}</Badge>
                  {!user.accountNonLocked && <Badge variant="destructive">{t("userDetailLocked")}</Badge>}
                </div>
              </div>
              <Detail label={t("userDetailCreated")} value={formatDateTime(user.createdAt, i18n.language)} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <div>
                <CardTitle>{t("userDetailRoles")}</CardTitle>
                <CardDescription>{t("userDetailRolesDesc")}</CardDescription>
              </div>
              <Button variant="outline" size="sm" onClick={openRoles}><ShieldCheck className="size-4" /> {t("userDetailEditDirectRoles")}</Button>
            </CardHeader>
            <CardContent>
              {user.roleAssignments.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("userDetailNoRoles")}</p>
              ) : (
                <div className="space-y-2">
                  {user.roleAssignments.map((a) => (
                    <div key={a.roleId} className="flex items-center justify-between gap-3 rounded-lg border p-3">
                      <span className="font-medium">{a.roleName}</span>
                      <div className="flex flex-wrap justify-end gap-1">
                        {a.direct && <Badge variant="default">{t("userDetailDirect")}</Badge>}
                        {a.viaGroups.map((g) => <Badge key={g} variant="secondary">{t("userDetailViaGroup", { group: g })}</Badge>)}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <div>
                <CardTitle>{t("userDetailDirectPerms")}</CardTitle>
                <CardDescription>{t("userDetailDirectPermsDesc")}</CardDescription>
              </div>
              <Button variant="outline" size="sm" onClick={openPerms}><KeyRound className="size-4" /> {t("userDetailEdit")}</Button>
            </CardHeader>
            <CardContent>
              {user.directPermissions.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("userDetailNoDirectPerms")}</p>
              ) : (
                <div className="flex flex-wrap gap-1">
                  {user.directPermissions.map((p) => <Badge key={p} variant="outline" className="font-mono text-xs">{p}</Badge>)}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>{t("userDetailEffectivePerms")}</CardTitle>
              <CardDescription>{t("userDetailEffectivePermsDesc")}</CardDescription>
            </CardHeader>
            <CardContent>
              {user.effectivePermissions.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("none")}</p>
              ) : (
                <div className="flex flex-wrap gap-1">
                  {user.effectivePermissions.map((p) => <Badge key={p} variant="muted" className="font-mono text-xs">{p}</Badge>)}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><AppWindow className="size-4" /> {t("userDetailAssignedApps")}</CardTitle>
              <CardDescription>{t("userDetailAssignedAppsDesc")}</CardDescription>
            </CardHeader>
            <CardContent>
              {apps.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("userDetailNoApps")}</p>
              ) : (
                <div className="space-y-2">
                  {apps.map((app) => (
                    <div key={`${app.type}:${app.id}`} className="flex items-center justify-between gap-3 rounded-lg border p-3">
                      <span className="font-medium">{app.name}</span>
                      <Badge variant="muted">{app.type}</Badge>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><Fingerprint className="size-4" /> {t("userDetailAuthDevices")}</CardTitle>
              <CardDescription>{t("userDetailAuthDevicesDesc")}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium">{t("userDetailTotpAuth")}</span>
                <Badge variant={devices?.totpEnabled ? "success" : "muted"}>
                  {devices?.totpEnabled ? t("userDetailEnrolled") : t("userDetailNotEnrolled")}
                </Badge>
              </div>
              <div>
                <p className="mb-2 text-sm font-medium">{t("userDetailPasskeys")}</p>
                {!devices || devices.passkeys.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{t("userDetailNoPasskeys")}</p>
                ) : (
                  <div className="space-y-2">
                    {devices.passkeys.map((pk) => (
                      <div key={pk.id} className="flex items-center justify-between gap-3 rounded-lg border p-3 text-sm">
                        <span className="inline-flex items-center gap-2"><KeyRound className="size-4 text-muted-foreground" />{pk.label}</span>
                        <span className="text-muted-foreground">{t("userDetailPasskeyAdded", { date: formatDate(pk.createdAt, i18n.language) })}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><Monitor className="size-4" /> {t("userDetailActiveSessions")}</CardTitle>
              <CardDescription>{t("userDetailActiveSessionsDesc", { count: sessions.length })}</CardDescription>
            </CardHeader>
            <CardContent>
              {sessions.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("userDetailNoSessions")}</p>
              ) : (
                <div className="space-y-2">
                  {sessions.map((s) => (
                    <div key={s.handle} className="flex items-center justify-between gap-3 rounded-lg border p-3 text-sm">
                      <span className="inline-flex items-center gap-2">
                        <Smartphone className="size-4 text-muted-foreground" />
                        <span className="truncate" title={s.userAgent ?? undefined}>{s.userAgent || t("userDetailUnknownDevice")}</span>
                      </span>
                      <span className="shrink-0 text-muted-foreground">{s.ip} · {formatDateTime(s.lastSeenAt, i18n.language)}</span>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          <div className="grid gap-4 sm:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">{t("userDetailProvisioning")}</CardTitle>
                <CardDescription>{t("userDetailProvisioningDesc")}</CardDescription>
              </CardHeader>
              <CardContent className="space-y-2 text-sm">
                <Badge variant={user.externalId ? "default" : "muted"}>
                  {user.externalId ? t("userDetailProvisionedScim") : t("userDetailLocallyManaged")}
                </Badge>
                {user.externalId && <p className="font-mono text-xs text-muted-foreground">{t("userDetailExternalIdPrefix", { id: user.externalId })}</p>}
              </CardContent>
            </Card>
            <Card className="opacity-60">
              <CardHeader>
                <CardTitle className="text-base">{t("userDetailCustomAttrs")}</CardTitle>
                <CardDescription>{t("userDetailCustomAttrsDesc")}</CardDescription>
              </CardHeader>
              <CardContent><Badge variant="muted">{t("userDetailComingSoon")}</Badge></CardContent>
            </Card>
          </div>
        </div>
      )}

      {user && tab === "activity" && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Activity className="size-4" /> {t("userDetailActivity")}</CardTitle>
            <CardDescription>{t("userDetailActivityDesc")}</CardDescription>
          </CardHeader>
          <CardContent>
            {activityPage.error ? (
              <Alert variant="destructive"><AlertDescription>{activityPage.error}</AlertDescription></Alert>
            ) : activityPage.items === null ? (
              <p className="text-sm text-muted-foreground">{t("loading")}</p>
            ) : activityPage.items.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("userDetailNoActivity")}</p>
            ) : (
              <div className="space-y-1">
                {activityPage.items.map((a) => (
                  <div key={a.id} className="flex items-center justify-between gap-3 border-b py-2 text-sm last:border-0">
                    <span className="inline-flex items-center gap-2">
                      <Badge variant={a.success ? "success" : "destructive"}>{a.success ? t("userDetailResultOk") : t("userDetailResultFail")}</Badge>
                      <span className="font-mono text-xs">{a.type}</span>
                    </span>
                    <span className="shrink-0 text-muted-foreground">{formatDateTime(a.occurredAt, i18n.language)}</span>
                  </div>
                ))}
              </div>
            )}
            <Pagination page={activityPage.page} size={activityPage.size} total={activityPage.total} onPage={activityPage.setPage} />
          </CardContent>
        </Card>
      )}

      <Dialog open={profileOpen} onOpenChange={setProfileOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("userDetailEditProfileTitle")}</DialogTitle>
            <DialogDescription>
              {t("userDetailEditProfileDesc")}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="profile-name">{t("userDetailProfileNameLabel")}</Label>
              <Input id="profile-name" value={profile.displayName}
                     onChange={(e) => setProfile((p) => ({ ...p, displayName: e.target.value }))} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="profile-email">{t("userDetailProfileEmailLabel")}</Label>
              <Input id="profile-email" type="email" value={profile.email}
                     onChange={(e) => setProfile((p) => ({ ...p, email: e.target.value }))} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setProfileOpen(false)}>{t("cancel")}</Button>
            <Button onClick={saveProfile} disabled={!profile.email.trim()}>{t("saveChanges")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={rolesOpen} onOpenChange={setRolesOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("userDetailDirectRolesTitle")}</DialogTitle>
            <DialogDescription>
              <Trans t={t} i18nKey="userDetailDirectRolesDesc" values={{ username: user?.username ?? "" }}
                     components={[<strong key="0" />]} />
            </DialogDescription>
          </DialogHeader>
          {allRoles.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("userDetailNoRolesAvailable")}</p>
          ) : (
            <div className="grid max-h-72 grid-cols-1 gap-1 overflow-y-auto sm:grid-cols-2">
              {allRoles.map((role) => {
                const locked = selfLockedAdmin && role.name === ADMIN_ROLE;
                return (
                  <label key={role.id} className={`flex items-center gap-2.5 rounded-md border p-2.5 text-sm transition-colors has-[:checked]:border-primary has-[:checked]:bg-accent ${locked ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:bg-muted/60"}`}>
                    <Checkbox className="size-4" checked={roleSel.includes(role.name)} disabled={locked} onCheckedChange={() => toggleRole(role.name)} />
                    <span>{role.name}</span>
                  </label>
                );
              })}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setRolesOpen(false)}>{t("cancel")}</Button>
            <Button onClick={saveRoles}>{t("userDetailSaveRoles")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={permsOpen} onOpenChange={setPermsOpen}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t("userDetailDirectPermsTitle")}</DialogTitle>
            <DialogDescription>
              <Trans t={t} i18nKey="userDetailDirectPermsDialogDesc" values={{ username: user?.username ?? "" }}
                     components={[<strong key="0" />]} />
            </DialogDescription>
          </DialogHeader>
          <PermissionPicker catalog={catalog} selected={permSel} onToggle={(perm) => setPermSel((sel) => togglePermission(sel, perm, catalog))} />
          <DialogFooter>
            <Button variant="outline" onClick={() => setPermsOpen(false)}>{t("cancel")}</Button>
            <Button onClick={savePerms}>{t("userDetailSavePermissions")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Detail({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-muted-foreground">{label}</p>
      <p className={`text-sm ${mono ? "font-mono" : ""}`}>{value}</p>
    </div>
  );
}

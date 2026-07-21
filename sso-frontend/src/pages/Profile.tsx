import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { AppWindow, KeyRound, Loader2, Mail, MessageSquare, MonitorSmartphone, Plus, ShieldCheck, Smartphone, LogOut, Trash2 } from "lucide-react";
import { ApiError } from "../api";
import { PageHeader } from "../components/PageHeader";
import PasskeyManager from "../components/PasskeyManager";
import { OtpInput } from "../components/auth/OtpInput";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent } from "../components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "../components/ui/dialog";
import { Input } from "../components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { DataList, EmptyState } from "../components/states";
import { useApiData } from "../useApiData";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { confirmEmail, confirmPhone, confirmTotp, disableTotp, logoutAppSession, removePhone, requestEmailCode, requestPhoneCode, revokeSession, setupTotp } from "../profile";
import type { AppSession, Profile as ProfileData, SessionDevice, TotpSetup } from "../profile";

/** A single security-factor card: icon + title + status badge + optional detail line + action. */
function FactorCard({ icon, title, badge, detail, action }: { icon: ReactNode; title: string; badge: ReactNode; detail?: string; action?: ReactNode }) {
  return (
    <Card>
      <CardContent className="flex items-start gap-3 p-5">
        <div className="text-muted-foreground">{icon}</div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <p className="text-sm font-medium">{title}</p>
            {badge}
          </div>
          {detail && <p className="mt-1 truncate text-sm text-muted-foreground">{detail}</p>}
          {action && <div className="mt-3">{action}</div>}
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * Self-service authenticator enrollment dialog. On open it fetches the QR + secret; the user scans,
 * enters a code, and confirms. `onEnrolled` reloads the profile so the card flips to "Enrolled".
 */
function TotpSetupDialog({ open, onOpenChange, onEnrolled }: { open: boolean; onOpenChange: (o: boolean) => void; onEnrolled: () => void }) {
  const { t } = useTranslation("auth");
  const [setup, setSetup] = useState<TotpSetup | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // (Re)start enrollment whenever the dialog opens. Driven by the `open` prop (the dialog is opened
  // programmatically by the "Set up" button), NOT by the Radix onOpenChange callback — which only
  // fires on user-driven open/close, so it never ran for an externally-controlled open.
  useEffect(() => {
    if (!open) return;
    setSetup(null); setCode(""); setError(null); setBusy(false);
    setupTotp()
      .then(setSetup)
      .catch((e) => setError(e instanceof ApiError && e.status === 409
        ? t("profileTotpAlreadyEnrolled")
        : t("profileTotpStartFailed")));
  }, [open]);

  async function verify(event: React.FormEvent) {
    event.preventDefault();
    setError(null); setBusy(true);
    try {
      await confirmTotp(code);
      onEnrolled();
      onOpenChange(false);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 400 ? t("profileTotpInvalidCode") : t("profileTotpVerifyFailed"));
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t("profileTotpDialogTitle")}</DialogTitle>
          <DialogDescription>{t("profileTotpDialogDesc")}</DialogDescription>
        </DialogHeader>

        {error && <p className="text-sm text-destructive">{error}</p>}

        {!setup && !error && (
          <div className="flex justify-center py-8 text-muted-foreground"><Loader2 className="size-6 animate-spin" /></div>
        )}

        {setup?.qrDataUri && (
          <>
            <div className="flex flex-col items-center gap-3 rounded-lg border bg-muted/40 p-4">
              <img src={setup.qrDataUri} alt="TOTP QR code" width={170} height={170} className="rounded-md bg-white p-2 shadow-sm" />
              <details className="w-full text-center text-xs text-muted-foreground">
                <summary className="cursor-pointer">{t("mfaEnterKeyManually")}</summary>
                <code className="mt-1 block break-all font-mono">{setup.secret}</code>
              </details>
            </div>
            <form onSubmit={verify} className="space-y-3">
              <OtpInput value={code} onChange={(e) => setCode(e.target.value)} />
              <Button type="submit" className="w-full" disabled={busy}>
                {busy ? <Loader2 className="animate-spin" /> : <Smartphone />} {t("profileVerifyAndEnable")}
              </Button>
            </form>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

/**
 * Self-service phone-enrollment dialog for the SMS factor: enter a number (E.164), receive a texted code,
 * confirm it. `onEnrolled` reloads the profile so the card flips to "Verified".
 */
function PhoneSetupDialog({ open, onOpenChange, onEnrolled }: { open: boolean; onOpenChange: (o: boolean) => void; onEnrolled: () => void }) {
  const { t } = useTranslation("auth");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) { setPhone(""); setCode(""); setSent(false); setError(null); setBusy(false); }
  }, [open]);

  async function send(event: React.FormEvent) {
    event.preventDefault();
    setError(null); setBusy(true);
    try {
      await requestPhoneCode(phone);
      setSent(true); setBusy(false);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 400 ? t("profilePhoneInvalid") : t("profilePhoneSendFailed"));
      setBusy(false);
    }
  }

  async function verify(event: React.FormEvent) {
    event.preventDefault();
    setError(null); setBusy(true);
    try {
      await confirmPhone(code);
      onEnrolled();
      onOpenChange(false);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 400 ? t("profilePhoneCodeInvalid") : t("profilePhoneVerifyFailed"));
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t("profilePhoneDialogTitle")}</DialogTitle>
          <DialogDescription>{t("profilePhoneDialogDesc")}</DialogDescription>
        </DialogHeader>

        {error && <p className="text-sm text-destructive">{error}</p>}

        {!sent ? (
          <form onSubmit={send} className="space-y-3">
            <Input type="tel" value={phone} onChange={(e) => setPhone(e.target.value)}
                   placeholder={t("profilePhonePlaceholder")} autoFocus required />
            <Button type="submit" className="w-full" disabled={busy}>
              {busy ? <Loader2 className="animate-spin" /> : <MessageSquare />} {t("smsMeCode")}
            </Button>
          </form>
        ) : (
          <form onSubmit={verify} className="space-y-3">
            <p className="text-sm text-muted-foreground">{t("profilePhoneCodeSentTo", { phone })}</p>
            <OtpInput value={code} onChange={(e) => setCode(e.target.value)} />
            <Button type="submit" className="w-full" disabled={busy}>
              {busy ? <Loader2 className="animate-spin" /> : <MessageSquare />} {t("profileVerifyAndEnable")}
            </Button>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}

/**
 * Re-proves the account's own email address. There is no address field on purpose: the code goes to whatever
 * the account currently holds, so this can only prove the mailbox the user already has — never move the
 * address to one they merely typed.
 */
function EmailVerifyDialog({ open, onOpenChange, email, onVerified }: {
  open: boolean; onOpenChange: (open: boolean) => void; email: string; onVerified: () => void;
}) {
  const { t } = useTranslation(["auth"]);
  const [sent, setSent] = useState(false);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function send(event: React.FormEvent) {
    event.preventDefault();
    setError(null); setBusy(true);
    try {
      await requestEmailCode();
      setSent(true); setBusy(false);
    } catch {
      setError(t("profileEmailSendFailed"));
      setBusy(false);
    }
  }

  async function verify(event: React.FormEvent) {
    event.preventDefault();
    setError(null); setBusy(true);
    try {
      await confirmEmail(code);
      onVerified();
      onOpenChange(false);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 400
        ? t("profileEmailCodeInvalid") : t("profileEmailSendFailed"));
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t("profileEmailDialogTitle")}</DialogTitle>
          <DialogDescription>{t("profileEmailDialogDesc")}</DialogDescription>
        </DialogHeader>

        {error && <p className="text-sm text-destructive">{error}</p>}

        {!sent ? (
          <form onSubmit={send} className="space-y-3">
            <p className="text-sm text-muted-foreground">{email}</p>
            <Button type="submit" className="w-full" disabled={busy}>
              {busy ? <Loader2 className="animate-spin" /> : <Mail />} {t("emailMeCode")}
            </Button>
          </form>
        ) : (
          <form onSubmit={verify} className="space-y-3">
            <p className="text-sm text-muted-foreground">{t("profileEmailCodeSentTo", { email })}</p>
            <OtpInput value={code} onChange={(e) => setCode(e.target.value)} />
            <Button type="submit" className="w-full" disabled={busy}>
              {busy ? <Loader2 className="animate-spin" /> : <Mail />} {t("profileVerifyAndEnable")}
            </Button>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function Profile() {
  const { t } = useTranslation(["auth", "states"]);
  const confirmRevoke = useDeleteConfirm();
  const confirmDelete = useDeleteConfirm();
  const confirmAppLogout = useDeleteConfirm();
  const [totpOpen, setTotpOpen] = useState(false);
  const [phoneOpen, setPhoneOpen] = useState(false);
  const [emailOpen, setEmailOpen] = useState(false);
  const profile = useApiData<ProfileData>("/api/auth/profile");
  const sessions = useApiData<SessionDevice[]>("/api/auth/sessions");
  const appSessions = useApiData<AppSession[]>("/api/portal/app-sessions");

  async function removeTotp() {
    await confirmDelete({
      title: t("profileRemoveTotpTitle"),
      description: t("profileRemoveTotpDesc"),
      confirmText: t("remove"),
      run: () => disableTotp(),
      onDeleted: () => profile.reload(),
    });
  }

  async function unlinkPhone() {
    await confirmDelete({
      title: t("profileRemovePhoneTitle"),
      description: t("profileRemovePhoneDesc"),
      confirmText: t("remove"),
      run: () => removePhone(),
      onDeleted: () => profile.reload(),
    });
  }

  async function revoke(s: SessionDevice) {
    await confirmRevoke({
      title: s.current ? t("profileSignOutDeviceTitle") : t("profileRevokeSessionTitle"),
      description: s.current
        ? t("profileSignOutDeviceDesc")
        : t("profileRevokeSessionDesc", { device: s.device, ip: s.ip }),
      confirmText: s.current ? t("signOut") : t("revoke"),
      run: () => revokeSession(s.id),
      onDeleted: () => sessions.reload(),
    });
  }

  async function logoutApp(app: AppSession) {
    await confirmAppLogout({
      title: t("profileAppLogoutTitle"),
      description: t("profileAppLogoutDesc", { app: app.name }),
      confirmText: t("signOut"),
      run: async () => {
        await logoutAppSession(app.type, app.appId);
      },
      onDeleted: () => appSessions.reload(),
    });
  }

  return (
    <>
      <PageHeader
        title={t("profileTitle")}
        description={t("profileDescription")}
      />

      {/* Security factors ---------------------------------------------------- */}
      <h3 className="mb-3 text-sm font-semibold text-muted-foreground">{t("profileSecurityFactors")}</h3>
      {profile.error && <p className="mb-4 text-sm text-destructive">{profile.error}</p>}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <FactorCard
          icon={<Mail className="size-5" />}
          title={t("profileEmailTitle")}
          detail={profile.data?.email ?? undefined}
          badge={profile.data
            ? <Badge variant={profile.data.emailVerified ? "success" : "muted"}>{profile.data.emailVerified ? t("verified") : t("unverified")}</Badge>
            : <Badge variant="muted">…</Badge>}
          action={profile.data && !profile.data.emailVerified
            ? <Button size="sm" onClick={() => setEmailOpen(true)}><Mail /> {t("profileEmailVerifyBtn")}</Button>
            : undefined}
        />
        <FactorCard
          icon={<MessageSquare className="size-5" />}
          title={t("profilePhoneTitle")}
          detail={profile.data?.phoneNumber ?? t("profilePhoneNone")}
          badge={profile.data
            ? <Badge variant={profile.data.phoneVerified ? "success" : "muted"}>{profile.data.phoneVerified ? t("verified") : t("unverified")}</Badge>
            : <Badge variant="muted">…</Badge>}
          action={profile.data && (profile.data.phoneVerified
            ? <Button variant="outline" size="sm" onClick={unlinkPhone}><Trash2 /> {t("remove")}</Button>
            : <Button size="sm" onClick={() => setPhoneOpen(true)}><Plus /> {profile.data.phoneNumber ? t("profilePhoneVerifyBtn") : t("setUp")}</Button>)}
        />
        <FactorCard
          icon={<Smartphone className="size-5" />}
          title={t("profileTotpCardTitle")}
          detail={t("profileTotpDetail")}
          badge={profile.data
            ? <Badge variant={profile.data.totpEnrolled ? "success" : "muted"}>{profile.data.totpEnrolled ? t("enrolled") : t("notSetUp")}</Badge>
            : <Badge variant="muted">…</Badge>}
          action={profile.data && (profile.data.totpEnrolled
            ? <Button variant="outline" size="sm" onClick={removeTotp}><Trash2 /> {t("remove")}</Button>
            : <Button size="sm" onClick={() => setTotpOpen(true)}><Plus /> {t("setUp")}</Button>)}
        />
        <FactorCard
          icon={<KeyRound className="size-5" />}
          title={t("passkeys")}
          detail={t("profilePasskeysDetail")}
          badge={profile.data
            ? <Badge variant={profile.data.passkeyCount > 0 ? "success" : "muted"}>{t("profilePasskeysCount", { count: profile.data.passkeyCount })}</Badge>
            : <Badge variant="muted">…</Badge>}
          action={<Button asChild variant="outline" size="sm"><Link to="/passkeys"><KeyRound /> {t("managePasskeys")}</Link></Button>}
        />
      </div>

      <TotpSetupDialog open={totpOpen} onOpenChange={setTotpOpen} onEnrolled={profile.reload} />
      <EmailVerifyDialog open={emailOpen} onOpenChange={setEmailOpen} email={profile.data?.email ?? ""}
                         onVerified={profile.reload} />
      <PhoneSetupDialog open={phoneOpen} onOpenChange={setPhoneOpen} onEnrolled={profile.reload} />

      {profile.data && profile.data.roles.length > 0 && (
        <div className="mt-4 flex items-center gap-2 text-sm text-muted-foreground">
          <ShieldCheck className="size-4" />
          <span>{t("profileRolesLabel")}</span>
          {profile.data.roles.map((r) => <Badge key={r} variant="secondary">{r}</Badge>)}
        </div>
      )}

      {/* Passkeys ------------------------------------------------------------ */}
      <h3 className="mb-3 mt-8 text-sm font-semibold text-muted-foreground">{t("profilePasskeysSection")}</h3>
      <PasskeyManager onChanged={profile.reload} />

      {/* Active sessions ---------------------------------------------------- */}
      <h3 className="mb-3 mt-8 text-sm font-semibold text-muted-foreground">{t("profileActiveSessions")}</h3>
      <DataList
        data={sessions.data}
        error={sessions.error}
        errorAlways
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<MonitorSmartphone className="size-8" />} title={t("states:profileSessionsEmptyTitle")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Device</TableHead>
                <TableHead>{t("ipAddress")}</TableHead>
                <TableHead>{t("lastSeen")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((s) => (
                <TableRow key={s.id}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      <MonitorSmartphone className="size-4 text-muted-foreground" />
                      {s.device}
                      {s.current && <Badge variant="default">{t("thisDevice")}</Badge>}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{s.ip}</TableCell>
                  <TableCell className="text-muted-foreground">{s.lastSeenAt ? new Date(s.lastSeenAt).toLocaleString() : "—"}</TableCell>
                  <TableCell className="text-right">
                    <Button variant={s.current ? "ghost" : "outline"} size="sm" onClick={() => revoke(s)}>
                      <LogOut /> {s.current ? t("signOut") : t("revoke")}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>

      {/* Active app sessions (per-app SLO from the IdP) --------------------- */}
      <h3 className="mb-3 mt-8 text-sm font-semibold text-muted-foreground">{t("profileAppSessionsSection")}</h3>
      <DataList
        data={appSessions.data}
        error={appSessions.error}
        errorAlways
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<AppWindow className="size-8" />} title={t("states:profileAppSessionsEmptyTitle")} />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("profileAppSessionsCol")}</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((app) => (
                <TableRow key={`${app.type}:${app.appId}`}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      <AppWindow className="size-4 text-muted-foreground" />
                      {app.name}
                      <Badge variant="secondary">{app.type}</Badge>
                    </span>
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!app.oneClickLogoutSupported}
                      title={app.oneClickLogoutSupported ? undefined : t("profileAppLogoutUnsupported")}
                      onClick={() => logoutApp(app)}
                    >
                      <LogOut /> {t("signOut")}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
    </>
  );
}

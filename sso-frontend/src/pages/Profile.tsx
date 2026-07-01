import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { KeyRound, Loader2, Mail, MonitorSmartphone, Plus, ShieldCheck, Smartphone, LogOut, Trash2 } from "lucide-react";
import { ApiError } from "../api";
import { PageHeader } from "../components/PageHeader";
import PasskeyManager from "../components/PasskeyManager";
import { OtpInput } from "../components/auth/OtpInput";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent } from "../components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "../components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { DataList, EmptyState } from "../components/states";
import { useApiData } from "../useApiData";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { confirmTotp, disableTotp, revokeSession, setupTotp } from "../profile";
import type { Profile as ProfileData, SessionDevice, TotpSetup } from "../profile";

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
        ? "An authenticator is already enrolled."
        : "Could not start enrollment. Please try again."));
  }, [open]);

  async function verify(event: React.FormEvent) {
    event.preventDefault();
    setError(null); setBusy(true);
    try {
      await confirmTotp(code);
      onEnrolled();
      onOpenChange(false);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 400 ? "Invalid code, try again." : "Could not verify the code.");
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Set up authenticator app</DialogTitle>
          <DialogDescription>Scan the QR code with your authenticator app, then enter the 6-digit code to enable it.</DialogDescription>
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
                <summary className="cursor-pointer">Enter key manually</summary>
                <code className="mt-1 block break-all font-mono">{setup.secret}</code>
              </details>
            </div>
            <form onSubmit={verify} className="space-y-3">
              <OtpInput value={code} onChange={(e) => setCode(e.target.value)} />
              <Button type="submit" className="w-full" disabled={busy}>
                {busy ? <Loader2 className="animate-spin" /> : <Smartphone />} Verify &amp; enable
              </Button>
            </form>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function Profile() {
  const confirmRevoke = useDeleteConfirm();
  const confirmDelete = useDeleteConfirm();
  const [totpOpen, setTotpOpen] = useState(false);
  const profile = useApiData<ProfileData>("/api/auth/profile");
  const sessions = useApiData<SessionDevice[]>("/api/auth/sessions");

  async function removeTotp() {
    await confirmDelete({
      title: "Remove authenticator?",
      description: "Time-based one-time codes will no longer be accepted for your account.",
      confirmText: "Remove",
      run: () => disableTotp(),
      onDeleted: () => profile.reload(),
    });
  }

  async function revoke(s: SessionDevice) {
    await confirmRevoke({
      title: s.current ? "Sign out this device?" : "Revoke session?",
      description: s.current
        ? "You will be signed out on this device on your next request."
        : `The session on "${s.device}" (${s.ip}) will be ended.`,
      confirmText: s.current ? "Sign out" : "Revoke",
      run: () => revokeSession(s.id),
      onDeleted: () => sessions.reload(),
    });
  }

  return (
    <>
      <PageHeader
        title="My Profile"
        description="Manage your own security factors, passkeys, and active sessions."
      />

      {/* Security factors ---------------------------------------------------- */}
      <h3 className="mb-3 text-sm font-semibold text-muted-foreground">Security factors</h3>
      {profile.error && <p className="mb-4 text-sm text-destructive">{profile.error}</p>}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <FactorCard
          icon={<Mail className="size-5" />}
          title="Email"
          detail={profile.data?.email ?? undefined}
          badge={profile.data
            ? <Badge variant={profile.data.emailVerified ? "success" : "muted"}>{profile.data.emailVerified ? "Verified" : "Unverified"}</Badge>
            : <Badge variant="muted">…</Badge>}
        />
        <FactorCard
          icon={<Smartphone className="size-5" />}
          title="Authenticator app (TOTP)"
          detail="Time-based one-time codes"
          badge={profile.data
            ? <Badge variant={profile.data.totpEnrolled ? "success" : "muted"}>{profile.data.totpEnrolled ? "Enrolled" : "Not set up"}</Badge>
            : <Badge variant="muted">…</Badge>}
          action={profile.data && (profile.data.totpEnrolled
            ? <Button variant="outline" size="sm" onClick={removeTotp}><Trash2 /> Remove</Button>
            : <Button size="sm" onClick={() => setTotpOpen(true)}><Plus /> Set up</Button>)}
        />
        <FactorCard
          icon={<KeyRound className="size-5" />}
          title="Passkeys"
          detail="Passwordless sign-in + FIDO2"
          badge={profile.data
            ? <Badge variant={profile.data.passkeyCount > 0 ? "success" : "muted"}>{profile.data.passkeyCount} registered</Badge>
            : <Badge variant="muted">…</Badge>}
          action={<Button asChild variant="outline" size="sm"><Link to="/passkeys"><KeyRound /> Manage passkeys</Link></Button>}
        />
      </div>

      <TotpSetupDialog open={totpOpen} onOpenChange={setTotpOpen} onEnrolled={profile.reload} />

      {profile.data && profile.data.roles.length > 0 && (
        <div className="mt-4 flex items-center gap-2 text-sm text-muted-foreground">
          <ShieldCheck className="size-4" />
          <span>Roles:</span>
          {profile.data.roles.map((r) => <Badge key={r} variant="secondary">{r}</Badge>)}
        </div>
      )}

      {/* Passkeys ------------------------------------------------------------ */}
      <h3 className="mb-3 mt-8 text-sm font-semibold text-muted-foreground">Passkeys</h3>
      <PasskeyManager onChanged={profile.reload} />

      {/* Active sessions ---------------------------------------------------- */}
      <h3 className="mb-3 mt-8 text-sm font-semibold text-muted-foreground">Active sessions</h3>
      <DataList
        data={sessions.data}
        error={sessions.error}
        errorAlways
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<MonitorSmartphone className="size-8" />} title="No active sessions" />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Device</TableHead>
                <TableHead>IP address</TableHead>
                <TableHead>Last seen</TableHead>
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
                      {s.current && <Badge variant="default">This device</Badge>}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{s.ip}</TableCell>
                  <TableCell className="text-muted-foreground">{s.lastSeenAt ? new Date(s.lastSeenAt).toLocaleString() : "—"}</TableCell>
                  <TableCell className="text-right">
                    <Button variant={s.current ? "ghost" : "outline"} size="sm" onClick={() => revoke(s)}>
                      <LogOut /> {s.current ? "Sign out" : "Revoke"}
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

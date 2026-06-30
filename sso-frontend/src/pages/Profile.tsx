import type { ReactNode } from "react";
import { KeyRound, Mail, MonitorSmartphone, ShieldCheck, Smartphone, LogOut } from "lucide-react";
import { PageHeader } from "../components/PageHeader";
import PasskeyManager from "../components/PasskeyManager";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent } from "../components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { DataList, EmptyState } from "../components/states";
import { useApiData } from "../useApiData";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { revokeSession } from "../profile";
import type { Profile as ProfileData, SessionDevice } from "../profile";

/** A single security-factor card: icon + title + status badge + optional detail line. */
function FactorCard({ icon, title, badge, detail }: { icon: ReactNode; title: string; badge: ReactNode; detail?: string }) {
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
        </div>
      </CardContent>
    </Card>
  );
}

export default function Profile() {
  const confirmRevoke = useDeleteConfirm();
  const profile = useApiData<ProfileData>("/api/auth/profile");
  const sessions = useApiData<SessionDevice[]>("/api/auth/sessions");

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
        />
        <FactorCard
          icon={<KeyRound className="size-5" />}
          title="Passkeys"
          detail="Passwordless sign-in + FIDO2"
          badge={profile.data
            ? <Badge variant={profile.data.passkeyCount > 0 ? "success" : "muted"}>{profile.data.passkeyCount} registered</Badge>
            : <Badge variant="muted">…</Badge>}
        />
      </div>

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

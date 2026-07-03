import { Routes, Route, Navigate } from "react-router-dom";
import type { SessionView } from "./auth";
import AppShell from "./components/layout/AppShell";
import { StepUpProvider } from "./components/StepUpProvider";
import { SessionTimers } from "./components/SessionTimers";
import { AdminGuard, AdminCallback } from "./components/AdminPortal";
import Dashboard from "./pages/Dashboard";
import Users from "./pages/Users";
import UserDetail from "./pages/UserDetail";
import Roles from "./pages/Roles";
import RoleDetail from "./pages/RoleDetail";
import Groups from "./pages/Groups";
import Resources from "./pages/Resources";
import GroupDetail from "./pages/GroupDetail";
import Clients from "./pages/Clients";
import ClientDetail from "./pages/ClientDetail";
import RelyingParties from "./pages/RelyingParties";
import Audit from "./pages/Audit";
import ScimTokens from "./pages/ScimTokens";
import AuthPolicies from "./pages/AuthPolicies";
import Passkeys from "./pages/Passkeys";
import SessionPolicy from "./pages/SessionPolicy";
import IpRanges from "./pages/IpRanges";
import MyApps from "./pages/MyApps";
import Applications from "./pages/Applications";
import Profile from "./pages/Profile";

/**
 * Single SPA, two entry points: the USER portal ("/", the Account shell) and the ADMIN console
 * ("/admin/*", the Administration shell). Entering /admin runs a real OIDC + PKCE flow that forces
 * step-up at this IdP (see {@link AdminGuard} / adminPortal.ts). Step-up + session timers stay
 * mounted for both shells.
 */
export default function Console({ session }: { session: SessionView }) {
  return (
    <StepUpProvider session={session}>
      <SessionTimers />
      <Routes>
        {/* OIDC redirect target: completes the code exchange, then enters /admin. */}
        <Route path="/admin/callback" element={<AdminCallback />} />

        {/* ADMIN console — OIDC-gated Administration shell. */}
        <Route
          path="/admin/*"
          element={
            <AdminGuard>
              <AppShell session={session} variant="admin">
                <Routes>
                  <Route index element={<Navigate to="users" replace />} />
                  <Route path="users" element={<Users />} />
                  <Route path="users/:id" element={<UserDetail session={session} />} />
                  <Route path="roles" element={<Roles />} />
                  <Route path="roles/:id" element={<RoleDetail />} />
                  <Route path="groups" element={<Groups />} />
                  <Route path="groups/:id" element={<GroupDetail />} />
                  <Route path="resources" element={<Resources />} />
                  <Route path="applications" element={<Applications />} />
                  <Route path="clients" element={<Clients />} />
                  <Route path="clients/:id" element={<ClientDetail />} />
                  <Route path="relying-parties" element={<RelyingParties />} />
                  <Route path="auth-policies" element={<AuthPolicies />} />
                  <Route path="session-policy" element={<SessionPolicy />} />
                  <Route path="ip-ranges" element={<IpRanges />} />
                  <Route path="scim-tokens" element={<ScimTokens />} />
                  <Route path="audit" element={<Audit />} />
                  <Route path="*" element={<Navigate to="/admin" replace />} />
                </Routes>
              </AppShell>
            </AdminGuard>
          }
        />

        {/* USER portal — Account shell. */}
        <Route
          path="/*"
          element={
            <AppShell session={session} variant="user">
              <Routes>
                <Route index element={<Dashboard session={session} />} />
                <Route path="profile" element={<Profile />} />
                <Route path="apps" element={<MyApps />} />
                <Route path="passkeys" element={<Passkeys />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </AppShell>
          }
        />
      </Routes>
    </StepUpProvider>
  );
}

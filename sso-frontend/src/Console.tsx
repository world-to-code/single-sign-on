import { Routes, Route, Navigate } from "react-router-dom";
import type { SessionView } from "./auth";
import AppShell from "./components/layout/AppShell";
import { StepUpProvider } from "./components/StepUpProvider";
import { SessionTimers } from "./components/SessionTimers";
import { AdminGuard, AdminCallback } from "./components/AdminPortal";
import Dashboard from "./pages/Dashboard";
import Users from "./pages/Users";
import UserCreate from "./pages/UserCreate";
import UserDetail from "./pages/UserDetail";
import Organizations from "./pages/Organizations";
import Roles from "./pages/Roles";
import RoleDetail from "./pages/RoleDetail";
import Groups from "./pages/Groups";
import Resources from "./pages/Resources";
import GroupDetail from "./pages/GroupDetail";
import Clients from "./pages/Clients";
import ClientCreate from "./pages/ClientCreate";
import ClientDetail from "./pages/ClientDetail";
import RelyingParties from "./pages/RelyingParties";
import RelyingPartyDetail from "./pages/RelyingPartyDetail";
import Audit from "./pages/Audit";
import ScimTokens from "./pages/ScimTokens";
import AuthPolicies from "./pages/AuthPolicies";
import AuthPolicyDetail from "./pages/AuthPolicyDetail";
import Passkeys from "./pages/Passkeys";
import SessionPolicy from "./pages/SessionPolicy";
import SessionPolicyDetail from "./pages/SessionPolicyDetail";
import NetworkZones from "./pages/NetworkZones";
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
                  <Route path="organizations" element={<Organizations />} />
                  <Route path="users" element={<Users />} />
                  <Route path="users/new" element={<UserCreate />} />
                  <Route path="users/:id" element={<UserDetail session={session} />} />
                  <Route path="roles" element={<Roles />} />
                  <Route path="roles/:id" element={<RoleDetail />} />
                  <Route path="groups" element={<Groups />} />
                  <Route path="groups/:id" element={<GroupDetail />} />
                  <Route path="resources" element={<Resources />} />
                  <Route path="applications" element={<Applications />} />
                  <Route path="clients" element={<Clients />} />
                  <Route path="clients/new" element={<ClientCreate />} />
                  <Route path="clients/:id" element={<ClientDetail />} />
                  <Route path="relying-parties" element={<RelyingParties />} />
                  <Route path="relying-parties/new" element={<RelyingPartyDetail />} />
                  <Route path="relying-parties/:id" element={<RelyingPartyDetail />} />
                  <Route path="auth-policies" element={<AuthPolicies />} />
                  <Route path="auth-policies/new" element={<AuthPolicyDetail />} />
                  <Route path="auth-policies/:id" element={<AuthPolicyDetail />} />
                  <Route path="session-policy" element={<SessionPolicy />} />
                  <Route path="session-policy/new" element={<SessionPolicyDetail />} />
                  <Route path="session-policy/:id" element={<SessionPolicyDetail />} />
                  <Route path="network-zones" element={<NetworkZones />} />
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

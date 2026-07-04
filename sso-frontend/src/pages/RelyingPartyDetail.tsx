import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { apiGet, apiPost, apiPut, errorMessage, type Page } from "../api";
import { EditorPage } from "@/components/EditorPage";
import { SettingsSection } from "@/components/SettingsSection";
import { Field, Toggle } from "@/components/form/fields";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

interface RelyingParty {
  id: string;
  entityId: string;
  displayName: string | null;
  acsUrl: string;
  nameIdFormat: string;
  signAssertion: boolean;
  signResponse: boolean;
  encryptAssertion: boolean;
  signatureAlgorithm: string;
  dataEncryptionAlgorithm: string;
  keyTransportAlgorithm: string;
  wantAuthnRequestsSigned: boolean;
  allowIdpInitiated: boolean;
  signingCertificate: string | null;
  encryptionCertificate: string | null;
  spLoginUrl: string | null;
  singleLogoutUrl: string | null;
  sloBinding: string | null;
}

const blank = {
  id: null as string | null,
  entityId: "", displayName: "", acsUrl: "", nameIdFormat: "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress",
  signAssertion: true, signResponse: false, encryptAssertion: false,
  signatureAlgorithm: "RSA_SHA256", dataEncryptionAlgorithm: "AES256_GCM", keyTransportAlgorithm: "RSA_OAEP",
  wantAuthnRequestsSigned: false, allowIdpInitiated: true,
  signingCertificate: "", encryptionCertificate: "", spLoginUrl: "",
  singleLogoutUrl: "", sloBinding: "REDIRECT",
};
type Editor = typeof blank;

function toEditor(rp: RelyingParty): Editor {
  return {
    ...rp,
    displayName: rp.displayName ?? "",
    signingCertificate: rp.signingCertificate ?? "",
    encryptionCertificate: rp.encryptionCertificate ?? "",
    spLoginUrl: rp.spLoginUrl ?? "",
    singleLogoutUrl: rp.singleLogoutUrl ?? "",
    sloBinding: rp.sloBinding ?? "REDIRECT",
  };
}

/** Okta-style full-page create/edit for a SAML relying party (routes `relying-parties/new` + `:id`). */
export default function RelyingPartyDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const isNew = !id;
  const [editor, setEditor] = useState<Editor | null>(isNew ? { ...blank } : null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (isNew) return;
    apiGet<Page<RelyingParty>>("/api/admin/saml/relying-parties?size=100")
      .then((p) => {
        const found = p.items.find((x) => x.id === id);
        if (found) setEditor(toEditor(found));
        else setError("Relying party not found.");
      })
      .catch((e) => setError(errorMessage(e)));
  }, [id, isNew]);

  const set = (patch: Partial<Editor>) => setEditor((e) => (e ? { ...e, ...patch } : e));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editor) return;
    if (!editor.entityId.trim() || !editor.acsUrl.trim()) {
      setError("Entity ID and ACS URL are required.");
      return;
    }
    setError(null); setBusy(true);
    try {
      if (editor.id) await apiPut(`/api/admin/saml/relying-parties/${editor.id}`, editor);
      else await apiPost("/api/admin/saml/relying-parties", editor);
      navigate("/admin/relying-parties");
    } catch (e) {
      setError(errorMessage(e)); // a cancelled step-up maps to "" — form stays as-is
      setBusy(false);
    }
  }

  const crumbName = isNew ? "New relying party" : (editor?.entityId || "Edit");

  return (
    <EditorPage
      backTo="/admin/relying-parties" backLabel="SAML Relying Parties" crumb={crumbName}
      title={isNew ? "Register a relying party" : (editor?.displayName || editor?.entityId || "…")}
      description="Endpoint and per-RP SAML signing and encryption configuration."
      error={error} formId="rp-form" onSubmit={submit} busy={busy}
      submitLabel={isNew ? "Register" : "Save changes"}
      onCancel={() => navigate("/admin/relying-parties")} loading={!editor}
    >
      {editor && (
        <>
          <SettingsSection title="Endpoint" description="How the service provider is identified and where assertions are posted.">
            <Field label="Entity ID" hint={editor.id ? "The entity ID can't be changed after registration." : undefined}>
              <Input value={editor.entityId} disabled={!!editor.id} onChange={(e) => set({ entityId: e.target.value })} />
            </Field>
            <Field label="Display name" hint="Friendly name shown in app lists (defaults to the entity ID).">
              <Input value={editor.displayName} onChange={(e) => set({ displayName: e.target.value })} />
            </Field>
            <Field label="ACS URL" hint="Assertion Consumer Service — where the SAML response is posted.">
              <Input value={editor.acsUrl} onChange={(e) => set({ acsUrl: e.target.value })} />
            </Field>
            <Field label="NameID format">
              <Input value={editor.nameIdFormat} onChange={(e) => set({ nameIdFormat: e.target.value })} />
            </Field>
            <Field label="SP-initiated login URL"
                   hint="Portal “launch” redirects here so the SP starts SP-initiated SSO. Blank = IdP-initiated (unsolicited) SSO.">
              <Input value={editor.spLoginUrl} placeholder="https://sp.example.com/login"
                     onChange={(e) => set({ spLoginUrl: e.target.value })} />
            </Field>
            <Field label="Single Logout URL"
                   hint="Where the IdP sends LogoutRequests when the user's session ends. Blank = no SLO for this SP.">
              <Input value={editor.singleLogoutUrl} placeholder="https://sp.example.com/saml/slo"
                     onChange={(e) => set({ singleLogoutUrl: e.target.value })} />
            </Field>
            <Field label="Logout binding"
                   hint="REDIRECT/POST are front-channel (explicit logout only). SOAP is back-channel — also logs out on idle expiry.">
              <Select value={editor.sloBinding} onChange={(e) => set({ sloBinding: e.target.value })}>
                <option value="REDIRECT">Redirect (front-channel)</option>
                <option value="POST">POST (front-channel)</option>
                <option value="SOAP">SOAP (back-channel)</option>
              </Select>
            </Field>
          </SettingsSection>

          <SettingsSection title="Signing" description="Whether the IdP signs the outgoing assertion and/or response, and with which algorithm.">
            <Toggle label="Sign assertion" checked={editor.signAssertion} onChange={(v) => set({ signAssertion: v })} />
            <Toggle label="Sign response" checked={editor.signResponse} onChange={(v) => set({ signResponse: v })} />
            <Field label="Signature algorithm">
              <Select value={editor.signatureAlgorithm} onChange={(e) => set({ signatureAlgorithm: e.target.value })}>
                <option>RSA_SHA256</option><option>RSA_SHA512</option><option value="RSA_SHA1">RSA_SHA1 (legacy)</option>
              </Select>
            </Field>
          </SettingsSection>

          <SettingsSection title="Assertion encryption" description="Encrypt the assertion to the SP's certificate so only it can read the claims.">
            <Toggle label="Encrypt assertion" checked={editor.encryptAssertion} onChange={(v) => set({ encryptAssertion: v })} />
            <div className="grid gap-5 sm:grid-cols-2">
              <Field label="Data algorithm">
                <Select value={editor.dataEncryptionAlgorithm} onChange={(e) => set({ dataEncryptionAlgorithm: e.target.value })}>
                  <option>AES256_GCM</option><option>AES128_GCM</option>
                  <option value="AES256_CBC">AES256_CBC (legacy)</option><option value="AES128_CBC">AES128_CBC (legacy)</option>
                </Select>
              </Field>
              <Field label="Key transport">
                <Select value={editor.keyTransportAlgorithm} onChange={(e) => set({ keyTransportAlgorithm: e.target.value })}>
                  <option>RSA_OAEP</option><option value="RSA_1_5">RSA_1_5 (legacy)</option>
                </Select>
              </Field>
            </div>
            <Field label="SP encryption certificate (PEM)">
              <Textarea rows={3} value={editor.encryptionCertificate} onChange={(e) => set({ encryptionCertificate: e.target.value })} />
            </Field>
          </SettingsSection>

          <SettingsSection title="Inbound AuthnRequest" description="Verify AuthnRequests the SP sends against its signing certificate.">
            <Toggle label="Require signed AuthnRequests" checked={editor.wantAuthnRequestsSigned} onChange={(v) => set({ wantAuthnRequestsSigned: v })} />
            <Field label="SP signing certificate (PEM)">
              <Textarea rows={3} value={editor.signingCertificate} onChange={(e) => set({ signingCertificate: e.target.value })} />
            </Field>
          </SettingsSection>

          <SettingsSection title="IdP-initiated SSO" description="Allow this IdP to push an unsolicited assertion to the SP (portal launch).">
            <Toggle label="Allow IdP-initiated SSO" checked={editor.allowIdpInitiated} onChange={(v) => set({ allowIdpInitiated: v })} />
          </SettingsSection>
        </>
      )}
    </EditorPage>
  );
}

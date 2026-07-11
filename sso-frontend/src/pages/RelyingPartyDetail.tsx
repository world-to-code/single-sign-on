import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { useTranslation } from "react-i18next";
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
  const { t } = useTranslation("console");
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
        else setError(t("rpDetailNotFound"));
      })
      .catch((e) => setError(errorMessage(e)));
  }, [id, isNew]);

  const set = (patch: Partial<Editor>) => setEditor((e) => (e ? { ...e, ...patch } : e));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!editor) return;
    if (!editor.entityId.trim() || !editor.acsUrl.trim()) {
      setError(t("rpDetailRequired"));
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

  const crumbName = isNew ? t("rpDetailNewCrumb") : (editor?.entityId || t("rpDetailEditCrumb"));

  return (
    <EditorPage
      backTo="/admin/relying-parties" backLabel={t("rpDetailBack")} crumb={crumbName}
      title={isNew ? t("rpDetailNewTitle") : (editor?.displayName || editor?.entityId || "…")}
      description={t("rpDetailDescription")}
      error={error} formId="rp-form" onSubmit={submit} busy={busy}
      submitLabel={isNew ? t("rpDetailRegister") : t("saveChanges")}
      onCancel={() => navigate("/admin/relying-parties")} loading={!editor}
    >
      {editor && (
        <>
          <SettingsSection title={t("rpDetailEndpoint")} description={t("rpDetailEndpointDesc")}>
            <Field label={t("rpDetailEntityId")} hint={editor.id ? t("rpDetailEntityIdHint") : undefined}>
              <Input value={editor.entityId} disabled={!!editor.id} onChange={(e) => set({ entityId: e.target.value })} />
            </Field>
            <Field label={t("rpDetailDisplayName")} hint={t("rpDetailDisplayNameHint")}>
              <Input value={editor.displayName} onChange={(e) => set({ displayName: e.target.value })} />
            </Field>
            <Field label={t("rpDetailAcsUrl")} hint={t("rpDetailAcsUrlHint")}>
              <Input value={editor.acsUrl} onChange={(e) => set({ acsUrl: e.target.value })} />
            </Field>
            <Field label={t("rpDetailNameIdFormat")}>
              <Input value={editor.nameIdFormat} onChange={(e) => set({ nameIdFormat: e.target.value })} />
            </Field>
            <Field label={t("rpDetailSpLoginUrl")}
                   hint={t("rpDetailSpLoginUrlHint")}>
              <Input value={editor.spLoginUrl} placeholder="https://sp.example.com/login"
                     onChange={(e) => set({ spLoginUrl: e.target.value })} />
            </Field>
            <Field label={t("rpDetailSloUrl")}
                   hint={t("rpDetailSloUrlHint")}>
              <Input value={editor.singleLogoutUrl} placeholder="https://sp.example.com/saml/slo"
                     onChange={(e) => set({ singleLogoutUrl: e.target.value })} />
            </Field>
            <Field label={t("rpDetailLogoutBinding")}
                   hint={t("rpDetailLogoutBindingHint")}>
              <Select value={editor.sloBinding} onChange={(e) => set({ sloBinding: e.target.value })}>
                <option value="REDIRECT">{t("rpDetailBindingRedirect")}</option>
                <option value="POST">{t("rpDetailBindingPost")}</option>
                <option value="SOAP">{t("rpDetailBindingSoap")}</option>
              </Select>
            </Field>
          </SettingsSection>

          <SettingsSection title={t("rpDetailSigning")} description={t("rpDetailSigningDesc")}>
            <Toggle label={t("rpDetailSignAssertion")} checked={editor.signAssertion} onChange={(v) => set({ signAssertion: v })} />
            <Toggle label={t("rpDetailSignResponse")} checked={editor.signResponse} onChange={(v) => set({ signResponse: v })} />
            <Field label={t("rpDetailSignatureAlg")}>
              <Select value={editor.signatureAlgorithm} onChange={(e) => set({ signatureAlgorithm: e.target.value })}>
                <option>RSA_SHA256</option><option>RSA_SHA512</option><option value="RSA_SHA1">{t("rpDetailRsaSha1Legacy")}</option>
              </Select>
            </Field>
          </SettingsSection>

          <SettingsSection title={t("rpDetailEncryption")} description={t("rpDetailEncryptionDesc")}>
            <Toggle label={t("rpDetailEncryptAssertion")} checked={editor.encryptAssertion} onChange={(v) => set({ encryptAssertion: v })} />
            <div className="grid gap-5 sm:grid-cols-2">
              <Field label={t("rpDetailDataAlg")}>
                <Select value={editor.dataEncryptionAlgorithm} onChange={(e) => set({ dataEncryptionAlgorithm: e.target.value })}>
                  <option>AES256_GCM</option><option>AES128_GCM</option>
                  <option value="AES256_CBC">{t("rpDetailAes256CbcLegacy")}</option><option value="AES128_CBC">{t("rpDetailAes128CbcLegacy")}</option>
                </Select>
              </Field>
              <Field label={t("rpDetailKeyTransport")}>
                <Select value={editor.keyTransportAlgorithm} onChange={(e) => set({ keyTransportAlgorithm: e.target.value })}>
                  <option>RSA_OAEP</option><option value="RSA_1_5">{t("rpDetailRsa15Legacy")}</option>
                </Select>
              </Field>
            </div>
            <Field label={t("rpDetailEncCert")}>
              <Textarea rows={3} value={editor.encryptionCertificate} onChange={(e) => set({ encryptionCertificate: e.target.value })} />
            </Field>
          </SettingsSection>

          <SettingsSection title={t("rpDetailInbound")} description={t("rpDetailInboundDesc")}>
            <Toggle label={t("rpDetailRequireSigned")} checked={editor.wantAuthnRequestsSigned} onChange={(v) => set({ wantAuthnRequestsSigned: v })} />
            <Field label={t("rpDetailSignCert")}>
              <Textarea rows={3} value={editor.signingCertificate} onChange={(e) => set({ signingCertificate: e.target.value })} />
            </Field>
          </SettingsSection>

          <SettingsSection title={t("rpDetailIdpInit")} description={t("rpDetailIdpInitDesc")}>
            <Toggle label={t("rpDetailAllowIdpInit")} checked={editor.allowIdpInitiated} onChange={(v) => set({ allowIdpInitiated: v })} />
          </SettingsSection>
        </>
      )}
    </EditorPage>
  );
}

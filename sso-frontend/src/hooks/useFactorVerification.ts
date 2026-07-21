import { useCallback, useEffect, useState } from "react";
import type { Dispatch, FormEvent, SetStateAction } from "react";
import { ApiError, errorMessage } from "@/api";
import { prepareFactor, verifyFactor } from "@/auth";
import { requestEmailCode } from "@/profile";
import type { SessionView } from "@/auth";
import { assertFactorCredential, registerFactorCredential } from "@/webauthn";

export interface FactorVerificationState {
  factor: string;
  setFactor: Dispatch<SetStateAction<string>>;
  code: string;
  setCode: Dispatch<SetStateAction<string>>;
  password: string;
  setPassword: Dispatch<SetStateAction<string>>;
  codeSent: boolean;
  error: string | null;
  setError: Dispatch<SetStateAction<string | null>>;
  busy: boolean;
  setBusy: Dispatch<SetStateAction<boolean>>;
  submitCode: (event: FormEvent) => Promise<void>;
  submitPassword: (event: FormEvent) => Promise<void>;
  sendCode: () => Promise<void>;
  /** The selected code factor refuses to send because the address behind it was never proven. */
  addressUnverified: boolean;
  /** Mails a proof-of-ownership code so the owner can unlock the factor without leaving this screen. */
  sendAddressVerification: () => Promise<void>;
  addressVerificationSent: boolean;
  fido2: () => Promise<void>;
  fido2Register: () => Promise<void>;
}

/**
 * Shared state machine for the factor-collection screens (login MFA + per-app step-up): tracks the
 * selected factor and its inputs, and runs prepare/verify for the password, TOTP/email-and-SMS-code and
 * passkey factors with consistent busy/error handling. `onSuccess` receives the resolved session.
 */
export function useFactorVerification(
  { initialFactor, onSuccess }: { initialFactor: string; onSuccess: (session: SessionView) => void | Promise<void> },
): FactorVerificationState {
  const [factor, setFactor] = useState(initialFactor);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [codeSent, setCodeSent] = useState(false);
  const [addressUnverified, setAddressUnverified] = useState(false);
  const [addressVerificationSent, setAddressVerificationSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // One set of inputs is reused across factors: clear them whenever the active factor changes.
  useEffect(() => {
    setCode(""); setPassword(""); setCodeSent(false); setError(null); setBusy(false);
    setAddressUnverified(false); setAddressVerificationSent(false);
  }, [factor]);

  const run = useCallback(async (
    action: () => Promise<SessionView>,
    apiMessage: string,
    genericMessage = "Verification failed.",
  ) => {
    setError(null); setBusy(true);
    try {
      const session = await action();
      await onSuccess(session);
      setBusy(false);
    } catch (e) {
      setError(e instanceof ApiError ? apiMessage : genericMessage);
      setBusy(false);
    }
  }, [onSuccess]);

  const submitCode = useCallback((event: FormEvent) => {
    event.preventDefault();
    return run(() => verifyFactor(factor, { code }), "Invalid code — try again.");
  }, [run, factor, code]);

  const submitPassword = useCallback((event: FormEvent) => {
    event.preventDefault();
    return run(() => verifyFactor("PASSWORD", { password }), "Incorrect password.");
  }, [run, password]);

  const fido2 = useCallback(() => run(async () => {
    const prepared = await prepareFactor("FIDO2");
    return verifyFactor("FIDO2", { credential: await assertFactorCredential(prepared) });
  }, "Passkey verification failed.", "Passkey ceremony was cancelled or failed."), [run]);

  // Enroll-at-login: register a brand-new passkey, then the same prepare/verify grants the factor.
  const fido2Register = useCallback(() => run(async () => {
    const prepared = await prepareFactor("FIDO2");
    return verifyFactor("FIDO2", { credential: await registerFactorCredential(prepared) });
  }, "Passkey registration failed.", "Passkey registration was cancelled or failed."), [run]);

  // Sends a code for the CURRENTLY selected code factor (EMAIL or SMS); the backend prepare texts/emails it.
  const sendCode = useCallback(async () => {
    setError(null); setAddressUnverified(false);
    try {
      await prepareFactor(factor);
      setCodeSent(true);
    } catch (e) {
      // A 403 here is not a failure to send — it is the factor refusing an address nobody has proven. Saying
      // "could not send, try again" invites the one action that cannot possibly work; surface the server's
      // (localized) reason and offer the step that actually unblocks it.
      if (e instanceof ApiError && e.status === 403 && factor === "EMAIL") {
        setAddressUnverified(true);
      }
      setError(errorMessage(e));
    }
  }, [factor]);

  const sendAddressVerification = useCallback(async () => {
    setError(null);
    try {
      await requestEmailCode();
      setAddressVerificationSent(true);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  return {
    factor, setFactor, code, setCode, password, setPassword, codeSent,
    error, setError, busy, setBusy, submitCode, submitPassword, sendCode, fido2, fido2Register,
    addressUnverified, sendAddressVerification, addressVerificationSent,
  };
}

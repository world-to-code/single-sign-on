import { useCallback, useEffect, useState } from "react";
import type { Dispatch, FormEvent, SetStateAction } from "react";
import { ApiError } from "@/api";
import { prepareFactor, verifyFactor } from "@/auth";
import type { SessionView } from "@/auth";
import { assertFactorCredential, registerFactorCredential } from "@/webauthn";

export interface FactorVerificationState {
  factor: string;
  setFactor: Dispatch<SetStateAction<string>>;
  code: string;
  setCode: Dispatch<SetStateAction<string>>;
  password: string;
  setPassword: Dispatch<SetStateAction<string>>;
  emailSent: boolean;
  error: string | null;
  setError: Dispatch<SetStateAction<string | null>>;
  busy: boolean;
  setBusy: Dispatch<SetStateAction<boolean>>;
  submitCode: (event: FormEvent) => Promise<void>;
  submitPassword: (event: FormEvent) => Promise<void>;
  sendEmail: () => Promise<void>;
  fido2: () => Promise<void>;
  fido2Register: () => Promise<void>;
}

/**
 * Shared state machine for the factor-collection screens (login MFA + per-app step-up): tracks the
 * selected factor and its inputs, and runs prepare/verify for the password, TOTP/email-code and
 * passkey factors with consistent busy/error handling. `onSuccess` receives the resolved session.
 */
export function useFactorVerification(
  { initialFactor, onSuccess }: { initialFactor: string; onSuccess: (session: SessionView) => void | Promise<void> },
): FactorVerificationState {
  const [factor, setFactor] = useState(initialFactor);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [emailSent, setEmailSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // One set of inputs is reused across factors: clear them whenever the active factor changes.
  useEffect(() => {
    setCode(""); setPassword(""); setEmailSent(false); setError(null); setBusy(false);
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

  const sendEmail = useCallback(async () => {
    setError(null);
    try {
      await prepareFactor("EMAIL");
      setEmailSent(true);
    } catch {
      setError("Could not send the code. Try again.");
    }
  }, []);

  return {
    factor, setFactor, code, setCode, password, setPassword, emailSent,
    error, setError, busy, setBusy, submitCode, submitPassword, sendEmail, fido2, fido2Register,
  };
}

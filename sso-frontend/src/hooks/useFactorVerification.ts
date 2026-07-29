import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { Dispatch, FormEvent, SetStateAction } from "react";
import { ApiError, errorMessage } from "@/api";
import { factorDeliveryFailed, prepareFactor, verifyFactor } from "@/auth";
import { requestEmailCode } from "@/profile";
import type { SessionView } from "@/auth";
import { assertFactorCredential, registerFactorCredential } from "@/webauthn";

/**
 * When to ask whether the code actually went out, in millis BETWEEN checks.
 *
 * <p>A refusal comes back in well under a second, so the early checks catch nearly everything. The window as a
 * whole must outlast the SLOWEST way a send can fail, which is a connection timing out rather than being
 * refused — an SMTP relay is given ten seconds. The first version of this stopped at eight, so the one failure
 * people actually hit, a blocked submission port, was the one it could never report.
 */
const DELIVERY_CHECKS_MS = [1500, 2500, 4000, 4000, 4000];

/**
 * How long before another code may be requested. A text costs the tenant money per message and arrives with a
 * delay people underestimate, so an unthrottled resend button is a button that gets pressed five times.
 */
const RESEND_COOLDOWN_MS = 30_000;

export interface FactorVerificationState {
  factor: string;
  setFactor: Dispatch<SetStateAction<string>>;
  code: string;
  setCode: Dispatch<SetStateAction<string>>;
  password: string;
  setPassword: Dispatch<SetStateAction<string>>;
  codeSent: boolean;
  /** Seconds the sent code remains usable; 0 once it has expired or when none was sent. */
  codeSecondsLeft: number;
  /** Seconds until another code may be requested; 0 when it may be requested now. */
  resendSecondsLeft: number;
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
  const { t } = useTranslation("auth");
  const [factor, setFactor] = useState(initialFactor);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [codeSent, setCodeSent] = useState(false);
  const [codeExpiresAt, setCodeExpiresAt] = useState<number | null>(null);
  const [resendAt, setResendAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [addressUnverified, setAddressUnverified] = useState(false);
  const [addressVerificationSent, setAddressVerificationSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // One set of inputs is reused across factors: clear them whenever the active factor changes.
  useEffect(() => {
    setCode(""); setPassword(""); setCodeSent(false); setError(null); setBusy(false);
    setCodeExpiresAt(null); setResendAt(null);
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

  /**
   * Asks, for a short while, whether the code actually went out.
   *
   * <p>The send is deliberately off the request thread, so `prepare` has already answered by the time it can
   * fail — leaving somebody staring at a code box for a text that is never coming. Polling briefly is the only
   * way to tell them without putting the provider's latency back on the login path.
   *
   * <p>It stops at the first failure and gives up after the window: a code that has not failed by then has
   * been handed to the provider, and anything after that is the carrier's business, not ours.
   */
  const watchDelivery = useCallback(async () => {
    for (const waitMs of DELIVERY_CHECKS_MS) {
      await new Promise((resume) => setTimeout(resume, waitMs));
      // A check that itself fails says nothing about the send; leave the screen as it is.
      const failed = await factorDeliveryFailed(factor).catch(() => false);
      if (failed) {
        setError(t("factorCodeNotDelivered"));
        return;
      }
    }
  }, [factor, t]);

  // Sends a code for the CURRENTLY selected code factor (EMAIL or SMS); the backend prepare texts/emails it.
  const sendCode = useCallback(async () => {
    setError(null); setAddressUnverified(false);
    try {
      const challenge = await prepareFactor(factor);
      setCodeSent(true);
      // Both clocks start from the answer, not from the send: the person's wait began when the screen changed.
      setNow(Date.now());
      setCodeExpiresAt(challenge.expiresInSeconds > 0 ? Date.now() + challenge.expiresInSeconds * 1000 : null);
      setResendAt(Date.now() + RESEND_COOLDOWN_MS);
      void watchDelivery();
    } catch (e) {
      // A 403 here is not a failure to send — it is the factor refusing an address nobody has proven. Saying
      // "could not send, try again" invites the one action that cannot possibly work; surface the server's
      // (localized) reason and offer the step that actually unblocks it.
      if (e instanceof ApiError && e.status === 403 && factor === "EMAIL") {
        setAddressUnverified(true);
      }
      setError(errorMessage(e));
    }
  }, [factor, watchDelivery]);


  // One ticker for both countdowns, running only while something is counting down.
  useEffect(() => {
    if (codeExpiresAt === null && resendAt === null) {
      return;
    }
    const tick = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(tick);
  }, [codeExpiresAt, resendAt]);

  const secondsUntil = (deadline: number | null) =>
    deadline === null ? 0 : Math.max(0, Math.ceil((deadline - now) / 1000));

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
    codeSecondsLeft: secondsUntil(codeExpiresAt), resendSecondsLeft: secondsUntil(resendAt),
    error, setError, busy, setBusy, submitCode, submitPassword, sendCode, fido2, fido2Register,
    addressUnverified, sendAddressVerification, addressVerificationSent,
  };
}

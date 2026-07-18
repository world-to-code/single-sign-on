import { Fingerprint, KeyRound, Lock, Mail, MessageSquare, Smartphone } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { auth as authResources } from "@/i18n/en/auth";

/** Canonical authentication factor identifiers used across login, step-up and policy UIs. */
export const FACTORS = ["PASSWORD", "TOTP", "EMAIL", "SMS", "FIDO2"] as const;
export type Factor = (typeof FACTORS)[number];

/** A key in the `auth` i18n namespace; the render site resolves it via t() bound to that namespace. */
type FactorLabelKey = keyof typeof authResources;

interface FactorMeta {
  label: FactorLabelKey;
  icon: LucideIcon;
}

const META: Record<string, FactorMeta> = {
  PASSWORD: { label: "factorPassword", icon: Lock },
  TOTP: { label: "factorTotp", icon: Smartphone },
  EMAIL: { label: "factorEmail", icon: Mail },
  SMS: { label: "factorSms", icon: MessageSquare },
  FIDO2: { label: "factorPasskey", icon: Fingerprint },
};

/**
 * Presentation metadata for a factor id. `label` is an i18n key resolved with t() by the caller; an
 * unknown factor falls back to its raw id, which t() returns unchanged when it matches no key.
 */
export function factorMeta(factor: string): FactorMeta {
  return META[factor] ?? { label: factor as FactorLabelKey, icon: KeyRound };
}

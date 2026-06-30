import { Fingerprint, KeyRound, Lock, Mail, Smartphone } from "lucide-react";
import type { LucideIcon } from "lucide-react";

/** Canonical authentication factor identifiers used across login, step-up and policy UIs. */
export const FACTORS = ["PASSWORD", "TOTP", "EMAIL", "FIDO2"] as const;
export type Factor = (typeof FACTORS)[number];

interface FactorMeta {
  label: string;
  icon: LucideIcon;
}

const META: Record<string, FactorMeta> = {
  PASSWORD: { label: "Password", icon: Lock },
  TOTP: { label: "Authenticator app", icon: Smartphone },
  EMAIL: { label: "Email code", icon: Mail },
  FIDO2: { label: "Passkey", icon: Fingerprint },
};

/** Presentation metadata (label + icon) for a factor id, with a safe fallback for unknown values. */
export function factorMeta(factor: string): FactorMeta {
  return META[factor] ?? { label: factor, icon: KeyRound };
}

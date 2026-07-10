import type { auth as enAuth } from "../en/auth";

export const auth: Record<keyof typeof enAuth, string> = {
  factorPassword: "비밀번호",
  factorTotp: "인증 앱",
  factorEmail: "이메일 코드",
  factorPasskey: "패스키",
};

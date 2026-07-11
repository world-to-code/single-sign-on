import type { validation as enValidation } from "../en/validation";

export const validation: Record<keyof typeof enValidation, string> = {
  oneFieldNeedsAttention: "확인이 필요한 항목 1개",
  fieldsNeedAttention: "확인이 필요한 항목 {{count}}개",
};

import type { errors as enErrors } from "../en/errors";

export const errors: Record<keyof typeof enErrors, string> = {
  badRequest: "입력값을 확인하세요.",
  unauthorized: "다시 인증해야 합니다. 다시 시도하세요.",
  forbidden: "이 작업을 수행할 권한이 없습니다.",
  notFound: "찾을 수 없습니다. 이미 삭제되었을 수 있습니다.",
  conflict: "변경사항이 적용되지 않았습니다.",
  failed: "요청을 처리하지 못했습니다 ({{status}}).",
};

import type { console as enConsole } from "../en/console";

export const console: Record<keyof typeof enConsole, string> = {
  editorCancel: "취소",
  editorNothingToSave: "저장할 변경사항 없음",
};

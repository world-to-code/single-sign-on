import type { common as enCommon } from "../en/common";

export const common: Record<keyof typeof enCommon, string> = {
  appName: "Mini SSO",
  brandSubtitle: "아이덴티티 제공자",

  // Shared controls --------------------------------------------------------
  cancel: "취소",
  confirm: "확인",
  close: "닫기",
  dismiss: "닫기",
  remove: "삭제",
  loading: "불러오는 중",
  searching: "검색 중…",
  noMatches: "검색 결과 없음",
  noOptions: "선택 항목 없음",
  copy: "복사",
  copyToClipboard: "클립보드에 복사",
  moreInformation: "자세히 보기",
  searchUsersPlaceholder: "이름으로 사용자 검색…",

  // ConfirmProvider — type-to-confirm --------------------------------------
  confirmPhraseLabel: "확인하려면 <0>{{phrase}}</0> 을(를) 입력하세요",
  confirmPhraseAria: "확인하려면 {{phrase}} 을(를) 입력하세요",

  // Pagination -------------------------------------------------------------
  paginationSummary: "{{pages}}페이지 중 {{page}}페이지 · 총 {{total}}건",
  paginationPrevious: "이전",
  paginationNext: "다음",

  // Charts -----------------------------------------------------------------
  signInTrendAlt: "일별 로그인 추이",
  signInTrendEmpty: "이 기간에 기록된 로그인이 없습니다.",
  signInTrendSuccessful: "성공",
  signInTrendFailed: "실패",
};

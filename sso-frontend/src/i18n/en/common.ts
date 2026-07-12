// Copy shared by the cross-cutting building blocks (Brand, confirm/toast providers, pagination,
// copy fields, pickers) — anything with no page of its own. Page copy lives in its own namespace.
export const common = {
  appName: "Mini SSO",
  brandSubtitle: "Identity Provider",

  // Shared controls --------------------------------------------------------
  cancel: "Cancel",
  confirm: "Confirm",
  close: "Close",
  dismiss: "Dismiss",
  remove: "Remove",
  loading: "Loading",
  searching: "Searching…",
  noMatches: "No matches",
  noOptions: "No options",
  copy: "Copy",
  copyToClipboard: "Copy to clipboard",
  moreInformation: "More information",
  searchUsersPlaceholder: "Search users by name…",

  // ConfirmProvider — type-to-confirm --------------------------------------
  confirmPhraseLabel: "Type <0>{{phrase}}</0> to confirm",
  confirmPhraseAria: "Type {{phrase}} to confirm",

  // Pagination -------------------------------------------------------------
  paginationSummary: "Page {{page}} of {{pages}} · {{total}} total",
  paginationPrevious: "Previous",
  paginationNext: "Next",

  // Charts -----------------------------------------------------------------
  signInTrendAlt: "Daily sign-in trend",
  signInTrendEmpty: "No sign-ins recorded in this period.",
  signInTrendSuccessful: "Successful",
  signInTrendFailed: "Failed",
} as const;

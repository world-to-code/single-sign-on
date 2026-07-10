// Generic status fallbacks for errorMessage(). A 400/409 prefers the server's localized ProblemDetail
// `detail`; these render only when the server sent none, or for statuses that carry no useful detail.
export const errors = {
  badRequest: "Invalid input — please check the form.",
  unauthorized: "Re-authentication required — please retry.",
  forbidden: "You don't have permission for this action.",
  notFound: "Not found — it may have been removed.",
  conflict: "Conflict — the change wasn't applied.",
  failed: "Request failed ({{status}}).",
} as const;

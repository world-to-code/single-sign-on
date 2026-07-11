// Generic status fallbacks for errorMessage(). A 400/409 prefers the server's localized ProblemDetail
// `detail`; these render only when the server sent none, or for statuses that carry no useful detail.
export const errors = {
  badRequest: "Invalid input — please check the form.",
  unauthorized: "Re-authentication required — please retry.",
  forbidden: "You don't have permission for this action.",
  notFound: "Not found — it may have been removed.",
  conflict: "Conflict — the change wasn't applied.",
  failed: "Request failed ({{status}}).",

  // The four load-failure panels (DESIGN.md §5). A 403 discloses nothing about existence; only
  // network/server failures are retryable and carry a trace ID. Titles say what happened, never apologise.
  failureNetworkTitle: "Couldn't reach the server",
  failureNetworkHint: "Check your connection, then try again.",
  failureForbiddenTitle: "You don't have permission to view this",
  failureForbiddenHint: "Ask an administrator if you need access to this area.",
  failureNotFoundTitle: "Not found",
  failureNotFoundHint: "It may have been removed, or the link is out of date.",
  failureServerTitle: "The request couldn't be completed",
  failureServerHint: "This is on our side. Try again in a moment.",
  failureRetry: "Try again",
  failureTraceId: "Trace ID {{id}}",
} as const;

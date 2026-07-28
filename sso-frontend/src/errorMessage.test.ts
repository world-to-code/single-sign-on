import { beforeEach, describe, expect, it } from "vitest";
import i18n from "i18next";
import { ApiError, StepUpCancelledError, errorMessage } from "./api";

/**
 * What the user is actually shown when a request is refused.
 *
 * <p>The defect this pins: the server's `detail` is preferred for every status, and that is only correct
 * because every 4xx detail is now resolved from the message bundle against Accept-Language. While three of
 * the five backend handlers built their detail in English, a Korean console rendered English — the response
 * and the screen disagreeing on language rather than on meaning. If a handler ever goes back to a hard-coded
 * string, this file cannot catch it (it is a server-side property, covered by GlobalExceptionHandlerI18nTest);
 * what it CAN catch is this side dropping a detail the server took the trouble to localize.
 */
describe("errorMessage", () => {
  beforeEach(async () => {
    if (!i18n.isInitialized) {
      await i18n.init({ lng: "en", resources: {}, interpolation: { escapeValue: false } });
    }
    i18n.addResourceBundle("en", "errors", {
      badRequest: "generic-400",
      unauthorized: "generic-401",
      forbidden: "generic-403",
      notFound: "generic-404",
      conflict: "generic-409",
      tooLarge: "generic-413",
      failed: "generic-{{status}}",
      code_FORBIDDEN: "code-403",
      code_NOT_FOUND: "code-404",
    }, true, true);
    await i18n.changeLanguage("en");
  });

  it("shows what the server said, for every status that carries a reason", () => {
    // Each of these details is bundle-resolved server-side, so preferring it is what keeps the screen and the
    // response in the same language AND at the same precision.
    expect(errorMessage(new ApiError(400, "Check these fields: email"))).toBe("Check these fields: email");
    expect(errorMessage(new ApiError(403, "이 작업을 수행할 권한이 없습니다."))).toBe("이 작업을 수행할 권한이 없습니다.");
    expect(errorMessage(new ApiError(409, "A directory owns these attributes."))).toBe(
      "A directory owns these attributes.");
  });

  it("no longer discards the reason on a 404", () => {
    // "Profile not found" and "User not found" are different problems with different fixes; the old code
    // replaced both with one generic line.
    expect(errorMessage(new ApiError(404, "Profile not found."))).toBe("Profile not found.");
  });

  it("falls back to copy for the error code when the response carried no detail", () => {
    // A proxy error page or a truncated body leaves parse() with only the status line.
    expect(errorMessage(new ApiError(403, undefined, "FORBIDDEN"))).toBe("code-403");
    expect(errorMessage(new ApiError(404, undefined, "NOT_FOUND"))).toBe("code-404");
  });

  it("falls back to the status line for a code it has no copy for", () => {
    // A code the server adds later must not render a missing-key placeholder at the user.
    expect(errorMessage(new ApiError(409, undefined, "SOME_NEW_CODE"))).toBe("generic-409");
    expect(errorMessage(new ApiError(500, undefined, "SOME_NEW_CODE"))).toBe("generic-500");
  });

  it("keeps 401 deliberately non-specific", () => {
    // Every unauthenticated outcome shares one message; saying something specific here is what account
    // enumeration is made of.
    expect(errorMessage(new ApiError(401, "Bad credentials for ada@corp.example"))).toBe("generic-401");
  });

  it("keeps 413 on its own copy, because the container answers before the application does", () => {
    expect(errorMessage(new ApiError(413, "<html>Payload Too Large</html>"))).toBe("generic-413");
  });

  it("says nothing when the user cancelled a step-up", () => {
    expect(errorMessage(new StepUpCancelledError())).toBe("");
  });
});

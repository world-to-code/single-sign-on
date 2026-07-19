import { describe, expect, it } from "vitest";
import { apexHost, isTenantHost, organizationPickerTarget, tenantHost } from "./tenantHost";

const APEX = "localhost:5173";
const TENANT = "octatco.localhost:5173";

describe("tenantHost", () => {
  describe("isTenantHost", () => {
    it("recognizes the tenant's own subdomain", () => {
      expect(isTenantHost(TENANT, "octatco")).toBe(true);
    });

    it("rejects the bare platform host", () => {
      expect(isTenantHost(APEX, "octatco")).toBe(false);
    });

    it("compares case-insensitively on both sides, as DNS labels are", () => {
      expect(isTenantHost("OCTATCO.localhost:5173", "octatco")).toBe(true);
      expect(isTenantHost(TENANT, "OCTATCO")).toBe(true);
    });

    it("matches a whole label, never a prefix of one", () => {
      expect(isTenantHost("octatcolabs.localhost:5173", "octatco")).toBe(false);
    });

    it("tolerates a slug with surrounding whitespace", () => {
      expect(isTenantHost(TENANT, "  octatco  ")).toBe(true);
    });
  });

  describe("tenantHost", () => {
    it("prefixes the tenant label onto the bare platform host", () => {
      expect(tenantHost(APEX, "octatco")).toBe(TENANT);
    });

    it("is idempotent — never nests the label when already on the tenant host", () => {
      expect(tenantHost(TENANT, "octatco")).toBe(TENANT);
    });

    it("normalizes the slug it prefixes", () => {
      expect(tenantHost(APEX, "  Octatco ")).toBe(TENANT);
    });

    it("preserves the port", () => {
      expect(tenantHost("sso.example.com:8443", "acme")).toBe("acme.sso.example.com:8443");
    });
  });

  describe("apexHost", () => {
    it("strips the tenant label to get back to the platform host", () => {
      expect(apexHost(TENANT, "octatco")).toBe(APEX);
    });

    it("leaves the host untouched when already on the platform host", () => {
      expect(apexHost(APEX, "octatco")).toBe(APEX);
    });

    it("strips case-insensitively", () => {
      expect(apexHost("OCTATCO.localhost:5173", "octatco")).toBe("localhost:5173");
    });

    it("keeps a multi-label platform host intact below the tenant label", () => {
      expect(apexHost("acme.sso.example.com:8443", "acme")).toBe("sso.example.com:8443");
    });

    it("does not strip a label that merely starts with the slug", () => {
      expect(apexHost("octatcolabs.localhost:5173", "octatco")).toBe("octatcolabs.localhost:5173");
    });
  });

  // Regression cover for the reported bug: on a tenant subdomain, "use a different organization" looped
  // straight back to the same screen, because the backend re-resolves the org from the Host header.
  describe("organizationPickerTarget", () => {
    it("leaves the tenant subdomain for the platform host's /login", () => {
      expect(organizationPickerTarget("http:", TENANT, "octatco")).toBe("http://localhost:5173/login");
    });

    it("targets /login, never the apex root — that root is a marketing path when signed out", () => {
      expect(organizationPickerTarget("https:", "acme.sso.example.com", "acme"))
        .toBe("https://sso.example.com/login");
    });

    it("stays put on the platform host, where the picker already lives", () => {
      expect(organizationPickerTarget("http:", APEX, "octatco")).toBeNull();
    });

    it("stays put when no organization is resolved", () => {
      expect(organizationPickerTarget("http:", TENANT, null)).toBeNull();
    });

    it("stays put for a blank organization rather than building a malformed origin", () => {
      expect(organizationPickerTarget("http:", TENANT, "   ")).toBeNull();
    });

    it("preserves the protocol it was given", () => {
      expect(organizationPickerTarget("https:", "acme.example.com:8443", "acme"))
        .toBe("https://example.com:8443/login");
    });
  });

  // KNOWN LIMIT, pinned deliberately. These helpers infer "am I on the tenant's subdomain?" from the leading
  // label alone, because the SPA is never told the platform host. So an organization whose slug equals the
  // platform host's own first label (apex "sso.example.com", slug "sso") is indistinguishable from being on
  // that tenant's subdomain. The robust fix is to compare against a known platform host, or to reserve such
  // slugs server-side — `Slug.java` validates shape only and has no reserved list today.
  describe("slug colliding with the platform host's first label (known limit)", () => {
    it("reads the apex as if it were the tenant's own subdomain", () => {
      expect(isTenantHost("sso.example.com", "sso")).toBe(true);
    });

    it("therefore leaves the apex unchanged instead of building a subdomain", () => {
      expect(tenantHost("sso.example.com", "sso")).toBe("sso.example.com");
    });

    it("and strips a label that actually belongs to the platform", () => {
      expect(apexHost("sso.example.com", "sso")).toBe("example.com");
    });
  });

  // Callers guard against a blank slug today (OrgSelect bails on an empty input, Login checks `org &&`);
  // these pin what would happen if one stopped, so the corruption is visible rather than silent.
  describe("degenerate slug", () => {
    it("does not treat any host as a blank tenant's subdomain", () => {
      expect(isTenantHost(APEX, "")).toBe(false);
    });

    it("builds a malformed host from a blank slug — callers must not pass one", () => {
      expect(tenantHost(APEX, "")).toBe(`.${APEX}`);
    });

    it("leaves the host alone when asked to strip a blank slug", () => {
      expect(apexHost(APEX, "")).toBe(APEX);
    });
  });
});

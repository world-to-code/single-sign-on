import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { EffectivePermissionList } from "./EffectivePermissionList";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

describe("EffectivePermissionList", () => {
  it("shows the effective permissions", () => {
    render(<EffectivePermissionList effective={["user:read", "user:update"]} denied={[]} />);

    expect(screen.getByText("user:read")).toBeInTheDocument();
    expect(screen.getByText("user:update")).toBeInTheDocument();
    expect(screen.queryByText("userDetailDeniedPerms")).not.toBeInTheDocument();
  });

  it("surfaces a denied grant separately from the effective set", () => {
    render(<EffectivePermissionList effective={["user:update"]} denied={["user:read"]} />);

    // the denied section header renders, and the denied permission is shown
    expect(screen.getByText("userDetailDeniedPerms")).toBeInTheDocument();
    expect(screen.getByText("user:read")).toBeInTheDocument();
    expect(screen.getByText("user:update")).toBeInTheDocument();
  });

  it("renders an empty state when nothing is in effect", () => {
    render(<EffectivePermissionList effective={[]} denied={[]} />);

    expect(screen.getByText("none")).toBeInTheDocument();
  });
});

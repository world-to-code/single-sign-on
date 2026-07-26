import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DenyControls } from "./DenyControls";
import { createDeny, liftDeny } from "@/denies";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/denies", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/denies")>()),
  createDeny: vi.fn().mockResolvedValue({ id: "d1" }),
  liftDeny: vi.fn().mockResolvedValue(undefined),
}));

vi.mock("@/api", () => ({ errorMessage: (e: unknown) => String(e) }));

describe("DenyControls", () => {
  beforeEach(() => vi.clearAllMocks());

  it("only offers permissions that are not already denied", () => {
    render(<DenyControls kind="USER" subjectId="u1" candidates={["user:read", "user:update"]}
                         denies={[{ id: "d1", pattern: "user:read" }]} onChanged={vi.fn()} />);

    // user:read is already denied (a badge), so it is not an option; user:update is
    expect(screen.getByRole("option", { name: "user:update" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "user:read" })).not.toBeInTheDocument();
  });

  it("authors a deny for the chosen permission and reloads", async () => {
    const onChanged = vi.fn();
    render(<DenyControls kind="USER" subjectId="u1" candidates={["user:update"]} denies={[]} onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText("userDetailDenyPick"), { target: { value: "user:update" } });
    fireEvent.click(screen.getByRole("button", { name: "userDetailDenyAdd" }));

    await waitFor(() => expect(createDeny).toHaveBeenCalledWith("USER", "u1", "user:update"));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it("renders nothing for a subject with no permissions and no denies", () => {
    const { container } = render(
      <DenyControls kind="ROLE" subjectId="r1" candidates={[]} denies={[]} onChanged={vi.fn()} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("lifts a deny when its restore control is clicked", async () => {
    const onChanged = vi.fn();
    render(<DenyControls kind="USER" subjectId="u1" candidates={[]} denies={[{ id: "d9", pattern: "user:read" }]}
                         onChanged={onChanged} />);

    fireEvent.click(screen.getByLabelText("userDetailDenyLift user:read"));

    await waitFor(() => expect(liftDeny).toHaveBeenCalledWith("d9", "USER"));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });
});

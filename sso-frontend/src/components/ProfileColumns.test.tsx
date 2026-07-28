import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ProfileColumns } from "./ProfileColumns";
import { getProfileAttributes, saveProfileAttributes, type ProfileColumn } from "@/profileAttributes";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/profileAttributes", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/profileAttributes")>()),
  getProfileAttributes: vi.fn(),
  saveProfileAttributes: vi.fn(),
}));

const USER = "11111111-1111-1111-1111-111111111111";
const PROFILE = "22222222-2222-2222-2222-222222222222";

const column = (over: Partial<ProfileColumn>): ProfileColumn => ({
  key: "department", displayName: "Department", description: null, dataType: "STRING",
  enumValues: [], multiValued: false, required: false, editable: true, base: false, values: [],
  ...over,
});

const loads = (...columns: ProfileColumn[]) =>
  vi.mocked(getProfileAttributes).mockResolvedValue({ columns });

describe("ProfileColumns", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(saveProfileAttributes).mockResolvedValue({ columns: [] });
  });

  it("shows the columns in the order the profile declares them", async () => {
    // Base columns first, then the tenant's own by sortOrder — the server sends them already ordered, and the
    // component must not re-sort them into something the profile did not ask for.
    loads(column({ key: "username", displayName: "Username", base: true, editable: false, values: ["ada"] }),
          column({ key: "department", displayName: "Department", values: ["engineering"] }));

    render(<ProfileColumns userId={USER} profileId={PROFILE} />);

    await waitFor(() => expect(screen.getByText("Username")).toBeInTheDocument());
    const labels = screen.getAllByText(/Username|Department/).map((n) => n.textContent);
    expect(labels[0]).toContain("Username");
  });

  it("marks a required column and a read-only one", async () => {
    loads(column({ required: true }),
          column({ key: "employeeId", displayName: "Employee ID", editable: false }));

    render(<ProfileColumns userId={USER} profileId={PROFILE} />);

    await waitFor(() => expect(screen.getByText("profileColumnRequired")).toBeInTheDocument());
    expect(screen.getByText("profileColumnReadOnly")).toBeInTheDocument();
  });

  it("says so when a column has no value rather than showing an empty row", async () => {
    loads(column({ values: [] }));

    render(<ProfileColumns userId={USER} profileId={PROFILE} />);

    await waitFor(() => expect(screen.getByText("profileColumnUnset")).toBeInTheDocument());
  });

  it("sends every writable column, including the ones left blank", async () => {
    // Dropping blanks would make a required column impossible to clear AND impossible to detect: the server
    // needs the whole set to tell an empty required column from a key the request did not mention.
    loads(column({ key: "department", values: ["engineering"] }),
          column({ key: "costCentre", displayName: "Cost centre", values: [] }));

    render(<ProfileColumns userId={USER} profileId={PROFILE} />);
    await waitFor(() => expect(screen.getByText("userDetailEdit")).toBeInTheDocument());
    fireEvent.click(screen.getByText("userDetailEdit"));
    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(saveProfileAttributes).toHaveBeenCalledWith(USER, {
      department: ["engineering"],
      costCentre: [],
    }));
  });

  it("never sends a column this form does not own", async () => {
    // A base field has its own dialog and a directory-owned one belongs to its connector; the server refuses
    // both, so sending them would turn every save into an error the administrator cannot act on.
    loads(column({ key: "username", base: true, editable: false, values: ["ada"] }),
          column({ key: "employeeId", editable: false, values: ["E-1"] }),
          column({ key: "department", values: ["engineering"] }));

    render(<ProfileColumns userId={USER} profileId={PROFILE} />);
    await waitFor(() => expect(screen.getByText("userDetailEdit")).toBeInTheDocument());
    fireEvent.click(screen.getByText("userDetailEdit"));
    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(saveProfileAttributes)
      .toHaveBeenCalledWith(USER, { department: ["engineering"] }));
  });

  it("re-reads the columns when the person moves to another profile", async () => {
    // The columns ARE the profile: keeping the old list would show the previous profile's schema over the new
    // profile's values, including columns the move just deleted.
    loads(column({ key: "team", displayName: "Team", values: ["Platform"] }));

    const { rerender } = render(<ProfileColumns userId={USER} profileId={PROFILE} />);
    await waitFor(() => expect(screen.getByText("Team")).toBeInTheDocument());

    loads(column({ key: "costCentre", displayName: "Cost centre", values: ["CC-9"] }));
    rerender(<ProfileColumns userId={USER} profileId="99999999-9999-9999-9999-999999999999" />);

    await waitFor(() => expect(screen.getByText("Cost centre")).toBeInTheDocument());
    expect(screen.queryByText("Team")).not.toBeInTheDocument();
  });

  it("keeps the form open with what was typed when the server refuses the save", async () => {
    // A refusal here is usually "a required column is empty". Closing the form on failure discards everything
    // the administrator typed, so the correction they were just asked for has to start over.
    loads(column({ key: "team", displayName: "Team", values: ["Platform"] }));
    vi.mocked(saveProfileAttributes).mockRejectedValue(new Error("400"));
    const onSaved = vi.fn();

    render(<ProfileColumns userId={USER} profileId={PROFILE} onSaved={onSaved} />);
    await waitFor(() => expect(screen.getByText("userDetailEdit")).toBeInTheDocument());
    fireEvent.click(screen.getByText("userDetailEdit"));
    fireEvent.change(screen.getByLabelText(/Team/), { target: { value: "Infra" } });
    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(screen.getByText("cancel")).toBeInTheDocument()); // still editing
    expect((screen.getByLabelText(/Team/) as HTMLInputElement).value).toBe("Infra");
    expect(onSaved).not.toHaveBeenCalled();
  });

  it("tells the page to reload once the columns are saved", async () => {
    // The saved values can be conditions on policy bindings and mapping rules, so the roles and policies the
    // rest of the page shows are stale the moment this succeeds.
    loads(column({ key: "team", displayName: "Team", values: ["Platform"] }));
    const onSaved = vi.fn();

    render(<ProfileColumns userId={USER} profileId={PROFILE} onSaved={onSaved} />);
    await waitFor(() => expect(screen.getByText("userDetailEdit")).toBeInTheDocument());
    fireEvent.click(screen.getByText("userDetailEdit"));
    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
  });

  it("restores the loaded values when the edit is cancelled", async () => {
    loads(column({ key: "team", displayName: "Team", values: ["Platform"] }));

    render(<ProfileColumns userId={USER} profileId={PROFILE} />);
    await waitFor(() => expect(screen.getByText("userDetailEdit")).toBeInTheDocument());
    fireEvent.click(screen.getByText("userDetailEdit"));
    fireEvent.change(screen.getByLabelText(/Team/), { target: { value: "Infra" } });
    fireEvent.click(screen.getByText("cancel"));

    // Without the restore the abandoned edit stays in the draft and rides along on the NEXT save.
    fireEvent.click(screen.getByText("userDetailEdit"));
    expect((screen.getByLabelText(/Team/) as HTMLInputElement).value).toBe("Platform");
  });

  it("splits a multi-valued column on commas and keeps them in a single-valued one", async () => {
    loads(column({ key: "tags", displayName: "Tags", multiValued: true, values: ["a", "b"] }),
          column({ key: "title", displayName: "Title", values: ["Head, Platform"] }));

    render(<ProfileColumns userId={USER} profileId={PROFILE} />);
    await waitFor(() => expect(screen.getByText("userDetailEdit")).toBeInTheDocument());
    fireEvent.click(screen.getByText("userDetailEdit"));
    fireEvent.click(screen.getByText("save"));

    await waitFor(() => expect(saveProfileAttributes).toHaveBeenCalledWith(USER, {
      tags: ["a", "b"],
      title: ["Head, Platform"],
    }));
  });
});

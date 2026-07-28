import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ProfileSwitcher } from "./ProfileSwitcher";
import { previewProfileSwitch, switchProfile, type ProfileSwitchPreview } from "@/profileAttributes";
import { listProfiles, type Profile } from "@/attributeDefinitions";

vi.mock("react-i18next", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react-i18next")>()),
  useTranslation: () => ({
    t: (key: string, vars?: Record<string, unknown>) => (vars ? `${key} ${JSON.stringify(vars)}` : key),
    i18n: { language: "en", changeLanguage: vi.fn() },
  }),
}));

vi.mock("@/profileAttributes", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/profileAttributes")>()),
  previewProfileSwitch: vi.fn(),
  switchProfile: vi.fn(),
}));

vi.mock("@/attributeDefinitions", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/attributeDefinitions")>()),
  listProfiles: vi.fn(),
}));

const USER = "11111111-1111-1111-1111-111111111111";
const CURRENT = "22222222-2222-2222-2222-222222222222";
const TARGET = "33333333-3333-3333-3333-333333333333";

const profile = (over: Partial<Profile>): Profile => ({
  id: TARGET, name: "Contractor", kind: "TENANT", connectorId: null, system: false,
  defaultForCreation: false,
  ...over,
});

const preview = (over: Partial<ProfileSwitchPreview> = {}): ProfileSwitchPreview => ({
  removedKeys: [], blockedKeys: [], externallyManaged: false, blocked: false, ...over,
});

const offers = (...profiles: Profile[]) => vi.mocked(listProfiles).mockResolvedValue(profiles);

/** Opens the picker and chooses the target profile, which is what triggers the preview. */
async function choose(target = TARGET) {
  await waitFor(() => expect(screen.getByLabelText("userDetailSwitchProfileLabel")).toBeInTheDocument());
  fireEvent.change(screen.getByLabelText("userDetailSwitchProfileLabel"), { target: { value: target } });
}

describe("ProfileSwitcher", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(previewProfileSwitch).mockResolvedValue(preview());
    vi.mocked(switchProfile).mockResolvedValue({ columns: [] });
  });

  it("offers only tenant profiles the person is not already on", async () => {
    // A source profile describes a remote directory's schema — moving a local person onto one would promise a
    // shape no console form can fill — and the current profile is not a move at all.
    offers(profile({ id: CURRENT, name: "Employee" }),
           profile({ id: TARGET, name: "Contractor" }),
           profile({ id: "44444444-4444-4444-4444-444444444444", name: "LDAP staff", kind: "LDAP" }));

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole("option", { name: "Contractor" })).toBeInTheDocument());
    expect(screen.queryByRole("option", { name: "Employee" })).not.toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "LDAP staff" })).not.toBeInTheDocument();
  });

  it("renders nothing when there is nowhere to move to", async () => {
    offers(profile({ id: CURRENT, name: "Employee" }));

    const { container } = render(
      <ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={vi.fn()} />);

    await waitFor(() => expect(listProfiles).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("names the attributes the move would delete before anything is written", async () => {
    offers(profile({}));
    vi.mocked(previewProfileSwitch).mockResolvedValue(preview({ removedKeys: ["costCentre", "team"] }));

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={vi.fn()} />);
    await choose();

    await waitFor(() => expect(previewProfileSwitch).toHaveBeenCalledWith(USER, TARGET));
    expect(screen.getByText(/profileSwitchRemoves/)).toHaveTextContent("costCentre, team");
    expect(switchProfile).not.toHaveBeenCalled();
  });

  it("says the move costs nothing when the target declares everything the person holds", async () => {
    offers(profile({}));

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={vi.fn()} />);
    await choose();

    await waitFor(() => expect(screen.getByText("profileSwitchLossless")).toBeInTheDocument());
  });

  it("refuses the move when a directory owns an attribute it would have to delete", async () => {
    offers(profile({}));
    vi.mocked(previewProfileSwitch)
      .mockResolvedValue(preview({ removedKeys: ["employeeId"], blockedKeys: ["employeeId"], blocked: true }));

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={vi.fn()} />);
    await choose();

    await waitFor(() => expect(screen.getByText(/profileSwitchBlockedKeys/)).toHaveTextContent("employeeId"));
    fireEvent.click(screen.getByRole("button", { name: "userDetailSwitchProfileAction" }));
    expect(switchProfile).not.toHaveBeenCalled();
  });

  it("refuses the move for an externally provisioned person, whose attributes are owned upstream", async () => {
    // A distinct reason from the one above, and the preview reports both: a console that showed only the first
    // left an administrator pressing a button the server then refused.
    offers(profile({}));
    vi.mocked(previewProfileSwitch).mockResolvedValue(preview({ externallyManaged: true, blocked: true }));

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={vi.fn()} />);
    await choose();

    await waitFor(() => expect(screen.getByText("profileSwitchBlockedExternal")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "userDetailSwitchProfileAction" }));
    expect(switchProfile).not.toHaveBeenCalled();
  });

  it("moves the person once the cost has been shown, and reports it up", async () => {
    offers(profile({}));
    vi.mocked(previewProfileSwitch).mockResolvedValue(preview({ removedKeys: ["team"] }));
    const onSwitched = vi.fn();

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={onSwitched} />);
    await choose();
    await waitFor(() => expect(screen.getByText(/profileSwitchRemoves/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "userDetailSwitchProfileAction" }));

    await waitFor(() => expect(switchProfile).toHaveBeenCalledWith(USER, TARGET, ["team"]));
    await waitFor(() => expect(onSwitched).toHaveBeenCalled());
  });

  it("drops the previous disclosure when the target changes", async () => {
    // Otherwise an administrator reads "team will be deleted" for profile A, switches the picker to B, and
    // confirms a deletion they were never shown — the button was still enabled by A's preview.
    offers(profile({ id: TARGET, name: "Contractor" }),
           profile({ id: "55555555-5555-5555-5555-555555555555", name: "Intern" }));
    vi.mocked(previewProfileSwitch).mockResolvedValueOnce(preview({ removedKeys: ["team"] }))
      .mockReturnValueOnce(new Promise(() => {}));

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={vi.fn()} />);
    await choose();
    await waitFor(() => expect(screen.getByText(/profileSwitchRemoves/)).toBeInTheDocument());

    await choose("55555555-5555-5555-5555-555555555555");

    expect(screen.queryByText(/profileSwitchRemoves/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "userDetailSwitchProfileAction" }));
    expect(switchProfile).not.toHaveBeenCalled();
  });

  it("reports a refusal instead of claiming the move happened", async () => {
    // The server refuses for reasons the preview cannot see — a directory-owned key added since, a stale
    // preview. Treating that as success reloads the page and tells the administrator it worked.
    offers(profile({}));
    vi.mocked(switchProfile).mockRejectedValue(new Error("409"));
    const onSwitched = vi.fn();

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={onSwitched} />);
    await choose();
    await waitFor(() => expect(screen.getByText("profileSwitchLossless")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "userDetailSwitchProfileAction" }));

    await waitFor(() => expect(switchProfile).toHaveBeenCalled());
    expect(onSwitched).not.toHaveBeenCalled();
  });

  it("will not move while the preview is still in flight", async () => {
    // The preview is the disclosure; a move authorised before it lands is a deletion the administrator never
    // saw — including one the server would have refused.
    offers(profile({}));
    vi.mocked(previewProfileSwitch).mockReturnValue(new Promise(() => {}));

    render(<ProfileSwitcher userId={USER} currentProfileId={CURRENT} onSwitched={vi.fn()} />);
    await choose();

    fireEvent.click(screen.getByRole("button", { name: "userDetailSwitchProfileAction" }));

    expect(switchProfile).not.toHaveBeenCalled();
  });
});

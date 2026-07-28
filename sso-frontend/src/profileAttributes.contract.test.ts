import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getProfileAttributes, previewProfileSwitch, saveProfileAttributes, switchProfile,
} from "./profileAttributes";

/**
 * The wire contract of the four profile calls, against a stubbed fetch.
 *
 * <p>Both component tests mock this module wholesale, so nothing exercised what it actually SENDS — and two
 * of these four are destructive. Swapping the profile-move path with the column-save path, or sending
 * `{values}` where the server reads `{profileId}`, left every frontend test green AND every backend test
 * green: the backend's own MVC test builds its JSON by hand, so neither side ever compared them. A move
 * deletes attributes and can retract a role; sending one down the wrong route is not a cosmetic mistake.
 *
 * <p>About the REQUEST and the parsed shape only. Behaviour belongs to the component tests, and duplicating
 * it here would just be two places to change.
 */
describe("profile attributes wire contract", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  /** A FRESH response per call — a Response body can only be read once, and the calls share the mock. */
  const respondWith = (body: unknown) =>
    () => new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const requestFor = (call: number) => {
    const [path, init] = fetchMock.mock.calls[call] as [string, RequestInit];
    return { path, init };
  };

  const USER = "u-1";

  it("reads and replaces the columns on the same path, with the whole set in the body", async () => {
    fetchMock.mockImplementation(respondWith({ columns: [] }));

    await getProfileAttributes(USER);
    await saveProfileAttributes(USER, { team: ["Platform"], costCentre: [] });

    expect(requestFor(0).path).toBe(`/api/admin/users/${USER}/profile-attributes`);
    expect(requestFor(0).init.method ?? "GET").toBe("GET");
    expect(requestFor(1).path).toBe(`/api/admin/users/${USER}/profile-attributes`);
    expect(requestFor(1).init.method).toBe("PUT");
    // The server clears a column the map omits, so the body has to be the whole declared set — including the
    // empty one, which is how the form says "unset this".
    expect(JSON.parse(String(requestFor(1).init.body)))
      .toEqual({ values: { team: ["Platform"], costCentre: [] } });
  });

  it("moves a user on its own path, naming the target profile in the body", async () => {
    fetchMock.mockImplementation(respondWith({ columns: [] }));

    await switchProfile(USER, "p-9", ["team"]);

    expect(requestFor(0).path).toBe(`/api/admin/users/${USER}/profile`);
    expect(requestFor(0).init.method).toBe("PUT");
    // The confirmed cost travels with it: the server refuses a move whose cost changed since the preview.
    expect(JSON.parse(String(requestFor(0).init.body))).toEqual({ profileId: "p-9", confirmedKeys: ["team"] });
  });

  it("asks what the move would cost on a separate, read-only path", async () => {
    fetchMock.mockImplementation(respondWith(
      { removedKeys: ["team"], blockedKeys: [], externallyManaged: false, blocked: false }));

    const preview = await previewProfileSwitch(USER, "p-9");

    expect(requestFor(0).path).toBe(`/api/admin/users/${USER}/profile/preview?profileId=p-9`);
    expect(requestFor(0).init.method ?? "GET").toBe("GET");
    // The console disables its confirm button on `blocked`; a preview parsed without it reads undefined and
    // re-opens the button on a move the server refuses.
    expect(preview.blocked).toBe(false);
    expect(preview.removedKeys).toEqual(["team"]);
  });

  it("keeps the two destructive routes apart", async () => {
    fetchMock.mockImplementation(respondWith({ columns: [] }));

    await switchProfile(USER, "p-9", []);
    await saveProfileAttributes(USER, {});

    expect(requestFor(0).path).not.toBe(requestFor(1).path);
  });
});

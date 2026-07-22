import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { applyCsvImport, downloadCsvTemplate, previewCsvImport } from "./attributeDefinitions";

/**
 * The wire contract of the three CSV calls, against a stubbed fetch.
 *
 * <p>The component tests mock this module wholesale, so nothing exercised what it actually SENDS. Renaming
 * the multipart field, swapping the preview and apply paths, or dropping the language header all left every
 * frontend test green while breaking the feature — the backend looks the part up by name
 * (`CsvImportServiceImpl.FILE_PART`), resolves each row's reason against `Accept-Language`, and answers a
 * different question on each of the two paths.
 *
 * <p>Deliberately about the REQUEST and the parsed shape, not about behaviour: the component tests own that,
 * and duplicating them here would just be two places to change.
 */
describe("CSV import wire contract", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  /** A FRESH response per call — a Response body can only be read once, and two calls share the mock. */
  const respondWith = (body: unknown, contentType = "application/json") =>
    () => new Response(JSON.stringify(body), { status: 200, headers: { "content-type": contentType } });

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const file = () => new File(["username,email\nada,a@x.io\n"], "users.csv", { type: "text/csv" });

  const requestFor = (call: number) => {
    const [path, init] = fetchMock.mock.calls[call] as [string, RequestInit];
    return { path, init };
  };

  it("previews on its own path, and applies on another", async () => {
    fetchMock.mockImplementation(respondWith({ rowsRead: 1, toCreate: [], existing: [], failures: [] }));

    await previewCsvImport("p-1", file());
    await applyCsvImport("p-1", file());

    expect(requestFor(0).path).toBe("/api/admin/profiles/p-1/csv-import/preview");
    expect(requestFor(1).path).toBe("/api/admin/profiles/p-1/csv-import");
  });

  it("sends the file under the name the server looks up", async () => {
    fetchMock.mockImplementation(respondWith({ rowsRead: 0, toCreate: [], existing: [], failures: [] }));

    await previewCsvImport("p-1", file());

    const body = requestFor(0).init.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect(body.get("file")).toBeInstanceOf(File); // CsvImportServiceImpl.FILE_PART
  });

  it("asks for the administrator's language, so row reasons come back translated", async () => {
    fetchMock.mockImplementation(respondWith({ rowsRead: 0, toCreate: [], existing: [], failures: [] }));

    await previewCsvImport("p-1", file());

    const headers = requestFor(0).init.headers as Record<string, string>;
    expect(headers["Accept-Language"]).toBeTruthy();
  });

  it("parses the preview shape the server actually returns", async () => {
    fetchMock.mockImplementation(respondWith({
      rowsRead: 3,
      toCreate: [{ line: 2, username: "ada", attributes: { team: "platform" }, groups: ["eng"] }],
      existing: ["grace"],
      failures: [{ line: 4, reason: "already here" }],
    }));

    const preview = await previewCsvImport("p-1", file());

    expect(preview.rowsRead).toBe(3);
    expect(preview.toCreate[0].username).toBe("ada");
    expect(preview.existing).toEqual(["grace"]);
    expect(preview.failures[0]).toEqual({ line: 4, reason: "already here" });
  });

  it("parses the apply shape, which counts rather than lists what it created", async () => {
    fetchMock.mockImplementation(respondWith({
      created: 2,
      existing: ["grace"],
      failures: [{ line: 5, reason: "duplicate" }],
    }));

    const result = await applyCsvImport("p-1", file());

    expect(result.created).toBe(2);
    expect(result.failures[0].line).toBe(5);
  });

  it("escapes a profile id rather than pasting it into the path", async () => {
    fetchMock.mockImplementation(respondWith({ rowsRead: 0, toCreate: [], existing: [], failures: [] }));

    await previewCsvImport("a/b", file());

    expect(requestFor(0).path).toBe("/api/admin/profiles/a%2Fb/csv-import/preview");
  });

  it("downloads the template as text, from the template path", async () => {
    fetchMock.mockImplementation(() => new Response("username,email\n", {
      status: 200,
      headers: { "content-type": "text/csv", "content-disposition": 'attachment; filename="users-acme.csv"' },
    }));

    const template = await downloadCsvTemplate("p-1");

    expect(requestFor(0).path).toBe("/api/admin/profiles/p-1/csv-template");
    expect(template.content).toContain("username");
    expect(template.filename).toBe("users-acme.csv");
  });
});

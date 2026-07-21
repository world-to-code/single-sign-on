import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Download, Upload } from "lucide-react";
import {
  applyCsvImport,
  downloadCsvTemplate,
  previewCsvImport,
  type CsvImportPreview,
  type CsvImportResult,
  type CsvRowFailure,
  type Profile,
} from "@/attributeDefinitions";
import { errorMessage } from "@/api";
import { Button } from "@/components/ui/button";

/**
 * Creating users from a file, on a profile the administrator chose.
 *
 * <p>Three steps, and the middle one is the point. The template comes from the profile, so the file's columns
 * cannot disagree with what the profile declares. The preview writes NOTHING — every other import path in this
 * system fills existing accounts, and this is the one that creates them, so a file aimed at the wrong profile
 * has to be visible as a COUNT before it is a tenant full of accounts nobody meant to make.
 *
 * <p>The confirm step re-uploads the same file rather than sending the preview back: the server re-reads and
 * re-plans it, so what was confirmed is a number the administrator saw, not an instruction the client composed.
 */
export function CsvImport({ profile }: { profile: Profile }) {
  const { t } = useTranslation("console");
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<CsvImportPreview | null>(null);
  const [result, setResult] = useState<CsvImportResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const picker = useRef<HTMLInputElement>(null);

  function reset() {
    setPreview(null);
    setResult(null);
    setError(null);
  }

  async function download() {
    setError(null);
    try {
      const { filename, content } = await downloadCsvTemplate(profile.id);
      // A BOM so Excel opens a UTF-8 file as UTF-8 rather than guessing the local codepage and mangling every
      // non-ASCII name. The importer strips it back off.
      const blob = new Blob(["﻿", content], { type: "text/csv;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function choose(chosen: File | null) {
    setFile(chosen);
    reset();
    if (!chosen) return;
    setBusy(true);
    try {
      setPreview(await previewCsvImport(profile.id, chosen));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      setResult(await applyCsvImport(profile.id, file));
      setPreview(null);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-4">
      <div className="space-y-1">
        <h3 className="text-sm font-medium">{t("csvImportTitle")}</h3>
        <p className="text-sm text-muted-foreground">{t("csvImportDescription", { profile: profile.name })}</p>
      </div>

      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={download}>
          <Download className="mr-2 size-4" aria-hidden="true" />
          {t("csvImportDownloadTemplate")}
        </Button>
        <Button variant="outline" onClick={() => picker.current?.click()} disabled={busy}>
          <Upload className="mr-2 size-4" aria-hidden="true" />
          {t("csvImportChooseFile")}
        </Button>
        <input
          ref={picker}
          type="file"
          accept=".csv,text/csv"
          className="sr-only"
          aria-label={t("csvImportChooseFile")}
          onChange={(e) => void choose(e.target.files?.[0] ?? null)}
        />
        {file && <span className="self-center text-sm text-muted-foreground">{file.name}</span>}
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      {preview && (
        <div className="space-y-3 rounded-md border p-4">
          <p className="text-sm">
            {t("csvImportSummary", {
              create: preview.toCreate.length,
              existing: preview.existing.length,
              failed: preview.failures.length,
            })}
          </p>
          <Failures failures={preview.failures} />
          <div className="flex gap-2">
            {/* Disabled when a file would create nothing: confirming a no-op reads as a broken button. */}
            <Button onClick={confirm} disabled={busy || preview.toCreate.length === 0}>
              {t("csvImportConfirm", { count: preview.toCreate.length })}
            </Button>
            <Button variant="ghost" onClick={() => void choose(null)} disabled={busy}>
              {t("csvImportCancel")}
            </Button>
          </div>
        </div>
      )}

      {result && (
        <div className="space-y-3 rounded-md border p-4">
          <p className="text-sm">
            {t("csvImportDone", {
              created: result.created,
              existing: result.existing.length,
              failed: result.failures.length,
            })}
          </p>
          <Failures failures={result.failures} />
        </div>
      )}
    </section>
  );
}

/**
 * The rows that will not be applied.
 *
 * <p>Line number and reason only. The server deliberately never sends the cell values back in a failure — a
 * report is read in a console and pasted into tickets, and the rows that fail are disproportionately the ones
 * holding a typo in somebody's name or address. The line number is what finds the row in the file the
 * administrator still has.
 */
function Failures({ failures }: { failures: CsvRowFailure[] }) {
  const { t } = useTranslation("console");
  if (failures.length === 0) return null;
  return (
    <ul className="space-y-1 text-sm">
      {failures.map((failure) => (
        <li key={`${failure.line}-${failure.reason}`} className="text-muted-foreground">
          {/* The reason arrives already resolved in the caller's language, with its subject interpolated —
              appending `detail` too printed the group name twice. It stays on the type because a future
              screen may want to group failures by it. */}
          <span className="font-medium">{t("csvImportLine", { line: failure.line })}</span> {failure.reason}
        </li>
      ))}
    </ul>
  );
}

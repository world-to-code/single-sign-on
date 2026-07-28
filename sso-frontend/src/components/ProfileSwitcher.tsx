import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { errorMessage } from "@/api";
import { listProfiles, type Profile } from "@/attributeDefinitions";
import { previewProfileSwitch, switchProfile, type ProfileSwitchPreview } from "@/profileAttributes";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";

/**
 * Moves a person onto another profile — the one write on this page that DELETES data, since a profile decides
 * which attributes a person has and the target does not declare the rest.
 *
 * <p>Hence the two steps: choosing a profile only asks the server what the move would cost, and nothing is
 * written until that answer has been rendered. Those deleted keys can be conditions on mapping rules and
 * policy bindings, so the cost is not merely tidiness — a move can retract a role.
 */
export function ProfileSwitcher({ userId, currentProfileId, onSwitched }:
  { userId: string; currentProfileId: string | null; onSwitched: () => void }) {
  const { t } = useTranslation("console");
  const [candidates, setCandidates] = useState<Profile[]>([]);
  const [target, setTarget] = useState("");
  const [preview, setPreview] = useState<ProfileSwitchPreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Only TENANT profiles, minus the one the person is already on: a source profile describes a remote
  // directory's schema, which no console form can fill.
  useEffect(() => {
    listProfiles()
      .then((all) => setCandidates(all.filter((p) => p.kind === "TENANT" && p.id !== currentProfileId)))
      // Not swallowed into an empty list: this component renders nothing when it has no candidates, so a
      // refused load looked exactly like "this organization has one profile" — the administrator saw no
      // control and no reason for its absence.
      .catch((e) => setError(errorMessage(e)));
  }, [currentProfileId]);

  const pick = (profileId: string) => {
    setTarget(profileId);
    setPreview(null);
    setError(null);
    if (!profileId) return;
    previewProfileSwitch(userId, profileId).then(setPreview).catch((e) => setError(errorMessage(e)));
  };

  const move = () => {
    // Guarded rather than merely disabled: the preview is the disclosure, and a blocked move is one the
    // server refuses anyway.
    if (!target || !preview || preview.blocked || busy) return;
    setBusy(true);
    setError(null);
    switchProfile(userId, target, preview.removedKeys)
      .then(() => {
        setTarget("");
        setPreview(null);
        onSwitched();
      })
      .catch((e) => setError(errorMessage(e)))
      .finally(() => setBusy(false));
  };

  // A load failure still renders, precisely because there is nothing else on screen to hint at it: with no
  // candidates this component draws nothing, so a refused list looked like "there is nowhere to move to".
  if (candidates.length === 0 && !error) return null;

  return (
    <div className="space-y-3">
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

      {candidates.length > 0 && (
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-56 space-y-1.5">
          <Label htmlFor="profile-switch-target">{t("userDetailSwitchProfileLabel")}</Label>
          <Select id="profile-switch-target" value={target} onChange={(e) => pick(e.target.value)}>
            <option value="">{t("userDetailSwitchProfilePick")}</option>
            {candidates.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        </div>
        <Button variant="outline" size="sm" onClick={move}
                disabled={busy || !preview || preview.blocked}>
          {t("userDetailSwitchProfileAction")}
        </Button>
      </div>
      )}

      {preview && <PreviewNotice preview={preview} />}
    </div>
  );
}

/** What the chosen move would cost, or the reason it cannot happen. */
function PreviewNotice({ preview }: { preview: ProfileSwitchPreview }) {
  const { t } = useTranslation("console");

  if (preview.externallyManaged) {
    return <Alert variant="destructive"><AlertDescription>{t("profileSwitchBlockedExternal")}</AlertDescription></Alert>;
  }
  if (preview.blockedKeys.length > 0) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{t("profileSwitchBlockedKeys", { keys: preview.blockedKeys.join(", ") })}</AlertDescription>
      </Alert>
    );
  }
  if (preview.removedKeys.length > 0) {
    return (
      <Alert variant="warn">
        <AlertDescription>{t("profileSwitchRemoves", { keys: preview.removedKeys.join(", ") })}</AlertDescription>
      </Alert>
    );
  }
  return <p className="text-sm text-muted-foreground">{t("profileSwitchLossless")}</p>;
}

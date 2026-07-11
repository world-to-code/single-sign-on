import { useTranslation } from "react-i18next";
import { PageHeader } from "../components/PageHeader";
import PasskeyManager from "../components/PasskeyManager";

export default function Passkeys() {
  const { t } = useTranslation("auth");
  return (
    <>
      <PageHeader
        title={t("passkeysTitle")}
        description={t("passkeysDescription")}
      />
      <PasskeyManager />
    </>
  );
}

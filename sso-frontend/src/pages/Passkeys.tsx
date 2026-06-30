import { PageHeader } from "../components/PageHeader";
import PasskeyManager from "../components/PasskeyManager";

export default function Passkeys() {
  return (
    <>
      <PageHeader
        title="My Passkeys"
        description="Security keys and platform passkeys on your account. One passkey works for both passwordless sign-in and the FIDO2 step of your policy."
      />
      <PasskeyManager />
    </>
  );
}

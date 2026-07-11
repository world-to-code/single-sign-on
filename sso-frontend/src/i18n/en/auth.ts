export const auth = {
  // Factor labels (resolved via t() at the render site) --------------------
  factorPassword: "Password",
  factorTotp: "Authenticator app",
  factorEmail: "Email code",
  factorPasskey: "Passkey",

  // Shared words / buttons -------------------------------------------------
  continue: "Continue",
  cancel: "Cancel",
  home: "Home",
  or: "or",
  verify: "Verify",
  remove: "Remove",
  setUp: "Set up",
  signIn: "Sign in",
  signOut: "Sign out",
  revoke: "Revoke",
  optional: "(optional)",
  passwordPlaceholder: "Password",

  // Shared status labels ---------------------------------------------------
  enrolled: "Enrolled",
  notSetUp: "Not set up",
  registered: "Registered",
  none: "None",
  verified: "Verified",
  unverified: "Unverified",

  // Shared field labels ----------------------------------------------------
  emailLabel: "Email",
  newPassword: "New password",
  confirmPassword: "Confirm password",
  adminPassword: "Admin password",
  passwordsMismatch: "The passwords don't match.",

  // Shared identity terms --------------------------------------------------
  user: "User",
  roles: "Roles",
  device: "Device",
  ipAddress: "IP address",
  lastSeen: "Last seen",
  thisDevice: "This device",
  activeSessions: "Active sessions",
  passkeys: "Passkeys",

  // Shared factor actions --------------------------------------------------
  usePasskey: "Use your passkey",
  emailMeCode: "Email me a code",
  managePasskeys: "Manage passkeys",
  manageMyPasskeys: "Manage my passkeys",
  continueToSignIn: "Continue to sign in",
  goToSignIn: "Go to sign in",
  createWorkspace: "Create workspace",
  useDifferentOrg: "Use a different organization",

  // AuthLayout -------------------------------------------------------------
  layoutBack: "Back",
  layoutSecuredBy: "Secured by Mini SSO · single-node Identity Provider",

  // OrgSelect --------------------------------------------------------------
  orgSelectDescription: "Enter your organization to continue.",
  orgLabel: "Organization",
  orgPlaceholder: "your-org",
  orgContinueToThis: "Continue to this organization",
  orgSelectHint: "Don't know your organization identifier? Contact your administrator.",
  orgNotFound: "We couldn't find that organization. Check the identifier with your administrator.",
  orgContinueFailed: "Could not continue. Please try again.",

  // Login ------------------------------------------------------------------
  loginTitle: "Sign in",
  loginDescription: "Enter your email to continue with your organization's sign-in policy.",
  loginEmailPlaceholder: "you@example.com",
  signInWithPasskey: "Sign in with a passkey",
  loginPolicyHint: "We'll ask for your password or other factors based on your sign-in policy. Need an account? Contact your administrator.",
  loginNoAccount: "No account found for that email. Accounts are created by an administrator — please contact them.",
  loginStartFailed: "Could not start sign-in. Please try again.",
  loginPasskeyFailed: "Passkey sign-in did not complete. You can continue with your email instead.",

  // MfaStep ----------------------------------------------------------------
  mfaBackToSignIn: "Back to sign in",
  mfaSigningIn: "Signing in",
  mfaSigningInAs: "Signing in as {{name}}",
  mfaTitle: "Verify your identity",
  mfaEnrollDescription: "Set up your authenticator app to finish securing your account.",
  mfaFactorDescription: "Complete the {{factor}} step required by your sign-in policy.",
  mfaRegisterPasskey: "Register a passkey & continue",
  mfaRegisterPasskeyHint: "Your device will prompt you to create a passkey, then you'll be signed in.",
  mfaPasskeyDisabled: "No passkey is set up, and registration during login is disabled. Ask an administrator to enable it.",
  mfaVerifyPassword: "Verify password",
  mfaTotpEnrollBlocked: "Your authenticator app isn't set up yet, and setup during login is disabled. Ask an administrator to enable it for your account.",
  mfaScanWithApp: "Scan with your authenticator app",
  mfaEnrollStartFailed: "Could not start enrollment.",
  mfaVerifyAndEnroll: "Verify & enroll",

  // Shared TOTP QR ---------------------------------------------------------
  totpQrAlt: "TOTP QR code",
  totpEnterKeyManually: "Enter key manually",

  // AppStepUp --------------------------------------------------------------
  stepUpStep: "Additional verification",
  stepUpChecking: "Checking requirements…",
  stepUpTitle: "Extra security required",
  stepUpDescription: "This application requires an additional verification step before you can continue.",
  stepUpLoadFailed: "Could not load the required verification.",

  // Signup -----------------------------------------------------------------
  signupTitle: "Get started",
  signupDescription: "Create your organization's workspace. We'll email your admin a link to verify and finish setup.",
  signupRequiredFields: "A subdomain, company name, and the admin's name and email are required.",
  signupCheckEmail: "Check your email",
  signupEmailedBody: "We've emailed <b>{{email}}</b> a link to verify your address and finish creating <b>{{slug}}</b>. Open it to set your admin password — your workspace is created once you do.",
  signupNoApproval: "No approval needed — just confirm your email.",
  signupSubdomain: "Company subdomain",
  signupSubdomainHint: "Lowercase letters, digits, and hyphens. Your first workspace will live at <b>main.{{slug}}</b> — add more later.",
  signupCompanyName: "Company name",
  signupCompanySize: "Company size",
  signupPreferNotToSay: "Prefer not to say",
  signupEmployees: "{{range}} employees",
  signupCountry: "Country",
  signupIndustry: "Industry",
  signupAdminName: "Administrator name",
  signupWorkEmail: "Work email",
  signupAdminEmailHint: "The admin gets an email to set their password and manage the workspace.",
  signupHaveWorkspace: "Already have a workspace? <a>Sign in</a>",

  // Activate ---------------------------------------------------------------
  activateTitle: "Verify your email",
  activateDescription: "Set your admin password to finish creating your workspace.",
  activateMissingToken: "This link is missing its token — please use the link from your verification email.",
  activateFailed: "We couldn't create your workspace. The verification link may have expired.",
  activateDoneTitle: "Your workspace is ready",
  activateDoneDesc: "{{slug}} is all set up.",
  activateDoneBody: "Your email is verified and <b>{{slug}}</b> has been created with your admin account.",
  activateDoneAddress: "Your workspace address is <b>{{host}}</b>.",
  activateHint: "Use at least 8 characters. This one-time link expires soon; your workspace is created when you submit.",

  // SetPassword (invitation) ----------------------------------------------
  setPasswordTitle: "Set your password",
  setPasswordDescription: "Activate your admin account to finish setting up your workspace.",
  setPasswordMissingToken: "This link is missing its token — please use the link from your invitation email.",
  setPasswordFailed: "We couldn't set your password. The invitation link may have expired.",
  setPasswordDoneTitle: "You're all set",
  setPasswordDoneDesc: "Your admin account is active.",
  setPasswordDoneBody: "Your password has been set. You can now sign in to your workspace.",
  setPasswordSubmit: "Set password",
  setPasswordHint: "Use at least 8 characters. This is a one-time link and expires soon.",

  // ForcePasswordReset -----------------------------------------------------
  forceResetTitle: "Set a new password",
  forceResetDescription: "Your account was created with a temporary password. Choose your own to finish signing in.",
  forceResetCancel: "Cancel and sign out",
  forceResetTooShort: "Choose a password of at least 8 characters.",
  forceResetFailed: "We couldn't update your password. Please try again.",
  forceResetSubmit: "Set password and continue",

  // Profile ----------------------------------------------------------------
  profileTitle: "My Profile",
  profileDescription: "Manage your own security factors, passkeys, and active sessions.",
  profileSecurityFactors: "Security factors",
  profileTotpCardTitle: "Authenticator app (TOTP)",
  profileTotpDetail: "Time-based one-time codes",
  profilePasskeysDetail: "Passwordless sign-in + FIDO2",
  profilePasskeysCount: "{{count}} registered",
  profileRemoveTotpTitle: "Remove authenticator?",
  profileRemoveTotpDesc: "Time-based one-time codes will no longer be accepted for your account.",
  profileSignOutDeviceTitle: "Sign out this device?",
  profileSignOutDeviceDesc: "You will be signed out on this device on your next request.",
  profileRevokeSessionTitle: "Revoke session?",
  profileRevokeSessionDesc: "The session on \"{{device}}\" ({{ip}}) will be ended.",
  profileTotpDialogTitle: "Set up authenticator app",
  profileTotpDialogDesc: "Scan the QR code with your authenticator app, then enter the 6-digit code to enable it.",
  profileTotpAlreadyEnrolled: "An authenticator is already enrolled.",
  profileTotpStartFailed: "Could not start enrollment. Please try again.",
  profileTotpInvalidCode: "Invalid code, try again.",
  profileTotpVerifyFailed: "Could not verify the code.",
  profileVerifyAndEnable: "Verify & enable",

  // Passkeys page ----------------------------------------------------------
  passkeysTitle: "My Passkeys",
  passkeysDescription: "Security keys and platform passkeys on your account. One passkey works for both passwordless sign-in and the FIDO2 step of your policy.",

  // MyApps -----------------------------------------------------------------
  myAppsTitle: "My Applications",
  myAppsDescription: "Single sign-on to the apps assigned to you.",
  myAppsSsoEnabled: "SSO enabled",
  myAppsLaunch: "Launch",

  // Dashboard --------------------------------------------------------------
  dashboardWelcome: "Welcome, {{name}}",
  dashboardDescription: "Your identity and security status at a glance.",
  dashboardTotpLabel: "Authenticator (TOTP)",
  dashboardPasskeyLabel: "Passkey (FIDO2)",
  dashboardFactorsThisSession: "Factors this session",
  dashboardIdentity: "Identity",
  dashboardIdentityDesc: "Details of your current session.",
  dashboardFactorsSatisfied: "Factors satisfied",
  dashboardSecurityKeys: "Security keys",
  dashboardSecurityKeysDesc: "One passkey covers passwordless sign-in and your FIDO2 policy step.",
} as const;

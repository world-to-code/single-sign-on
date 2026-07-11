import type { auth as enAuth } from "../en/auth";

export const auth: Record<keyof typeof enAuth, string> = {
  // Factor labels ----------------------------------------------------------
  factorPassword: "비밀번호",
  factorTotp: "인증 앱",
  factorEmail: "이메일 코드",
  factorPasskey: "패스키",

  // Shared words / buttons -------------------------------------------------
  continue: "계속",
  cancel: "취소",
  home: "홈",
  or: "또는",
  verify: "확인",
  remove: "삭제",
  setUp: "설정",
  signIn: "로그인",
  signOut: "로그아웃",
  revoke: "해지",
  optional: "(선택)",
  passwordPlaceholder: "비밀번호",

  // Shared status labels ---------------------------------------------------
  enrolled: "등록됨",
  notSetUp: "미설정",
  registered: "등록됨",
  none: "없음",
  verified: "인증됨",
  unverified: "미인증",

  // Shared field labels ----------------------------------------------------
  emailLabel: "이메일",
  newPassword: "새 비밀번호",
  confirmPassword: "비밀번호 확인",
  adminPassword: "관리자 비밀번호",
  passwordsMismatch: "비밀번호가 일치하지 않습니다.",

  // Shared identity terms --------------------------------------------------
  user: "사용자",
  roles: "역할",
  device: "기기",
  ipAddress: "IP 주소",
  lastSeen: "마지막 접속",
  thisDevice: "현재 기기",
  activeSessions: "활성 세션",
  passkeys: "패스키",

  // Shared factor actions --------------------------------------------------
  usePasskey: "패스키 사용",
  emailMeCode: "이메일로 코드 받기",
  managePasskeys: "패스키 관리",
  manageMyPasskeys: "내 패스키 관리",
  continueToSignIn: "로그인 화면으로 이동",
  goToSignIn: "로그인 화면으로 이동",
  createWorkspace: "워크스페이스 생성",
  useDifferentOrg: "다른 조직 사용",

  // AuthLayout -------------------------------------------------------------
  layoutBack: "뒤로",
  layoutSecuredBy: "Mini SSO 보안 · 단일 노드 인증 제공자",

  // OrgSelect --------------------------------------------------------------
  orgSelectDescription: "계속하려면 조직을 입력하세요.",
  orgLabel: "조직",
  orgPlaceholder: "your-org",
  orgContinueToThis: "이 조직으로 계속",
  orgSelectHint: "조직 식별자를 모르면 관리자에게 문의하세요.",
  orgNotFound: "해당 조직을 찾을 수 없습니다. 관리자에게 식별자를 확인하세요.",
  orgContinueFailed: "계속할 수 없습니다. 다시 시도하세요.",

  // Login ------------------------------------------------------------------
  loginTitle: "로그인",
  loginDescription: "조직의 로그인 정책으로 계속하려면 이메일을 입력하세요.",
  loginEmailPlaceholder: "you@example.com",
  signInWithPasskey: "패스키로 로그인",
  loginPolicyHint: "로그인 정책에 따라 비밀번호나 다른 인증 수단을 요청합니다. 계정이 필요하면 관리자에게 문의하세요.",
  loginNoAccount: "해당 이메일의 계정이 없습니다. 계정은 관리자가 생성하므로 관리자에게 문의하세요.",
  loginStartFailed: "로그인을 시작할 수 없습니다. 다시 시도하세요.",
  loginPasskeyFailed: "패스키 로그인이 완료되지 않았습니다. 이메일로 계속할 수 있습니다.",

  // MfaStep ----------------------------------------------------------------
  mfaBackToSignIn: "로그인으로 돌아가기",
  mfaSigningIn: "로그인 중",
  mfaSigningInAs: "{{name}} 계정으로 로그인 중",
  mfaTitle: "본인 확인",
  mfaEnrollDescription: "계정 보안을 완료하려면 인증 앱을 설정하세요.",
  mfaFactorDescription: "로그인 정책에 필요한 {{factor}} 단계를 완료하세요.",
  mfaRegisterPasskey: "패스키 등록 후 계속",
  mfaRegisterPasskeyHint: "기기에서 패스키 생성을 요청하며, 완료하면 로그인됩니다.",
  mfaPasskeyDisabled: "등록된 패스키가 없고 로그인 중 등록이 비활성화되어 있습니다. 관리자에게 활성화를 요청하세요.",
  mfaVerifyPassword: "비밀번호 확인",
  mfaTotpEnrollBlocked: "인증 앱이 아직 설정되지 않았고 로그인 중 설정이 비활성화되어 있습니다. 관리자에게 계정 활성화를 요청하세요.",
  mfaScanWithApp: "인증 앱으로 스캔",
  mfaEnterKeyManually: "키 직접 입력",
  mfaEnrollStartFailed: "등록을 시작할 수 없습니다.",
  mfaVerifyAndEnroll: "확인 후 등록",

  // Shared TOTP QR ---------------------------------------------------------
  totpQrAlt: "TOTP QR 코드",
  totpEnterKeyManually: "키 직접 입력",

  // AppStepUp --------------------------------------------------------------
  stepUpStep: "추가 인증",
  stepUpChecking: "요구사항 확인 중…",
  stepUpTitle: "추가 보안 필요",
  stepUpDescription: "이 애플리케이션은 계속하기 전에 추가 인증 단계가 필요합니다.",
  stepUpLoadFailed: "필요한 인증을 불러올 수 없습니다.",

  // Signup -----------------------------------------------------------------
  signupTitle: "시작하기",
  signupDescription: "조직의 워크스페이스를 생성합니다. 관리자에게 확인 및 설정 완료 링크를 이메일로 보냅니다.",
  signupRequiredFields: "서브도메인, 회사명, 관리자 이름과 이메일은 필수입니다.",
  signupCheckEmail: "이메일을 확인하세요",
  signupEmailedBody: "인증 링크를 <b>{{email}}</b> 주소로 보냈습니다. 링크를 열어 관리자 비밀번호를 설정하면 <b>{{slug}}</b> 워크스페이스가 생성됩니다.",
  signupNoApproval: "승인이 필요 없습니다 — 이메일만 확인하세요.",
  signupSubdomain: "회사 서브도메인",
  signupSubdomainHint: "소문자, 숫자, 하이픈만 사용합니다. 첫 워크스페이스 주소는 <b>main.{{slug}}</b> 형식이며, 나중에 더 추가할 수 있습니다.",
  signupCompanyName: "회사명",
  signupCompanySize: "회사 규모",
  signupPreferNotToSay: "선택 안 함",
  signupEmployees: "직원 {{range}}명",
  signupCountry: "국가",
  signupIndustry: "업종",
  signupAdminName: "관리자 이름",
  signupWorkEmail: "업무 이메일",
  signupAdminEmailHint: "관리자에게 비밀번호 설정 및 워크스페이스 관리를 위한 이메일이 전송됩니다.",
  signupHaveWorkspace: "이미 워크스페이스가 있나요? <a>로그인</a>",

  // Activate ---------------------------------------------------------------
  activateTitle: "이메일 확인",
  activateDescription: "워크스페이스 생성을 완료하려면 관리자 비밀번호를 설정하세요.",
  activateMissingToken: "이 링크에 토큰이 없습니다 — 확인 이메일의 링크를 사용하세요.",
  activateFailed: "워크스페이스를 생성할 수 없습니다. 확인 링크가 만료되었을 수 있습니다.",
  activateDoneTitle: "워크스페이스 준비 완료",
  activateDoneDesc: "{{slug}} 설정이 완료되었습니다.",
  activateDoneBody: "이메일이 인증되었고 관리자 계정과 함께 <b>{{slug}}</b> 워크스페이스가 생성되었습니다.",
  activateDoneAddress: "워크스페이스 주소는 <b>{{host}}</b> 입니다.",
  activateHint: "8자 이상 사용하세요. 이 일회용 링크는 곧 만료되며, 제출 시 워크스페이스가 생성됩니다.",

  // SetPassword (invitation) ----------------------------------------------
  setPasswordTitle: "비밀번호 설정",
  setPasswordDescription: "워크스페이스 설정을 완료하려면 관리자 계정을 활성화하세요.",
  setPasswordMissingToken: "이 링크에 토큰이 없습니다 — 초대 이메일의 링크를 사용하세요.",
  setPasswordFailed: "비밀번호를 설정할 수 없습니다. 초대 링크가 만료되었을 수 있습니다.",
  setPasswordDoneTitle: "설정 완료",
  setPasswordDoneDesc: "관리자 계정이 활성화되었습니다.",
  setPasswordDoneBody: "비밀번호가 설정되었습니다. 이제 워크스페이스에 로그인할 수 있습니다.",
  setPasswordSubmit: "비밀번호 설정",
  setPasswordHint: "8자 이상 사용하세요. 이 일회용 링크는 곧 만료됩니다.",

  // ForcePasswordReset -----------------------------------------------------
  forceResetTitle: "새 비밀번호 설정",
  forceResetDescription: "임시 비밀번호로 생성된 계정입니다. 로그인을 완료하려면 직접 비밀번호를 설정하세요.",
  forceResetCancel: "취소하고 로그아웃",
  forceResetTooShort: "8자 이상의 비밀번호를 설정하세요.",
  forceResetFailed: "비밀번호를 변경할 수 없습니다. 다시 시도하세요.",
  forceResetSubmit: "비밀번호 설정 후 계속",

  // Profile ----------------------------------------------------------------
  profileTitle: "내 프로필",
  profileDescription: "본인의 보안 인증 수단, 패스키, 활성 세션을 관리합니다.",
  profileSecurityFactors: "보안 인증 수단",
  profileEmailTitle: "이메일",
  profileRolesLabel: "역할:",
  profilePasskeysSection: "패스키",
  profileActiveSessions: "활성 세션",
  profileTotpCardTitle: "인증 앱 (TOTP)",
  profileTotpDetail: "시간 기반 일회용 코드",
  profilePasskeysDetail: "비밀번호 없는 로그인 + FIDO2",
  profilePasskeysCount: "{{count}}개 등록됨",
  profileRemoveTotpTitle: "인증 앱을 삭제할까요?",
  profileRemoveTotpDesc: "이 계정에서 시간 기반 일회용 코드가 더 이상 허용되지 않습니다.",
  profileSignOutDeviceTitle: "이 기기에서 로그아웃할까요?",
  profileSignOutDeviceDesc: "다음 요청 시 이 기기에서 로그아웃됩니다.",
  profileRevokeSessionTitle: "세션을 해지할까요?",
  profileRevokeSessionDesc: "\"{{device}}\" ({{ip}}) 세션을 종료합니다.",
  profileTotpDialogTitle: "인증 앱 설정",
  profileTotpDialogDesc: "인증 앱으로 QR 코드를 스캔한 뒤 6자리 코드를 입력해 활성화하세요.",
  profileTotpAlreadyEnrolled: "이미 인증 앱이 등록되어 있습니다.",
  profileTotpStartFailed: "등록을 시작할 수 없습니다. 다시 시도하세요.",
  profileTotpInvalidCode: "잘못된 코드입니다. 다시 시도하세요.",
  profileTotpVerifyFailed: "코드를 확인할 수 없습니다.",
  profileVerifyAndEnable: "확인 후 활성화",

  // Passkeys page ----------------------------------------------------------
  passkeysTitle: "내 패스키",
  passkeysDescription: "계정의 보안 키와 플랫폼 패스키입니다. 패스키 하나로 비밀번호 없는 로그인과 정책의 FIDO2 단계를 모두 처리합니다.",

  // MyApps -----------------------------------------------------------------
  myAppsTitle: "내 애플리케이션",
  myAppsDescription: "할당된 앱에 대한 싱글 사인온입니다.",
  myAppsSsoEnabled: "SSO 활성화",
  myAppsLaunch: "실행",

  // Dashboard --------------------------------------------------------------
  dashboardWelcome: "{{name}}님, 환영합니다",
  dashboardDescription: "본인의 신원과 보안 상태를 한눈에 확인합니다.",
  dashboardTotpLabel: "인증 앱 (TOTP)",
  dashboardPasskeyLabel: "패스키 (FIDO2)",
  dashboardFactorsThisSession: "이번 세션 인증 수단",
  dashboardIdentity: "신원",
  dashboardIdentityDesc: "현재 세션의 상세 정보입니다.",
  dashboardFactorsSatisfied: "충족된 인증 수단",
  dashboardSecurityKeys: "보안 키",
  dashboardSecurityKeysDesc: "패스키 하나로 비밀번호 없는 로그인과 FIDO2 정책 단계를 처리합니다.",
};

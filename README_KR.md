# Mini SSO System — 멀티테넌트 Identity Provider

*🌏 [English README](README.md)*

직접 구현한 멀티테넌트 SSO Identity Provider(IdP)입니다. 조직(organization) 하나가 곧 하나의 테넌트이고,
테넌트마다 자기 서브도메인, 사용자, 정책, 앱, 서명 키를 따로 갖습니다. 다른 애플리케이션은 OIDC,
SAML 2.0, SCIM 2.0으로 인증을 위임하고, 사람이 로그인할 때는 정책 기반 MFA로 보호합니다. Spring Boot 4
와 Spring Security 7 위에 Spring Modulith 모듈러 모놀리스로 만들었고, React 관리자·로그인 콘솔을 같은
오리진에서 서빙하는 단일 배포물입니다. 테넌트 사이의 경계는 PostgreSQL Row-Level Security(RLS)로 막습니다.

한 줄로 요약하면, 각 회사는 `자기slug.example.com`에서 자기만의 격리된 IdP(사용자·MFA·정책·앱·감사 로그)를
갖고, 얇은 플랫폼 계층은 테넌트 레지스트리만 관리하며, RLS와 호스트 기반 issuer가 테넌트별 데이터와 토큰을
철저히 분리합니다.

---

## 목차

- [무엇을 하는가 (한눈에)](#무엇을-하는가-한눈에)
- [멀티테넌시](#멀티테넌시--모든-조직은-격리된-테넌트)
- [아키텍처](#아키텍처)
- [인증(Authentication)](#인증authentication--사용자가-자신을-증명하는-방법)
- [인가(Authorization)](#인가authorization--사용자가-무엇을-할-수-있는가)
- [암호화 & 키 관리](#암호화--키-관리)
- [세션 & 데이터 처리](#세션--데이터-처리)
- [보안 하드닝](#보안-하드닝)
- [연합 프로토콜](#연합-프로토콜)
- [기술 스택 & 도구](#기술-스택--도구)
- [저장소 구조](#저장소-구조)
- [빠른 시작](#빠른-시작)
- [설정](#설정)
- [주요 엔드포인트](#주요-엔드포인트)
- [흐름 검증](#흐름-검증)
- [프로덕션](#프로덕션)
- [컨벤션](#컨벤션)

---

## 무엇을 하는가 (한눈에)

| 기능 | 요약 |
|---|---|
| 멀티테넌시 | 조직이 곧 테넌트다. 테넌트마다 서브도메인(`{slug}.base`)이 있고, OIDC issuer와 서명 키를 호스트에서 유도하며, org 스코프 테이블은 PostgreSQL RLS로 격리한다. 전역/공유 행(`org_id IS NULL`)은 어디서나 보이지만 테넌트는 자기 것만 본다. |
| 2계층 관리 | 플랫폼 super-admin은 테넌트 레지스트리와 전역 설정을 관리하고, 필요할 때 테넌트로 drill-in 한다(감사 남김). 테넌트 관리자(`ROLE_ORG_ADMIN`)는 자기 org의 사용자, 앱, 역할, 정책, 키, SCIM, 자기 감사까지 모두 관리한다. |
| 테넌트 온보딩 | 공개 셀프서비스 가입(이메일 검증을 먼저 하고, 링크를 사용하기 전까지는 아무것도 만들지 않는다)과 관리자 주도 온보딩(먼저 만들고 초대) 두 경로가 있다. 테넌트를 만들면 기본 세션·인증 정책과 "All Users" 그룹이 자동으로 생긴다. |
| OIDC Provider | OAuth 2.1 + OpenID Connect 1.0. discovery, JWKS(회전 가능한 RS256), authorization-code + PKCE, client-credentials, refresh, consent, UserInfo를 지원한다. issuer는 서브도메인에서 유도되어 테넌트마다 다르다. |
| SAML 2.0 IdP | OpenSAML 5 기반. metadata, `AuthnRequest`(HTTP-Redirect/POST), 서명된 assertion, 테넌트별 relying-party 레지스트리. |
| SCIM 2.0 서버 | Users/Groups 인바운드 provisioning(`/scim/v2`, bearer 인증). 테넌트 토큰은 자기 org에만 provision 한다. |
| MFA | 테넌트를 먼저 정하고 이메일로 사용자를 식별한 뒤, password·TOTP·email OTP·FIDO2 passkey를 사용자별 인증 정책 순서대로 요구한다. org 설정에 따라 passkey를 첫 factor로 쓰는 passwordless 로그인도 가능하다. |
| Step-up / 승격 | 민감한 작업에는 RFC 9470 방식의 신선한 재인증을 요구한다. 관리자 콘솔 진입은 토큰 기반 권한 승격이며, 그 범위는 현재 테넌트의 세션 정책이 정한다. |
| RBAC + PBAC | 역할이 URL을, 세분화된 권한이 개별 작업(`@PreAuthorize`)을 통제하고, 인스턴스 단위(ABAC) 검사가 모든 객체를 현재 테넌트 범위로 좁힌다. |
| 셀프서비스 | "My Profile"에서 등록한 factor·passkey, 이메일 검증 상태, 기기별로 끊을 수 있는 활성 세션 목록을 관리한다. |
| 관리자 콘솔 | 같은 SPA에서 사용자 생애주기, OIDC 클라이언트, SAML relying party, 그룹·역할·리소스, 세션/인증 정책, 네트워크 존, 감사 로그, SCIM 토큰, 키 회전을 다룬다. 모든 화면이 현재 테넌트 범위로 스코프된다. |

---

## 멀티테넌시 — 모든 조직은 격리된 테넌트

조직이 곧 테넌트다. 회사 하나에 org 하나가 대응하고, 그 안에 자기 사용자·그룹·역할·정책·앱·서명 키·감사
로그가 담긴다. 격리는 세 계층에서 이뤄진다.

### 1. 데이터 격리 — PostgreSQL Row-Level Security

org 스코프 테이블에는 `org_id`가 있고, `org_id IS NULL OR org_id = current_setting('app.current_org')`
형태의 RLS 정책이 걸려 있다. 그래서 테넌트는 자기 행과 전역/공유 행(`org_id IS NULL`, 예: 시드된 기본
정책)만 보고 다른 테넌트 행은 보지 못한다. 런타임은 요청마다 해석한 테넌트로 `app.current_org`를 세팅한다.

RLS는 강한 경계지만 앱이 non-superuser 역할로 접속할 때만 동작한다(superuser는 RLS를 그냥 통과한다).
그래서 앱은 시작할 때 런타임 역할이 superuser면 뜨지 않고 죽는다(`sso.tenancy.require-non-superuser-role`).

`app_user`와 `audit_event`는 일부러 RLS를 걸지 않았다. 로그인, 로그아웃 전파, SCIM처럼 브라우저 없이
또는 org 컨텍스트가 잡히기 전에 읽히는 경로가 많기 때문이다. 대신 이 두 테이블은 `org_id` 컬럼을 두고
애플리케이션 코드에서 읽을 때마다 명시적으로 org로 좁혀 격리한다.

### 2. 테넌트 해석 — 서브도메인과 호스트 기반 issuer

테넌트는 `{slug}.base`(예: `acme.localhost:9000`, `acme.idp.example.com`)에 산다. 인증 전에 호스트로 org를
정하기 때문에 다음이 성립한다.

- OIDC issuer가 테넌트마다 다르다(`http://acme.localhost:9000`이 자기 discovery와 JWKS를 갖는다). 그 테넌트
  서명 키로 뒷받침하고, 없으면 전역 키로 폴백한다.
- 세션은 호스트에 묶인다. 한 테넌트 서브도메인에서 만든 세션을 다른 서브도메인에서 쓰면 거부되고
  (`TenantSessionHostGuard`), 없는 서브도메인은 404다(`TenantUnknownSubdomainGuard`).
- 로그인은 호스트에서 org를 자동으로 골라 주므로 멤버는 그냥 로그인만 하면 된다.

### 3. 2계층 관리 — 플랫폼과 테넌트, 그리고 drill-in

| | 플랫폼 super-admin (`ROLE_ADMIN`) | 테넌트 관리자 (`ROLE_ORG_ADMIN`) |
|---|---|---|
| 범위 | 테넌트 레지스트리와 전역/공유 설정. 전 테넌트를 한꺼번에 보는 화면은 없다. | 자기 org 안의 모든 것. 사용자·그룹·역할·앱·인증/세션 정책·네트워크 존·서명 키·SCIM·자기 감사. |
| 교차 테넌트 | drill-in(`X-Org-Context`, 멤버십을 실시간 확인, 감사 남김)으로만 테넌트 데이터에 들어간다. okta와 같은 방식이다. | 없다. 호스트와 멤버십으로 자기 org에만 묶인다. |
| 권한 | `Permissions.PLATFORM`(테넌트 레지스트리). 테넌트에게는 보이지도, 부여되지도 않는다. | `Permissions.tenantGrantable()`, 즉 나머지 전부. 도메인마다 org로 격리돼 있다. |

drill-in 하면 super-admin이 특정 테넌트인 것처럼 행동하고(RLS가 그 org로 다시 좁혀진다), 누가 어느 테넌트에
들어갔는지 감사 로그에 자세히 남는다. drill-in 하지 않은 상태에서는 전역 행만 보인다.

### 4. 테넌트 기본값 — 생성 시 자동 provision

조직을 만들면 이벤트가 발행되고, 그 테넌트의 편집 가능한 기본값이 생긴다. 기본 세션 정책과 인증(로그인)
정책이 org 소유로 만들어지는데, 전역 기본값보다 우선순위가 높아 그 org에서는 이 정책이 이기고, 배정이
없어 모든 멤버에게 적용된다. 여기에 org별 "All Users" 그룹도 함께 만들어진다.
정책 우선순위는 tier(각 org의 집합 + 전역 집합) 안에서 유일해서, 같은 특이성의 tie-break — 즉 이기는
정책 — 이 결정적이다.

Default 정책은 잠긴 fallback이다. 배정·우선순위·활성 상태가 고정돼서, 관리자가 catch-all을 빈 대상에 걸어
아무도 로그인 못 하게 만드는 사고를 막는다. 관리자 콘솔 설정(elevation 토큰 TTL, IP allowlist)도 테넌트별로
두며, 관리자 세션 수명은 그 테넌트의 세션 정책에서 가져온다.

---

## 아키텍처

단일 배포물이지만 내부는 Spring Modulith 모듈러 모놀리스로 짜여 있다. `user`, `organization`,
`authpolicy`, `session`, `oidc`, `saml`, `scim`, `admin`, `onboarding`, `resource` 같은 각 도메인이
루트 API(인터페이스와 record DTO)만 노출하는 모듈이고, 엔티티나 리포지토리는 모듈 경계를 넘지 못한다.
`ModularityTests`가 이 경계를 계속 검증한다. React SPA는 백엔드 정적 리소스로 빌드해 IdP 오리진에서
서빙하므로 IdP 세션 쿠키를 그대로 공유한다(1st-party UI라 교차 오리진 토큰을 주고받을 필요가 없다).
`SecurityFilterChain`을 관심사별로 나눴다. OAuth2 Authorization Server(프로토콜 엔드포인트, 테넌트 호스트
필터 포함), SCIM 체인(무상태 bearer), 앱/SPA 체인(세션 + CSRF + 테넌트 컨텍스트 + RLS)이 각각 따로 돈다.

```
             {slug}.example.com                ┌───────────────────────────────────────────────┐
  OIDC RP  ──/oauth2/*,/.well-known,/userinfo──►│   Spring Boot 4 IdP (Spring Modulith)         │
  SAML SP  ──/saml2/idp/{metadata,sso}─────────►│   ├─ Spring Security 7 (+ Auth Server)        │──JPA──► PostgreSQL 17
  Ext IdP  ──/scim/v2/*  (Bearer)──────────────►│   ├─ OpenSAML 5  (SAML IdP)                   │  Row-Level Security
  Browser  ──/ (React SPA) + /api/auth/* (cookie)►│   ├─ scim-sdk    (SCIM 2.0)                  │  (Flyway-managed)
  Browser  ──/api/admin/* (Bearer elevation)────►│   ├─ WebAuthn / TOTP / email OTP / RSA keys   │
             ▲ host → tenant + issuer + key      │   └─ TenantHostFilter + OrgContext + RLS bind │──SMTP──► MailHog (dev)
                                       └───────────────────────────────────────────────┘
```

테넌트는 요청 호스트가 정한다. `TenantHostFilter`와 `OrgContextFilter`가 `{slug}.base`를 조직으로 해석해
요청의 `OrgContext`로 잡고 PostgreSQL `app.current_org` GUC를 세팅하면, RLS가 모든 쿼리를 그 테넌트로
좁힌다. 같은 호스트에서 OIDC issuer와 서명 키도 유도된다. 인증하지 않은 채 `/oauth2/authorize`나
`/saml2/idp/sso`로 들어오면 SPA 로그인으로 보내고, 인증 정책을 마치면 원래 요청을 이어서 처리한다.

---

## 인증(Authentication) — 사용자가 자신을 증명하는 방법

### 테넌트 우선, identifier-first, 정책 기반 흐름

0. 테넌트 정하기. 서브도메인(`{slug}.base`)에서 org를 가져오거나, bare 플랫폼 호스트라면 화면에서 고른 뒤
   pre-auth 세션에 고정한다. 이후 로그인은 이 org 범위에서만 이뤄진다. 여러 org에 같은 username이 있어도
   이 org의 계정으로만 인증한다.
1. 식별(Identify). 이메일을 입력하면 해당 org의 멤버인지 확인한다. 멤버가 아니면 존재하지 않는 계정과
   똑같이 거절하므로 폼으로 계정 존재 여부를 알아낼 수 없다.
2. 정책 해석. `AuthPolicyResolver`가 로그인 org 안에서 그 사용자에게 배정된 인증 정책을 고른다(없으면 org
   기본값, 그것도 없으면 전역 기본값). 정책은 필요한 factor의 순서 목록이고, org 설정에 따라 첫 factor를
   passwordless passkey로 둘 수도 있다.
3. factor 진행. SPA가 `GET /api/auth/session`을 폴링하고, 현재 단계에 대해 범용
   `POST /api/auth/factors/{factor}/{prepare,verify}`를 호출하면 `FactorHandler` 전략으로 넘어간다. 통과한
   factor마다 `FACTOR_*` 권한이 붙는다.
4. 완료. 필요한 factor를 모두 채우면 세션이 `MFA_COMPLETE` 권한과 실제 역할·권한으로 승격된다. 보호된
   API는 `MFA_COMPLETE`를 요구하고, OIDC/SAML 인가 엔드포인트도 마찬가지다.

### 지원하는 factor

| Factor | 방식 | 비고 |
|---|---|---|
| Password | Spring Security form/JSON 로그인. | delegating encoder로 해시한다(기본 bcrypt). 알고리즘을 마이그레이션 없이 올릴 수 있다. |
| TOTP | 직접 구현한 RFC 6238(HMAC-SHA1, 6자리, 30초 스텝, ±1 skew). 등록하면 `otpauth://` URI를 스캔용 QR(ZXing)로 그린다. | 시크릿은 Base32로 저장하되 암호화한다. 마지막에 쓴 타임스텝을 소각해 재생 공격을 막는다. |
| Email OTP | 사용자에게 6자리 코드를 메일로 보낸다. | TTL과 시도 횟수 상한을 둔다(너무 틀리면 코드 소각). 최초 로그인 이메일 검증에도 쓴다. |
| FIDO2 / Passkey | Spring Security 7 WebAuthn 모듈을 쓴 passwordless·소유 factor. | 로그인 중에(enroll-at-login) 또는 셀프서비스로 등록한다. 강한·하드웨어 factor로 친다. |

### 온보딩 (최초 로그인)

새 계정은 이메일 검증을 거친 뒤 강한 factor(QR로 TOTP, 또는 passkey)를 등록한다. 로그인 도중 등록을
허용할지는 정책마다 켜고 끌 수 있다(`allowEnrollmentAtLogin`). okta와 같은 방식이다.

### Step-up과 재인증 (RFC 9470)

민감한 작업에는 최근 로그인만으로는 부족하고, 의도적으로 다시 인증해야 한다.
`POST /api/auth/reauth/{factor}/verify`가 강한 factor를 다시 검증하고, 로그인 `auth_time`과는 별개인
`stepup_time` 마커를 세션에 찍는다.

### 인증 컨텍스트 클레임 (RFC 8176)

OIDC ID 토큰과 access 토큰에는 사용자가 어떻게 인증했는지가 실려 있어서, relying party와 관리자 게이트가
인증 강도와 신선도를 판단할 수 있다.

- `amr`: 사용한 방법(`pwd`, `otp`, `hwk`(passkey), `mfa`).
- `acr`: `mfa`(factor 2개 이상) 또는 `sfa`.
- `auth_time`: 로그인 완료 시각.
- `stepup_time`: 마지막으로 의도적 step-up 한 시각(`/reauth` 뒤에만 있다).
- `org`: 이 세션이 로그인한 조직(테넌트) id.

이 값들은 JDBC 인가 저장소를 통과할 때 마커 `GrantedAuthority`(예: `AUTH_TIME_<epoch>`)로 실린다. 임의의
details 객체와 달리 이렇게 하면 직렬화가 깨지지 않는다.

---

## 인가(Authorization) — 사용자가 무엇을 할 수 있는가

- RBAC. 역할이 필터 체인에서 관리자 URL 공간을 통제한다(`ROLE_ADMIN`이 플랫폼 super-admin,
  `ROLE_ORG_ADMIN`이 테넌트 관리자).
- PBAC. 세분화된 권한(`user:update`, `key:rotate`, `audit:read` 등)이 메서드 단위 `@PreAuthorize`로 개별
  작업을 통제한다. `Permissions.PLATFORM`(테넌트 레지스트리)은 super-admin 전용이라 테넌트에 줄 수 없고,
  나머지는 모두 tenant-grantable이며 org로 격리돼 있다. 그래서 테넌트 관리자는 자기 org는 온전히
  관리하지만 그 밖으로는 손대지 못한다.
- ABAC(인스턴스 단위). 클라이언트가 넘긴 id로 참조하는 객체마다 소유·범위 검사가 `and`로 붙는다. 테넌트
  관리자는 자기 org의 행에만 닿으므로(`AdminAccessPolicy` + RLS + org-tier 가드) 테넌트를 넘는 IDOR이
  생기지 않는다. 관리자 목록 화면은 tier로 좁혀서, drill-in 안 한 super-admin은 전역 행만, 테넌트 관리자는
  자기 것만 본다. 감사 로그 조회도 현재 테넌트를 기준으로 스코프한다.
- 관리자 콘솔 진입 자체가 권한 승격이다. `/api/admin/**`에 들어가려면 전용 1st-party `admin-console` OIDC
  클라이언트(PKCE, 호스트에 무관해 어느 테넌트 서브도메인에서도 쓸 수 있다)로 받은 신선한 bearer access
  토큰이 필요하다. `AdminElevationFilter`는 RBAC/PBAC 뒤에서 추가로 도는 검사이고, 다음을 모두 만족할
  때만 요청을 통과시킨다.
  - 요청이 온 그 호스트의 이 IdP가 발급했고(`iss`), `admin-console` 클라이언트용(`azp`)일 것,
  - 예약된 `admin` 스코프를 가질 것,
  - `acr=mfa`이고, 현재 테넌트 세션 정책의 step-up 창 안에 드는 신선한 `stepup_time`을 가질 것. 토큰 자체
    나이도 그 테넌트의 elevation 토큰 TTL로 제한된다,
  - 그 테넌트의 관리자 콘솔 IP allowlist를 통과할 것,
  - 현재 세션 주체(`sub`)에 묶여 있을 것. 다른 사용자나 다른 클라이언트용으로 발급된 토큰으로는 승격할
    수 없다.

  덕분에 관리자 진입은 겉치레 프롬프트가 아니라 진짜 토큰 기반 step-up 승격이 된다. 예약된 `admin`
  스코프는 다른 어떤 클라이언트에도 배정할 수 없다.

---

## 암호화 & 키 관리

| 항목 | 방식 |
|---|---|
| 비밀번호 해시 | `DelegatingPasswordEncoder`(PHC 접두, 기본 bcrypt). 마이그레이션 없이 알고리즘을 올릴 수 있다. |
| 저장 시 시크릿 | `SecretCipher`로 인증형 AES-256-GCM(`Encryptors.delux`, `encg:` 접두)을 쓴다. 키는 env로 준 마스터 비밀번호와 salt에서 PBKDF2로 유도한다. 레거시 AES-256-CBC(`enc:`)와 평문도 여전히 읽고, 다음에 쓸 때 GCM으로 자동 업그레이드한다. |
| OIDC 토큰 서명 | 회전 가능한 RSA 키페어(RS256)를 DB에 저장하고 개인 키는 암호화한다. 활성 키로 서명하고 모든 키를 JWKS로 공개하므로, 회전 전에 발급한 토큰도 검증된다. 회전은 관리자 API로 한다. |
| SAML assertion 서명 | PKCS#12 키스토어에 담은 self-signed X.509(RSA, `SHA256withRSA`)를 쓰고 회전 가능하다. assertion은 서명 전에 marshalling하고 `KeyInfo`를 담아 SP가 검증할 수 있게 한다. |
| TOTP 시크릿 | Base32로 저장하되 `SecretCipher`로 암호화한다. |
| SCIM 토큰 | 한 번만 발급하고 SHA-256 해시로만 저장한다. 만료는 선택 사항이다. |
| 키 크기·수명 | RSA 키 크기, SAML 인증서 유효기간, assertion 창을 모두 설정할 수 있다(`sso.crypto.*`, `sso.saml.*`). |

---

## 세션 & 데이터 처리

- 영속성은 PostgreSQL 17(JPA)이다. 사용자·역할·권한·MFA factor·OAuth2 클라이언트/인가/consent·SAML
  relying party·SCIM 토큰·그룹·인증/세션 정책·서명 키·감사까지 전체 스키마를 Flyway 마이그레이션으로
  관리한다.
- 세션은 `JSESSIONID` 쿠키(HttpOnly, SameSite, 프로덕션에서는 Secure)로 키잉하는 서버측 HTTP 세션이고,
  상태는 단일 노드 인메모리다.
  - `SessionRegistry`가 사용자별 활성 세션을 추적해 최대 동시 세션을 통제한다(정책 상한을 넘으면 가장
    오래된 세션을 끊고, 매 요청마다 확인한다).
  - `SessionMetadataStore`가 세션별 기기 정보(파싱한 User-Agent, IP, 타임스탬프)를 불투명 핸들 뒤에
    보관한다. 실제 세션 id는 노출하지 않으며, 이 정보로 셀프서비스 세션 목록과 기기별 끊기를 제공한다.
- 세션 id는 인증할 때와 step-up 할 때마다 회전하고(`changeSessionId`), 레지스트리와 메타데이터도 같이
  다시 키잉한다.
- 인증·인가 이벤트(성공/실패, 식별, 관리자 작업, 거부)는 감사 테이블에 기록한다.

---

## 보안 하드닝

- 무차별 대입 방어. auth 엔드포인트에 IP별 rate limit(token-bucket)을 건다. 클라이언트 IP는 스푸핑에
  안전하게 판정한다(dev는 `X-Forwarded-For`를 믿지 않고, prod는 고정한 프록시 CIDR에서 온 것만 믿는다).
- IP 접근 목록. 실제 peer 주소에 대해 선택적으로 allow/deny 한다.
- 재생 공격 방어. TOTP는 맞은 타임스텝을 소각하고, 등록 코드 자체 스텝도 소각하며, email OTP는 시도
  상한과 TTL을 둔다. 코드 비교는 상수 시간으로 한다.
- CSRF. 세션 기반 SPA 체인에는 double-submit 쿠키(`XSRF-TOKEN` 읽기용 쿠키 + `X-XSRF-TOKEN` 헤더)를 쓴다.
  무상태 프로토콜/SCIM 체인은 설계상 제외한다.
- Zero-Trust 세션. 짧은 idle 타임아웃, 절대 세션 수명, defense-in-depth로서의 선택적 클라이언트
  (User-Agent) 바인딩, 민감 작업 시 재인증을 둔다. 세션은 테넌트 호스트에 묶여, 다른 테넌트 서브도메인에서
  쿠키를 재생하면 거부된다.
- 테넌트 격리. org 스코프 테이블에 RLS를 걸고(런타임 DB 역할이 superuser면 fail-fast), 호스트에서
  테넌트·issuer·키를 유도하며, 테넌트별 관리자 콘솔 IP allowlist를 둔다.
- 시크릿 위생. 프로덕션 시크릿은 모두 환경에서 받는다. 마스터 비밀번호와 crypto salt는 prod에서 기본값이
  없어 없으면 뜨지 않고, 시크릿이 알려진 데모 OIDC 클라이언트는 프로덕션에서 시드하지 않는다.

---

## 연합 프로토콜

| 프로토콜 | 엔드포인트 | 특징 |
|---|---|---|
| OIDC | `/.well-known/openid-configuration`, `/oauth2/{authorize,token,jwks}`, `/userinfo` | auth-code + PKCE, client-credentials, refresh, consent, 커스텀 클레임(profile/email/roles/`org`/`amr`/`acr`/`auth_time`/`stepup_time`/`azp`). issuer가 테넌트별이라 discovery/JWKS도 요청 서브도메인에서 해석된다. |
| SAML 2.0 | `/saml2/idp/{metadata,sso}` | `AuthnRequest`(Redirect/POST), MFA 게이트, 서명된 `Response`/`Assertion`, 테넌트별 relying-party 레지스트리. |
| SCIM 2.0 | `/scim/v2/{ServiceProviderConfig,Users,Groups}` | bearer 인증, list/filter/bulk 한도 설정. 테넌트 토큰은 자기 org로만 provision 하고 자기 멤버만 본다. |

---

## 기술 스택 & 도구

| 계층 | 선택 |
|---|---|
| 언어 / 런타임 | Java 21 (LTS) |
| 프레임워크 | Spring Boot 4.0.x, Spring Security 7(병합된 OAuth2 Authorization Server 포함) |
| 모듈성 | Spring Modulith 2. 모듈 경계를 강제하고 `ModularityTests`로 검증 |
| 멀티테넌시 | PostgreSQL Row-Level Security(`app.current_org` GUC), 호스트 기반 테넌트별 issuer/키 |
| SAML | OpenSAML 5.1.x. Spring에 네이티브 SAML IdP가 없어 직접 구현 |
| SCIM | scim-sdk 1.33. 프레임워크 무관, Spring `@RestController`로 노출 |
| WebAuthn / Passkey | spring-security-webauthn |
| TOTP / QR | 직접 구현한 RFC 6238 + ZXing QR 렌더 |
| 암호화 | Spring Security `Encryptors`(AES-256-GCM), JCA RSA, BouncyCastle(self-signed X.509) |
| 영속성 | PostgreSQL 17, JPA/Hibernate, Flyway 마이그레이션 |
| 빌드 | Gradle(toolchain 고정), 버전 카탈로그 |
| 프론트엔드 | React + Vite + TypeScript, shadcn/ui |
| Dev 인프라 | Docker Compose(PostgreSQL + MailHog), Testcontainers |

---

## 저장소 구조

```
mini-sso-system/
├── sso-backend/        Spring Boot IdP(Gradle 프로젝트). 빌드된 SPA를 정적 리소스에서
│   ├── src/            서빙하고, 모든 auth/crypto/protocol 로직을 소유한다.
│   ├── data/           런타임 SAML 키스토어(gitignore)
│   └── build.gradle, settings.gradle, gradlew, gradle/
├── sso-frontend/       React 관리자 + 로그인 SPA(Vite). sso-backend의 정적 리소스로
│   └── src/            빌드한다(단일 오리진 배포물).
├── docker-compose.yml  dev 인프라: PostgreSQL + MailHog
├── Dockerfile          자립적 멀티스테이지 빌드(context = repo 루트):
│                       1단계에서 SPA를 빌드하고 2단계에서 jar에 번들한다.
├── scripts/            Python end-to-end 흐름 검증(OIDC/SAML/SCIM/admin/tenant)
└── test-client/        수동 테스트용 샘플 OIDC RP
```

---

## 빠른 시작

사전 준비물: JDK 21, Docker, Node 22.

```bash
docker compose up -d                                       # PostgreSQL + MailHog(온보딩/OTT 이메일)
cd sso-frontend && npm install && npm run build && cd ..   # SPA를 sso-backend static으로 빌드
cd sso-backend && ./gradlew bootRun                        # http://localhost:9000 에 IdP + SPA
```

http://localhost:9000 을 열고 `admin` / `admin123!`(플랫폼 super-admin)로 로그인한다. 새 사용자는 최초
로그인 때 이메일 코드(MailHog http://localhost:8025 에서 확인)와 강한 factor(QR로 TOTP, 또는 passkey)를
거친다. 개발 중 SPA 핫리로드는 `cd sso-frontend && npm run dev`(Vite :5173이 API/auth를 :9000으로 프록시).

테넌트는 서브도메인에 있다. 대부분 시스템에서 `*.localhost`가 `127.0.0.1`로 해석되므로, slug가 `acme`인
테넌트는 `http://acme.localhost:9000`에서 접근한다. 관리자 콘솔(Organizations)이나 공개 셀프서비스 가입으로
만들 수 있고, 활성화 링크는 플랫폼 호스트로 도착한 뒤 사용을 마치면 새 관리자를 자기 서브도메인으로 보낸다.

프로덕션 이미지는 프론트와 백엔드를 한 번에 빌드한다.

```bash
docker build -t mini-sso .        # 멀티스테이지, context는 repo 루트
```

### 시드된 dev 데이터

| 항목 | 값 |
|---|---|
| 플랫폼 super-admin(이메일 사전 검증) | `admin` / `admin123!`(`ROLE_ADMIN`, 테넌트 레지스트리 소유, 테넌트로 drill-in) |
| 전역 기본 정책 | 테넌트가 커스터마이즈하기 전까지 상속하는 전역 Default 세션 정책 + 인증 정책(`org_id IS NULL`) |
| OIDC confidential 클라이언트(dev 전용) | `demo-client` / `demo-secret`(auth-code+PKCE, consent) |
| 관리자 콘솔 OIDC 클라이언트 | `admin-console`(public, PKCE, 호스트 무관, 관리자 승격에 사용) |
| SCIM bearer 토큰 | `dev-scim-token` |
| SAML 테스트 SP | entityID `urn:example:sp`, ACS `http://127.0.0.1:8090/acs` |

---

## 설정

운영 값은 모두 `sso.*` 아래에 있다(`application.yml` 참고, prod 오버라이드는 `application-prod.yml`에서
env로). 주요 항목은 다음과 같다.

| 영역 | 키 |
|---|---|
| Issuer / admin 시드 | `sso.issuer`, `sso.admin.{username,email,password}` |
| 멀티테넌시 | `sso.tenancy.{base-domains,require-non-superuser-role}`, `DB_APP_USERNAME`/`DB_APP_PASSWORD`(non-superuser 런타임 역할) |
| 온보딩 | `sso.onboarding.{verification-ttl,resend-cooldown,min-password-length,set-password-url,activate-url,workspace-url-template}` |
| Crypto | `sso.crypto.{master-password,salt,rsa-key-size}` |
| Email OTP | `sso.email-otp.{ttl-minutes,max-attempts}` |
| 관리자 콘솔 / 승격 | `sso.admin-console.{redirect-uris,access-token-ttl-minutes,refresh-token-ttl-minutes}` |
| 데모 클라이언트 | `sso.demo-client.{enabled,access-token-ttl-minutes,refresh-token-ttl-days}`(prod 비활성) |
| SAML | `sso.saml.{entity-id,sso-location,keystore-*,certificate-dn,key-size,certificate-validity-days,assertion-validity-seconds}` |
| SCIM | `sso.scim.{max-results,max-filter-depth,max-bulk-operations}` |
| Rate limit / lockout | `sso.ratelimit.*`, `sso.lockout.*` |
| Zero-Trust | `sso.zerotrust.{bind-client,session-absolute-lifetime-minutes}` |
| TOTP | `sso.totp.qr-size` |

---

## 주요 엔드포인트

| 영역 | 엔드포인트 |
|---|---|
| Auth(SPA, 세션) | `/api/auth/{session,organization,identify,login,logout,factors/*,reauth/*,profile,sessions}` |
| 온보딩(공개) | `/api/onboarding/{apply,activate,set-password}`(셀프서비스 가입 → 이메일 검증 → 활성화, 초대 redeem) |
| OIDC | `/.well-known/openid-configuration`, `/oauth2/{authorize,token,jwks}`, `/userinfo`(호스트별 테넌트 issuer) |
| SAML | `/saml2/idp/{metadata,sso}` |
| SCIM | `/scim/v2/{ServiceProviderConfig,Users,Groups}`(Bearer) |
| Admin(역할 + 권한 + 승격, tier 스코프) | `/api/admin/{organizations,users,roles,groups,resources,applications,clients,relying-parties,auth-policies,session-policy,network-zones,portal-settings,audit,scim-tokens,metrics,keys}`. super-admin은 `X-Org-Context`를 붙여 테넌트로 drill-in |

---

## 흐름 검증

```bash
cd sso-backend && ./gradlew test        # Testcontainers 통합 테스트(ModularityTests + RLS 포함)
python3 scripts/oidc_authcode_flow.py   # OIDC: MFA 세션 -> PKCE -> ID 토큰
python3 scripts/saml_sso_flow.py        # SAML: MFA 게이트 SSO -> 서명 assertion
python3 scripts/admin_api_flow.py       # Admin API: RBAC/PBAC + 사용자 생애주기(세션)
python3 scripts/tenant_login_flow.py    # 멀티테넌시: 서브도메인에서 테넌트별 로그인 + 격리
python3 scripts/scim_provision_flow.py  # SCIM: org에 사용자 provision 후 그 사용자로 로그인
```

---

## 프로덕션

`SPRING_PROFILES_ACTIVE=prod`로 실행하고, 시크릿은 환경에서 받는다(`application-prod.yml` 참고). 필수 값은
`DB_PASSWORD`, `SSO_ISSUER`, `SSO_ADMIN_PASSWORD`, `SSO_SAML_ENTITY_ID`, `SSO_SAML_SSO_LOCATION`,
`SSO_SAML_KEYSTORE_PASSWORD`, `SSO_CRYPTO_MASTER_PASSWORD`, `SSO_CRYPTO_SALT`,
`SSO_ADMIN_CONSOLE_REDIRECT_URIS`이다. IP 기반 제어가 스푸핑에 안전하도록 `SSO_TRUSTED_PROXIES`를
로드밸런서 CIDR로 맞춰 둔다. 이미지는 `docker build -t mini-sso .`로 빌드한다. 단일 노드이고 세션이
인메모리라, 스케일 아웃하려면 먼저 sticky 세션을 두거나 세션 저장소를 외부화한다.

테넌트 격리에는 non-superuser DB 역할이 반드시 필요하다. 테넌트 사이의 경계인 PostgreSQL Row-Level
Security를 superuser는 그냥 통과하므로, 애플리케이션은 non-superuser 역할로 접속해야 한다. 아무것도 소유하지
않는 역할을 하나 만들고(마이그레이션 `V54`가 필요한 DML을 부여한다) 런타임을 그 역할로 가리킨다.
`DB_APP_USERNAME` / `DB_APP_PASSWORD`가 앱용이고, `DB_USERNAME` / `DB_PASSWORD`는 Flyway가 마이그레이션할
스키마 소유자로 둔다. `sso.tenancy.require-non-superuser-role`이 `true`라, 런타임 역할이 superuser면 시작이
바로 실패한다(격리 없이 조용히 도는 상황을 막는다). 로컬에서는 `docker/postgres-init/10-runtime-role.sql`이
이 역할(`sso_app`)을 자동으로 만든다.

---

## 컨벤션

도메인 객체는 setter 대신 동작을 노출하고, 불변 DTO는 각자 파일의 `record`로 두며, 서비스는 얇게 두고
추상에 의존한다(SOLID). 운영 값은 하드코딩하지 않고 설정으로 빼며, Java 완전한정명은 인라인으로 쓰지
않는다. 커밋은 Conventional Commits를 따르고(`docs/commit-convention.md`), 주석·문서·커밋 메시지는 영어로 쓴다.

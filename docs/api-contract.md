# IHRMS API contract

> **This is the spec the Spring Boot API must satisfy.** It is the HTTP contract the
> finished `apps/web` (Next.js) already calls. The Java backend must preserve every path,
> method, request/response JSON shape, status code, error envelope, and auth/cookie
> behavior below so the frontend keeps working unchanged.
>
> Reference implementation: the archived NestJS backend at git tag **`archive/ihrms-node`**
> (and the vendored contract types in `apps/web/lib/contract/*`, formerly `@ihrms/shared`).
>
> Base URL: the web sends requests to `NEXT_PUBLIC_API_URL` with **no global path prefix**
> (routes are at the root, e.g. `/auth/login`). All requests/responses are JSON
> (`Content-Type: application/json`) except the direct‑to‑storage upload/download.

---

## 1. Cross-cutting behavior

### 1.1 Auth model
- **Access token**: returned in the JSON body as `accessToken` (a JWT). The web stores it
  in memory and sends it as `Authorization: Bearer <accessToken>` on every authenticated
  request.
- **Refresh token**: set as an **httpOnly cookie** named **`ihrms_refresh`** (see §1.4).
  The web never reads it; it relies on `credentials: 'include'`.
- **401 recovery**: on any `401`, the web calls `POST /auth/refresh` once (cookie-based),
  and if it returns a new `accessToken`, retries the original request once.
- The web sends `credentials: 'include'` and `Accept: application/json` on all calls.

### 1.2 Role scopes (enforced server-side)
| Scope | Routes |
|---|---|
| Public (no auth) | `GET /health`, `POST /auth/login`, `POST /auth/employee/request-otp`, `POST /auth/employee/verify-otp`, `POST /auth/refresh`, `POST /auth/logout` |
| Authenticated (any principal) | `GET /auth/me` |
| `SUPER_ADMIN` | `/companies/**` |
| `COMPANY_ADMIN` | `/teams/**` |
| `HR` | `/employees/**` |
| `EMPLOYEE` (own record only) | `/me/onboarding/**` |

Every company-scoped query filters by `companyId` (no cross-tenant access). Resource
ownership is re-checked (e.g. a document must belong to the calling employee).

### 1.3 Status codes
- `POST` that succeeds → **201**; `GET`/`PATCH`/`PUT`/`DELETE` → **200**.
  (The web treats any 2xx as success, but the Java API should preserve these for fidelity.)
- Errors use the envelope in §1.5.

### 1.4 Refresh cookie (`ihrms_refresh`)
Set on `POST /auth/login`, `POST /auth/employee/verify-otp`, `POST /auth/refresh`; cleared
on `POST /auth/logout`.

| Attribute | Production | Local/dev |
|---|---|---|
| `HttpOnly` | true | true |
| `Secure` | true | false |
| `SameSite` | `None` | `Lax` |
| `Path` | `/auth` | `/auth` |
| `Max-Age` | `REFRESH_TOKEN_TTL` (e.g. `7d`) in seconds | same |

Production vs dev is decided by the runtime profile (Node used `NODE_ENV==='production'`).

### 1.5 Error envelope
Every non-2xx response is exactly this JSON (no extra fields):

```json
{
  "statusCode": 400,
  "error": "BAD_REQUEST",
  "message": "Team not found",
  "path": "/teams/abc",
  "timestamp": "2026-06-30T00:00:00.000Z"
}
```

- `statusCode` — the numeric HTTP status.
- `error` — the SCREAMING_SNAKE_CASE reason for the status (`BAD_REQUEST`, `UNAUTHORIZED`,
  `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `INTERNAL_SERVER_ERROR`, …). In Java this is
  `HttpStatus.valueOf(code).name()`.
- `message` — a **string** for most errors, or a **string[]** for validation failures,
  each entry formatted `"<field>: <message>"` (e.g. `["name: Name is required"]`). For a
  body-level validation error the field prefix is `(body)`.
- `path` — the request path.
- `timestamp` — ISO-8601 UTC.

The web surfaces `message` (joined if an array) and branches on `statusCode` (e.g. 409 →
inline field error). No other error fields are consumed.

---

## 2. Enums & ID format (canonical — now owned by Java)

- **UserRole**: `SUPER_ADMIN`, `COMPANY_ADMIN`, `HR`, `MANAGER`
- **EmployeeStatus**: `INVITED`, `IN_PROGRESS`, `SUBMITTED`, `HR_VERIFIED`, `APPROVED`, `REJECTED`
- **SectionKey**: `PERSONAL`, `BACKGROUND`, `GOVERNMENT`
- **SectionStatus**: `DRAFT`, `SUBMITTED`, `VERIFIED`, `REJECTED`
- **DocumentType**: `EXPERIENCE_LETTER`, `PAN`, `AADHAAR`, `BGV_DOCUMENT`, `OTHER`
- **DocumentStatus**: `PENDING`, `UPLOADED`, `VERIFIED`, `REJECTED`
- **ApprovalStatus**: `PENDING`, `APPROVED`, `REJECTED`
- **NotificationType**: `EMPLOYEE_ONBOARDED`, `EMPLOYEE_SUBMITTED`, `APPROVAL_REQUESTED`, `EMPLOYEE_APPROVED`, `EMPLOYEE_REJECTED`
- **Company.status** (free string, not an enum): `ACTIVE` | `SUSPENDED`.

**Employee ID**: `{COMPANY_CODE}-EMP-{NNNNNN}` (e.g. `ACME-EMP-000123`).
`COMPANY_CODE` matches `^[A-Z][A-Z0-9]{1,15}$`; `NNNNNN` is a 6-digit zero-padded,
per-company sequence (atomic allocation). Full employee code matches
`^[A-Z][A-Z0-9]{1,15}-EMP-\d{6}$`.

---

## 3. Endpoints

Object shapes use TypeScript-ish notation; `?` = optional/may be omitted; dates are
ISO-8601 strings. `Session` is the discriminated union in §3.2.

### 3.1 Health

**`GET /health`** — public.
Response `200`:
```ts
{ status: "ok", db: "up" | "down" }   // db reflects DB reachability; status is always "ok" when up
```
(Used by the web's status indicator. Distinct from `GET /actuator/health`, which is ops-only.)

### 3.2 Auth (`/auth`)

`Session` (returned in `AuthResult.session` and by `/auth/me`):
```ts
type Session =
  | { type: "USER"; userId: string; email: string; name: string;
      role: UserRole; companyId: string | null; teamId: string | null }
  | { type: "EMPLOYEE"; employeeId: string; employeeCode: string; email: string; companyId: string }
```
`AuthResult = { accessToken: string; session: Session }`

| Method | Path | Auth | Request body | Response (2xx) | Notes |
|---|---|---|---|---|---|
| POST | `/auth/login` | public | `{ email: string; password: string(min 8) }` | `201 AuthResult` | Sets `ihrms_refresh` cookie. Bad creds → `401`. |
| POST | `/auth/employee/request-otp` | public | `{ employeeCode: string(EMPLOYEE_CODE_REGEX, upper-cased); email: string }` | `201 { sent: boolean; expiresInSeconds: number; devOtp?: string }` | `devOtp` only in non-prod. Enumeration-safe (always `sent:true`). |
| POST | `/auth/employee/verify-otp` | public | `{ employeeCode: string; otp: string(/^\d{6}$/) }` | `201 AuthResult` | Sets cookie. Invalid/expired → `401`. Single-use OTP. |
| POST | `/auth/refresh` | public (cookie) | _none_ | `201 AuthResult` | Reads `ihrms_refresh` cookie; rotates it. No/expired cookie → `401`. |
| POST | `/auth/logout` | public | _none_ | `201 { ok: true }` | Clears `ihrms_refresh` cookie. |
| GET | `/auth/me` | authenticated | — | `200 Session` | `401` if unauthenticated. |

### 3.3 Companies (`/companies`) — `SUPER_ADMIN`

```ts
type CompanyAdmin   = { id; email; name; status: string; createdAt: string }
type CompanySummary = { id; name; code; status: "ACTIVE"|"SUSPENDED";
                        teamCount: number; employeeCount: number; hasAdmin: boolean; createdAt: string }
type CompanyDetail  = CompanySummary & { admin: CompanyAdmin | null }
```

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| GET | `/companies` | — | `200 CompanySummary[]` | |
| POST | `/companies` | `{ name: string(2..120); code: string(COMPANY_CODE_REGEX, upper) }` | `201 CompanyDetail` | Duplicate code → `409`. |
| GET | `/companies/:id` | — | `200 CompanyDetail` | `404` if missing. |
| PATCH | `/companies/:id` | `{ name?: string; status?: "ACTIVE"|"SUSPENDED" }` (≥1 field) | `200 CompanyDetail` | |
| POST | `/companies/:id/admin` | `{ name: string; email: string }` | `201 { admin: CompanyAdmin; devPassword?: string }` | Creates the `COMPANY_ADMIN` user; emails creds. One admin/company → `409` if exists; email taken → `409`. `devPassword` non-prod only. |

### 3.4 Teams (`/teams`) — `COMPANY_ADMIN` (own company)

```ts
type TeamMember  = { id; name; email; role: UserRole; status: string }
type TeamSummary = { id; name; hr: TeamMember|null; manager: TeamMember|null; memberCount: number; createdAt: string }
type TeamDetail  = TeamSummary & { members: TeamMember[] }
type AssignMemberInput = { userId: string } | { name: string; email: string }   // attach existing OR create new
```

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| GET | `/teams` | — | `200 TeamSummary[]` | Own company only. |
| GET | `/teams/assignable-users?role=HR\|MANAGER` | — | `200 TeamMember[]` | Unassigned company users of that role. **Declare before `/teams/:id`.** |
| GET | `/teams/:id` | — | `200 TeamDetail` | `404` if not in company. |
| POST | `/teams` | `{ name: string(2..120) }` | `201 TeamDetail` | hr/manager start `null`. |
| PATCH | `/teams/:id` | `{ name: string(2..120) }` | `200 TeamDetail` | |
| DELETE | `/teams/:id` | — | `200 { ok: true }` | Blocked (`409`) if approval history. |
| PUT | `/teams/:id/hr` | `AssignMemberInput` | `200 { team: TeamDetail; devPassword?: string }` | Exactly one HR; rejects wrong-role / cross-company / dual-role. |
| PUT | `/teams/:id/manager` | `AssignMemberInput` | `200 { team: TeamDetail; devPassword?: string }` | Exactly one Manager. |

### 3.5 Employees (`/employees`) — `HR` (own onboarded)

```ts
type EmployeeSummary = { id; employeeCode; email; status: EmployeeStatus; createdAt: string }
```

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| GET | `/employees` | — | `200 EmployeeSummary[]` | Only employees this HR onboarded. |
| POST | `/employees` | `{ email: string }` | `201 { employee: EmployeeSummary; loginUrl: string }` | Mints `{CODE}-EMP-{NNNNNN}` (atomic per-company), status `INVITED`, emails the ID + login link. |

### 3.6 Employee onboarding (`/me/onboarding`) — `EMPLOYEE` (own record)

```ts
type ProfileSectionDto = { key: SectionKey; data: Record<string,unknown>; status: SectionStatus; updatedAt: string }
type DocumentDto       = { id; sectionKey: SectionKey; docType: DocumentType; fileName: string;
                           mimeType: string; sha256: string|null; status: DocumentStatus; uploadedAt: string }   // NO storageKey
type OnboardingDashboard = { employeeCode; email; status: EmployeeStatus;
                             sections: ProfileSectionDto[]; documents: DocumentDto[] }
```

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| GET | `/me/onboarding` | — | `200 OnboardingDashboard` | |
| PUT | `/me/onboarding/sections/:key` | `{ data: Record<string,unknown> }` | `200 ProfileSectionDto` | `:key` ∈ SectionKey. Validated per-section (see §3.7). `DRAFT`; flips employee `INVITED→IN_PROGRESS`. |
| POST | `/me/onboarding/documents` | `{ sectionKey; docType; fileName; mimeType ∈ {application/pdf,image/png,image/jpeg}; sizeBytes(≤10MB) }` | `201 { documentId; uploadUrl; method:"PUT"; headers: Record<string,string>; expiresInSeconds }` | Creates `Document` `PENDING`; `uploadUrl` is a **short-lived presigned PUT**. Never returns the storage key. |
| POST | `/me/onboarding/documents/:id/confirm` | _none_ | `201 DocumentDto` | Server reads the uploaded object, computes `sha256`, sets `UPLOADED`. `404` if not owned. |
| GET | `/me/onboarding/documents/:id/url` | — | `200 { url: string; expiresInSeconds: number }` | Short-lived presigned **GET**. Sensitive read → audited. `404` if not owned. |
| POST | `/me/onboarding/submit` | _none_ | `201 OnboardingDashboard` | Gated (see §3.7); `IN_PROGRESS→SUBMITTED`; locks the record (later edits → `409`). Incomplete → `400`. |

### 3.7 Section validation & submission requirements

Per-section `data` validation (server + client share these rules):
- **PERSONAL**: `fullName(2..120)`, `dateOfBirth(YYYY-MM-DD)`, `phone(7..20)`, `addressLine(3..200)`, `city(2..80)`.
- **BACKGROUND**: `previousCompany?(≤120)`, `yearsOfExperience?(0..60)`, `notes?(≤1000)`.
- **GOVERNMENT**: `panNumber(/^[A-Z]{5}[0-9]{4}[A-Z]$/, upper)`, `aadhaarLast4?(/^\d{4}$/)`.

Submission gate (`POST /me/onboarding/submit` returns `400` until satisfied):
- Required sections saved: **PERSONAL, GOVERNMENT**.
- Required documents (status `UPLOADED`|`VERIFIED`): **GOVERNMENT/PAN**.

Upload constraints: `MAX_UPLOAD_BYTES = 10 MiB`; allowed mimes `application/pdf`,
`image/png`, `image/jpeg`.

### 3.8 Direct-to-storage (not the API)

The browser uploads/downloads the file bytes **directly** to object storage using the
presigned URLs the API returns — the API never proxies bytes and never exposes raw keys:
- Upload: `PUT <uploadUrl>` with the `headers` from the `documents` response (includes
  `Content-Type` matching the signed type) and the raw file body.
- View/download: `GET <url>` from `documents/:id/url`.
(The storage bucket must allow CORS for the web origin so the browser PUT/GET succeed.)

---

## 4. Configuration (env the API reads)

`DATABASE_URL` (or `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD`), `PORT`, `CORS_ORIGINS`
(comma-separated; CORS must allow credentials), `JWT_SECRET`, `REFRESH_SECRET`,
`ACCESS_TOKEN_TTL`, `REFRESH_TOKEN_TTL`, `REFRESH_COOKIE_NAME` (default `ihrms_refresh`),
`OTP_TTL`, `WEB_APP_URL` (employee login link), `MAIL_*`, `S3_*`
(`S3_ENDPOINT/S3_REGION/S3_BUCKET/S3_ACCESS_KEY_ID/S3_SECRET_ACCESS_KEY/S3_FORCE_PATH_STYLE`).

Swagger/OpenAPI: `GET /v3/api-docs` + swagger-ui (springdoc). Actuator: `GET /actuator/health`.

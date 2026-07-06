# IHRMS — Architecture & Domain Reference

> Single source of truth for what IHRMS is and how it is structured.
> Committed at the repo root so Claude Code (and humans) share the same context.
> **Status:** v1 (foundation). Living document — sections marked _[parked]_ will be expanded later.

---

## 1. Overview

**IHRMS** is a web-based employee information and onboarding management system. It manages every
employee's records — onboarding details, identity/government documents, background-verification
documents, and (later) issued letters — under a single **unique employee ID**.

The process is **HR-initiated, never employee self-signup**: HR triggers onboarding, the system
emails the new employee their unique ID, the employee fills in tabbed forms and uploads documents
under their record, HR verifies them, and a **Manager approves**. Everything that happens in the
portal is **audit-logged and partitioned per company**.

The system is **multi-tenant**: many companies live under one Super Admin, and all access is scoped
to the role hierarchy below.

---

## 2. Roles & Hierarchy

The hierarchy **is** the authorization model. Each role sees and acts only within its scope.

```
Super Admin
   └── Company  (each has one Company Admin)
          └── Team  (exactly 1 HR + 1 Manager)
                 └── Employee  (onboarded subject)
```

| Role | Scope | Can do |
|------|-------|--------|
| **Super Admin** | Entire portal | Create/manage Companies; provision each Company's Company Admin; **archive (soft-delete) a company and restore it**; view **all** companies' audit logs (separated per company), including archived companies'. |
| **Company Admin** | One company | Create teams and assign the team's HR and Manager (one each); view **own company's** audit logs. |
| **HR** | Own team / own onboarded employees | Trigger onboarding (email + unique ID); look up an employee by ID and see all their forms/documents; verify documents; route the approval request to the team's Manager. |
| **Manager** | Own team | Workspace inbox/notifications (who was onboarded, who was verified, pending approvals); **approve** verified employees. Approval is the **final step** _[parked: post-approval actions]_. |
| **Employee** | Own record only | Authenticate with **unique ID + email/OTP**; fill tabbed forms and upload documents under their own record. |

**Company archival (soft-delete):** Super Admin can **delete a company as a reversible archive** — it
disappears from all active lists and operations, and **every principal under it (Company Admin, HR,
Managers, employees) immediately loses access**: OTP login is denied and any already-issued access
token stops working. It is **never a physical purge** — child rows, uploaded documents, and the
company's **audit trail are all retained**, and Super Admin can still view the archived company's
history. A **restore** returns it to active and its people can sign in again. The **Super Admin is
never affected** (they belong to no company).

**Team rule:** exactly one HR and one Manager per team. Approvals stay **within the team**.

**Employee ↔ team linkage:** an Employee is tied to a **Company** and their **onboarding HR**
(no direct team field in v1). The approving Manager is therefore **the Manager on the onboarding
HR's team** — that is the path that connects an employee to their approver.

---

## 3. Core Workflows

### 3.1 Company & team setup
1. **Super Admin** creates a **Company** and provisions its **Company Admin**.
2. **Company Admin** creates **Teams** and assigns one **HR** and one **Manager** to each.

### 3.2 Onboarding (the spine — starts with HR)
1. **HR** initiates onboarding with **{full name, email, designation, date of joining}**. The system
   creates the employee record (`status = INVITED`). **No employee ID is minted here** — the unique
   ID is allocated only on Manager approval (see §3.3 / §5).
2. The system **emails the employee a selection note** — *"Hello {full name}, you are selected to the
   {designation} role in {company name}."* — plus a **link to the employee login**. The email carries
   **no ID** (there isn't one yet).
3. **Employee** logs in with **full name + email → OTP** (the OTP to that email is the security
   factor) and lands on their **dashboard**.
4. Employee completes a **guided four-form stepper** under their own record:
   - **Form 1 — Personal Details** (identity, addresses, character references, education, work
     experience, family, declaration)
   - **Form 2 — Employee Info** (employment + government/bank details; `employeeId` is read-only and
     blank until approval)
   - **Form 3 — Previous Employment** (one block per prior employer — **repeatable**)
   - **Form 4 — Documents** (a grouped upload checklist: educational, per-employment, identity proofs,
     other)
5. The employee **draws or types one e-signature** and **submits**. The system then **generates PDFs** —
   one per form plus one **merged complete application** — branded with the **joining company**, the
   signature stamped into Forms 1 & 2; these are stored under the record and **regenerated whenever a
   form is edited and re-submitted** (and `employeeId` is stamped in once approval mints it).
6. Every field value, uploaded file, the signature, and the generated PDFs are stored **under that
   employee's record**; submission routes to HR for verification.

### 3.3 Verification & approval
1. **HR** opens the employee's record (by ID), reviews each section/document, marks items
   **verified** (or rejects/requests changes).
2. On completion, HR **routes an approval request** to the **team's Manager**.
3. **Manager** sees it in their **notifications/approvals inbox** and **approves** → final step in v1.
4. **On approval, the system allocates the unique employee ID** (§5) from the company's atomic
   per-company sequence, stamps it on the record, and welcomes the employee with it. The ID is an
   **org/HR-facing identifier** — it is *not* used to log in.

### 3.4 HR lookup
At any time, **HR finds an employee** in their workspace (by name/email, or by ID once allocated) and
sees **all** of that employee's details and documents in one place.

---

## 4. Data Model (conceptual)

Entities and key fields. Phase 1 translates this into the precise Prisma schema; field types here are
indicative. **Schema is additive-only thereafter.**

### Accounts
- **User** _(staff/operator account)_ — `id`, `email` (unique), `name`, `role` (UserRole),
  `companyId?` (null for Super Admin), `teamId?` (for HR/Manager), `authCredential` (see §6),
  `status`, `createdAt`.
- **Employee** _(the onboarded subject — distinct from User; different auth & lifecycle)_ —
  `id`, `fullName`, `email` (**globally unique** — the login handle, with OTP), `designation`
  (job-title string, e.g. "Software Engineer"), `dateOfJoining`, `companyId`,
  `onboardingHrId` (→ User), `employeeCode` (**nullable**; unique once set — allocated on Manager
  approval, see §5), `status` (EmployeeStatus), OTP/session fields (see §6), `createdAt`.

### Organisation
- **Company** — `id`, `name`, `code` (short mnemonic, e.g. `ACME`; used in the employee ID),
  `status` (`ACTIVE` | `SUSPENDED` | `DELETED`), `deletedAt?` + `deletedByUserId?` (→ User) — set
  when archived (soft-delete), cleared on restore, `createdAt`.
- **Team** — `id`, `companyId`, `name`, `hrUserId` (→ User), `managerUserId` (→ User).
  Holding both FKs enforces the "exactly one HR + one Manager" rule.

### Employee record (the four onboarding forms)
- **Form1Personal** — Personal Details: `name`, `dob`, `email`, `mobile`, `designation`,
  `offeredCtc` [SENSITIVE], `currentAddress`, `permanentAddress`, `maritalStatus`, `bloodGroup`,
  `closestRelativeName`, `closestRelativePhone`, `city`, `relationship`, `declaration`; + child rows:
  **EducationalQualification[]** (qualification/university/yearOfPassing/percentage),
  **WorkingExperience[]** (organization/period/designation/`salaryCtc` [SENSITIVE]/reasonForLeaving),
  **FamilyDetail[]** (name/age/relation/occupation), **CharacterReference[]** (name/address/phone — min 2).
- **Form2Info** — Employee Info: `fullName`, `fatherName`, `employeeId` [SYSTEM/READONLY — blank until
  approval], `dob`, `dateOfJoining`, `bloodGroup`, `mobile`, `alternateNumber`, `officialEmail`,
  `personalEmail`, `designation`, `sparkId` [HR/ADMIN-set], `documentSubmitted`, `vehicleNo2W4W`,
  `panNumber` [SENSITIVE], `axisAccountNumber` [SENSITIVE], `currentAddress`, `permanentAddress`.
- **Form3PreviousEmployment** _(repeatable — one row per prior employer)_ — `companyName` (the employee's
  previous employer — employee-entered), `companyAddress`, `dateOfJoining`, `dateOfRelieving`,
  `designation`, `lastDrawnSalary` [SENSITIVE], `jobType`, `reasonForLeaving`, `reportingTo`,
  `roContact`, `hrNameContact`.
- **Document** _(Form 4 uploads)_ — `id`, `employeeId`, `docType` (DocumentType — the Form-4 slots),
  `groupIndex?` (1–4 for the per-employment groups), `fileName`, `storageKey` (never exposed raw),
  `mimeType`, `sha256?`, `status`, `uploadedAt`. Reached only via short-lived presigned URLs.
- **Signature** — `id`, `employeeId`, the drawn/typed e-signature (image `storageKey`), `signedAt`.
  Captured once at final submit and stamped into Forms 1 & 2 and the merged PDF.
- **GeneratedDocument** _(the produced PDFs)_ — `id`, `employeeId`, `kind`
  (FORM1 | FORM2 | FORM3 | FORM4_MANIFEST | MERGED), `storageKey`, `sha256`, `generatedAt`.
  Regenerated on every edit-and-resubmit; re-stamped with `employeeId` on approval.

Each form carries a `status` (DRAFT | SUBMITTED | VERIFIED | REJECTED). SENSITIVE fields are encrypted at
rest and masked in HR views (reveal is an explicit **audited** action — §6). `employeeId` is
**system-assigned on Manager approval** and never employee-editable; `sparkId` is HR/admin-set. The
generated PDFs' header/branding is the employee's **joining company** (resolved from `companyId`).

### Verification & approval
- **ApprovalRequest** — `id`, `employeeId`, `hrUserId`, `managerUserId`, `teamId`,
  `status` (ApprovalStatus: PENDING | APPROVED | REJECTED), `note?`, `submittedAt`, `decidedAt?`.
- **Notification** — `id`, `recipientUserId`, `type` (NotificationType), `employeeId?`,
  `read` (bool), `createdAt`. Drives the Manager's inbox.

### Audit
- **AuditLog** — `id`, `companyId?` (partition key; null only for portal-level/system events),
  `actorType` (USER | EMPLOYEE | SYSTEM), `actorId?`, `action`, `targetType?`, `targetId?`,
  `metadata` (Json), `ipAddress?`, `createdAt`. **Append-only** (see §7).

### Enums (defined in the shared package — single source of truth)
- **UserRole**: `SUPER_ADMIN`, `COMPANY_ADMIN`, `HR`, `MANAGER`
- **EmployeeStatus**: `INVITED`, `IN_PROGRESS`, `SUBMITTED`, `HR_VERIFIED`, `APPROVED`, `REJECTED`
- **SectionStatus** _(status of each form + review item)_: `DRAFT`, `SUBMITTED`, `VERIFIED`, `REJECTED`
- **DocumentType** _(Form 4 slots)_: `SECONDARY`, `INTERMEDIATE`, `DIPLOMA`, `GRADUATION`,
  `POST_GRADUATION`, `OFFER_OR_APPOINTMENT_LETTER`, `HIKE_LETTER`, `RELIEVING_LETTER` _(per-employment,
  with `groupIndex` 1–4)_, `AADHAAR`, `PAN`, `VOTER_ID`, `DRIVING_LICENCE`, `PASSPORT`, `OTHER`
- **DocumentStatus**: `PENDING`, `UPLOADED`, `VERIFIED`, `REJECTED`
- **GeneratedDocumentKind**: `FORM1`, `FORM2`, `FORM3`, `FORM4_MANIFEST`, `MERGED`
- **ApprovalStatus**: `PENDING`, `APPROVED`, `REJECTED`
- **NotificationType**: `EMPLOYEE_ONBOARDED`, `EMPLOYEE_SUBMITTED`, `APPROVAL_REQUESTED`,
  `EMPLOYEE_APPROVED`, `EMPLOYEE_REJECTED`

---

## 5. Unique Employee ID

Every **approved** employee gets a unique, human-readable, **company-scoped** ID. It is allocated **on
Manager approval** (not at onboarding) and is an **org/HR-facing identifier** — it is **not** used to
log in (employees authenticate with full name + email + OTP, see §6).

- **Format:** `{COMPANY_CODE}-EMP-{NNNNNN}` — e.g. `ACME-EMP-000123`.
- `COMPANY_CODE` is the company's short mnemonic; `NNNNNN` is a zero-padded sequence **unique within
  the company** (atomic, gap-tolerant allocation, no collisions under concurrency).
- Until approval the record exists (`status` in {INVITED … HR_VERIFIED}) with **no ID**; the ID is
  stamped on the transition to `APPROVED`, then used as the org-facing reference for HR lookups.

---

## 6. Security & Access Control

Security is structural, because the data is sensitive PII (PAN, Aadhaar, BGV, experience letters).

- **Authorization = the hierarchy.** Every request is scoped:
  - Super Admin → all companies.
  - Company Admin → only their `companyId`.
  - HR → only employees they onboarded (`onboardingHrId == self`) within their company.
  - Manager → only employees in their team's scope (onboarded by their team's HR) + their own
    notifications/approvals.
  - Employee → only their own record.
  - Centralise these relationship checks; do not scatter them across handlers.
  - **Archived-company denial:** a principal whose `companyId` refers to a **DELETED** company is
    denied on **every** request (so already-issued access tokens stop working) and at OTP login. The
    check lives in the same centralized gate. **Guard:** deny only when the principal *has* a
    `companyId` **and** that company is deleted — the **Super Admin has a null `companyId` and is
    never denied** (no self-lockout).
- **Authentication (two audiences, two entry points).** Staff and employees are distinct principals
  with distinct sign-in methods; each entry point resolves against **only one** table so the two can
  never cross over.
  - **Staff (Super Admin, Company Admin, HR, Manager) — email + password, at `/login`.** `POST
    /auth/login {email, password}` resolves a **User by email** (an employee email or an unknown email
    gets the same generic denial), verifies the password (hashed with the app's `PasswordEncoder` —
    BCrypt today; argon2 is a drop-in swap), and issues the session. **No OTP for any staff role.**
    - The password is **set at provisioning** (see below) and is **self-service changeable** while
      logged in: `POST /auth/change-password {currentPassword, newPassword}` (verify current → rehash
      → audit `PASSWORD_CHANGED`). A minimum length of **8** is enforced everywhere a password is set.
    - **Forgot-password** (email reset link) is **deferred until SMTP** is wired.
  - **Employees — full name + email → email-OTP, at `/employee/login`.** `POST /auth/request-otp`
    resolves an **Employee by email** (unique across both tables via the creation-time guard) and, only
    when the HR-entered full name matches (case-insensitive, trimmed), emails a single-use, time-boxed
    OTP; `POST /auth/verify-otp {email, code}` issues the session. The OTP to the email is the security
    factor. The **HR onboarding invitation email links to `/employee/login?email=…`**.
  - **Both paths** are **rate-limited** (RateLimitFilter on `/auth/**`) and **enumeration-safe** (a
    generic response either way), and both apply the **archived-company denial** (a principal whose
    company is DELETED is refused at login and on every request).
  - Verify/login issues the session; its **principal type + role + scope come from the resolved
    account** (a User's role + companyId + teamId, or EMPLOYEE own-record), and the response carries the
    role so the web routes to that role's area.
  - Session: short-lived access token + httpOnly refresh cookie on the API domain; CORS with
    credentials for the web origin.
  - `email` is the login handle and is **unique across both the User and Employee tables** (a
    creation-time guard rejects an email already used by the other). The dormant `users.otpHash` /
    `users.otpExpiresAt` columns are **retained but unused** (staff no longer use OTP; additive-only —
    never dropped). Adding TOTP (e.g. for SUPER_ADMIN) or SSO is a future option.
- **Uploads:** object storage (S3-compatible) accessed **only via short-lived presigned URLs** —
  never public paths or raw storage keys returned to clients.
- **Document integrity:** store `sha256` of each upload.
- **Field-level encryption (PII/financial):** `offeredCtc`, `workingExperience.salaryCtc`,
  `form3.lastDrawnSalary`, `panNumber`, `axisAccountNumber` are **encrypted at rest** (AES-GCM, key
  from `FIELD_ENC_KEY`) so a DB dump never exposes them; they are **masked by default** in HR views,
  and a **reveal is an explicit, audited action** (`SENSITIVE_FIELD_REVEALED`). The generated PDFs
  that embed these values are themselves sensitive — presigned + audited on view.
- **Transport/headers:** helmet, strict CORS allowlist, validation on every input.
- **Tenancy isolation:** every company-scoped query is filtered by `companyId`; no cross-company
  reads. This is the most important invariant in the system.

---

## 7. Audit

- **Every mutating action** in the portal is logged to `AuditLog` (actor, action, target, ip, time).
- Logs are **partitioned per company** via `companyId` so Super Admin can view each company's trail
  separately and Company Admin sees only their own.
- **Append-only:** no code path updates or deletes an `AuditLog` row (enforce at the data layer).
- **Retained across company archival:** deleting (archiving) a company never removes its audit rows.
  Super Admin can still **select an archived company** in the explorer (shown flagged as deleted) and
  read its retained trail; the archival itself is recorded (`COMPANY_DELETED` / `COMPANY_RESTORED`).
- Implemented as a global API interceptor for mutations, plus explicit log writes for sensitive
  reads (e.g. viewing/downloading an employee's documents).

---

## 8. Deployment Architecture (existing shell — keep as-is)

The project is built into the already-deployed monorepo shell. **Do not change the deployment
pipeline.**

```
repo root
├── apps/
│   ├── api/        NestJS + Prisma + PostgreSQL   → Railway   (service Root Dir = apps/api)
│   └── web/        Next.js 14 (App Router)         → Vercel    (project Root Dir = apps/web)
├── packages/
│   └── shared/     enums · zod DTOs · ID helpers   (the cross-app contract)
└── pnpm-workspace.yaml   (pnpm workspaces; Turborepo optional)
```

- **Frontend:** Next.js 14 App Router, **Tailwind pinned at v3.4** (deliberate — do not let it drift
  to v4's CSS-first model), shadcn/ui, react-query, zod. `NEXT_PUBLIC_API_URL` is **build-time
  inlined** (set it in Vercel before build).
- **Backend:** NestJS, Prisma, PostgreSQL. `prisma migrate deploy` runs on start. `/health` and
  `/docs` (Swagger). CORS from `CORS_ORIGINS`.
- **Shared package:** the single source of truth for enums, DTO zod schemas, and the ID helpers.
  Both apps import from it; nothing is duplicated. (Currently aliased `@cdpp/shared` from the prior
  scaffold; an optional rename to `@ihrms/shared` is import-only and deployment-agnostic.)
- **DB note:** the deployed Railway database still carries the prior baseline; when the new schema is
  first deployed it will need a `prisma migrate reset` (or a fresh database).

---

## 9. Design Language & UX

Goal: **interactive, user-friendly, trustworthy.** One coherent design system across four distinct
surfaces, each designed for its specific job (not a generic admin template).

- **Surfaces**
  - **Super Admin** — company management; a per-company **audit-log explorer** (filter/sort/search).
  - **Company Admin** — team management (create teams, assign HR + Manager); own-company audit view.
  - **HR** — onboarding action (trigger email + ID), **employee lookup by ID**, a **verification
    workspace** (review sections/documents, mark verified, route to Manager).
  - **Manager** — a live **notifications/approvals inbox** with a clear approve action.
  - **Employee** — a guided, **tabbed form dashboard** with obvious upload states (drag-drop,
    progress, per-item status) — should feel like guidance, not paperwork.
- **Patterns:** clear status badges (employee status, document status, approval status), optimistic
  updates with toasts, skeleton loaders, empty states, filterable/sortable tables for lists,
  responsive + accessible (labels, focus, keyboard).
- **Tone:** calm, precise, professional. Restrained motion; no flashy effects. The visual palette and
  tokens are defined in the web foundation phase and inherited everywhere.

---

## 10. Conventions (for Claude Code)

- Work only in the app/package named in a given phase; survey before building; smallest change.
- **Schema is additive-only**; every change is a new Prisma migration; never edit a past migration.
- **Enums, DTO schemas, and ID helpers live in the shared package** — never redefined in an app.
  Keep an **enum-parity test** asserting Prisma enums match the shared enums.
- **Audit every mutation** (global interceptor) + sensitive reads; `AuditLog` is append-only.
- **Every company-scoped query filters by `companyId`** — no cross-tenant leakage.
- **Uploads via presigned URLs only**; never return raw storage keys/URLs.
- Match existing repo conventions (module layout, env-var names, error handling); don't import
  patterns from other projects.

---

## 11. Deferred / Open _[parked]_

- **Offer & experience letter issuance** — whether HR uploads or the system generates them, and at
  which stage. (Records exist conceptually under the employee; flow TBD.)
- **Post–Manager-approval steps** — what approval unlocks (e.g. employee → active, letter issuance).
- **Multi-HR / multi-Manager teams** — v1 is exactly one each.
- **Deeper BGV workflow** — external provider integration vs. manual document upload.
- **Decision points to confirm:** employee ID format (§5), staff auth method (§6), and whether HR
  scope should broaden from "own onboarded employees" to "all company employees".

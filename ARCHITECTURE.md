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
| **Super Admin** | Entire portal | Create/manage Companies; provision each Company's Company Admin; view **all** companies' audit logs (separated per company). |
| **Company Admin** | One company | Create teams and assign the team's HR and Manager (one each); view **own company's** audit logs. |
| **HR** | Own team / own onboarded employees | Trigger onboarding (email + unique ID); look up an employee by ID and see all their forms/documents; verify documents; route the approval request to the team's Manager. |
| **Manager** | Own team | Workspace inbox/notifications (who was onboarded, who was verified, pending approvals); **approve** verified employees. Approval is the **final step** _[parked: post-approval actions]_. |
| **Employee** | Own record only | Authenticate with **unique ID + email/OTP**; fill tabbed forms and upload documents under their own record. |

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
4. Employee fills **tabbed/sectioned forms** and uploads documents:
   - **Personal details**
   - **Background details** (uploads — e.g. previous-company experience letters)
   - **Government details** (uploads — e.g. PAN card)
   - …(extensible — new sections can be added)
5. Every field value and uploaded file is stored **under that employee's record**.
6. Employee **submits** for verification.

### 3.3 Verification & approval
1. **HR** opens the employee's record (by ID), reviews each section/document, marks items
   **verified** (or rejects/requests changes).
2. On completion, HR **routes an approval request** to the **team's Manager**.
3. **Manager** sees it in their **notifications/approvals inbox** and **approves** → final step in v1.
4. **On approval, the system allocates the unique employee ID** (§5) from the company's atomic
   sequence and stamps it on the record. The ID is an **org/HR-facing identifier** — it is *not* used
   to log in. (Allocation-at-approval ships as a separate delta; until then approved records may carry
   no ID.)

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
  `status`, `createdAt`.
- **Team** — `id`, `companyId`, `name`, `hrUserId` (→ User), `managerUserId` (→ User).
  Holding both FKs enforces the "exactly one HR + one Manager" rule.

### Employee record
- **ProfileSection** — `id`, `employeeId`, `key` (SectionKey: PERSONAL | BACKGROUND | GOVERNMENT | …),
  `data` (Json — the structured field values for that tab), `status` (SectionStatus), `updatedAt`.
  One row per section per employee; extensible by adding new SectionKey values.
- **Document** _(uploads)_ — `id`, `employeeId`, `sectionKey`, `docType` (DocumentType),
  `fileName`, `storageKey` (object-storage key — never exposed raw), `mimeType`, `sha256?`,
  `status` (DocumentStatus: UPLOADED | VERIFIED | REJECTED), `uploadedAt`. Always stored **under the
  employee record**.

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
- **SectionKey**: `PERSONAL`, `BACKGROUND`, `GOVERNMENT` _(extensible)_
- **SectionStatus**: `DRAFT`, `SUBMITTED`, `VERIFIED`, `REJECTED`
- **DocumentType**: `EXPERIENCE_LETTER`, `PAN`, `AADHAAR`, `BGV_DOCUMENT`, `OTHER` _(extensible)_
- **DocumentStatus**: `UPLOADED`, `VERIFIED`, `REJECTED`
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
- **Authentication**
  - **Employee:** **full name + email → OTP** (no password; no ID at login). The single-use,
    time-boxed **OTP sent to the email is the security factor**; email is the globally-unique handle
    and the full name is matched against the record. OTP issuance is **rate-limited** and
    **enumeration-safe** (a generic "if a match exists, a code was sent" response).
  - **Staff (User):** email + password (hashed with a strong KDF, e.g. argon2/bcrypt) in v1;
    structured so SSO can drop in later. _[decision point: confirm staff auth method.]_
  - Session: short-lived access token + httpOnly refresh cookie on the API domain; CORS with
    credentials for the web origin.
- **Uploads:** object storage (S3-compatible) accessed **only via short-lived presigned URLs** —
  never public paths or raw storage keys returned to clients.
- **Document integrity:** store `sha256` of each upload.
- **Transport/headers:** helmet, strict CORS allowlist, validation on every input.
- **Tenancy isolation:** every company-scoped query is filtered by `companyId`; no cross-company
  reads. This is the most important invariant in the system.

---

## 7. Audit

- **Every mutating action** in the portal is logged to `AuditLog` (actor, action, target, ip, time).
- Logs are **partitioned per company** via `companyId` so Super Admin can view each company's trail
  separately and Company Admin sees only their own.
- **Append-only:** no code path updates or deletes an `AuditLog` row (enforce at the data layer).
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

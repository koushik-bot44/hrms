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
| **Super Admin** | Entire portal | Create/manage Companies; provision each Company's Company Admin; **provision the single Accounts Admin**; **archive (soft-delete) a company and restore it**; **manage teams in any company** (create / rename / reassign HR + Manager + Accountant) and **onboard employees into any company** (selecting company → team → HR); view **all** companies' audit logs (separated per company), including archived companies'. |
| **Accounts Admin** | Entire portal — **read-only** | A central, cross-company **viewer** (`companyId = null`, like Super Admin but never writes). Sees **approved** employees across **all** companies and their **full records** (the four forms + documents/PDFs) with sensitive fields **masked by default** and an **audited reveal** — the exact HR mechanism; and an **approval-only** audit trail across companies. **No onboarding / verify / approve / edit / archive / delete / provisioning — GET-only.** In-flight (non-approved) employees are **not** visible. **Exactly one** may exist; provisioned by Super Admin. |
| **Company Admin** | One company | Create teams and assign the team's HR, Manager and Accountant (one each); **assign/reset the mailbox credentials of any APPROVED employee in the company** (§6, alongside the onboarding HR); view **own company's** audit logs. |
| **HR** | Own team / own onboarded employees | Trigger onboarding (email + unique ID); look up an employee by ID and see all their forms/documents; verify documents; route the approval request to the team's Manager. |
| **Manager** | Own team | Workspace inbox/notifications (who was onboarded, who was verified, pending approvals); **approve** verified employees. Approval is the **final step** _[parked: post-approval actions]_. |
| **Accountant** | Own team — **read-only** | A **team-scoped** viewer (a staff `User` with a `teamId`, like HR/Manager, but never writes). Sees the **approved** employees of **its own team** (those onboarded by that team's HR) and their **full records** — masked by default with the same **audited reveal** as HR — plus an **approval-only** audit trail for **its team**. **No writes — GET-only.** Cannot see other teams' or other companies' employees. |
| **Employee** | Own record only | Authenticate with **unique ID + email/OTP**; fill tabbed forms and upload documents under their own record. |

**Company archival (soft-delete):** Super Admin can **delete a company as a reversible archive** — it
disappears from all active lists and operations, and **every principal under it (Company Admin, HR,
Managers, employees) immediately loses access**: OTP login is denied and any already-issued access
token stops working. It is **never a physical purge** — child rows, uploaded documents, and the
company's **audit trail are all retained**, and Super Admin can still view the archived company's
history. A **restore** returns it to active and its people can sign in again. The **Super Admin is
never affected** (they belong to no company).

**Team rule:** a team holds **exactly one HR, one Manager, and one Accountant** (`{1 HR, 1 Manager, 1
Accountant}`). All three are expected on a **new** team; **existing** teams may still be missing one and
keep working, surfacing a "needs accountant" (or needs-HR/Manager) state until filled. Approvals stay
**within the team**, and the team's Accountant reads only that team's approved employees.

**Super Admin cross-company operations.** Team management and onboarding are normally the Company
Admin's and HR's jobs; the **Super Admin can do both in any company** by selecting the target company
explicitly (Company Admin stays locked to its own). Team ops reuse the same one-HR-one-Manager rule
and are audited under the **target** company. When the Super Admin onboards, they pick **company →
team → HR** — the employee attaches to that **team's HR** (`onboardingHrId`, exactly as if that HR had
onboarded them; there is no direct team field, so **the HR is determined by the selected team**, which
has exactly one). Everything downstream is **unchanged**: the employee is `INVITED` with no ID, gets
the same selection email + `/employee/login` link, appears in **that HR's** queue, is verified by that
HR, and approved by **that team's Manager** (who mints the unique ID).

**Employee ↔ team linkage:** an Employee is tied to a **Company** and their **onboarding HR**
(no direct team field in v1). The approving Manager is therefore **the Manager on the onboarding
HR's team** — that is the path that connects an employee to their approver.

**Accounts Admin (central read-only viewer).** A top-level, cross-company **read** role for oversight of
finished onboardings. The Accounts Admin is a staff `User` with **`companyId = null`** — the same
cross-company breadth as the Super Admin, but with **no write capability at all**. They can list
**approved** employees across every company and open each one's full record (four forms + Form-4
documents + generated PDFs) with the sensitive financial/PII fields **masked by default and revealed
only via the audited reveal action** (the identical `EmployeeRecordAssembler` + `SENSITIVE_FIELD_REVEALED`
path HR uses), and read an **approval-only** slice of the audit trail across all companies. They never
appear in an onboarding, verification, approval, provisioning, or company-management flow. **Exactly
one Accounts Admin may exist** — the Super Admin provisions it (email + name + initial password), and a
second creation is rejected. It signs in with **staff email + password** (§6), like the other staff roles.

**Accountant (team-scoped read-only viewer).** The **per-team** analogue of the Accounts Admin: a staff
`User` with a `companyId` **and a `teamId`** (like HR/Manager), **read-only**. It sees exactly the
**approved** employees of **its own team** — those onboarded by that team's HR (`onboardingHrId ==
team.hrUserId`) — and opens their full records with the same masking + audited reveal, plus an
**approval-only** audit trail scoped to **its team's** employees. It never writes anything (no onboard /
verify / approve / edit) and **cannot** see other teams or other companies. It is provisioned like HR and
Manager — the **Company Admin** (own company) or **Super Admin** (any company) assigns the team's
Accountant slot (create a new staff user or attach an existing unassigned one); **at most one per team**.
Both roles are served by the same read-only workspace, scoped by the signed-in role (all companies for
Accounts Admin, own team for Accountant).

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
1. **HR** opens the employee's record (by ID) and reviews each form and document with **two per-item
   actions**:
   - **Verify** → the item is `VERIFIED`.
   - **Send back for revision** (with a **note**) → opens that item and returns **only that
     form/document** to the employee (`REVISION_REQUESTED`). The item becomes re-editable /
     re-uploadable while **everything else stays locked**; the employee's overall status reflects
     that a revision is pending. Verify and Send-back are **re-decidable** (HR may flip an item back
     and forth) and remain available only while the employee is **under HR review** (i.e. not yet
     routed to the Manager or approved). There is **no per-item Reject** — terminal rejection of the
     whole application is the Manager's action at approval (step 3).
   The employee fixes the flagged item(s) and **re-submits**: each revised item returns to
   awaiting-HR, the affected form PDFs **regenerate**, the onboarding HR is notified, and the items
   come back for **re-review**.
2. On completion — **every** form and document `VERIFIED` (a single `REVISION_REQUESTED` item blocks
   this) — HR **routes an approval request** to the **team's Manager**.
3. **Manager** sees it in their **notifications/approvals inbox** and **approves** → final step in v1.
   (Rejecting the application, if needed, is the Manager's decision here.)
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
- **Team** — `id`, `companyId`, `name`, `hrUserId` (→ User), `managerUserId` (→ User),
  `accountantUserId` (→ User, nullable). Holding the three single FKs structurally enforces the
  "exactly one HR + one Manager + one Accountant" rule (each slot holds at most one person). A team may
  transiently be missing a slot (surfaced as a "needs …" state) until filled.

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

Each form and document carries a `status` (DRAFT | SUBMITTED | VERIFIED | REVISION_REQUESTED | REJECTED)
plus a nullable `revisionNote` + `revisionRequestedAt` set when HR sends that item back (§3.3). SENSITIVE fields are encrypted at
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
- **UserRole**: `SUPER_ADMIN`, `ACCOUNTS_ADMIN` _(cross-company read-only viewer; singleton)_,
  `COMPANY_ADMIN`, `HR`, `MANAGER`, `ACCOUNTANT` _(team-scoped read-only viewer)_
- **EmployeeStatus**: `INVITED`, `IN_PROGRESS`, `SUBMITTED`, `REVISION_REQUESTED` _(HR sent one or more
  items back; the employee is fixing them)_, `HR_VERIFIED`, `APPROVED`, `REJECTED`
- **SectionStatus** _(status of each form + review item)_: `DRAFT`, `SUBMITTED`, `VERIFIED`,
  `REVISION_REQUESTED` _(HR asked for changes to this item)_, `REJECTED`
- **DocumentType** _(Form 4 slots)_: `SECONDARY`, `INTERMEDIATE`, `DIPLOMA`, `GRADUATION`,
  `POST_GRADUATION`, `OFFER_OR_APPOINTMENT_LETTER`, `HIKE_LETTER`, `RELIEVING_LETTER` _(per-employment,
  with `groupIndex` 1–4)_, `AADHAAR`, `PAN`, `VOTER_ID`, `DRIVING_LICENCE`, `PASSPORT`, `OTHER`
- **DocumentStatus**: `PENDING`, `UPLOADED`, `VERIFIED`, `REVISION_REQUESTED` _(HR asked for a
  re-upload)_, `REJECTED`
- **GeneratedDocumentKind**: `FORM1`, `FORM2`, `FORM3`, `FORM4_MANIFEST`, `MERGED`
- **ApprovalStatus**: `PENDING`, `APPROVED`, `REJECTED`
- **LeaveType** _(§8b)_: `CASUAL`, `SICK`, `UNPAID`
- **LeaveStatus** _(§8b)_: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`
- **NotificationType**: `EMPLOYEE_ONBOARDED`, `EMPLOYEE_SUBMITTED`, `APPROVAL_REQUESTED`,
  `EMPLOYEE_APPROVED`, `EMPLOYEE_REJECTED`, `LEAVE_REQUESTED` _(§8b — Manager bell on a leave submission)_

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
  - **Accounts Admin → all companies, but READ-ONLY and APPROVED-only.** A cross-company principal
    (`companyId = null`) that may only issue GET/read operations, and only over **approved**
    employees + their records + an approval-only audit view. No handler that mutates state (onboard,
    verify, approve, edit, archive, purge, provision, …) accepts an Accounts Admin.
  - Company Admin → only their `companyId` — but **company-wide** within it: may read **any** employee's
    record in the company and **assign/reset any APPROVED employee's mailbox credentials** (Stage 5),
    alongside the onboarding HR. (Verify / route-to-Manager / reveal remain HR-only.)
  - HR → only employees they onboarded (`onboardingHrId == self`) within their company — including
    assigning/resetting their mailbox credentials (Stage 5).
  - Manager → only employees in their team's scope (onboarded by their team's HR) + their own
    notifications/approvals.
  - **Accountant → its own team, READ-ONLY and APPROVED-only.** A team-scoped principal (has a
    `companyId` + `teamId`) that may only read the **approved** employees onboarded by its team's HR
    (the same team-resolution as the Manager, restricted to approved) + their records + an approval-only
    audit view for its team. No mutating handler accepts an Accountant.
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
  - **Staff (Super Admin, Accounts Admin, Company Admin, HR, Manager, Accountant) — email + password,
    at `/login`.** `POST /auth/login {email, password}` resolves a **User by email** (an employee email
    or an unknown email gets the same generic denial), verifies the password (hashed with the app's
    `PasswordEncoder` — BCrypt today; argon2 is a drop-in swap), and issues the session. **No OTP for any
    staff role.** The Accounts Admin and the team Accountant use this same path; the reveal action on
    their read-only record views is audited (`SENSITIVE_FIELD_REVEALED`) exactly as for HR.
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
- **Accounts Admin / Accountant audit visibility.** Both read an **approval-only** slice — the
  `APPROVAL_ROUTED` / `APPROVAL_APPROVED` / `APPROVAL_REJECTED` events, never the full trail. The
  **Accounts Admin** sees these across **all companies**; the team **Accountant** sees only those for
  **its own team's** employees. Their own sensitive reads (opening a record, revealing a masked field)
  are logged like any other principal's (`EMPLOYEE_RECORD_VIEWED`, `SENSITIVE_FIELD_REVEALED`).
- **Retained across company archival:** deleting (archiving) a company never removes its audit rows.
  Super Admin can still **select an archived company** in the explorer (shown flagged as deleted) and
  read its retained trail; the archival itself is recorded (`COMPANY_DELETED` / `COMPANY_RESTORED`).
- **The one exception — permanent purge.** A Super Admin may **permanently delete** a company
  (`DELETE /companies/{id}/purge`): an irreversible hard-delete of the company and **all** its data —
  staff, teams, employees, every onboarding record/document/signature/generated PDF, stored blob
  bytes, approvals, notifications, and the company's own audit rows (the append-only guard is toggled
  off only for that company's rows, inside the purge transaction). This is distinct from archival
  (which is reversible and retains everything). A **portal-level `COMPANY_PURGED` trace** (no
  `companyId`, so it is not swept up) survives, recording who purged what.
- Implemented as a global API interceptor for mutations, plus explicit log writes for sensitive
  reads (e.g. viewing/downloading an employee's documents).

---

## 8. Internal Mail

A lightweight **internal-only** messaging system between IHRMS accounts — no external email, no SMTP or
DNS. A "message" is just rows in our own DB, scoped exactly like everything else.

- **Addresses are logical identifiers `localpart@domain`** — display/reference handles, not routable
  email. Routing/authorization is by the account's **role + companyId**, never by parsing the address.
  - The **platform domain `ihrms`** carries the two top-level roles: `SUPER_ADMIN` and `ACCOUNTS_ADMIN`
    (e.g. `superadmin@ihrms`, `accounts@ihrms`).
  - Each **Company has a mail domain** (e.g. `anvicorp`), set by the Super Admin at company creation
    (prefilled from the company's code, editable, **unique across companies**). Its staff
    (`COMPANY_ADMIN` / `HR` / `MANAGER` / `ACCOUNTANT`) get `localpart@companyDomain`.
- **One mailbox per staff `User`, and the mailbox address IS the login email — a single identity, no
  second login.** When a user is provisioned the assigner types the **local part**; the system forms
  `localpart@domain`, and sets it as **both** the user's `mailLocalPart` and login `email`. Because the
  address is the login email, uniqueness "within a domain" is enforced by the **existing global
  email-unique index** (a duplicate `localpart@domain` is a duplicate email).
- **Send graph — who may message whom (relationship-based; ONE central check, `canSendMail`; symmetric
  both directions; only CREDENTIALED employees participate; every pair is SAME-COMPANY unless a platform
  row says otherwise, and cross-company is ALWAYS forbidden):**
  - A **team's mail members** = the team's **HR** + the team's **Manager** + the **employees onboarded by
    that HR**. (The team's **Accountant is deliberately NOT** part of team mail.)
  - `EMPLOYEE` ↔ the members of **their** team (their onboarding-HR's team): their HR, their Manager, the
    **other employees on that team**; **AND** their `COMPANY_ADMIN`.
  - `HR` ↔ the members of their team (their employees, their Manager); **AND** their `COMPANY_ADMIN`.
  - `MANAGER` ↔ the members of their team(s) (the HR, the employees); **AND** their `COMPANY_ADMIN`.
  - `COMPANY_ADMIN` ↔ **anyone in their company** — all HRs, Managers, Accountants, **all employees**;
    **AND** `SUPER_ADMIN`.
  - `ACCOUNTANT` ↔ their `COMPANY_ADMIN` **only** (not team mail).
  - `ACCOUNTS_ADMIN` ↔ `SUPER_ADMIN` only.
  - `SUPER_ADMIN` ↔ all `COMPANY_ADMIN`s + `ACCOUNTS_ADMIN`.
  - **Everything not listed — and EVERY cross-company pair — is forbidden.** Resolution reuses the
    existing team/company queries: an employee's team is their **onboarding HR's** team; "employees of a
    team" are those whose `onboardingHr` is that team's HR (the same set approvals/attendance scope to);
    "anyone in a company" is all users + all credentialed employees with that `companyId`.
- **Model:** a `Message` (sender, subject, text body, createdAt) fans out to one or more
  `MessageRecipient` rows — each carries a **`recipientType` (`TO` | `CC` | `BCC`)**, its own nullable
  `readAt`, and its own soft-delete. The sender sees **Sent**, each recipient sees **Inbox**; opening a
  message stamps that recipient's `readAt`. Sending is a mutation → audited (`MAIL_SENT`); opening is an
  audited read. The send check lives in the authorization component (`canSendMail`) so it can never be
  bypassed or duplicated. The **same send path** is reused programmatically for system courtesy mail —
  e.g. leave submit/decision auto-mails (§8b) go through `canSendMail` exactly like a user-composed message,
  land as ordinary repliable threads, and are audited `MAIL_SENT`.
- **Multiple recipients — TO / CC / BCC (§8):** a message needs **at least one TO**; CC and BCC are
  optional. **Every** recipient across TO+CC+BCC is individually permission-checked through the ONE
  `canSendMail` graph — if **any** single recipient is disallowed the **whole send is rejected** as a
  permission failure (never silently dropped; the compose contacts list already offers only allowed
  people, so this is the server-side backstop). Cross-company is impossible for any recipient type.
- **BCC privacy (load-bearing).** A BCC recipient is delivered the message and sees it in their inbox, but
  is **NEVER** visible to any other recipient. The visible recipient set is computed **server-side, per
  viewer**: everyone sees all **TO + CC**; the **sender** additionally sees all **BCC**; a **BCC**
  recipient additionally sees **only themselves** (never other BCCs). TO/CC recipients see no BCC at all.
  A response to a viewer who isn't entitled contains **no BCC identities whatsoever**.
- **Reply vs Reply All.** **Reply** goes to the **original message's sender only**. **Reply All** goes to
  the original **sender + all TO + all CC**, **excluding the actor** and **excluding all BCC** (BCC is
  never propagated), de-duplicated, as TO. Both are sends: **every** resulting recipient is re-validated
  through `canSendMail` (403 if a relationship is no longer allowed). A BCC recipient replying never
  reveals their BCC status.
- **Webmail UI:** every portal's topbar (staff **and** the employee `/workspace`) carries a **Mail**
  control with a live unread badge (`GET /mail/unread-count`, refetched on focus + after send/open). It
  opens the mailbox **in-session at `/mail`** — a **Gmail-style three-region client** in the IHRMS theme:
  a left rail (Compose + Inbox/Sent with the unread count), a dense thread list (participants, subject +
  snippet, relative time, unread emphasis, attachment + count hints), and a reading pane; it collapses to
  list→detail on narrow widths. **Compose is a docked bottom-right window** (minimise / expand / close),
  and recipient entry is **type-to-search**: To / Cc / Bcc are chip inputs that filter the graph-derived
  `/mail/contacts` list. The dropdown is **hidden while a field is empty** (no pre-populated list) and opens
  only once ≥1 character is typed, so only allowed people are selectable — a free-typed non-match shows "No
  matches" and can never become a recipient. **Reply / Reply All** open the same docked composer prefilled
  from the thread. The page also
  offers **Open in new tab**. This is a pure UI layer — the send graph, BCC privacy, and all endpoints are
  unchanged.
- **Provisioning assigns the local part:** every staff-create flow (Company Admin, Accounts Admin,
  HR/Manager/Accountant) takes a **mailbox local part** (with a live `localpart@domain` preview) instead
  of a raw email; the Super Admin sets each company's mail **domain** at creation (prefilled from the
  code). The address so formed IS the login email — the earlier transitional `email` fallback has been
  **retired**, so an address is created exactly one way.
- **Threads, reply, search, read-state + delete (Stage 3):**
  - **Threads (conversations).** Every message carries a `threadId` (an opaque grouping key). A new
    compose starts a thread; a **reply** joins it. Inbox / Sent / Search list **threads**, not messages —
    each row shows the other participant(s), the subject, a snippet, the latest message time, the message
    count, and is **unread if ANY message in the thread is unread for the viewer**. Opening a thread shows
    its messages in chronological order and marks the viewer's unread messages read.
  - **Reply / Reply All.** Recipients are **derived** from the thread (never free-typed): **Reply** →
    the original sender only; **Reply All** → the original sender + all TO + all CC, minus the actor, minus
    all BCC. A reply **is a send**, so every derived recipient is re-validated through `canSendMail` every
    time (403 if the graph would now forbid it, e.g. a participant changed company/role after the thread
    started). Audited `MAIL_SENT`.
  - **Search** (`GET /mail/search?q=`) runs over the viewer's **own mail only** (threads with a message
    they sent or received), matching **subject + body** case-insensitively — never another mailbox, never
    cross-company.
  - **Read state** is explicit per thread (`POST /mail/threads/{id}/read` | `/unread`); the unread badge
    counts **unread threads**.
  - **Delete is a per-user soft-hide:** deleting a thread stamps the viewer's own copies
    (`message_recipients.deletedAt` for received messages, `messages.senderDeletedAt` for sent ones) —
    the rows are **never destroyed** and the counterparty still sees their copy. Deleted threads vanish
    from the viewer's lists; a later reply (an un-hidden message) resurfaces the thread. Audited
    `MAIL_DELETED`.
- **Attachments (Stage 4):** a message (new send or reply) may carry files, stored in **S3 via the same
  presigned upload→confirm handshake as employee documents** (`storage.buildKey` → presigned PUT →
  server reads the bytes to compute + store the **sha256**). Attachment rows (`message_attachments`) are
  bound to the message they were sent with; **storage keys are never exposed** — a raw key never leaves
  the server.
  - **Access is governed by the THREAD, not by company scope.** `GET /mail/attachments/{id}/download`
    resolves the attachment → its message → its thread and issues a short-lived presigned **GET only if the
    requester is a participant** in that thread (sender or a recipient). Anyone else — **including a Super
    Admin who is not a participant** — gets 403. This is a distinct authorization basis from the employee
    document/company scope; mail attachments have their OWN participant-scoped endpoint (never reuse the
    employee-document download). Every download is audited `MAIL_ATTACHMENT_DOWNLOADED`.
  - **Limits (enforced server-side, the authority; mirrored client-side):** at most **5 files per
    message**, **10 MB per file**; allowed types are images (png/jpeg/gif/webp), pdf, plain text, csv,
    the office docs (docx/xlsx/pptx) and zip. Executables/scripts are **rejected** — the server validates
    **both the declared content type AND the file extension**, so a spoofed content type or a bypassed
    client cannot smuggle an `.exe`/`.sh`/`.js`/`.jar`. A message still requires a subject and/or body —
    no attachment-only messages. Thread/list responses expose attachment metadata (id, name, type, size)
    and a paperclip indicator, never keys. Text-only threads are unaffected; per-user delete hides the
    viewer's copy but the attachment follows the message (the counterparty keeps theirs).
- **Employee credentials + mailbox (Stage 5):** once a Manager **approves** an employee (§3.4 — the ID is
  minted and the onboarding HR is notified `EMPLOYEE_APPROVED`), the employee's **onboarding HR** — or a
  **`COMPANY_ADMIN` of the same company** (§6) — may **assign the employee internal credentials**: a mailbox
  local part (→ `localpart@companyDomain` via `MailAddresses`, unique across every account through
  `AccountEmails`) and a password (**typed or system-generated** — generate is the default). The address IS a
  login email; the password is BCrypt-hashed. The address + password + the `/login` link are **emailed to the
  employee's PERSONAL email** (the one HR entered at invitation; dev-logged, no SMTP). Audited
  `EMPLOYEE_CREDENTIALS_ASSIGNED`; may be **re-issued** (regenerate + re-email).
  - **Who may assign/reset:** the acting user must be **either** the employee's onboarding HR (own-onboarded
    scope) **or** a `COMPANY_ADMIN` of the employee's company (company-wide scope) — and only for an
    **APPROVED** employee (else `409`). Every other role is denied `403` (Accountant, Accounts Admin, Manager,
    an HR who didn't onboard them, a Company Admin of another company, Super Admin). The Company Admin reaches
    this from a company-wide **Employees** view (search → record → Assign/Reset), which reuses HR's read-only
    record view; the employee **list** and **record read** open to `COMPANY_ADMIN` too (company-scoped), while
    verify / route / reveal stay HR-only.
  - **Two sign-in doors stay open for a credentialed employee** (both yield the same EMPLOYEE session —
    own-record scope): `/login` (mailbox address + password — the staff resolver now also resolves an
    Employee by `mailAddress`) **and** `/employee/login` (full name + personal email + OTP, unchanged before
    and after approval). Employees WITHOUT assigned credentials can only use `/employee/login`.
  - **The door determines the landing area** (Stage 6) — same principal, same scope, different home:
    signing in at `/login` (credentials) lands in the **employee PORTAL** (`/workspace`); signing in at
    `/employee/login` (name + email + OTP) lands in the **onboarding area** (`/employee`), as before. To make
    this stable across a page refresh, the auth response + the refresh token carry an explicit
    **`authMethod`** (`PASSWORD` | `OTP`) — the landing is never inferred from `mailAddress` alone (a
    credentialed employee can use either door). The portal is **not** a new privilege level; it is a
    different landing for the same EMPLOYEE. v1 contains the **mailbox** (the shared `/mail` client, contacts
    = their **team** — HR, Manager, teammates — plus their Company Admin) plus a header with the employee's
    name / ID / mail address, built as an extensible shell so more sections can be added later. The
    onboarding area is unchanged and still reachable.
  - **Mailbox:** a credentialed employee enters the mail system with their **team-and-company** contacts (the
    send graph above, symmetric, same company). Every send/reply is still `canSendMail`,
    and thread/attachment access stays participant-scoped. `messages`/`message_recipients`/
    `message_attachments` gain a nullable **employee-id** column beside the user-id one (a sender/recipient/
    uploader is a User OR an Employee); the `/mail` area opens to authenticated employees, but one WITHOUT a
    mailbox is refused. The Mail button + the employee's own address appear once credentials exist.

---

## 8a. Attendance (clock in / clock out)

A credentialed employee records working time from the portal (`/workspace`) against a **fixed overnight
shift**, with **breaks** excluded from worked hours; a Manager sees his team's attendance including late
arrivals and break time. Past punches are **view-only** (no editing/correction).

**Shift constants (centralized in `ShiftConfig`, easy to change later):** shift **19:00 → 04:00** (next day),
**LATE** after **19:20**, timezone **Asia/Kolkata**.

- **Who:** only a **credentialed** employee (the `/workspace` principal — `mailAddress` assigned) has
  attendance. An OTP-only onboarding employee has none (403 on every attendance endpoint). A Manager reads
  his team-scope employees' attendance (below); no one else gets the manager endpoints.
- **Actions + one open session:** the buttons are **Clock In** / **Clock Out**. Exactly **one open session
  at a time** per employee — clocking in with an open session is a **409**; clocking out with none open is a
  **409**. Multiple **completed** sessions per day are allowed (in → out → in → out). The single-open rule is
  enforced BOTH in the service AND by a **partial unique index** `(employee_id) WHERE clock_out_at IS NULL`,
  so a double-click cannot create two open sessions (the constraint violation is surfaced as a clean 409).
- **Time source = the SERVER, always.** A client timestamp is never trusted. Instants are stored in **UTC**
  (`timestamptz`); all display, day grouping, and totals are computed in **Asia/Kolkata**.
- **Forgot to clock out:** an open session stays **open**, is shown as *In progress*, is **never
  auto-closed**, and contributes **0** to totals until it is closed. Daily/period totals sum **completed**
  sessions only.
- **Shift-day attribution (overnight):** a session belongs to the **date its shift started**, not the calendar
  date. Rule: take the clock-in's **IST date**; **if the IST time is before 04:00, subtract one day**. So a
  19:00 Mon → 04:00 Tue session is **Monday**; an early 18:30 Mon clock-in is **Monday**; a 02:00 Tue clock-in
  is **Monday** (the tail of Monday's shift); a 05:00 Tue clock-in is **Tuesday**. History + totals group by
  this **shift-day** (`shift_date`, persisted on the session), not the calendar day; a session's whole span
  counts to its shift-day (not split at midnight).
- **Late:** the **first** clock-in of a shift-day **after 19:20 IST** is **LATE** (at/before 19:20 is on-time).
  `is_late` + `late_minutes` are computed at clock-in and **persisted on that first session**, so the Manager
  sees lateness without recomputing. Later sessions of the same shift-day are not themselves late.
- **Working hours = worked time, breaks excluded:** per-shift-day total + a this-week / period total, each the
  sum of **completed** sessions' duration **minus their break time**. No overtime or leave interaction.
- **Breaks:** within an OPEN session an employee can **Start Break / End Break**. A session is `clock in →
  (start break → end break)* → clock out`. **One open break at a time** — starting a second, or ending with
  none open, is a **409** (enforced in the service AND by a partial unique index `(session_id) WHERE
  break_end_at IS NULL`). **Clocking out requires ending an open break first** (else 409). Break time is
  **excluded** from worked hours.
- **Workspace entry prompts (server-state-driven).** Employee machines sleep, so the two prompts are evaluated
  from **server state** (`/attendance/me/status`) on `/workspace` **entry and on window focus/visibility** —
  never a live page timer. If the employee is **not clocked in**, a **clock-in dialog** appears ("Clock in to
  start your shift") with **Clock In** and **Skip** (Skip dismisses for the session). If a **break is still
  open**, a **return-from-break reminder** appears ("You're on break — end your break to resume working") with
  **End Break**. Both survive a slept machine because they read server state, not a timer.
- **Manager view (team-scope):** a Manager sees attendance ONLY for employees in his team scope — those whose
  onboarding HR is the HR on the Manager's team (the SAME set he approves; the existing scope resolution is
  reused). Cross-team / cross-company is denied. A roster (who's clocked in, **late-today**, today + period
  **worked** hours) with a **late** filter, plus a read-only per-employee **shift-day** history showing each
  session's breaks + worked time + late tag.
- **Attendance activity feed (pull-based, NO bell):** punches do **not** create notification-bell entries.
  Instead the Manager has a dedicated **activity feed** — a flat, reverse-chronological list of his team's
  clock-in/out **and break start/end** events (name + ID, event, time), derived from the **audit log** and
  refetched when he opens or focuses the page. It is checked, never interruptive.
- **Clock-out reminders (best-effort):** on app **sign-out**, if a session is open the UI confirms
  *"You're still clocked in — clock out first?"* (Clock out & sign out / Sign out anyway / Cancel) — reliable.
  On **tab/browser close**, a `beforeunload` handler (registered only while clocked in) triggers the browser's
  generic leave prompt — a nudge only; its text isn't customizable and leaving can't be prevented.
- **Audit:** every punch is audited `ATTENDANCE_CLOCK_IN` / `ATTENDANCE_CLOCK_OUT`, and every break
  `ATTENDANCE_BREAK_START` / `ATTENDANCE_BREAK_END`, with `companyId` set.
  `attendance_sessions` carries a denormalized `company_id` so every query filters by tenant.

---

## 8b. Leave requests

A credentialed employee requests time off from the portal (`/workspace`); the request routes to the
**Manager who approved them** — the Manager on the employee's onboarding-HR's team — who approves or rejects
it. **v1 is request → route → decide: there are NO leave balances / quota / accrual** (history only;
balances are a future feature).

- **Who can request:** only a **credentialed** employee (the `/workspace` principal — `mailAddress`
  assigned). An OTP-only onboarding employee has no leave → `403` on every leave endpoint.
- **Routing (reuses the approval resolution):** the approver is `employee.onboardingHr → that HR's team →
  team.managerUser` — the SAME Manager who approved the employee. There is **no** company-level manager; if
  the team has no Manager yet, the request is refused (`409`). The resolved `managerUserId` is stored on the
  request, and the Manager lists/decides by it (mirroring `ApprovalRequest`).
- **Request:** `startDate`, `endDate` (dates, not times), `leaveType` (`CASUAL` | `SICK` | `UNPAID`),
  `reason`. `endDate >= startDate` (else `400`). Overlapping requests are allowed in v1 (no conflict
  detection).
- **Lifecycle:** `PENDING → APPROVED | REJECTED`. A Manager decision may carry a note (**required on
  reject**). The employee may **cancel their own PENDING** request (→ `CANCELLED`); once decided it can't be
  cancelled.
- **Notifications:** leave is low-frequency and needs a decision, so — unlike attendance — it **does** use
  the notification model. On submit the **Manager** gets a real bell entry (`LEAVE_REQUESTED`). On decision
  the **employee** (who has no notification inbox) sees the outcome + note in their own **leave history** and
  is emailed (dev-logged, like the welcome email) — the existing employee-facing channel.
- **Courtesy internal mail (§8):** _in addition_ to the above, a real repliable **internal mail** is dropped
  into both mailboxes through the ordinary `canSendMail`-guarded send path (§8) — on **submit**, employee →
  resolved Manager (summarizing type, dates, reason); on **decision**, deciding Manager → employee (outcome +
  any note). It's therefore audited `MAIL_SENT` as well. Sent by the **controller AFTER the leave tx commits**
  (so the send opens its own transaction) and **best-effort**: if the mail isn't permitted / the recipient
  isn't credentialed / delivery fails, it is logged and skipped — the leave submit/decision still succeeds.
  Exactly one mail per submission and one per decision.
- **Scope:** an employee sees ONLY their own requests; a Manager sees ONLY his team-scope requests (those
  routed to him) and can decide only the requests he is the resolved approver for — cross-team / cross-company
  is denied. Every action is audited `LEAVE_REQUESTED` / `LEAVE_APPROVED` / `LEAVE_REJECTED` /
  `LEAVE_CANCELLED` with `companyId` set; `leave_requests` carries a denormalized `company_id` so every query
  filters by tenant.

---

## 8c. OS notifications (Web Push)

OS-level notifications are delivered with **Web Push (VAPID)** — the browser standard for background
notifications that appear even when the IHRMS tab is closed or unfocused. Built in stages: **N1** is the
delivery backend + subscription storage; **N2 (this stage)** is the Service Worker + opt-in permission
flow; **N3** triggers pushes from mail/notification events. As of N2 a push (e.g. `POST /push/test`)
surfaces a **real OS notification** — including when the IHRMS tab is minimized or fully closed — and
clicking it focuses/opens the app.

- **How Web Push works.** In the browser, a **Service Worker** (`/sw.js`, served at the web origin root)
  creates a **`PushSubscription`** — an `endpoint` URL at the browser vendor's push service (Google /
  Mozilla / Apple) plus two keys (`p256dh`, `auth`). The server stores that subscription and, to notify,
  POSTs an **encrypted** payload to the endpoint, **signed with the VAPID private key**. The push service
  delivers it to the browser, which fires the Service Worker's `push` event → an OS notification. IHRMS
  never talks to the browser directly — only outbound HTTPS to the push services (works behind Railway; no
  inbound socket).
- **Service Worker (N2, `public/sw.js` → served at `/sw.js`).** A **push-only** worker: it handles `push`
  (render the JSON `{title, body, url}` as `showNotification`, with sane defaults) and `notificationclick`
  (focus an open IHRMS window, else open one at the payload url). It **does not intercept fetch or cache**
  — IHRMS is deliberately not turned into an offline/PWA app. `install`→`skipWaiting` + `activate`→
  `clients.claim` so an updated worker applies predictably. **Requires HTTPS** (or `localhost`); the root
  scope means one registration covers the whole app.
- **Opt-in flow (N2).** A **Notifications** control (in the portal user menu, shown to any mailbox
  principal — staff + credentialed employees) drives it, requesting permission **only on an explicit
  click** — never auto-prompting on load. Enable → `Notification.requestPermission()`; on grant, fetch the
  VAPID public key, `pushManager.subscribe({ userVisibleOnly: true })`, and POST the subscription. Off →
  `pushManager.unsubscribe()` + `DELETE /push/unsubscribe`. States are explicit: **unsupported** (feature-
  detected, hidden/disabled), **default** (offer Enable), **granted + subscribed** (on, with a way to turn
  off + a self-test), and **denied** — where the browser blocks re-prompting, so we show guidance to re-
  enable in browser settings rather than nagging.
- **Subscriptions (`push_subscriptions`, additive V20).** One row per browser/device — the `endpoint` is
  **unique**, so subscribe is an upsert. Owned by **exactly one principal**: a staff `userId` **OR** a
  credentialed `employeeId` (the same nullable-either polymorphism as `message_recipients`), with a
  denormalized `companyId` (null for platform roles). Only **mailbox-capable** principals may subscribe —
  staff always; an employee only once credentialed (§8, Stage 5) — else `403`. Indexed by principal for
  fan-out.
- **Endpoints (`/push/**`, any authenticated principal; owns only its own rows).**
  - `GET /push/public-key` — the VAPID public key the browser needs to subscribe (+ an `enabled` flag).
  - `POST /push/subscribe {endpoint, keys:{p256dh, auth}, userAgent?}` — upsert for the caller; audited
    `PUSH_SUBSCRIBED`.
  - `DELETE /push/unsubscribe {endpoint}` — remove the caller's subscription (idempotent); audited
    `PUSH_UNSUBSCRIBED`.
  - `POST /push/test` — **N1/N2 verification**: sends a test notification to the CALLER's own
    subscriptions (the "Send test" button). N3 replaces this trigger with real mail events; it's kept for
    debugging and never notifies anyone but the caller.
- **Sending is best-effort.** `PushService.sendToPrincipal(ref, title, body, url)` loads that principal's
  subscriptions and POSTs an encrypted, VAPID-signed payload to each via the `nl.martijndwars:web-push`
  library (BouncyCastle crypto). Failures are **logged, never thrown** to callers; a `404/410` from the
  push service means the subscription is gone, so that row is **pruned**. `sendToPrincipal` is the seam N3
  will call from mail events.
- **VAPID keys are env secrets.** `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` (a `mailto:`)
  come from the environment (set in Railway; generate with `npx web-push generate-vapid-keys`) — **never
  generated in code, hardcoded, or committed**. Absent keys **disable** push in dev (logged) and **refuse
  startup in the `prod` profile** (fail-fast). The Service Worker `sw.js` **must** be served at the site
  root scope on the web origin (Next.js serves `public/sw.js` at `/sw.js`).

---

## 9. Deployment Architecture (existing shell — keep as-is)

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

## 10. Design Language & UX

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

## 11. Conventions (for Claude Code)

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

## 12. Deferred / Open _[parked]_

- **Offer & experience letter issuance** — whether HR uploads or the system generates them, and at
  which stage. (Records exist conceptually under the employee; flow TBD.)
- **Post–Manager-approval steps** — what approval unlocks (e.g. employee → active, letter issuance).
- **Multi-HR / multi-Manager teams** — v1 is exactly one each.
- **Deeper BGV workflow** — external provider integration vs. manual document upload.
- **Decision points to confirm:** employee ID format (§5), staff auth method (§6), and whether HR
  scope should broaden from "own onboarded employees" to "all company employees".

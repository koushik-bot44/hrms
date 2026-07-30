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
| **Hierarchy** | Entire platform — **read-only, aggregates-only** | A top-level, cross-platform **overview** role (`companyId = null`, like Super Admin/Accounts Admin but never writes). Sees only **platform-wide aggregates / counts / summaries** — **never** individual employee records or PII, **never** attendance or leave, **no** writes anywhere. It never appears in any onboarding / verification / approval / provisioning / company-management flow. **Exactly one** may exist; provisioned by Super Admin; signs in with staff **email + password**. _(Aggregate read endpoints are added later; this stage establishes the role, login, provisioning and a placeholder overview.)_ |
| **Company Admin** | One company | Create teams and assign the team's HR, Manager and Accountant (one each); **assign/reset the mailbox credentials of any APPROVED employee in the company** (§6, alongside the onboarding HR); view **own company's** audit logs. |
| **HR** | Own team / own onboarded employees | Onboard by **filling Form 2** (which creates the record + sends the invite); **edit Form 2 while the employee is `INVITED`** (locked once they start, 409; a personal-email change re-invites); look up an employee by ID and see all their forms/documents; verify **Forms 1/3/4 + documents** (Form 2 is not verified); route the approval request to the team's Manager. |
| **Manager** | Own team | Workspace inbox/notifications (who was onboarded, who was verified, pending approvals); **approve** verified employees (approval is the **final step** _[parked: post-approval actions]_); **read-only attendance analytics for their own team** (§8a — the same live per-employee / per-team metrics the Accountant sees, own team only). |
| **Accountant** | Own team — **read-only** | A **team-scoped** viewer (a staff `User` with a `teamId`, like HR/Manager, but never writes). Sees the **approved** employees of **its own team** (those onboarded by that team's HR) and their **full records** — masked by default with the same **audited reveal** as HR — plus an **approval-only** audit trail for **its team**. **No writes — GET-only.** Cannot see other teams' or other companies' employees. |
| **Employee** | Own record only | Authenticate with **full name + personal email/OTP**; fill **Forms 1, 3, 4** and upload documents under their own record (Form 2 is HR/SA-authored — the employee never sees it). |

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

**Team-wise browsing for the read-only viewers (§2).** Both viewer roles browse employees **grouped by
team** (a team = an HR's team; its employees are those whose `onboardingHr` is that team's HR — the same
resolution as manager scope / the mail graph; approved-only). The **Accounts Admin** (cross-company) drills
**COMPANY → TEAM → EMPLOYEE**: pick a company, see its teams (with HR, Manager and approved-employee count),
open a team for its approved roster, then a read-only record. The **Accountant** (single team, not widened)
lands **directly on their own team's roster** — no company/team pickers — and can reach only their own team
(a request for any other team/company is refused). Served read-only under `/accountant/**`:
`GET /accountant/companies`, `GET /accountant/companies/{companyId}/teams`,
`GET /accountant/teams/{teamId}/employees` (own-team-only for the Accountant), and `GET /accountant/my-team`.
The same scoping backs the **live attendance analytics** (§8a) — per-employee and per-team metrics under
`/accountant/**`, read-only and computed on each call; those analytics endpoints are additionally open to
the **Manager for their own team** (own-team-only, resolved via the same `managerUser` mapping; no other
`/accountant/**` path is widened), and each summary read accepts an optional **custom from/to range**
alongside the month selector.

**Super Admin cross-company operations.** Team management and onboarding are normally the Company
Admin's and HR's jobs; the **Super Admin can do both in any company** by selecting the target company
explicitly (Company Admin stays locked to its own). Team ops reuse the same one-HR-one-Manager rule
and are audited under the **target** company. When the Super Admin onboards, they pick **company →
team → HR** and **fill Form 2** — the employee attaches to that **team's HR** (`onboardingHrId`, exactly
as if that HR had onboarded them; there is no direct team field, so **the HR is determined by the
selected team**, which has exactly one). Everything downstream is **unchanged**: the employee is
`INVITED` with no ID, gets the same selection email + `/employee/login` link (to their personal email),
appears in **that HR's** queue, is verified by that HR (Forms 1/3/4), and approved by **that team's
Manager** (who mints the unique ID). The Super Admin can also **browse any company's employees**
(`GET /companies/{id}/employees`, all teams) and **open an employee's record** — the read-only
forms-viewer (Forms 1/3/4 + the HR-authored Form 2 + the generated PDFs, incl. the standalone HR-only
Form-2 PDF) — and **edit Form 2 while `INVITED`**, identically to HR. Record read is therefore open to
the onboarding **HR**, the employee's **Company Admin**, and the **Super Admin** (each scoped by role);
the Super Admin never verifies or routes.

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

**Hierarchy (platform-wide aggregates viewer).** A third top-level, cross-company **read** principal
(with **Super Admin** and **Accounts Admin**) — a staff `User` with **`companyId = null`** — but the
**narrowest** read scope: it sees **only platform-wide aggregates / counts / summaries**, and **never**
individual employee records, PII, attendance or leave, and **never writes** anything. Where the Accounts
Admin can open a full masked record, the Hierarchy role **cannot reach any individual record at all** —
its authorization boundary denies employee-record access outright (aggregates only). **Exactly one
Hierarchy may exist** — the Super Admin provisions it (email + name + initial password; its login
identity is formed as `localPart@ihrms`, the platform domain, exactly like the Accounts Admin), a second
creation is rejected, and it signs in with **staff email + password** (§6). It is **not** part of the
internal-mail send graph.

_Hierarchy overview metrics (cross-company, aggregates-only; read-only under `/hierarchy/**`)._ Every
figure is a **count / summary** computed server-side across **all** companies — no individual employee
identity or record field ever appears. Sources are stated so they stay stable:
- **Platform totals** — companies (total, active, archived [`status = DELETED`]), teams (all companies),
  employees (all companies), and **staff-by-role counts** (Company Admins, HRs, Managers, Accountants,
  Accounts Admins currently assigned — `User` counts by role, not a people list).
- **Onboarding funnel** (current snapshot) — the count of employees at **each** `EmployeeStatus` right
  now: INVITED, IN_PROGRESS, SUBMITTED, REVISION_REQUESTED, HR_VERIFIED, APPROVED, REJECTED. The
  **status distribution** (donut) is the same counts as proportions — counts are exposed; the UI computes %.
- **Trends** (monthly series, last _N_ months, default 12, **Asia/Kolkata**):
  - **joinedPerMonth** = employees onboarded per month, bucketed by **`Employee.createdAt`** (the record
    is created at onboard = INVITED; there is no separate `invitedAt`).
  - **approvedPerMonth** = employees approved per month, bucketed by **`ApprovalRequest.decidedAt`** where
    `status = APPROVED` (there is no `employees.approvedAt` column — the approval record's decision time
    is the source of truth).
  - **offboardedPerMonth** = **labelled placeholder, always 0** (offboarding isn't built; the field is
    present so a later stage can fill it).
- **Employees per company** — per company: name, active/archived, employee count (for a company-size
  bar/pie + the drill list). Aggregate only.
- **Per-company org breakdown** — for one company: #teams, #employees (+ by-status counts), the assigned
  **Company Admin** (staff name/email), and per team: label + assigned **HR / Manager / Accountant**
  (staff names/emails) + that team's employee count (employees whose `onboardingHrId` is the team's HR).
  This is **org structure** — it names **staff** (admins/HR/manager/accountant), which is org data, **not**
  employee PII; it never includes an employee's name or record.
- **Ops metrics** —
  - **onboardingCompletionRate** = APPROVED ÷ total onboarded (all-time; total onboarded = every employee
    record, since a record only exists once onboarded). 0 when none onboarded.
  - **averageTimeToApprovalDays** = mean(`ApprovalRequest.decidedAt` − `Employee.createdAt`) over approved
    employees, in **days**; **null** when none approved.
  - **stuckOnboardings** = count of employees in a **pre-approval** state (INVITED / IN_PROGRESS /
    SUBMITTED / HR_VERIFIED / REVISION_REQUESTED) whose **`createdAt`** is older than a configurable
    threshold (**`STUCK_THRESHOLD_DAYS = 7`**), with an optional by-stage breakdown.

Company scoping does **not** restrict the Hierarchy (it is the platform role), but every query is an
efficient GROUP BY / COUNT (a constant number per endpoint — no N+1 over companies/teams/employees). These
aggregates surface in a single-glance, read-only **Platform Overview** dashboard at `/hierarchy` — headline
totals, the onboarding funnel + status-distribution donut (both from the same counts), monthly trends
(with the offboarding series shown as a labelled "coming soon" placeholder — never faked), employees-per-
company + the per-company org drill-down (naming assigned staff, never employee identities), and the ops
metric cards. It is **live-on-load** with polling + refetch-on-focus, and **read-only** throughout. Two
**client-side CSV exports** (from the already-loaded data — no refetch) are offered: the companies
roll-up and a drilled company's per-team org breakdown (staff only, never employee identities).

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
1. **HR** (or the **Super Admin**, cross-company) initiates onboarding by **filling Form 2 — Employee
   Info** for the new hire (**full name**, DOJ, **personal email**, designation).
   Submitting Form 2 **creates the employee record** (`status = INVITED`) **and sends the invite in one
   action** — there is no separate 4-field onboard step. The **personal email is the employee's login
   identity** (globally unique, §6). **No employee ID is minted here** — the unique ID is allocated only
   on Manager approval (see §3.3 / §5), so the Form-2 `employeeId` shows **greyed / blank** until then.
   The **official email** is left **blank/inert** at onboarding (slated for removal — the column stays,
   unpopulated). _Father's name, date of birth, blood group, mobile and Spark ID were **removed from
   Form 2's display + PDF** (they remain on **Form 1**); additive-only — their `form2_info` columns/keys
   stay and any previously stored values are preserved (carried over on re-save), just no longer
   captured or shown on Form 2._
2. The system **emails the employee a selection note** — *"Hello {full name}, you are selected to the
   {designation} role in {company name}."* — plus a **link to the employee login**, sent to the
   **personal email**. The email carries **no ID** (there isn't one yet).
   - **Form 2 is editable only while `INVITED`.** HR/SA may correct Form 2 up until the employee starts
     their own forms; once the employee reaches `IN_PROGRESS` (or beyond), Form 2 is **read-only** to
     HR/SA — a manual edit is rejected (**409**). The lock blocks **manual** edits only; the **system**
     still stamps the minted `employeeId` into Form 2 at approval (render-time, unaffected by the lock).
   - **Changing the personal email (while `INVITED`) re-invites.** Because the personal email *is* the
     login identity, editing it **re-sends the invite to the new address** (the old address gets nothing
     further) and re-checks global uniqueness. Editing any **other** Form-2 field does **not** re-invite.
3. **Employee** logs in with **full name + email → OTP** (the OTP to that email is the security
   factor) and lands on their **dashboard**.
4. Employee completes a **guided stepper** under their own record — **Forms 1, 3 and 4 only** (the
   employee **never sees Form 2** — not to fill, not read-only, not in their PDFs):
   - **Form 1 — Personal Details** (identity, addresses, alternate number, vehicle no, **PAN + bank
     account** (encrypted at rest, masked with audited reveal), conduct references, education, family,
     emergency contact, declaration). _Offered CTC, the standalone designation, relationship, relative
     phone and the working-experience table were REMOVED from Form 1's display + PDF — additive-only:
     their columns/JSON keys stay and previously stored values are preserved (carried over on re-save),
     just no longer shown/captured. "Closest relative" is displayed as **"Emergency contact"** (the
     `closestRelativeName` key is unchanged)._
   - **Form 2 — Employee Info** — **filled by HR/SA at onboard, not the employee** (see step 1). _Note:
     alternate number, vehicle no, PAN, account number and the two addresses moved to Form 1's
     PRESENTATION; their storage stayed on `form2_info` (encrypted columns + data keys) — Form 1
     writes them through and reads them back, so pre-move employee data surfaces under Form 1
     unchanged (no migration, no column changes). Form 1's write-through and HR's Form-2 employment
     fields share the one `form2_info` row without clobbering each other._
   - **Form 3 — Previous Employment** (one block per prior employer — **repeatable**)
   - **Form 4 — Documents** (a grouped upload checklist: educational, per-employment, identity proofs,
     other). **Aadhaar, PAN and ITR are mandatory** under identity proofs and gate submission. ITR is
     required for onboardings created after it was introduced (per-employee `itrRequired` flag, set at
     onboard); employees onboarded earlier keep `itrRequired=false` and are never gated on it, so no
     already-submitted/approved record is retroactively re-opened.
5. The employee **draws or types one e-signature** and **submits**. The system then **generates PDFs** —
   one per form plus one **merged complete application** — branded with the **joining company**, the
   signature stamped into Forms 1 & 2; these are stored under the record and **regenerated whenever a
   form is edited and re-submitted** (and `employeeId` is stamped in once approval mints it).
   **The merged/complete PDF contains only Forms 1, 3 and 4** — Form 2 is generated as a **standalone
   HR/SA-only PDF** (not merged into the complete application, and never fetchable by the employee).
6. Every field value, uploaded file, the signature, and the generated PDFs are stored **under that
   employee's record**; submission routes to HR for verification.

### 3.3 Verification & approval
**Form 2 is not verified** — HR/SA authored it at onboard, so it carries no Verify / Send-back action
and is **not** part of the routing-to-Manager gate. HR verifies **Forms 1, 3, 4 (+ documents)** only;
the record view still **displays** Form 2 (read-only) and exposes its standalone HR-only PDF.
1. **HR** opens the employee's record (by ID) and reviews each form and document with **two per-item
   actions**:
   - **Verify** → the item is `VERIFIED`. For a **document**, Verify stays **disabled until HR opens it
     via Preview** (no approving an uploaded file sight-unseen); Send-back is always available. Forms are
     shown inline, so they are not preview-gated.
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
2. On completion — **Forms 1, 3, 4 and every document `VERIFIED`** (a single `REVISION_REQUESTED` item
   blocks this; Form 2 is HR-authored and not gated) — HR **routes an approval request** to the **team's
   Manager**.
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
  `slug` (the **permanent URL identifier** — see below), `status` (`ACTIVE` | `SUSPENDED` | `DELETED`),
  `deletedAt?` + `deletedByUserId?` (→ User) — set when archived (soft-delete), cleared on restore,
  `createdAt`.
- **Team** — `id`, `companyId`, `name`, `hrUserId` (→ User), `managerUserId` (→ User),
  `accountantUserId` (→ User, nullable). Holding the three single FKs structurally enforces the
  "exactly one HR + one Manager + one Accountant" rule (each slot holds at most one person). A team may
  transiently be missing a slot (surfaced as a "needs …" state) until filled.

**Company slug & URL naming policy** (Stage 1 = the slug foundation; Stages 2/3 build `/{companySlug}/…`
routing on it). Every company has a **`slug`** — a permanent, URL-safe identifier derived from its name.
- **Format:** trim → lowercase → strip diacritics → replace each run of non-`[a-z0-9]` with a single `-`
  → trim leading/trailing `-` → truncate to ~50 chars at a hyphen boundary. An empty result (all-symbol
  name) falls back to `company`.
- **Uniqueness:** globally unique across **all** companies **including archived** (it is a URL segment),
  **case-insensitive** — lowercase storage + a `lower(slug)` unique index. On collision the lowest free
  numeric suffix is appended (`-2`, `-3`, …).
- **Reserved words:** a generated slug may never equal an app top-level route name. Reserved:
  `super-admin, company-admin, hr, manager, accountant, hierarchy, employee, login, mail, workspace,
  accounts, api, requests, push, provisioning`. A reserved hit is suffixed (`-2`) like any other collision.
- **Permanence:** minted **once** at creation (backfilled for pre-existing companies), then **IMMUTABLE**
  — a company **rename changes the display `name` only, never the slug**; no endpoint may update it.
- **Resolution:** `GET /companies/by-slug/{slug}` maps a slug → its company for routing, authorized like
  the by-id read (SUPER_ADMIN / ACCOUNTS_ADMIN any; a company-scoped session resolves only its **own**
  company); never a public resolver.

**URL NAMING POLICY (hard rule for every stage).** The **company slug is the ONLY human-readable name
that may EVER appear in a URL.** Team names, employee/person names, and any other entity names are
**FORBIDDEN** as URL segments — those entities are referenced by their **opaque id** only. Thus
`/{companySlug}/hr` and `/{companySlug}/teams/{teamId}` are allowed; a team name or person name as a path
segment is not. **Only Company has a slug** — no slug column/generator exists for teams or any other entity.

**Routing map (Stage 2 — tenant-scoped URLs).** Company-scoped areas live under the tenant slug; platform
areas stay top-level:
- **Slugged** (`/{companySlug}/…`): `company-admin`, `hr`, `manager`, `accountant` (the **team** Accountant),
  `workspace` (credentialed-employee portal), and `employee` (the OTP-session onboarding area).
- **Top-level**: `/super-admin`, `/accounts` (the platform **Accounts Admin**), `/hierarchy`, `/mail`
  (identity-scoped — used by ALL roles, never moves), `/login`, `/employee/login` (a login page cannot know a
  company), and static/API paths. The reserved-word list guarantees no slug shadows these.
- **The shared read-only viewer** (§2/§6) is **one set of screen components** mounted by two thin route trees:
  `/{companySlug}/accountant` (ACCOUNTANT, team-scoped) and top-level `/accounts` (ACCOUNTS_ADMIN,
  cross-company). Each mount guards its own role; no screen code is duplicated.
- **The guard.** A client tenancy guard at the `[companySlug]` layout resolves the URL slug against the
  **session's `companySlug`** (added to the session/me payload, nullable — null for platform roles): a MATCH
  renders; a company-scoped session on ANOTHER slug is redirected to the SAME sub-path under its OWN slug
  (deep-link-preserving); a platform role on a slugged path goes to its platform home; unauthenticated → login.
  It **composes with** (does not replace) `RequireRole`: the `[companySlug]` guard enforces **tenancy**, each
  area's `RequireRole` enforces **role**. Server-side tenancy (§6) is still the real gate — this is UX.
  _Anti-enumeration:_ because the by-slug resolver returns **404 for any cross-company slug** (a real other
  company and a non-existent slug are indistinguishable to a company-scoped client — Stage 1), a mismatch
  **redirects** rather than 404-ing, so existence is never leaked.
- **Old-path redirects** (transition safety): the vacated top-level `/{hr,manager,company-admin,accountant,
  workspace,employee}[/…]` paths session-redirect to the caller's Stage-2 home so old bookmarks don't 404;
  external deep-links (email/push/SW) migrate in Stage 3.

### Employee record (the four onboarding forms)
- **Form1Personal** — Personal Details: `name`, `dob`, `email`, `mobile`, `designation`,
  `offeredCtc` [SENSITIVE], `currentAddress`, `permanentAddress`, `maritalStatus`, `bloodGroup`,
  `closestRelativeName`, `closestRelativePhone`, `city`, `relationship`, `declaration`; + child rows:
  **EducationalQualification[]** (qualification/university/yearOfPassing/percentage),
  **WorkingExperience[]** (organization/period/designation/`salaryCtc` [SENSITIVE]/reasonForLeaving),
  **FamilyDetail[]** (name/age/relation/occupation), **CharacterReference[]** (name/address/phone — min 2).
- **Form2Info** — Employee Info, **HR/SA-authored at onboard** (the employee never fills or sees it):
  `fullName`, `employeeId` [SYSTEM/READONLY — blank until approval], `dateOfJoining`, `officialEmail`
  [blank/inert — slated for removal], `personalEmail` [the login identity], `designation`,
  `alternateNumber`, `vehicleNo2W4W`, `panNumber` [SENSITIVE], `axisAccountNumber`
  [SENSITIVE], `currentAddress`, `permanentAddress` — plus retained-but-no-longer-captured columns/keys
  `fatherName`, `dob`, `bloodGroup`, `mobile`, `sparkId`, `documentSubmitted` (**removed from Form 2's
  display + PDF**; columns/keys kept, values preserved — additive-only; DOB/blood group/mobile live on
  **Form 1**). Editable by
  HR/SA only while the employee is `INVITED` (then read-only, 409); a personal-email change while
  `INVITED` re-invites. Not part of the
  verification loop.
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
  Regenerated on every edit-and-resubmit; re-stamped with `employeeId` on approval. The **MERGED**
  complete application contains **Forms 1, 3, 4 only**; **FORM2** is a **standalone HR/SA-only** PDF —
  excluded from the merge and never returned to the employee (their generated-docs list omits it and a
  direct fetch is refused, 403).

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
  `HIERARCHY` _(cross-platform read-only, aggregates-only; singleton)_, `COMPANY_ADMIN`, `HR`,
  `MANAGER`, `ACCOUNTANT` _(team-scoped read-only viewer)_
- **EmployeeStatus**: `INVITED`, `IN_PROGRESS`, `SUBMITTED`, `REVISION_REQUESTED` _(HR sent one or more
  items back; the employee is fixing them)_, `HR_VERIFIED`, `APPROVED`, `REJECTED`
- **SectionStatus** _(status of each form + review item)_: `DRAFT`, `SUBMITTED`, `VERIFIED`,
  `REVISION_REQUESTED` _(HR asked for changes to this item)_, `REJECTED`
- **DocumentType** _(Form 4 slots)_: `SECONDARY`, `INTERMEDIATE`, `DIPLOMA`, `GRADUATION`,
  `POST_GRADUATION`, `OFFER_OR_APPOINTMENT_LETTER`, `HIKE_LETTER`, `RELIEVING_LETTER` _(per-employment,
  with `groupIndex` 1–4)_, `AADHAAR`, `PAN`, `VOTER_ID`, `DRIVING_LICENCE`, `PASSPORT`, `ITR`, `OTHER`
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
  - **Hierarchy → all companies, READ-ONLY and AGGREGATES-ONLY.** A cross-company principal
    (`companyId = null`) that may only read **platform-wide aggregates / counts / summaries** under its
    own `/hierarchy/**` namespace — **never** an individual employee record/PII, attendance or leave, and
    **no** mutating handler. The centralized employee-record gate denies it outright (it is not an
    approved-record reader like the Accounts Admin). Provisioned SUPER_ADMIN-only as a singleton;
    staff email + password.
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
  - Each **Company has a mail domain** (e.g. `anvicorp`, `yourdomain.com`) — **REQUIRED and typed by the
    Super Admin at company creation** (the create-company form prefills it from the company's code but it
    is editable). It is validated as a **lowercase domain** — one or more dot-separated labels, each
    alphanumeric with internal hyphens, no leading/trailing `-`/`.` (dots allowed) — **globally unique
    across ALL companies, case-insensitive** (a collision is a 409; addresses/the send graph would
    otherwise clash), and it is **IMMUTABLE after creation** (`updatable=false`, no edit endpoint —
    addresses are login identities). The **platform domain `ihrms` is RESERVED** and can never be
    claimed by a company. A **company rename never touches the mail domain**. Its staff
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
    that HR**. (The team's **Accountant is deliberately NOT** a general team-mail member — it is **not**
    connected to the team's HR, Manager, or other teams — but see the dedicated employee edge below.)
  - `EMPLOYEE` ↔ the members of **their** team (their onboarding-HR's team): their HR, their Manager, the
    **other employees on that team**; **their team's `ACCOUNTANT`** (§8d — the accountant of their
    onboarding-HR's team, same company); **AND** their `COMPANY_ADMIN`.
  - `HR` ↔ the members of their team (their employees, their Manager); **AND** their `COMPANY_ADMIN`.
    _(NOT the team's Accountant.)_
  - `MANAGER` ↔ the members of their team(s) (the HR, the employees); **AND** their `COMPANY_ADMIN`.
    _(NOT the team's Accountant.)_
  - `COMPANY_ADMIN` ↔ **anyone in their company** — all HRs, Managers, Accountants, **all employees**;
    **AND** `SUPER_ADMIN`.
  - `ACCOUNTANT` ↔ the **employees of their own team(s)** (§8d — symmetric with the employee edge above)
    **AND** their `COMPANY_ADMIN`. **Not** the team's HR/Manager, **not** other teams' members.
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
  land as ordinary repliable threads, and are audited `MAIL_SENT`. **Delivery also fires an OS push** (§8c
  N3) to every recipient except the sender — best-effort, post-commit, and BCC-privacy-preserving (the
  payload is only sender + subject + `/mail`, never a recipient list).
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
  - **Search + structured filters** (`GET /mail/search`) runs over the viewer's **own mail only** (threads
    with a message they sent or received, respecting their soft-delete) — never another mailbox, never
    cross-company. The text `q` matches **subject + body** case-insensitively, and it **ANDs** with any
    combination of optional filters (all thread-level, all narrowing — never widening the participant scope):
    * `from` — the thread has a viewer-visible message whose **sender** matches by **address or name**
      (`users.email`/`users.name` or `employees.mailAddress`/`employees.fullName`), case-insensitive.
    * `after` / `before` — bound the thread's **latest activity** (`MAX(createdAt)`), both inclusive.
    * `hasAttachment` — the thread has a viewer-visible message with ≥1 attachment.
    * `unread` — the viewer has an unopened message in the thread (their per-user read state).
    * `starred` — the viewer has starred the thread (`thread_stars`).
    * `scope` — restrict to `INBOX` (received, not archived) | `SENT` | `STARRED` | `ARCHIVE`, reusing each
      view's own rule; **default `ALL`** (all my mail, archived included — like today's global search).

    Every criterion is optional and **ANDed**; a thread is returned only if it satisfies **all** supplied
    ones. The whole thing is **one parameterized query** — the base participant/soft-delete-scoped
    `searchThreadIds` extended with null-guarded `EXISTS`/join/`HAVING` clauses (no forked search path, no
    N+1, no Java post-filtering). **Empty criteria** (no `q`, no filters, `scope=ALL`) returns nothing, as
    before; any single filter (or a non-`ALL` scope) runs even with blank text. Results are the same
    thread-list rows (with the viewer's `unread`/`starred`/`archived`/`hasAttachments` flags), so the UI
    reuses the list. `unread`/`starred` always reflect the **viewer's** own flags — cross-user leakage is
    impossible because the base query is already participant-scoped and filters only narrow it.
  - **Read state** is explicit per thread (`POST /mail/threads/{id}/read` | `/unread`); the unread badge
    counts **unread threads**.
  - **Starred is a per-user, thread-level flag** (like read/unread — the viewer's own relationship to the
    conversation): `POST` / `DELETE /mail/threads/{id}/star` set/clear the acting user's star (only on a
    thread they participate in — `403` if it isn't theirs, `404` if it doesn't exist; both are idempotent),
    and `GET /mail/starred` lists **their** starred threads
    (same list shape + soft-delete scoping as Inbox, newest activity first). It is stored per-user-per-thread
    in `thread_stars` (one row per `threadId` + `accountId`; row-exists = starred) — the first of the
    per-user-thread states (Archive + Labels will reuse the same shape). Starring is **independent** of
    Inbox/Sent/Search/read/deleted: a thread can be starred **and** unread **and** in Inbox at once, and
    starring **never moves or deletes** it. Every thread row exposes the viewer's `starred` flag. Only the
    acting user sees their stars — other participants' views are unaffected. Audited `MAIL_STARRED` /
    `MAIL_UNSTARRED`.
  - **Archive is a per-user, thread-level state** — the same per-user-thread shape as Starred
    (`thread_archives`, one row per `threadId` + `accountId`, row-exists = archived), but with one behavioural
    difference: **an archived thread is HIDDEN FROM THE VIEWER'S INBOX** (the Inbox query excludes any thread
    the viewer has archived — `AND NOT EXISTS thread_archives`). It is **not deleted**: it remains in the
    viewer's **Archive** view (`GET /mail/archived`, same list shape + soft-delete scoping as Inbox), and
    still appears in their **Sent** (if they sent in it), in **Search**, in **Starred** (an archived thread
    can be starred), and it exists **normally for every other participant**. `POST` / `DELETE
    /mail/threads/{id}/archive` set/clear the acting user's archive (participant-only — `403` if it isn't
    theirs, `404` if it doesn't exist; both idempotent). Un-archiving returns the thread to their Inbox.
    Archive is **independent** of read/star/delete: archiving does **not** mark read and does **not** delete;
    an archived thread can still be unread and starred. **Resurface on new activity (Gmail-style):** when a
    **new message is delivered** into a thread, each recipient's archive row for that thread is **cleared in
    the same transaction as delivery** — so a reply brings the thread **back to their Inbox** (mirroring how a
    soft-deleted thread resurfaces when a fresh, non-deleted message row arrives; the sender's own archive is
    left as-is, exactly like the soft-delete model). Every thread row/detail exposes the viewer's `archived`
    flag; only the acting user's views are affected. Audited `MAIL_ARCHIVED` / `MAIL_UNARCHIVED`.
  - **Delete is a per-user soft-hide:** deleting a thread stamps the viewer's own copies
    (`message_recipients.deletedAt` for received messages, `messages.senderDeletedAt` for sent ones) —
    the rows are **never destroyed** and the counterparty still sees their copy. Deleted threads vanish
    from the viewer's lists; a later reply (an un-hidden message) resurfaces the thread. Audited
    `MAIL_DELETED`.
  - **Drafts are author-private, UNSENT compositions** (a dedicated `mail_drafts` table — **never** a
    `Message`, so no delivery row, no thread, no inbox, no notification). A draft holds whatever the author
    has entered so far: `to`/`cc`/`bcc` recipient ids (may be empty or point at accounts **not currently
    permitted**), `subject`, `body` (both may be empty), attachment ids, and — for a reply-draft — the
    `replyToThreadId` (+ `replyAll`) it would reply into. **Save is permissive:** `POST /mail/drafts` +
    `PUT /mail/drafts/{id}` store the composition with **NO `canSendMail` check and NO required-field
    validation**; `GET /mail/drafts` (list) + `GET /mail/drafts/{id}` (open) and `DELETE` (discard) are all
    **author-only** (others get `404` — a draft's existence is never leaked). Attachments **reuse the same
    unbound `message_attachments` upload→confirm handshake** as compose (the draft just remembers their ids);
    on discard they're best-effort deleted (row + S3 object). **Sending a draft runs the REAL send path:**
    `POST /mail/drafts/{id}/send` reconstructs the compose (or reply) request, applies the **same validation
    as a normal compose** (≥1 recipient, non-empty subject/body, ≤5 attachments) **and `canSendMail` per
    TO/CC/BCC**, creates the real `Message` + delivery + threading + the **after-commit push**, binds the
    draft's attachments onto the sent message, then **deletes the draft** — all in one transaction, so if
    validation or the send graph rejects it **nothing is delivered and the draft REMAINS** (with the error
    surfaced). Audited `DRAFT_SAVED` / `DRAFT_DISCARDED` (a successful send audits as the normal
    `MAIL_SENT`). Drafts appear in the **Drafts** view only — never in Inbox/Sent/Starred/Archive/Search.
  - **Labels are author-private, per-user TAGS** — user-created named tags for organizing conversations
    (`mail_labels` = the labels; `label_threads` = the per-user, thread-level `(labelId, threadId)`
    assignments, mirroring the `thread_stars`/`thread_archives` shape but with a label FK). A label belongs
    to **one** user, a conversation can carry **many** labels, and a label lists **many** conversations
    (many-to-many). **Applying a label is a TAG, not a move:** the conversation stays exactly where it is
    (Inbox/Sent/…) — it is **not** removed from Inbox, moved, or deleted — and is now **also** viewable under
    the label. Each label is a **view** (`GET /mail/labels/{id}/threads`) = the author's threads tagged with
    it, using the **same thread-list scoping** as the other views (participant + soft-delete, the viewer's
    `starred`/`archived`/`unread` flags) but **independent of archive** — a label view shows every tagged
    thread the author still participates in, whether or not it's archived (only their soft-deleted copies are
    excluded). **CRUD** (`POST /mail/labels {name}`, `GET /mail/labels`, `PATCH /mail/labels/{id} {name}`,
    `DELETE /mail/labels/{id}`) and **apply/remove** (`POST` / `DELETE /mail/threads/{threadId}/labels/{labelId}`)
    are all **author-only**: the label must be the caller's (`404` otherwise), and a thread can only be
    labelled by someone who **participates** in it (`403`/`404`). Names are **unique per author,
    case-insensitively** (`UNIQUE(authorAccountId, lower(name))`; a duplicate is a `409`); `(labelId,
    threadId)` is unique so applying is **idempotent**. **Delete** removes the label + all its assignments
    (the threads themselves are untouched — they just lose that tag); **rename** changes the name only.
    **Per-user isolation:** another participant in a labelled thread never sees the label or the assignment —
    labels + assignments belong to their author. Every thread row/detail exposes the **viewer's own** labels
    (batched, no N+1) so the UI shows label chips; a thread can be starred **and** archived **and** labelled
    independently. Audited `LABEL_CREATED` / `LABEL_RENAMED` / `LABEL_DELETED` / `THREAD_LABELED` /
    `THREAD_UNLABELED`.
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
- **Viewer attendance analytics (§2/§8a, read-only, computed LIVE on each call).** Three viewer roles get
  live-aggregated attendance metrics under `/accountant/**` — scoped exactly like their employee browsing:
  **ACCOUNTS_ADMIN** any team/employee (via company→team drilldown), **ACCOUNTANT** their own team only,
  and the **MANAGER** their own team only — the analytics endpoints are widened to the Manager for THEIR
  team (resolved via the same `managerUser` mapping the manager roster uses; a foreign team/employee →
  `404`, and no OTHER `/accountant/**` path is widened). The widening is applied across BOTH layers
  together — the `SecurityConfig` role gate (three per-path matchers for the summary / monthly / team-summary
  endpoints, ordered **before** the general `/accountant/**` gate) AND the service scope check
  (`assertViewableTeam` / `assertViewableEmployee` → own-team via `managerUser`). GET-only. Everything is
  derived from the SAME `sessions − breaks` computation used by the employee/manager views (extracted to one
  `AttendanceMath` so worked time is never forked). Metrics are reported over a **window** — either a
  **shift-month** (default = current; sessions whose persisted `shiftDate` falls in that IST calendar month,
  never the raw clock-in day) OR a **custom from/to range** (see the endpoints below). Definitions (per the
  reported window):
  - **workedSeconds** = Σ completed sessions' duration **minus their breaks** (an open session → 0);
    **breakSeconds** = Σ completed breaks. **daysPresent** = distinct `shiftDate`s with ≥1 session.
    **lateLogins** = count of `is_late = true` (persisted, not recomputed).
  - **leavesByType {CASUAL, SICK, UNPAID}** + **leaveDaysTotal** = APPROVED leaves clipped to the month,
    counting **all calendar days inclusive** (start..end, weekends included — unchanged); **leaveRequests** =
    approved requests overlapping the month. (These headline leave totals are calendar-day; the
    adherence/absence basis below is stricter — Mon–Fri only.)
  - **workingDays** = **Mon–Fri in the month** (weekends excluded; public holidays NOT excluded yet).
    Centralized + labeled in `AttendanceCalendar` (`workingDaysDefinition`), swap-ready for a richer
    calendar later. **expectedDays** = workingDays − approved-leave days that land **on** working days
    (a leave on a weekend never reduces the denominator). **adherencePct** =
    round(present-on-working ÷ expectedDays × 100), where present-on-working = `daysPresent ∩ Mon–Fri`;
    when **expectedDays = 0** (e.g. a whole-month leave) adherence is **null (N/A)** — never a divide-by-zero.
  - **unapprovedAbsences** = count of working days that are already in the **PAST** (`date < today`, IST)
    with **no session and no approved leave**. Today and every future working day are **never** counted
    (an as-yet-unworked day isn't an absence). Present on the per-employee summary, the monthly series, and
    each team-roster row.
  - **timeComposition** = `{workedSeconds, breakSeconds}` (both real; sum to gross clocked time; **idle
    omitted in v1** — no count metric is mixed into the split). **clockedInNow** = an OPEN session exists now.
  - Endpoints: `GET /accountant/employees/{id}/attendance/summary` and
    `GET /accountant/teams/{teamId}/attendance/summary` each accept EITHER `?month=YYYY-MM` (default =
    current) OR a **custom range** `?from=YYYY-MM-DD&to=YYYY-MM-DD` — mutually exclusive (both → `400`).
    Range rules: both bounds present + valid dates, `from ≤ to`, span **≤ 366 days** (else `400`); the SAME
    metric rules apply, range-clipped (sessions by `shiftDate` in `[from,to]`; leave clipped to the range;
    working days = Mon–Fri within `[from,to]`; absences past-only). The response echoes the window in
    `periodStart` / `periodEnd` (always) and `month` (the `YYYY-MM` for a month, **null** for a range). The
    per-month **series** `…/attendance/monthly?months=N` (default 6) is **inherently monthly** — it takes
    neither `from/to` nor `month`. The team roll-up adds a live **today** snapshot (presentToday,
    clockedInNow, onLeaveToday, totalLateThisMonth) + a per-employee row + a **team COMPOSITION aggregate**
    (`teamTimeComposition` = worked/break, `teamDaysPresent`, `teamLeavesByType`, `teamLeaveDaysTotal`,
    `teamUnapprovedAbsences`, `teamExpectedDays`, `teamAdherencePct`) — each field is the **sum of the
    per-member `computePeriod` results** for the window (no new aggregation; team adherence = Σ
    present-on-working ÷ Σ expectedDays, null when Σ = 0). The team read is **batched** (~4 queries, no N+1).
    The today-snapshot tiles are **always NOW** — a selected range never moves them. Every query is
    `companyId`/team scoped.
  - **Web (read-only dashboard).** ONE shared implementation of the dashboard, mounted by all three roles
    with **no screen duplication**: the viewer team view carries an **Employees | Work Log** tab
    (ACCOUNTS_ADMIN via company→team, ACCOUNTANT own team), and the **Manager** area gains a **Live roster |
    Work Log** tab that mounts the SAME Work-Log components for the manager's own team (teamId resolved via
    `GET /manager/my-team`) — **alongside**, not replacing, the manager's existing live roster + activity
    feed. The **period picker** offers four modes — **Today**, **Month**, **Payroll cycle**, **Custom
    range** — all resolving to a `[from,to]` window client-side (Month keeps `?month=`); the picker,
    header, and CSV filename follow the active window. **Payroll cycle** is the universal **26th → 25th**
    window (a shared pure util: the current cycle is chosen from today's day-of-month, back/forward arrows
    step one whole cycle, spanning month/year boundaries — Dec 26 → Jan 25). **Today mode** consolidates:
    the separate "always-live" snapshot strip is dropped (the main tiles ARE the live today view — polling +
    the live indicator stay active), the late tile reads **"Late today"**, and the **Unapproved-absences**
    and **Adherence** tiles are **hidden** (a past-only count and a one-day fraction would mislead); all
    other modes show the full set. Both the **team** roll-up and the **employee** detail render the SAME
    shared **composition** view — a **worked-vs-break donut** (recharts; the only same-unit split — counts
    never go in the pie) + same-unit **meters** + **stat cards** for the counts (worked, break, days present,
    late, adherence [N/A when no expected days], unapproved absences, leaves by type C·S·U). The team view
    feeds the **team aggregate** into it (above the per-employee roster, which is unchanged); the employee
    detail additionally has a **month-wise report** (bar + table, from the series — always monthly). ~45s
    polling + refetch-on-focus keep the window live. Numbers use the shared duration formatter (e.g.
    63,300s → "17h 35m"). Both views offer a client-side **CSV export** (no refetch — built from the
    already-loaded state): the employee's month-wise series (monthly) and the team's per-employee roster for
    the **active window** — the filename carries the month, the single day, or the `from_to` range (RFC-4180
    escaping, UTF-8 BOM; durations as decimal hours, header-labeled).

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

## 8d. HR/Accounts Requests (Accounts side)

A credentialed employee asks their team's **Accountant** for a document (a payslip, salary certificate,
Form 16, etc.); the Accountant works it and uploads the requested file(s) to fulfil it. This is the
**Accounts** side; an **HR** side (HR-fulfilled document requests) is a **future** addition — the same
shape, routed to the team's HR.

- **Who can request:** only a **credentialed** employee (the `/workspace` principal — `mailAddress`
  assigned), exactly as leave/attendance. An OTP-only onboarding employee has no request access → `403` on
  every endpoint.
- **Routing (reuses the team resolution):** the routee is `employee.onboardingHr → that HR's team →
  team.accountantUser` — the Accountant of the team whose HR onboarded the employee. There is **no**
  fallback to the central Accounts Admin; if that team has **no Accountant assigned**, the submit is
  refused (`409` "No accountant assigned to your team"). The resolved `accountantUserId` is stored on the
  row, and the Accountant lists/acts by it (mirroring `LeaveRequest.managerUserId`).
- **Request:** `requestType` (`PAYSLIP` | `SALARY_CERTIFICATE` | `FORM16` | `TAX_DOCUMENT` | `OTHER`) and a
  free-text `note` (e.g. the period "Jan–Mar 2026").
- **Lifecycle:** `SUBMITTED → IN_PROGRESS → RESOLVED`. The Accountant **picks up** a request (→
  `IN_PROGRESS`, `pickedUpAt`), uploads one or more documents, and **resolves** it (→ `RESOLVED`,
  `resolvedAt`, with an optional resolve note). The employee may **cancel their own** request while it is
  `SUBMITTED` (→ `CANCELLED`); once it is `IN_PROGRESS`/`RESOLVED` it can no longer be cancelled.
- **Fulfilment (the existing S3 handshake):** the Accountant uploads via the same **presigned upload →
  confirm** handshake as employee documents / mail attachments — request an upload URL (server validates
  type + extension + size: images/pdf/office/csv/zip, ≤10 MB, executables/scripts rejected), PUT to
  storage, then **resolve** binds the file(s): the server re-reads the bytes, re-validates, computes the
  **sha256**, and marks them fulfilled. Files live under the **employee's** record key
  (`companies/{companyId}/employees/{employeeId}/requests/…`); raw storage keys are never exposed.
- **Download:** `GET /requests/{id}/documents/{docId}/download` issues a short-lived presigned **GET**,
  authorized to the request's **own employee** OR the **routed Accountant** (their team) — anyone else
  `403`. Audited `REQUEST_DOCUMENT_DOWNLOADED`.
- **Notifications — real internal mail + OS push.** The send graph now includes the **employee ↔ their
  team Accountant** edge (§8), so these notifications go through the **ordinary `canSendMail`-guarded
  internal-mail send path** (exactly like the leave auto-mails), landing as repliable threads and audited
  `MAIL_SENT`: on **submit**, employee → the routed Accountant ("‹employee› from team ‹team› requested
  ‹type›" + note); on **resolve**, the resolving Accountant → the employee ("Your ‹type› request is
  ready"). Each is **also** accompanied by a **best-effort OS push** (§8c) — the Accountant on submit, the
  employee on resolve — and the employee still gets the dev-logged email + sees the state in their own
  **`/requests/me`** history; the Accountant's durable record remains their **`/requests/team`** queue.
  Both are sent by the **controller AFTER the request tx commits** and are best-effort — a mail/push
  failure never breaks (or rolls back) the request action. Exactly one mail per submit / per resolve.
- **Scope:** an employee sees ONLY their own requests; an Accountant sees ONLY the requests routed to them
  (`accountantUserId == self`, same company) and may act only on those — cross-team / cross-company is
  denied. Every action is audited (`REQUEST_SUBMITTED` / `REQUEST_PICKED_UP` / `REQUEST_RESOLVED` /
  `REQUEST_CANCELLED` + `REQUEST_DOCUMENT_UPLOAD_REQUESTED` / `REQUEST_DOCUMENT_UPLOADED` /
  `REQUEST_DOCUMENT_DOWNLOADED`) with `companyId` set; `document_requests` carries a denormalized
  `companyId` so every query filters by tenant.

---

## 8c. OS notifications (Web Push)

OS-level notifications are delivered with **Web Push (VAPID)** — the browser standard for background
notifications that appear even when the IHRMS tab is closed or unfocused. Built in stages: **N1** is the
delivery backend + subscription storage; **N2** is the Service Worker + opt-in permission flow; **N3
(this stage)** triggers a push on **new internal mail**. A push surfaces a **real OS notification** —
including when the IHRMS tab is minimized or fully closed — and clicking it focuses/opens the app.

- **New-mail trigger (N3).** Every path that DELIVERS a message (compose, reply, reply-all — including the
  leave courtesy mails, which are real internal mail) notifies **each recipient (TO + CC + BCC) except the
  sender**: title `New message from {sender}`, body = the subject (snippet fallback), url `/mail`. The
  payload carries **no recipient information**, so a BCC recipient's notification reveals nothing about who
  else got the message and nothing ever leaks the BCC list (BCC privacy, §8). Fired from the controller
  **after the send transaction commits** (the same post-commit pattern as the leave auto-mail, via
  `MailPushNotifier`) and **best-effort**: any failure is logged and swallowed — mail delivery is never
  affected. Recipients without a subscription simply get nothing (they see it in-app); exactly one push per
  recipient per delivered message.

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
  - `POST /push/test` — sends a test notification to the CALLER's own subscriptions (the "Send test"
    button). Kept for debugging alongside the real N3 new-mail trigger; it never notifies anyone but the
    caller.
- **Sending is best-effort.** `PushService.sendToPrincipal(ref, title, body, url)` loads that principal's
  subscriptions and POSTs an encrypted, VAPID-signed payload to each via the `nl.martijndwars:web-push`
  library (BouncyCastle crypto). Failures are **logged, never thrown** to callers; a `404/410` from the
  push service means the subscription is gone, so that row is **pruned**. `sendToPrincipal` is the seam the
  N3 new-mail trigger calls; future events reuse it the same way.
- **VAPID keys are env secrets.** `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` (a `mailto:`)
  come from the environment (set in Railway; generate with `npx web-push generate-vapid-keys`) — **never
  generated in code, hardcoded, or committed**. Push is an **optional add-on**: absent/invalid keys simply
  **disable** it (logged loudly) in **every** environment and **never block startup** — a missing key for an
  optional feature must not take down login/mail/etc. The Service Worker `sw.js` **must** be served at the
  site root scope on the web origin (Next.js serves `public/sw.js` at `/sw.js`).

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

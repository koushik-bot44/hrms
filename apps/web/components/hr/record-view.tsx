'use client';

import * as React from 'react';
import { CheckCircle2, Eye, ExternalLink, FileText, Undo2 } from 'lucide-react';
import type {
  DecisionResult,
  EmployeeRecord,
  Form1View,
  Form2View,
  Form3EntryView,
  RevealedSensitive,
  SectionStatus,
} from '@/lib/contract';
import { DOCUMENT_TYPE_LABELS, GeneratedDocumentKind, UserRole } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { StatusBadge } from '@/components/status-badge';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';
import { ApproveDecisionActions } from '@/components/hr/approve-decision';
import { AssignCredentialsDialog } from '@/components/hr/assign-credentials-dialog';
import { SendAgreementsDialog } from '@/components/hr/send-agreements-dialog';
import { AGREEMENT_TITLES } from '@/lib/contract';
import type { AgreementSummary } from '@/lib/contract';

export type ItemKind = 'form' | 'document';

const GENERATED_LABELS: Record<GeneratedDocumentKind, string> = {
  [GeneratedDocumentKind.FORM1]: 'Form 1 — Personal Details',
  [GeneratedDocumentKind.FORM2]: 'Form 2 — Employee Info',
  [GeneratedDocumentKind.FORM3]: 'Form 3 — Previous Employment',
  [GeneratedDocumentKind.FORM4_MANIFEST]: 'Form 4 — Documents',
  [GeneratedDocumentKind.MERGED]: 'Complete Application (Forms 1, 3, 4)',
};

interface RecordViewProps {
  record: EmployeeRecord;
  editable: boolean;
  busy?: boolean;
  revealed?: RevealedSensitive | null;
  onReveal?: () => void;
  onVerify?: (kind: ItemKind, id: string) => void;
  /** Send this item back to the employee for revision — asks for a note (§3.3). */
  onSendBack?: (kind: ItemKind, id: string, label: string) => void;
  /** HR's terminal decision (approve onto a team, or reject) once the record is fully verified (§3.3). */
  onDecided?: (result: DecisionResult) => void;
  /** HR/SA "Edit Employee Info" affordance for the Form 2 card — shown only while INVITED (§3.2). */
  form2EditAction?: React.ReactNode;
  /** Show the HR "Send agreements" action for an APPROVED employee (§Agreements) — HR host only. */
  enableSendAgreements?: boolean;
}

/** The employee record: the four forms + Form 4 uploads + generated PDFs, sensitive values masked. */
export function RecordView({
  record,
  editable,
  busy = false,
  revealed = null,
  onReveal,
  onVerify,
  onSendBack,
  onDecided,
  form2EditAction,
  enableSendAgreements = false,
}: RecordViewProps) {
  const { session } = useAuth();
  const canAct = editable && Boolean(onVerify) && Boolean(onSendBack);
  // A document can only be VERIFIED after HR has opened (previewed) it — no approving a file
  // sight-unseen. Tracked per document id for this record view; the file's own data is inline for forms,
  // so forms are not gated. Marked when the Preview link is clicked.
  const [previewed, setPreviewed] = React.useState<Set<string>>(new Set());
  const markPreviewed = React.useCallback(
    (id: string) => setPreviewed((prev) => (prev.has(id) ? prev : new Set(prev).add(id))),
    [],
  );
  // Assigning/resetting a mailbox (§8, Stage 5) is allowed for the onboarding HR OR a COMPANY_ADMIN of
  // the employee's company (§6). Read-only viewers (Accountant, Accounts Admin) and the Manager share this
  // record view — they must never see these controls (the API 403s them; this stops the buttons even
  // appearing). Reveal is separate: each role uses its own audited reveal endpoint via `onReveal`, so it
  // is intentionally not gated here.
  const viewerCanManageMailbox =
    session?.type === 'USER' &&
    (session.role === UserRole.HR || session.role === UserRole.COMPANY_ADMIN);
  const f1 = revealed?.form1 ?? record.form1;
  const f2 = revealed?.form2 ?? record.form2;
  const f3 = revealed ? revealed.form3 : record.form3;
  const f3Status = record.form3[0]?.status;
  const f3Note = record.form3[0]?.revisionNote;

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader className="flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:space-y-0">
          <div className="min-w-0 space-y-1">
            <CardTitle className="text-base">{record.fullName ?? record.email}</CardTitle>
            <p className="truncate text-sm text-muted-foreground">{record.email}</p>
            <p className="text-xs text-muted-foreground">
              {record.designation ?? '—'}
              {record.dateOfJoining ? ` · joins ${record.dateOfJoining}` : ''}
              {record.employeeCode ? (
                <>
                  {' · '}
                  <span className="font-mono">{record.employeeCode}</span>
                </>
              ) : null}
            </p>
          </div>
          <div className="flex items-center gap-3">
            {record.sensitiveRevealable && onReveal ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={onReveal}
                disabled={Boolean(revealed)}
                title="Revealing masked fields is recorded in the audit trail"
              >
                <Eye className="size-4" />
                {revealed ? 'Revealed' : 'Reveal (audited)'}
              </Button>
            ) : null}
            <StatusBadge status={record.status} />
            {editable && onDecided ? (
              <ApproveDecisionActions
                employeeId={record.id}
                disabled={record.status !== 'HR_VERIFIED'}
                onDecided={onDecided}
              />
            ) : null}
            {viewerCanManageMailbox && record.status === 'APPROVED' ? (
              record.credentialsAssigned ? (
                <MailboxAssigned
                  address={record.mailAddress}
                  mailDomain={record.mailDomain}
                  employeeId={record.id}
                  personalEmail={record.email}
                />
              ) : (
                <AssignCredentialsDialog
                  employeeId={record.id}
                  personalEmail={record.email}
                  mailDomain={record.mailDomain}
                />
              )
            ) : null}
            {enableSendAgreements &&
            record.status === 'APPROVED' &&
            record.agreements.length === 0 ? (
              <SendAgreementsDialog employeeId={record.id} />
            ) : null}
          </div>
        </CardHeader>
        {editable && !record.reviewComplete ? (
          <CardContent className="pt-0 text-sm text-muted-foreground">
            {record.status === 'REVISION_REQUESTED'
              ? 'Waiting on the employee to fix the items you sent back — you can’t approve until every item is verified.'
              : 'Verify every form and document to enable Approve / Reject.'}
          </CardContent>
        ) : null}
        {revealed ? (
          <CardContent className="pt-0 text-xs text-muted-foreground">
            Sensitive fields are shown in the clear — this reveal was recorded in the audit trail.
          </CardContent>
        ) : null}
        {record.aadhaarNumber ? (
          <CardContent className="pt-0">
            <div className="grid grid-cols-3 gap-2 text-sm">
              <span className="text-muted-foreground">Aadhaar No.</span>
              <span className="col-span-2 break-words font-mono">
                {revealed?.aadhaarNumber ?? record.aadhaarNumber}
              </span>
            </div>
          </CardContent>
        ) : null}
      </Card>

      <FormCard
        title="Form 1 — Personal Details"
        // Status is the LIVE review state (from `record`); the revealed snapshot only supplies
        // unmasked field values and would otherwise freeze the badge after a reveal.
        status={record.form1?.status}
        note={record.form1?.revisionNote}
        canAct={canAct}
        busy={busy}
        onVerify={() => onVerify?.('form', 'FORM1')}
        onSendBack={() => onSendBack?.('form', 'FORM1', 'Form 1')}
      >
        {f1 ? <Form1Body form1={f1} /> : <Empty />}
      </FormCard>

      {/* Form 2 is HR/SA-authored at onboard (§3.2) — READ-ONLY here (no verify / send-back); the
          "Edit Employee Info" affordance appears only while INVITED, supplied by the parent. */}
      <FormCard
        title="Form 2 — Employee Info"
        status={undefined}
        note={null}
        canAct={false}
        busy={busy}
        onVerify={() => {}}
        onSendBack={() => {}}
        headerExtra={
          <>
            <Badge variant="neutral">HR-authored</Badge>
            {form2EditAction}
          </>
        }
      >
        {f2 ? <Form2Body form2={f2} /> : <Empty />}
      </FormCard>

      <FormCard
        title="Form 3 — Previous Employment"
        status={f3Status}
        note={f3Note}
        canAct={canAct && record.form3.length > 0}
        busy={busy}
        onVerify={() => onVerify?.('form', 'FORM3')}
        onSendBack={() => onSendBack?.('form', 'FORM3', 'Form 3')}
      >
        {f3.length === 0 ? (
          <p className="text-sm text-muted-foreground">No previous employment declared.</p>
        ) : (
          <div className="space-y-3">
            {f3.map((e, i) => (
              <div key={e.id ?? i} className={cn(surface('subtle'), 'p-4')}>
                <p className="mb-2 text-sm font-semibold">Employer {i + 1}</p>
                <Form3Body entry={e} />
              </div>
            ))}
          </div>
        )}
      </FormCard>

      <section className="space-y-3">
        <h3 className="text-sm font-semibold text-muted-foreground">Form 4 — Documents</h3>
        {record.documents.length === 0 ? (
          <p className="text-sm text-muted-foreground">No documents uploaded.</p>
        ) : (
          <div className="space-y-2">
            {record.documents.map((d) => (
              <div key={d.id} className={cn(surface('subtle'), 'space-y-2 p-4')}>
                <div className="flex flex-wrap items-center gap-3">
                  <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium">{d.fileName}</p>
                    <p className="text-xs text-muted-foreground">
                      {DOCUMENT_TYPE_LABELS[d.docType]}
                      {d.groupIndex ? ` · Employment ${d.groupIndex}` : ''}
                    </p>
                  </div>
                  <StatusBadge status={d.status} />
                  {canAct ? (
                    <ItemActions
                      busy={busy}
                      viewUrl={d.viewUrl}
                      previewed={previewed.has(d.id)}
                      onPreview={() => markPreviewed(d.id)}
                      onVerify={() => onVerify?.('document', d.id)}
                      onSendBack={() => onSendBack?.('document', d.id, d.fileName)}
                    />
                  ) : (
                    <a href={d.viewUrl} target="_blank" rel="noreferrer">
                      <Button type="button" variant="outline" size="sm">
                        <ExternalLink />
                        Preview
                      </Button>
                    </a>
                  )}
                </div>
                <RevisionNote status={d.status} note={d.revisionNote} />
              </div>
            ))}
          </div>
        )}
      </section>

      {record.generatedDocuments.length > 0 ? (
        <section className="space-y-3">
          <h3 className="text-sm font-semibold text-muted-foreground">Generated PDFs</h3>
          <p className="text-xs text-muted-foreground">
            The complete application merges Forms 1, 3 &amp; 4. Form 2 is a separate HR/SA-only PDF — it
            is not in the merged file and is never shown to the employee (§3.2).
          </p>
          <div className="grid gap-2 sm:grid-cols-2">
            {record.generatedDocuments.map((g) => (
              <div key={g.id} className={cn(surface('subtle'), 'flex items-center gap-3 p-3')}>
                <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{GENERATED_LABELS[g.kind] ?? g.fileName}</p>
                  {g.kind === GeneratedDocumentKind.FORM2 ? (
                    <Badge variant="neutral" className="mt-1">
                      HR only
                    </Badge>
                  ) : null}
                </div>
                <a href={g.viewUrl} target="_blank" rel="noreferrer">
                  <Button type="button" variant="outline" size="sm">
                    <ExternalLink />
                    Open
                  </Button>
                </a>
              </div>
            ))}
          </div>
        </section>
      ) : null}

      {record.agreements.length > 0 ? (
        <section className="space-y-3">
          <h3 className="text-sm font-semibold text-muted-foreground">Agreements</h3>
          <p className="text-xs text-muted-foreground">
            The standard post-approval pack. Completed agreements are signed by the employee and stored on
            their record.
          </p>
          <div className="space-y-2">
            {record.agreements.map((a) => (
              <AgreementRow key={a.type} agreement={a} />
            ))}
          </div>
        </section>
      ) : null}
    </div>
  );
}

/** One row in the HR record Agreements section: title + status + download when completed. */
function AgreementRow({ agreement }: { agreement: AgreementSummary }) {
  const done = agreement.status === 'COMPLETED';
  return (
    <div className={cn(surface('subtle'), 'flex flex-wrap items-center gap-3 p-3')}>
      <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">
          {agreement.title ?? AGREEMENT_TITLES[agreement.type]}
        </p>
        <p className="text-xs text-muted-foreground">
          {done && agreement.completedAt
            ? `Signed ${new Date(agreement.completedAt).toLocaleDateString()}`
            : agreement.sentByName
              ? `Sent by ${agreement.sentByName}`
              : 'Awaiting the employee'}
        </p>
      </div>
      <Badge variant={done ? 'success' : 'warning'}>{done ? 'Completed' : 'Pending'}</Badge>
      {done && agreement.downloadUrl ? (
        <a href={agreement.downloadUrl} target="_blank" rel="noreferrer">
          <Button type="button" variant="outline" size="sm">
            <ExternalLink />
            Open
          </Button>
        </a>
      ) : null}
    </div>
  );
}

/**
 * The mailbox-assigned state (§8, Stage 5): a status pill (Approved/Verified badge style) + the assigned
 * address, replacing the primary "Assign mailbox" button once credentials exist. Re-issue stays reachable
 * only via the demoted "Reset credentials" action — not the primary path.
 */
function MailboxAssigned({
  address,
  mailDomain,
  employeeId,
  personalEmail,
}: {
  address: string | null;
  mailDomain: string | null;
  employeeId: string;
  personalEmail: string;
}) {
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
      <Badge variant="success" title={address ?? undefined}>
        <span className="size-1.5 rounded-full bg-current" aria-hidden />
        Mailbox assigned
      </Badge>
      {address ? (
        <span className="font-mono text-xs text-muted-foreground">{address}</span>
      ) : null}
      <AssignCredentialsDialog
        mode="reset"
        employeeId={employeeId}
        personalEmail={personalEmail}
        mailDomain={mailDomain}
        initialLocalPart={address ? (address.split('@')[0] ?? '') : ''}
      />
    </div>
  );
}

function FormCard({
  title,
  status,
  note,
  canAct,
  busy,
  onVerify,
  onSendBack,
  headerExtra,
  children,
}: {
  title: string;
  status?: SectionStatus;
  note?: string | null;
  canAct: boolean;
  busy: boolean;
  onVerify: () => void;
  onSendBack: () => void;
  headerExtra?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-2 space-y-0">
        <CardTitle className="text-base">{title}</CardTitle>
        <div className="flex flex-wrap items-center gap-2">
          {headerExtra}
          {status ? <StatusBadge status={status} /> : null}
          {canAct ? <ItemActions busy={busy} onVerify={onVerify} onSendBack={onSendBack} /> : null}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <RevisionNote status={status} note={note} />
        {children}
      </CardContent>
    </Card>
  );
}

/** Shows the note HR wrote when an item is awaiting the employee's revision. */
function RevisionNote({ status, note }: { status?: SectionStatus | string; note?: string | null }) {
  if (status !== 'REVISION_REQUESTED' || !note) return null;
  return (
    <p className="rounded-md border border-warning/40 bg-warning/5 px-3 py-2 text-xs">
      <span className="font-medium">Sent back to the employee:</span> {note}
    </p>
  );
}

const F1_FIELDS: Array<[keyof Form1View, string]> = [
  ['name', 'Name'], ['dateOfBirth', 'Date of Birth'], ['email', 'Email'], ['mobile', 'Mobile'],
  ['maritalStatus', 'Marital Status'],
  ['bloodGroup', 'Blood Group'], ['city', 'City'], ['currentAddress', 'Current Address'],
  ['permanentAddress', 'Permanent Address'],
  // Relocated from Form 2 (§3.2) — PAN/account arrive masked; the audited reveal unmasks them.
  ['alternateNumber', 'Alternate Number'], ['vehicleNo2W4W', 'Vehicle No'],
  ['panNumber', 'PAN Number'], ['axisAccountNumber', 'Axis Account'],
  // Display rename only — the key stays closestRelativeName (§3.2).
  ['closestRelativeName', 'Emergency Contact'],
];

function Form1Body({ form1 }: { form1: Form1View }) {
  return (
    <div className="space-y-4">
      <Dl
        entries={[
          ...F1_FIELDS.map(
            ([k, label]): [string, string | null | undefined] => [label, form1[k] as string | null | undefined],
          ),
          // The declaration is fixed boilerplate; HR only needs to see it was affirmed.
          ['Declaration', form1.declaration && form1.declaration.trim() ? 'Confirmed' : 'Not confirmed'],
        ]}
      />
      <MiniTable
        title="Educational Qualifications"
        cols={['Qualification', 'University', 'Year', '%']}
        rows={(form1.educationalQualifications ?? []).map((e) => [e.qualification, e.university, e.yearOfPassing, e.percentage])}
      />
      <MiniTable
        title="Family Details"
        cols={['Name', 'Age', 'Relation', 'Occupation']}
        rows={(form1.familyDetails ?? []).map((f) => [f.name, f.age, f.relation, f.occupation])}
      />
      <MiniTable
        title="Conduct References"
        cols={['Name', 'Address', 'Phone']}
        rows={(form1.characterReferences ?? []).map((r) => [r.name, r.address, r.phone])}
      />
    </div>
  );
}

// alternate number, vehicle no, PAN, account number + addresses moved to Form 1 (§3.2); father's name,
// DOB, blood group, mobile, Spark ID and documents-submitted were removed from Form 2 (§3.2).
const F2_FIELDS: Array<[keyof Form2View, string]> = [
  ['fullName', 'Full Name'], ['employeeId', 'Employee ID'], ['dateOfJoining', 'Date of Joining'],
  ['officialEmail', 'Official Email'], ['personalEmail', 'Personal Email'], ['designation', 'Designation'],
];

function Form2Body({ form2 }: { form2: Form2View }) {
  return <Dl entries={F2_FIELDS.map(([k, label]) => [label, form2[k] as string | null | undefined])} />;
}

const F3_FIELDS: Array<[keyof Form3EntryView, string]> = [
  ['companyName', 'Company'], ['companyAddress', 'Address'], ['dateOfJoining', 'Joined'],
  ['dateOfRelieving', 'Relieved'], ['designation', 'Designation'], ['lastDrawnSalary', 'Last Drawn Salary'],
  ['jobType', 'Job Type'], ['reasonForLeaving', 'Reason for Leaving'], ['reportingTo', 'Reporting To'],
  ['roContact', 'RO Contact'], ['hrNameContact', 'HR Name / Contact'],
];

function Form3Body({ entry }: { entry: Form3EntryView }) {
  return <Dl entries={F3_FIELDS.map(([k, label]) => [label, entry[k] as string | null | undefined])} />;
}

function Dl({ entries }: { entries: Array<[string, string | null | undefined]> }) {
  const shown = entries.filter(([, v]) => v != null && v !== '');
  if (shown.length === 0) return <Empty />;
  return (
    <dl className="divide-y divide-border">
      {shown.map(([label, v]) => (
        <div key={label} className="grid grid-cols-3 gap-2 py-1.5 text-sm">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="col-span-2 break-words">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

function MiniTable({
  title,
  cols,
  rows,
}: {
  title: string;
  cols: string[];
  rows: Array<Array<string | null | undefined>>;
}) {
  const filled = rows.filter((r) => r.some((c) => c != null && c !== ''));
  if (filled.length === 0) return null;
  return (
    <div className="space-y-1.5">
      <h5 className="text-xs font-semibold text-muted-foreground">{title}</h5>
      <div className="overflow-x-auto rounded-md border">
        <table className="w-full text-xs">
          <thead className="bg-muted/50">
            <tr>
              {cols.map((c) => (
                <th key={c} className="px-2 py-1 text-left font-medium">{c}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {filled.map((r, i) => (
              <tr key={i} className="border-t">
                {r.map((c, ci) => (
                  <td key={ci} className="px-2 py-1">{c ?? '—'}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Empty() {
  return <p className="text-sm text-muted-foreground">Not provided.</p>;
}

/**
 * The two per-item HR actions (§3.3): Verify, and Send back for revision. There is no per-item Reject
 * — terminal rejection of the application is HR's whole-record action once verified. Both stay enabled
 * after a decision so HR can re-decide (Verify ⇄ Send-back). For a DOCUMENT (has a {@code viewUrl}), Verify
 * stays disabled until HR opens it via Preview — no approving a file sight-unseen; sending it back is
 * always allowed.
 */
function ItemActions({
  busy,
  viewUrl,
  previewed = false,
  onPreview,
  onVerify,
  onSendBack,
}: {
  busy: boolean;
  /** When set (documents), shows a Preview link that opens the uploaded file in a new tab. */
  viewUrl?: string;
  /** Whether this document has been previewed yet (documents only). */
  previewed?: boolean;
  /** Called when the Preview link is clicked — unlocks Verify. */
  onPreview?: () => void;
  onVerify: () => void;
  onSendBack: () => void;
}) {
  const needsPreview = Boolean(viewUrl) && !previewed;
  return (
    <div className="flex items-center gap-1.5">
      {viewUrl ? (
        <a href={viewUrl} target="_blank" rel="noreferrer" onClick={() => onPreview?.()}>
          {/* Emphasised until opened — HR must preview before Verify unlocks. */}
          <Button type="button" variant={needsPreview ? 'default' : 'outline'} size="sm">
            <ExternalLink />
            Preview
          </Button>
        </a>
      ) : null}
      <Button
        type="button"
        variant="success"
        size="sm"
        disabled={busy || needsPreview}
        onClick={onVerify}
        title={needsPreview ? 'Preview the document before verifying it' : undefined}
      >
        <CheckCircle2 />
        Verify
      </Button>
      <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onSendBack}>
        <Undo2 />
        Send back for revision
      </Button>
    </div>
  );
}

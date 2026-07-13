'use client';

import * as React from 'react';
import { CheckCircle2, Eye, ExternalLink, FileText, Undo2 } from 'lucide-react';
import type {
  EmployeeRecord,
  Form1View,
  Form2View,
  Form3EntryView,
  RevealedSensitive,
  RouteToManagerResult,
  SectionStatus,
} from '@/lib/contract';
import { DOCUMENT_TYPE_LABELS } from '@/lib/contract';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { StatusBadge } from '@/components/status-badge';
import { RouteToManagerDialog } from '@/components/hr/route-to-manager-dialog';
import { AssignCredentialsDialog } from '@/components/hr/assign-credentials-dialog';

export type ItemKind = 'form' | 'document';

interface RecordViewProps {
  record: EmployeeRecord;
  editable: boolean;
  busy?: boolean;
  revealed?: RevealedSensitive | null;
  onReveal?: () => void;
  onVerify?: (kind: ItemKind, id: string) => void;
  /** Send this item back to the employee for revision — asks for a note (§3.3). */
  onSendBack?: (kind: ItemKind, id: string, label: string) => void;
  onRouted?: (result: RouteToManagerResult) => void;
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
  onRouted,
}: RecordViewProps) {
  const canAct = editable && Boolean(onVerify) && Boolean(onSendBack);
  const f1 = revealed?.form1 ?? record.form1;
  const f2 = revealed?.form2 ?? record.form2;
  const f3 = revealed ? revealed.form3 : record.form3;
  const f3Status = record.form3[0]?.status;
  const f3Note = record.form3[0]?.revisionNote;

  return (
    <div className="space-y-5">
      <Card>
        <CardHeader className="flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:space-y-0">
          <div className="min-w-0 space-y-1">
            <CardTitle className="text-base">{record.fullName ?? record.email}</CardTitle>
            <p className="truncate text-sm text-muted-foreground">{record.email}</p>
            <p className="text-xs text-muted-foreground">
              {record.designation ?? '—'}
              {record.dateOfJoining ? ` · joins ${record.dateOfJoining}` : ''}
              {record.employeeCode ? ` · ${record.employeeCode}` : ''}
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
            {editable && onRouted ? (
              <RouteToManagerDialog employeeId={record.id} disabled={!record.reviewComplete} onRouted={onRouted} />
            ) : null}
            {record.status === 'APPROVED' ? (
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
          </div>
        </CardHeader>
        {editable && !record.reviewComplete ? (
          <CardContent className="pt-0 text-sm text-muted-foreground">
            {record.status === 'REVISION_REQUESTED'
              ? 'Waiting on the employee to fix the items you sent back — they can’t be routed until every item is verified.'
              : 'Verify every form and document to enable routing to the Manager.'}
          </CardContent>
        ) : null}
        {revealed ? (
          <CardContent className="pt-0 text-xs text-muted-foreground">
            Sensitive fields are shown in the clear — this reveal was recorded in the audit trail.
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

      <FormCard
        title="Form 2 — Employee Info"
        status={record.form2?.status}
        note={record.form2?.revisionNote}
        canAct={canAct}
        busy={busy}
        onVerify={() => onVerify?.('form', 'FORM2')}
        onSendBack={() => onSendBack?.('form', 'FORM2', 'Form 2')}
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
              <div key={e.id ?? i} className="rounded-md border p-3">
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
              <div key={d.id} className="space-y-2 rounded-md border border-border p-3">
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
          <div className="grid gap-2 sm:grid-cols-2">
            {record.generatedDocuments.map((g) => (
              <div key={g.id} className="flex items-center gap-3 rounded-md border p-3">
                <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <span className="min-w-0 flex-1 truncate text-sm font-medium">{g.fileName}</span>
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
  children,
}: {
  title: string;
  status?: SectionStatus;
  note?: string | null;
  canAct: boolean;
  busy: boolean;
  onVerify: () => void;
  onSendBack: () => void;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-2 space-y-0">
        <CardTitle className="text-base">{title}</CardTitle>
        <div className="flex items-center gap-2">
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
    <p className="rounded-md border border-amber-500/40 bg-amber-500/5 px-3 py-2 text-xs">
      <span className="font-medium">Sent back to the employee:</span> {note}
    </p>
  );
}

const F1_FIELDS: Array<[keyof Form1View, string]> = [
  ['name', 'Name'], ['dateOfBirth', 'Date of birth'], ['email', 'Email'], ['mobile', 'Mobile'],
  ['designation', 'Designation'], ['offeredCtc', 'Offered CTC'], ['maritalStatus', 'Marital status'],
  ['bloodGroup', 'Blood group'], ['city', 'City'], ['currentAddress', 'Current address'],
  ['permanentAddress', 'Permanent address'], ['closestRelativeName', 'Closest relative'],
  ['closestRelativePhone', 'Relative phone'], ['relationship', 'Relationship'],
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
        title="Working Experience"
        cols={['Organization', 'Period', 'Designation', 'Salary/CTC', 'Reason']}
        rows={(form1.workingExperiences ?? []).map((w) => [w.organization, w.period, w.designation, w.salaryCtc, w.reasonForLeaving])}
      />
      <MiniTable
        title="Family Details"
        cols={['Name', 'Age', 'Relation', 'Occupation']}
        rows={(form1.familyDetails ?? []).map((f) => [f.name, f.age, f.relation, f.occupation])}
      />
      <MiniTable
        title="Character References"
        cols={['Name', 'Address', 'Phone']}
        rows={(form1.characterReferences ?? []).map((r) => [r.name, r.address, r.phone])}
      />
    </div>
  );
}

const F2_FIELDS: Array<[keyof Form2View, string]> = [
  ['fullName', 'Full name'], ['fatherName', "Father's name"], ['employeeId', 'Employee ID'],
  ['sparkId', 'Spark ID'], ['dateOfBirth', 'Date of birth'], ['dateOfJoining', 'Date of joining'],
  ['bloodGroup', 'Blood group'], ['mobile', 'Mobile'], ['alternateNumber', 'Alternate number'],
  ['officialEmail', 'Official email'], ['personalEmail', 'Personal email'], ['designation', 'Designation'],
  ['documentSubmitted', 'Documents submitted'], ['vehicleNo2W4W', 'Vehicle no'], ['panNumber', 'PAN number'],
  ['axisAccountNumber', 'Axis account'], ['currentAddress', 'Current address'], ['permanentAddress', 'Permanent address'],
];

function Form2Body({ form2 }: { form2: Form2View }) {
  return <Dl entries={F2_FIELDS.map(([k, label]) => [label, form2[k] as string | null | undefined])} />;
}

const F3_FIELDS: Array<[keyof Form3EntryView, string]> = [
  ['companyName', 'Company'], ['companyAddress', 'Address'], ['dateOfJoining', 'Joined'],
  ['dateOfRelieving', 'Relieved'], ['designation', 'Designation'], ['lastDrawnSalary', 'Last drawn salary'],
  ['jobType', 'Job type'], ['reasonForLeaving', 'Reason for leaving'], ['reportingTo', 'Reporting to'],
  ['roContact', 'RO contact'], ['hrNameContact', 'HR name / contact'],
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
 * — terminal rejection of the application is the Manager's action at approval. Both stay enabled after
 * a decision so HR can re-decide (Verify ⇄ Send-back).
 */
function ItemActions({
  busy,
  viewUrl,
  onVerify,
  onSendBack,
}: {
  busy: boolean;
  /** When set (documents), shows a Preview link that opens the uploaded file in a new tab. */
  viewUrl?: string;
  onVerify: () => void;
  onSendBack: () => void;
}) {
  return (
    <div className="flex items-center gap-1.5">
      {viewUrl ? (
        <a href={viewUrl} target="_blank" rel="noreferrer">
          <Button type="button" variant="outline" size="sm">
            <ExternalLink />
            Preview
          </Button>
        </a>
      ) : null}
      <Button type="button" variant="success" size="sm" disabled={busy} onClick={onVerify}>
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

'use client';

import * as React from 'react';
import { Briefcase, CheckCircle2, IdCard, Send, UserRound } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import {
  REQUIRED_DOCUMENTS,
  SECTION_DATA_SCHEMAS,
  SECTION_DOCUMENT_TYPES,
  SectionKey,
  evaluateSubmission,
  type DocumentType,
} from '@/lib/contract';
import { getDashboard, submitOnboarding } from '@/lib/api/onboarding';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { StatusBadge } from '@/components/status-badge';
import { Progress } from '@/components/ui/progress';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { SectionForm, type FieldConfig } from '@/components/employee/section-form';
import { DocumentUploader, DOC_TYPE_LABELS } from '@/components/employee/document-uploader';

const EDITABLE_STATUSES = new Set(['INVITED', 'IN_PROGRESS', 'REJECTED']);

const SECTION_CONFIG: Record<
  SectionKey,
  { label: string; icon: React.ComponentType<{ className?: string }>; fields: FieldConfig[] }
> = {
  [SectionKey.PERSONAL]: {
    label: 'Personal',
    icon: UserRound,
    fields: [
      { name: 'fullName', label: 'Full name', placeholder: 'Alex Doe' },
      { name: 'dateOfBirth', label: 'Date of birth', type: 'date' },
      { name: 'phone', label: 'Phone', type: 'tel', placeholder: '+1 555 0100' },
      { name: 'addressLine', label: 'Address' },
      { name: 'city', label: 'City' },
    ],
  },
  [SectionKey.BACKGROUND]: {
    label: 'Background',
    icon: Briefcase,
    fields: [
      { name: 'previousCompany', label: 'Previous company' },
      { name: 'yearsOfExperience', label: 'Years of experience', type: 'number' },
      { name: 'notes', label: 'Notes', type: 'textarea', placeholder: 'Anything relevant…' },
    ],
  },
  [SectionKey.GOVERNMENT]: {
    label: 'Government',
    icon: IdCard,
    fields: [
      { name: 'panNumber', label: 'PAN number', placeholder: 'ABCDE1234F' },
      { name: 'aadhaarLast4', label: 'Aadhaar (last 4)', placeholder: '1234' },
    ],
  },
};

const SECTION_ORDER: SectionKey[] = [SectionKey.PERSONAL, SectionKey.BACKGROUND, SectionKey.GOVERNMENT];

function isRequiredDoc(sectionKey: SectionKey, docType: DocumentType): boolean {
  return REQUIRED_DOCUMENTS.some((r) => r.sectionKey === sectionKey && r.docType === docType);
}

export default function EmployeeOnboardingPage() {
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error } = useApiQuery(['onboarding'], getDashboard);

  const submitMutation = useApiMutation(() => submitOnboarding(), {
    successMessage: 'Submitted for verification',
    onSuccess: (dashboard) => {
      queryClient.setQueryData(['onboarding'], dashboard);
    },
  });

  if (isLoading) {
    return (
      <div className="space-y-6">
        <LoadingSkeleton lines={2} />
        <LoadingSkeleton lines={4} />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <EmptyState
        icon={UserRound}
        title="Couldn't load your record"
        description={error?.message ?? 'Please try again.'}
      />
    );
  }

  const editable = EDITABLE_STATUSES.has(data.status);
  const evaluation = evaluateSubmission(
    data.sections.map((s) => ({ key: s.key })),
    data.documents,
  );
  const progressPct = evaluation.requiredTotal
    ? (evaluation.completedTotal / evaluation.requiredTotal) * 100
    : 0;
  const sectionData = (key: SectionKey) => data.sections.find((s) => s.key === key)?.data;
  const isSaved = (key: SectionKey) => data.sections.some((s) => s.key === key);

  const missing = [
    ...evaluation.missingSections.map((k) => `${SECTION_CONFIG[k].label} details`),
    ...evaluation.missingDocuments.map((d) => DOC_TYPE_LABELS[d.docType]),
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="My onboarding"
        description="Complete each section and upload your documents, then submit for verification."
        actions={<StatusBadge status={data.status} />}
      />

      {editable ? (
        <Card>
          <CardContent className="flex flex-col gap-4 py-5 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex-1 space-y-2">
              <div className="flex items-center justify-between text-sm">
                <span className="font-medium">Completion</span>
                <span className="text-muted-foreground">
                  {evaluation.completedTotal} of {evaluation.requiredTotal} required
                </span>
              </div>
              <Progress value={progressPct} />
              {!evaluation.complete ? (
                <p className="text-xs text-muted-foreground">Still needed: {missing.join(' · ')}</p>
              ) : (
                <p className="text-xs text-success">All required items complete — ready to submit.</p>
              )}
            </div>
            <Button
              onClick={() => submitMutation.mutate()}
              disabled={!evaluation.complete || submitMutation.isPending}
              className="shrink-0"
            >
              <Send className="size-4" />
              {submitMutation.isPending ? 'Submitting…' : 'Submit for verification'}
            </Button>
          </CardContent>
        </Card>
      ) : (
        <Card className="border-success/30 bg-success/5">
          <CardContent className="flex items-center gap-3 py-5">
            <CheckCircle2 className="size-5 text-success" />
            <div>
              <div className="font-medium">Submitted for verification</div>
              <p className="text-sm text-muted-foreground">
                Your record is locked while HR reviews it. You&apos;ll be notified of the outcome.
              </p>
            </div>
          </CardContent>
        </Card>
      )}

      <Tabs defaultValue={SectionKey.PERSONAL} className="animate-fade-in">
        <TabsList>
          {SECTION_ORDER.map((key) => {
            const config = SECTION_CONFIG[key];
            return (
              <TabsTrigger key={key} value={key}>
                <config.icon className="size-4" />
                {config.label}
                {isSaved(key) ? <CheckCircle2 className="size-3.5 text-success" /> : null}
              </TabsTrigger>
            );
          })}
        </TabsList>

        {SECTION_ORDER.map((key) => {
          const config = SECTION_CONFIG[key];
          const docTypes = SECTION_DOCUMENT_TYPES[key];
          return (
            <TabsContent key={key} value={key} className="space-y-4">
              <Card>
                <CardHeader>
                  <CardTitle className="text-base">{config.label} details</CardTitle>
                </CardHeader>
                <CardContent>
                  <SectionForm
                    sectionKey={key}
                    schema={SECTION_DATA_SCHEMAS[key]}
                    fields={config.fields}
                    initialData={sectionData(key)}
                    disabled={!editable}
                  />
                </CardContent>
              </Card>

              {docTypes.length > 0 ? (
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">Documents</CardTitle>
                  </CardHeader>
                  <CardContent className="grid gap-5 sm:grid-cols-2">
                    {docTypes.map((docType) => (
                      <DocumentUploader
                        key={docType}
                        sectionKey={key}
                        docType={docType}
                        required={isRequiredDoc(key, docType)}
                        documents={data.documents}
                        disabled={!editable}
                      />
                    ))}
                  </CardContent>
                </Card>
              ) : null}
            </TabsContent>
          );
        })}
      </Tabs>
    </div>
  );
}

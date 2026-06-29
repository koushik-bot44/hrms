import type { Metadata } from 'next';
import { Briefcase, FileText, IdCard, UserRound } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { EmptyState } from '@/components/empty-state';
import { StatusBadge } from '@/components/status-badge';

export const metadata: Metadata = { title: 'My profile' };

export default function EmployeeProfilePage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="My profile"
        description="Complete each section and upload your documents, then submit for verification."
        actions={<StatusBadge status="IN_PROGRESS" />}
      />
      <Tabs defaultValue="personal">
        <TabsList>
          <TabsTrigger value="personal">
            <UserRound className="size-4" />
            Personal
          </TabsTrigger>
          <TabsTrigger value="background">
            <Briefcase className="size-4" />
            Background
          </TabsTrigger>
          <TabsTrigger value="government">
            <IdCard className="size-4" />
            Government
          </TabsTrigger>
          <TabsTrigger value="documents">
            <FileText className="size-4" />
            Documents
          </TabsTrigger>
        </TabsList>
        <TabsContent value="personal">
          <EmptyState
            icon={UserRound}
            title="Personal details"
            description="Your personal-details form arrives in a later phase."
          />
        </TabsContent>
        <TabsContent value="background">
          <EmptyState
            icon={Briefcase}
            title="Background details"
            description="Upload previous-company experience letters here."
          />
        </TabsContent>
        <TabsContent value="government">
          <EmptyState
            icon={IdCard}
            title="Government details"
            description="Upload identity documents (e.g. PAN, Aadhaar) here."
          />
        </TabsContent>
        <TabsContent value="documents">
          <EmptyState
            icon={FileText}
            title="Documents"
            description="All your uploaded documents, with their verification status."
          />
        </TabsContent>
      </Tabs>
    </div>
  );
}

'use client';

import * as React from 'react';
import Link from 'next/link';
import {
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  Inbox,
  Mail,
  Send,
  SquarePen,
} from 'lucide-react';
import type { InboxMessage, SentMessage } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { useApiQuery } from '@/lib/api/hooks';
import type { ApiError } from '@/lib/api/client';
import { getInbox, getSent, getUnreadCount, mailKeys } from '@/lib/api/mail';
import { relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/empty-state';
import { Skeleton } from '@/components/ui/skeleton';
import { ComposeDialog } from '@/components/mail/compose-dialog';
import { MessageView } from '@/components/mail/message-view';

type Folder = 'inbox' | 'sent';

/** The full webmail client (§8): folders (Inbox/Sent) + message list + reading pane + compose. */
export function Mailbox() {
  const { session } = useAuth();
  const [folder, setFolder] = React.useState<Folder>('inbox');
  const [page, setPage] = React.useState(0);
  const [selectedId, setSelectedId] = React.useState<string | null>(null);
  const [composeOpen, setComposeOpen] = React.useState(false);

  const myAddress = session?.type === 'USER' ? session.email : '';
  const myName = session?.type === 'USER' ? session.name : '';
  const backHref = session ? homePathForSession(session) : '/login';

  const unread = useApiQuery(mailKeys.unread, getUnreadCount, {
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  });
  const inbox = useApiQuery(mailKeys.inbox(page), (s) => getInbox(page, 20, s), {
    enabled: folder === 'inbox',
  });
  const sent = useApiQuery(mailKeys.sent(page), (s) => getSent(page, 20, s), {
    enabled: folder === 'sent',
  });

  const switchFolder = (next: Folder) => {
    setFolder(next);
    setPage(0);
    setSelectedId(null);
  };

  const active = folder === 'inbox' ? inbox : sent;
  const unreadCount = unread.data?.unread ?? 0;

  return (
    <div className="flex min-h-dvh flex-col bg-background">
      {/* Header */}
      <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b bg-card px-4 md:px-6">
        <div className="flex min-w-0 items-center gap-2">
          <div className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <Mail className="size-4" />
          </div>
          <div className="min-w-0 leading-tight">
            <div className="text-sm font-semibold tracking-tight">IHRMS Mail</div>
            {myAddress ? (
              <div className="truncate text-[11px] text-muted-foreground" title={`${myName} <${myAddress}>`}>
                {myAddress}
              </div>
            ) : null}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <a
            href="/mail"
            target="_blank"
            rel="noopener noreferrer"
            className="hidden items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground sm:inline-flex"
          >
            <ExternalLink className="size-4" />
            Open in new tab
          </a>
          <Button variant="ghost" size="sm" asChild>
            <Link href={backHref}>Back to portal</Link>
          </Button>
        </div>
      </header>

      {/* Body */}
      <div className="flex min-h-0 flex-1">
        {/* Left rail */}
        <aside className="hidden w-52 shrink-0 flex-col gap-1 border-r bg-card p-3 md:flex">
          <Button className="mb-2 justify-start" onClick={() => setComposeOpen(true)}>
            <SquarePen />
            Compose
          </Button>
          <FolderButton
            icon={Inbox}
            label="Inbox"
            active={folder === 'inbox'}
            badge={unreadCount}
            onClick={() => switchFolder('inbox')}
          />
          <FolderButton
            icon={Send}
            label="Sent"
            active={folder === 'sent'}
            onClick={() => switchFolder('sent')}
          />
        </aside>

        {/* Mobile folder switch + compose */}
        <div className="flex w-full flex-col md:hidden">
          <div className="flex items-center gap-2 border-b p-2">
            <Button
              variant={folder === 'inbox' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('inbox')}
            >
              <Inbox />
              Inbox{unreadCount > 0 ? ` (${unreadCount})` : ''}
            </Button>
            <Button
              variant={folder === 'sent' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('sent')}
            >
              <Send />
              Sent
            </Button>
            <Button size="sm" className="ml-auto" onClick={() => setComposeOpen(true)}>
              <SquarePen />
              Compose
            </Button>
          </div>
          <MobilePanes
            folder={folder}
            selectedId={selectedId}
            setSelectedId={setSelectedId}
            active={active}
            page={page}
            setPage={setPage}
          />
        </div>

        {/* Desktop: list + reading pane */}
        <div className="hidden min-w-0 flex-1 md:flex">
          <div className="flex w-[22rem] shrink-0 flex-col border-r">
            <MessageList
              folder={folder}
              selectedId={selectedId}
              onSelect={setSelectedId}
              query={active}
            />
            <Pager query={active} page={page} setPage={setPage} />
          </div>
          <div className="min-w-0 flex-1">
            <MessageView messageId={selectedId} onBack={() => setSelectedId(null)} />
          </div>
        </div>
      </div>

      <ComposeDialog
        open={composeOpen}
        onOpenChange={setComposeOpen}
        onSent={() => switchFolder('sent')}
      />
    </div>
  );
}

function FolderButton({
  icon: Icon,
  label,
  active,
  badge,
  onClick,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  active: boolean;
  badge?: number;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
        active
          ? 'bg-primary/10 text-primary'
          : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground',
      )}
    >
      <Icon className="size-4 shrink-0" />
      <span className="flex-1 text-left">{label}</span>
      {badge && badge > 0 ? (
        <span className="rounded-full bg-primary px-1.5 text-[11px] font-semibold text-primary-foreground">
          {badge > 99 ? '99+' : badge}
        </span>
      ) : null}
    </button>
  );
}

/**
 * A structural view of either list query (inbox or sent) — just the fields the list/pager read, so one
 * component can render either folder without wrestling react-query's per-state result union.
 */
interface ListQuery {
  isLoading: boolean;
  isError: boolean;
  error: ApiError | null;
  data?: { content: (InboxMessage | SentMessage)[]; totalPages: number };
}

function MessageList({
  folder,
  selectedId,
  onSelect,
  query,
}: {
  folder: Folder;
  selectedId: string | null;
  onSelect: (id: string) => void;
  query: ListQuery;
}) {
  if (query.isLoading) {
    return (
      <div className="flex-1 space-y-1 overflow-y-auto p-2">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="space-y-2 rounded-md p-3">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-3 w-4/5" />
          </div>
        ))}
      </div>
    );
  }

  if (query.isError) {
    return (
      <EmptyState
        title="Couldn’t load mail"
        description={query.error?.message ?? 'Please try again.'}
        className="m-3 border-0 bg-transparent"
      />
    );
  }

  const rows = query.data?.content ?? [];
  if (rows.length === 0) {
    return (
      <EmptyState
        icon={folder === 'inbox' ? Inbox : Send}
        title={folder === 'inbox' ? 'No messages yet' : 'Nothing sent yet'}
        description={
          folder === 'inbox'
            ? 'Messages from your contacts will appear here.'
            : 'Messages you send will appear here.'
        }
        className="m-3 border-0 bg-transparent"
      />
    );
  }

  return (
    <ul className="flex-1 overflow-y-auto" aria-label={folder === 'inbox' ? 'Inbox' : 'Sent'}>
      {rows.map((row) => {
        // Inbox rows carry `from` + a read flag; Sent rows carry `to[]`.
        const unread = 'from' in row ? !row.read : false;
        const heading =
          'from' in row
            ? row.from.name
            : `To: ${row.to.map((t) => t.name).join(', ') || '(no recipient)'}`;
        return (
          <li key={row.id}>
            <button
              type="button"
              onClick={() => onSelect(row.id)}
              aria-current={selectedId === row.id ? 'true' : undefined}
              className={cn(
                'flex w-full flex-col gap-0.5 border-b px-4 py-3 text-left transition-colors',
                selectedId === row.id ? 'bg-accent' : 'hover:bg-accent/60',
              )}
            >
              <div className="flex items-baseline justify-between gap-2">
                <span
                  className={cn(
                    'truncate text-sm',
                    unread ? 'font-semibold text-foreground' : 'font-medium text-foreground/90',
                  )}
                >
                  {heading}
                </span>
                <span className="shrink-0 text-[11px] text-muted-foreground">
                  {relativeTime(row.createdAt)}
                </span>
              </div>
              <div className="flex items-center gap-2">
                {unread ? <span className="size-2 shrink-0 rounded-full bg-primary" aria-hidden /> : null}
                <span
                  className={cn(
                    'truncate text-sm',
                    unread ? 'font-medium text-foreground' : 'text-muted-foreground',
                  )}
                >
                  {row.subject}
                </span>
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

function Pager({
  query,
  page,
  setPage,
}: {
  query: ListQuery;
  page: number;
  setPage: (n: number) => void;
}) {
  const totalPages = query.data?.totalPages ?? 0;
  if (totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-between border-t px-3 py-2 text-xs text-muted-foreground">
      <span>
        Page {page + 1} of {totalPages}
      </span>
      <div className="flex gap-1">
        <Button
          variant="ghost"
          size="sm"
          disabled={page <= 0}
          onClick={() => setPage(Math.max(0, page - 1))}
        >
          <ChevronLeft />
        </Button>
        <Button
          variant="ghost"
          size="sm"
          disabled={page >= totalPages - 1}
          onClick={() => setPage(page + 1)}
        >
          <ChevronRight />
        </Button>
      </div>
    </div>
  );
}

/** Mobile: show the list, or the reading pane once a message is opened. */
function MobilePanes({
  folder,
  selectedId,
  setSelectedId,
  active,
  page,
  setPage,
}: {
  folder: Folder;
  selectedId: string | null;
  setSelectedId: (id: string | null) => void;
  active: ListQuery;
  page: number;
  setPage: (n: number) => void;
}) {
  if (selectedId) {
    return <MessageView messageId={selectedId} onBack={() => setSelectedId(null)} />;
  }
  return (
    <div className="flex flex-1 flex-col">
      <MessageList folder={folder} selectedId={selectedId} onSelect={setSelectedId} query={active} />
      <Pager query={active} page={page} setPage={setPage} />
    </div>
  );
}

'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Archive,
  ArchiveRestore,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  FileText,
  Inbox,
  Mail,
  MailMinus,
  MailOpen,
  Paperclip,
  Search,
  Send,
  SquarePen,
  Star,
  Trash2,
  X,
} from 'lucide-react';
import type { Draft, DraftListItem, DraftPage, ThreadListItem, ThreadPage } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import type { ApiError } from '@/lib/api/client';
import {
  archiveThread,
  deleteDraft,
  deleteThread,
  getArchived,
  getDraft,
  getDrafts,
  getInbox,
  getSent,
  getStarred,
  getUnreadCount,
  mailKeys,
  markThreadRead,
  markThreadUnread,
  searchMail,
  starThread,
  unarchiveThread,
  unstarThread,
} from '@/lib/api/mail';
import { relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/empty-state';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { DockedCompose, type ComposeState } from '@/components/mail/docked-compose';
import { ThreadView } from '@/components/mail/thread-view';

type Folder = 'inbox' | 'sent' | 'starred' | 'archived' | 'drafts';

/** The full webmail client (§8, Stage 3): Inbox/Sent thread lists + search + reading pane + compose. */
export function Mailbox() {
  const { session } = useAuth();
  const [folder, setFolder] = React.useState<Folder>('inbox');
  const [page, setPage] = React.useState(0);
  const [selectedId, setSelectedId] = React.useState<string | null>(null);
  // One docked compose window at a time; `seq` forces a fresh window (state reset) each time it opens.
  const [compose, setCompose] = React.useState<ComposeState | null>(null);
  const [composeSeq, setComposeSeq] = React.useState(0);
  const openCompose = React.useCallback((next: ComposeState) => {
    setCompose(next);
    setComposeSeq((s) => s + 1);
  }, []);
  const [searchInput, setSearchInput] = React.useState('');
  const [searchTerm, setSearchTerm] = React.useState('');
  const searching = searchTerm.trim().length > 0;

  // Staff: the login email IS the address. Employee: their assigned mailbox address (§8, Stage 5).
  const myAddress =
    session?.type === 'USER' ? session.email : (session?.mailAddress ?? session?.email ?? '');
  const myName =
    session?.type === 'USER' ? session.name : (session?.name ?? session?.employeeCode ?? '');
  const backHref = session ? homePathForSession(session) : '/login';

  const unread = useApiQuery(mailKeys.unread, getUnreadCount, {
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  });
  const inbox = useApiQuery(mailKeys.inbox(page), (s) => getInbox(page, 20, s), {
    enabled: !searching && folder === 'inbox',
  });
  const sent = useApiQuery(mailKeys.sent(page), (s) => getSent(page, 20, s), {
    enabled: !searching && folder === 'sent',
  });
  const starred = useApiQuery(mailKeys.starred(page), (s) => getStarred(page, 20, s), {
    enabled: !searching && folder === 'starred',
  });
  const archivedList = useApiQuery(mailKeys.archived(page), (s) => getArchived(page, 20, s), {
    enabled: !searching && folder === 'archived',
  });
  const draftsList = useApiQuery(mailKeys.drafts(page), (s) => getDrafts(page, 20, s), {
    enabled: !searching && folder === 'drafts',
  });
  const results = useApiQuery(mailKeys.search(searchTerm, page), (s) => searchMail(searchTerm, page, 20, s), {
    enabled: searching,
  });

  const isDrafts = !searching && folder === 'drafts';

  // Open a draft in the composer, prefilled + bound to its id (so Save updates it and Send runs /send).
  const openDraft = React.useCallback(
    async (id: string) => {
      try {
        const d = await getDraft(id);
        openCompose(composeStateFromDraft(d));
      } catch {
        toast.error('Could not open that draft.');
      }
    },
    [openCompose],
  );

  const switchFolder = (next: Folder) => {
    setFolder(next);
    setPage(0);
    setSelectedId(null);
    setSearchTerm('');
    setSearchInput('');
  };

  const submitSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setSearchTerm(searchInput.trim());
    setPage(0);
    setSelectedId(null);
  };
  const clearSearch = () => {
    setSearchTerm('');
    setSearchInput('');
    setPage(0);
    setSelectedId(null);
  };

  const active = searching
    ? results
    : folder === 'inbox'
      ? inbox
      : folder === 'sent'
        ? sent
        : folder === 'starred'
          ? starred
          : archivedList;
  const unreadCount = unread.data?.unread ?? 0;
  const mode: ListMode = searching ? 'search' : folder;

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

        <form onSubmit={submitSearch} className="relative hidden max-w-xs flex-1 sm:block">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="search"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search your mail"
            aria-label="Search your mail"
            className="pl-8 pr-8"
          />
          {searchInput ? (
            <button
              type="button"
              onClick={clearSearch}
              aria-label="Clear search"
              className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            >
              <X className="size-4" />
            </button>
          ) : null}
        </form>

        <div className="flex items-center gap-2">
          <a
            href="/mail"
            target="_blank"
            rel="noopener noreferrer"
            className="hidden items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground md:inline-flex"
          >
            <ExternalLink className="size-4" />
            Pop out
          </a>
          <Button variant="ghost" size="sm" asChild>
            <Link href={backHref}>Back</Link>
          </Button>
        </div>
      </header>

      {/* Body */}
      <div className="flex min-h-0 flex-1">
        {/* Left rail */}
        <aside className="hidden w-52 shrink-0 flex-col gap-1 border-r bg-card p-3 md:flex">
          <Button className="mb-2 justify-start" onClick={() => openCompose({ mode: 'new' })}>
            <SquarePen />
            Compose
          </Button>
          <FolderButton
            icon={Inbox}
            label="Inbox"
            active={!searching && folder === 'inbox'}
            badge={unreadCount}
            onClick={() => switchFolder('inbox')}
          />
          <FolderButton
            icon={Send}
            label="Sent"
            active={!searching && folder === 'sent'}
            onClick={() => switchFolder('sent')}
          />
          <FolderButton
            icon={Star}
            label="Starred"
            active={!searching && folder === 'starred'}
            onClick={() => switchFolder('starred')}
          />
          <FolderButton
            icon={Archive}
            label="Archive"
            active={!searching && folder === 'archived'}
            onClick={() => switchFolder('archived')}
          />
          <FolderButton
            icon={FileText}
            label="Drafts"
            active={!searching && folder === 'drafts'}
            onClick={() => switchFolder('drafts')}
          />
          {searching ? (
            <div className="mt-2 flex items-center justify-between rounded-md bg-muted px-3 py-2 text-xs">
              <span className="truncate">Results for “{searchTerm}”</span>
              <button type="button" onClick={clearSearch} aria-label="Clear search">
                <X className="size-3.5" />
              </button>
            </div>
          ) : null}
        </aside>

        {/* Mobile controls */}
        <div className="flex w-full flex-col md:hidden">
          <div className="flex items-center gap-2 border-b p-2">
            <Button
              variant={!searching && folder === 'inbox' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('inbox')}
            >
              <Inbox />
              Inbox{unreadCount > 0 ? ` (${unreadCount})` : ''}
            </Button>
            <Button
              variant={!searching && folder === 'sent' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('sent')}
            >
              <Send />
              Sent
            </Button>
            <Button
              variant={!searching && folder === 'starred' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('starred')}
            >
              <Star />
              Starred
            </Button>
            <Button
              variant={!searching && folder === 'archived' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('archived')}
            >
              <Archive />
              Archive
            </Button>
            <Button
              variant={!searching && folder === 'drafts' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('drafts')}
            >
              <FileText />
              Drafts
            </Button>
            <Button size="sm" className="ml-auto" onClick={() => openCompose({ mode: 'new' })}>
              <SquarePen />
              New
            </Button>
          </div>
          <form onSubmit={submitSearch} className="relative border-b p-2">
            <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              type="search"
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              placeholder="Search your mail"
              className="pl-8"
            />
          </form>
          {isDrafts ? (
            <div className="flex flex-1 flex-col">
              <DraftsList query={draftsList} onOpen={openDraft} />
              <Pager query={draftsList} page={page} setPage={setPage} />
            </div>
          ) : (
            <MobilePanes
              mode={mode}
              selectedId={selectedId}
              setSelectedId={setSelectedId}
              active={active}
              page={page}
              setPage={setPage}
              onReply={openCompose}
            />
          )}
        </div>

        {/* Desktop: list + reading pane (drafts open in the composer, so they get the full width) */}
        <div className="hidden min-w-0 flex-1 md:flex">
          {isDrafts ? (
            <div className="flex min-w-0 flex-1 flex-col">
              <DraftsList query={draftsList} onOpen={openDraft} />
              <Pager query={draftsList} page={page} setPage={setPage} />
            </div>
          ) : (
            <>
              <div className="flex w-[24rem] shrink-0 flex-col border-r">
                <ThreadList
                  mode={mode}
                  selectedId={selectedId}
                  onSelect={setSelectedId}
                  query={active}
                />
                <Pager query={active} page={page} setPage={setPage} />
              </div>
              <div className="min-w-0 flex-1">
                <ThreadView
                  threadId={selectedId}
                  onBack={() => setSelectedId(null)}
                  onDeleted={() => setSelectedId(null)}
                  onReply={openCompose}
                />
              </div>
            </>
          )}
        </div>
      </div>

      {compose ? (
        <DockedCompose
          key={composeSeq}
          state={compose}
          onClose={() => setCompose(null)}
          onSent={(threadId) => {
            if (compose.mode === 'new') {
              switchFolder('sent');
            }
            setSelectedId(threadId);
          }}
        />
      ) : null}
    </div>
  );
}

type ListMode = Folder | 'search';

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
        'flex items-center gap-3 rounded-xl px-3.5 py-2.5 text-sm font-medium transition-colors',
        active
          ? 'bg-surface-tint text-primary'
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

interface ListQuery {
  isLoading: boolean;
  isError: boolean;
  error: ApiError | null;
  data?: ThreadPage;
}

function ThreadList({
  mode,
  selectedId,
  onSelect,
  query,
}: {
  mode: ListMode;
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
    const emptyIcon =
      mode === 'search'
        ? Search
        : mode === 'inbox'
          ? Inbox
          : mode === 'starred'
            ? Star
            : mode === 'archived'
              ? Archive
              : Send;
    return (
      <EmptyState
        icon={emptyIcon}
        title={
          mode === 'search'
            ? 'No results'
            : mode === 'inbox'
              ? 'No conversations yet'
              : mode === 'starred'
                ? 'No starred conversations'
                : mode === 'archived'
                  ? 'No archived conversations'
                  : 'Nothing sent yet'
        }
        description={
          mode === 'search'
            ? 'Try a different word from a subject or message.'
            : mode === 'inbox'
              ? 'Conversations from your contacts will appear here.'
              : mode === 'starred'
                ? 'Star a conversation to keep it here. Only you can see your stars.'
                : mode === 'archived'
                  ? 'Archived conversations leave your Inbox but stay here (and in Sent/Search). A new reply brings one back.'
                  : 'Conversations you start will appear here.'
        }
        className="m-3 border-0 bg-transparent"
      />
    );
  }

  const listLabel =
    mode === 'inbox'
      ? 'Inbox'
      : mode === 'sent'
        ? 'Sent'
        : mode === 'starred'
          ? 'Starred'
          : mode === 'archived'
            ? 'Archive'
            : 'Search results';
  return (
    <ul className="flex-1 overflow-y-auto" aria-label={listLabel}>
      {rows.map((row) => (
        <ThreadRow key={row.threadId} row={row} selected={selectedId === row.threadId} onSelect={onSelect} />
      ))}
    </ul>
  );
}

function ThreadRow({
  row,
  selected,
  onSelect,
}: {
  row: ThreadListItem;
  selected: boolean;
  onSelect: (id: string) => void;
}) {
  const queryClient = useQueryClient();
  const refresh = React.useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: mailKeys.unread });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'inbox'] });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'sent'] });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'starred'] });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'search'] });
  }, [queryClient]);

  // Optimistically flip this thread's `starred` flag in every cached mail LIST page that holds it
  // (inbox/sent/starred/search). The guard makes this a no-op for the non-list mail caches
  // (unread-count, thread detail, contacts), which don't carry a `content` array.
  const patchStarred = React.useCallback(
    (next: boolean) => {
      queryClient.setQueriesData<ThreadPage>({ queryKey: ['mail'] }, (old) => {
        if (!old || !Array.isArray(old.content)) return old;
        if (!old.content.some((r) => r.threadId === row.threadId)) return old;
        return {
          ...old,
          content: old.content.map((r) =>
            r.threadId === row.threadId ? { ...r, starred: next } : r,
          ),
        };
      });
    },
    [queryClient, row.threadId],
  );

  const toggleRead = useApiMutation(
    () => (row.unread ? markThreadRead(row.threadId) : markThreadUnread(row.threadId)),
    { successMessage: row.unread ? 'Marked read' : 'Marked unread', onSuccess: refresh },
  );
  const del = useApiMutation(() => deleteThread(row.threadId), {
    successMessage: 'Removed from your mailbox',
    onSuccess: refresh,
  });
  const wasStarred = row.starred;
  const toggleStar = useApiMutation(
    () => (wasStarred ? unstarThread(row.threadId) : starThread(row.threadId)),
    {
      onMutate: () => patchStarred(!wasStarred), // optimistic: fill/outline immediately
      onError: () => patchStarred(wasStarred), // revert on failure (the error toast still fires)
      // The Starred view's membership changed — refetch it so an unstarred row drops out.
      onSettled: () => queryClient.invalidateQueries({ queryKey: ['mail', 'starred'] }),
    },
  );

  // Archive flips the `archived` flag everywhere the row appears AND removes it from the list it should
  // leave: archiving drops it from Inbox (hidden from Inbox only), unarchiving drops it from Archive.
  const patchArchived = React.useCallback(
    (next: boolean) => {
      queryClient.setQueriesData<ThreadPage>({ queryKey: ['mail'] }, (old) => {
        if (!old || !Array.isArray(old.content)) return old;
        if (!old.content.some((r) => r.threadId === row.threadId)) return old;
        return {
          ...old,
          content: old.content.map((r) =>
            r.threadId === row.threadId ? { ...r, archived: next } : r,
          ),
        };
      });
      // Membership: archived → leaves Inbox; unarchived → leaves Archive.
      queryClient.setQueriesData<ThreadPage>(
        { queryKey: ['mail', next ? 'inbox' : 'archived'] },
        (old) => {
          if (!old || !Array.isArray(old.content)) return old;
          return { ...old, content: old.content.filter((r) => r.threadId !== row.threadId) };
        },
      );
    },
    [queryClient, row.threadId],
  );
  const wasArchived = row.archived;
  const toggleArchive = useApiMutation(
    // Reconciliation lives INSIDE the mutation fn so it still runs after the row unmounts (archiving
    // removes it from the Inbox list, which unmounts this ThreadRow before onSettled could fire).
    async () => {
      try {
        await (wasArchived ? unarchiveThread(row.threadId) : archiveThread(row.threadId));
      } catch (e) {
        await queryClient.invalidateQueries({ queryKey: ['mail'] }); // failed — resync from server
        throw e;
      }
      // The membership-affected lists reconcile with the server (Inbox regains/loses it; Archive too).
      await queryClient.invalidateQueries({ queryKey: ['mail', 'inbox'] });
      await queryClient.invalidateQueries({ queryKey: ['mail', 'archived'] });
    },
    {
      successMessage: wasArchived ? 'Moved to Inbox' : 'Archived',
      onMutate: () => patchArchived(!wasArchived), // optimistic: remove from Inbox immediately
    },
  );

  const names = row.participants.map((p) => p.name).join(', ') || '(no one)';

  return (
    <li className="group relative">
      <button
        type="button"
        onClick={() => onSelect(row.threadId)}
        aria-current={selected ? 'true' : undefined}
        className={cn(
          'flex w-full flex-col gap-0.5 border-b px-4 py-3.5 pr-24 text-left transition-colors',
          selected ? 'bg-surface-tint' : 'hover:bg-accent/50',
        )}
      >
        <div className="flex items-baseline justify-between gap-2">
          <span
            className={cn(
              'truncate text-sm',
              row.unread ? 'font-semibold text-foreground' : 'font-medium text-foreground/90',
            )}
          >
            {names}
            {row.messageCount > 1 ? (
              <span className="ml-1 text-xs font-normal text-muted-foreground">({row.messageCount})</span>
            ) : null}
          </span>
          <span className="shrink-0 text-[11px] text-muted-foreground">{relativeTime(row.lastMessageAt)}</span>
        </div>
        <div className="flex items-center gap-2">
          {row.unread ? <span className="size-2 shrink-0 rounded-full bg-primary" aria-hidden /> : null}
          <span className={cn('truncate text-sm', row.unread ? 'font-medium text-foreground' : 'text-muted-foreground')}>
            {row.subject}
          </span>
          {row.hasAttachments ? (
            <Paperclip className="size-3.5 shrink-0 text-muted-foreground" aria-label="Has attachments" />
          ) : null}
        </div>
        {row.snippet ? <p className="truncate text-xs text-muted-foreground">{row.snippet}</p> : null}
      </button>

      {/* Star (per-user, thread-level): a filled amber star when starred is ALWAYS visible so you can
          see your stars at a glance; when unstarred it's an outline that appears on hover / focus. */}
      <button
        type="button"
        onClick={() => toggleStar.mutate()}
        disabled={toggleStar.isPending}
        aria-pressed={row.starred}
        aria-label={row.starred ? 'Unstar conversation' : 'Star conversation'}
        title={row.starred ? 'Starred — click to unstar' : 'Star this conversation'}
        className={cn(
          'absolute right-2 top-2 rounded p-1.5 transition-opacity hover:bg-background',
          row.starred
            ? 'text-amber-500 opacity-100 dark:text-amber-400'
            : 'text-muted-foreground opacity-0 hover:text-foreground focus:opacity-100 group-hover:opacity-100',
        )}
      >
        <Star className={cn('size-4', row.starred && 'fill-current')} />
      </button>

      {/* Read + delete (appear on hover / focus-within; always tappable on touch) */}
      <div className="absolute bottom-2 right-2 flex gap-0.5 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
        <button
          type="button"
          onClick={() => toggleRead.mutate()}
          disabled={toggleRead.isPending}
          aria-label={row.unread ? 'Mark read' : 'Mark unread'}
          title={row.unread ? 'Mark read' : 'Mark unread'}
          className="rounded p-1.5 text-muted-foreground hover:bg-background hover:text-foreground"
        >
          {row.unread ? <MailOpen className="size-4" /> : <MailMinus className="size-4" />}
        </button>
        <button
          type="button"
          onClick={() => toggleArchive.mutate()}
          disabled={toggleArchive.isPending}
          aria-label={row.archived ? 'Move to Inbox' : 'Archive'}
          title={row.archived ? 'Move to Inbox' : 'Archive (hide from Inbox)'}
          className="rounded p-1.5 text-muted-foreground hover:bg-background hover:text-foreground"
        >
          {row.archived ? <ArchiveRestore className="size-4" /> : <Archive className="size-4" />}
        </button>
        <button
          type="button"
          onClick={() => {
            if (window.confirm('Remove this conversation from YOUR mailbox? The other person keeps their copy.'))
              del.mutate();
          }}
          disabled={del.isPending}
          aria-label="Delete for me"
          title="Delete for me"
          className="rounded p-1.5 text-muted-foreground hover:bg-background hover:text-destructive"
        >
          <Trash2 className="size-4" />
        </button>
      </div>
    </li>
  );
}

function Pager({
  query,
  page,
  setPage,
}: {
  query: { data?: { totalPages?: number } };
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
        <Button variant="ghost" size="sm" disabled={page <= 0} onClick={() => setPage(Math.max(0, page - 1))}>
          <ChevronLeft />
        </Button>
        <Button variant="ghost" size="sm" disabled={page >= totalPages - 1} onClick={() => setPage(page + 1)}>
          <ChevronRight />
        </Button>
      </div>
    </div>
  );
}

/** Mobile: show the list, or the reading pane once a conversation is opened. */
function MobilePanes({
  mode,
  selectedId,
  setSelectedId,
  active,
  page,
  setPage,
  onReply,
}: {
  mode: ListMode;
  selectedId: string | null;
  setSelectedId: (id: string | null) => void;
  active: ListQuery;
  page: number;
  setPage: (n: number) => void;
  onReply: (state: ComposeState) => void;
}) {
  if (selectedId) {
    return (
      <ThreadView
        threadId={selectedId}
        onBack={() => setSelectedId(null)}
        onDeleted={() => setSelectedId(null)}
        onReply={onReply}
      />
    );
  }
  return (
    <div className="flex flex-1 flex-col">
      <ThreadList mode={mode} selectedId={selectedId} onSelect={setSelectedId} query={active} />
      <Pager query={active} page={page} setPage={setPage} />
    </div>
  );
}

// --- Drafts (author-private, unsent; §8) ------------------------------------

/** Reconstruct the composer state from a draft so it reopens prefilled + bound to its id. */
function composeStateFromDraft(d: Draft): ComposeState {
  const initialAttachments = d.attachments.map((a) => ({
    attachmentId: a.id,
    name: a.fileName,
    size: a.sizeBytes,
  }));
  if (d.replyToThreadId) {
    return {
      mode: d.replyAll ? 'replyAll' : 'reply',
      threadId: d.replyToThreadId,
      subject: d.subject ?? undefined,
      recipients: d.to,
      draftId: d.id,
      initialBody: d.body ?? '',
      initialAttachments,
    };
  }
  return {
    mode: 'new',
    draftId: d.id,
    initialTo: d.to,
    initialCc: d.cc,
    initialBcc: d.bcc,
    initialSubject: d.subject ?? '',
    initialBody: d.body ?? '',
    initialAttachments,
  };
}

interface DraftQuery {
  isLoading: boolean;
  isError: boolean;
  error: ApiError | null;
  data?: DraftPage;
}

function DraftsList({ query, onOpen }: { query: DraftQuery; onOpen: (id: string) => void }) {
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
        title="Couldn’t load drafts"
        description={query.error?.message ?? 'Please try again.'}
        className="m-3 border-0 bg-transparent"
      />
    );
  }
  const rows = query.data?.content ?? [];
  if (rows.length === 0) {
    return (
      <EmptyState
        icon={FileText}
        title="No drafts"
        description="Messages you save without sending appear here. Only you can see your drafts."
        className="m-3 border-0 bg-transparent"
      />
    );
  }
  return (
    <ul className="flex-1 overflow-y-auto" aria-label="Drafts">
      {rows.map((row) => (
        <DraftRow key={row.id} row={row} onOpen={onOpen} />
      ))}
    </ul>
  );
}

function DraftRow({ row, onOpen }: { row: DraftListItem; onOpen: (id: string) => void }) {
  const queryClient = useQueryClient();
  const discard = useApiMutation(() => deleteDraft(row.id), {
    successMessage: 'Draft discarded',
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['mail', 'drafts'] }),
  });

  const names = row.recipients.map((p) => p.name).join(', ');
  const subject = row.subject && row.subject.trim() ? row.subject : '(no subject)';

  return (
    <li className="group relative">
      <button
        type="button"
        onClick={() => onOpen(row.id)}
        className="flex w-full flex-col gap-0.5 border-b px-4 py-3.5 pr-12 text-left transition-colors hover:bg-accent/50"
      >
        <div className="flex items-baseline justify-between gap-2">
          <span className="truncate text-sm font-medium text-foreground/90">
            {names || <span className="text-muted-foreground">(no recipients)</span>}
          </span>
          <span className="shrink-0 text-[11px] text-muted-foreground">{relativeTime(row.updatedAt)}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="rounded bg-amber-100 px-1.5 text-[10px] font-semibold uppercase tracking-wide text-amber-700 dark:bg-amber-500/15 dark:text-amber-400">
            Draft
          </span>
          <span className="truncate text-sm text-foreground">{subject}</span>
          {row.hasAttachments ? (
            <Paperclip className="size-3.5 shrink-0 text-muted-foreground" aria-label="Has attachments" />
          ) : null}
        </div>
        {row.snippet ? <p className="truncate text-xs text-muted-foreground">{row.snippet}</p> : null}
      </button>

      <div className="absolute right-2 top-1/2 -translate-y-1/2 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
        <button
          type="button"
          onClick={() => {
            if (window.confirm('Discard this draft? It will be permanently deleted.')) discard.mutate();
          }}
          disabled={discard.isPending}
          aria-label="Discard draft"
          title="Discard draft"
          className="rounded p-1.5 text-muted-foreground hover:bg-background hover:text-destructive"
        >
          <Trash2 className="size-4" />
        </button>
      </div>
    </li>
  );
}

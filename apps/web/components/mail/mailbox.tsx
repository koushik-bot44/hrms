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
  MoreHorizontal,
  Paperclip,
  Pencil,
  Plus,
  Search,
  Send,
  SlidersHorizontal,
  SquarePen,
  Star,
  Tag,
  Trash2,
  X,
} from 'lucide-react';
import type {
  Draft,
  DraftListItem,
  DraftPage,
  MailLabel,
  ThreadListItem,
  ThreadPage,
} from '@/lib/contract';
import type { MailFilters, MailScope } from '@/lib/api/mail';
import { LabelChips, LabelPicker } from '@/components/mail/label-picker';
import { SidebarWaves } from '@/components/sidebar-waves';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import type { ApiError } from '@/lib/api/client';
import {
  archiveThread,
  createLabel,
  deleteDraft,
  deleteLabel,
  deleteThread,
  getArchived,
  getDraft,
  getDrafts,
  getInbox,
  getLabels,
  getLabelThreads,
  getSent,
  getStarred,
  getUnreadCount,
  hasActiveFilters,
  mailKeys,
  markThreadRead,
  markThreadUnread,
  renameLabel,
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

const EMPTY_FILTERS: MailFilters = { scope: 'ALL' };

const SCOPE_LABELS: Record<MailScope, string> = {
  ALL: 'All mail',
  INBOX: 'Inbox',
  SENT: 'Sent',
  STARRED: 'Starred',
  ARCHIVE: 'Archive',
};

/** The full webmail client (§8, Stage 3): Inbox/Sent thread lists + search + filters + reading pane + compose. */
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
  // Committed filters (drive the query) + the draft being edited in the filter bar.
  const [filters, setFilters] = React.useState<MailFilters>(EMPTY_FILTERS);
  const [filterDraft, setFilterDraft] = React.useState<MailFilters>(EMPTY_FILTERS);
  const [showFilters, setShowFilters] = React.useState(false);
  // The label view currently open (a tag overlay list), or null.
  const [labelView, setLabelView] = React.useState<MailLabel | null>(null);
  const filtersActive = hasActiveFilters(filters);
  const searching = searchTerm.trim().length > 0 || filtersActive;

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
    enabled: !searching && !labelView && folder === 'inbox',
  });
  const sent = useApiQuery(mailKeys.sent(page), (s) => getSent(page, 20, s), {
    enabled: !searching && !labelView && folder === 'sent',
  });
  const starred = useApiQuery(mailKeys.starred(page), (s) => getStarred(page, 20, s), {
    enabled: !searching && !labelView && folder === 'starred',
  });
  const archivedList = useApiQuery(mailKeys.archived(page), (s) => getArchived(page, 20, s), {
    enabled: !searching && !labelView && folder === 'archived',
  });
  const draftsList = useApiQuery(mailKeys.drafts(page), (s) => getDrafts(page, 20, s), {
    enabled: !searching && !labelView && folder === 'drafts',
  });
  const labelThreadsQuery = useApiQuery(
    mailKeys.labelThreads(labelView?.id ?? '__none__', page),
    (s) => getLabelThreads(labelView!.id, page, 20, s),
    { enabled: !searching && labelView != null },
  );
  const labelsQuery = useApiQuery(mailKeys.labels, getLabels);
  const results = useApiQuery(
    mailKeys.search(searchTerm, filters, page),
    (s) => searchMail(searchTerm, filters, page, 20, s),
    { enabled: searching },
  );

  const isDrafts = !searching && !labelView && folder === 'drafts';

  const openLabel = (label: MailLabel) => {
    setLabelView(label);
    setPage(0);
    setSelectedId(null);
    setSearchTerm('');
    setSearchInput('');
    setFilters(EMPTY_FILTERS);
    setShowFilters(false);
  };

  const applyFilters = () => {
    setFilters(filterDraft);
    setPage(0);
    setSelectedId(null);
    setShowFilters(false);
  };
  const clearFilters = () => {
    setFilters(EMPTY_FILTERS);
    setFilterDraft(EMPTY_FILTERS);
    setPage(0);
    setShowFilters(false);
  };
  const toggleFilters = () => {
    setFilterDraft(filters); // start the bar from the committed filters
    setShowFilters((v) => !v);
  };
  const removeFilter = (patch: MailFilters) => {
    const next = { ...filters, ...patch };
    setFilters(next);
    setFilterDraft(next);
    setPage(0);
  };

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
    setFilters(EMPTY_FILTERS);
    setFilterDraft(EMPTY_FILTERS);
    setShowFilters(false);
    setLabelView(null);
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
    setFilters(EMPTY_FILTERS);
    setFilterDraft(EMPTY_FILTERS);
    setShowFilters(false);
    setPage(0);
    setSelectedId(null);
  };

  const active = searching
    ? results
    : labelView
      ? labelThreadsQuery
      : folder === 'inbox'
        ? inbox
        : folder === 'sent'
          ? sent
          : folder === 'starred'
            ? starred
            : archivedList;
  const unreadCount = unread.data?.unread ?? 0;
  const mode: ListMode = searching ? 'search' : labelView ? 'label' : folder;

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

        <div className="hidden max-w-md flex-1 items-center gap-2 sm:flex">
          <form onSubmit={submitSearch} className="relative flex-1">
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
                onClick={() => {
                  setSearchInput('');
                  setSearchTerm('');
                }}
                aria-label="Clear search text"
                className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                <X className="size-4" />
              </button>
            ) : null}
          </form>
          <Button
            type="button"
            variant={showFilters || filtersActive ? 'secondary' : 'ghost'}
            size="sm"
            onClick={toggleFilters}
            aria-pressed={showFilters}
            className="shrink-0"
          >
            <SlidersHorizontal />
            Filters
            {countActiveFilters(filters) > 0 ? (
              <span className="ml-0.5 rounded-full bg-primary px-1.5 text-[11px] font-semibold text-primary-foreground">
                {countActiveFilters(filters)}
              </span>
            ) : null}
          </Button>
        </div>

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

      {/* Search filter bar (toggle) + active-filter chips */}
      {showFilters ? (
        <FilterBar
          draft={filterDraft}
          setDraft={setFilterDraft}
          onApply={applyFilters}
          onClear={clearFilters}
          onClose={() => setShowFilters(false)}
        />
      ) : null}
      {filtersActive && !showFilters ? (
        <FilterChips filters={filters} onRemove={removeFilter} onClear={clearFilters} />
      ) : null}

      {/* Body */}
      <div className="flex min-h-0 flex-1">
        {/* Left rail — the same deep indigo-navy surface as the app sidebar. */}
        <aside className="relative hidden w-52 shrink-0 flex-col gap-1 overflow-hidden border-r border-sidebar-border bg-sidebar p-3 text-sidebar-foreground md:flex">
          <div className="sidebar-gradient pointer-events-none absolute inset-x-0 bottom-0 h-48" aria-hidden />
          <SidebarWaves />
          <Button className="relative mb-2 justify-start" onClick={() => openCompose({ mode: 'new' })}>
            <SquarePen />
            Compose
          </Button>
          <FolderButton
            icon={Inbox}
            label="Inbox"
            active={!searching && !labelView && folder === 'inbox'}
            badge={unreadCount}
            onClick={() => switchFolder('inbox')}
          />
          <FolderButton
            icon={Send}
            label="Sent"
            active={!searching && !labelView && folder === 'sent'}
            onClick={() => switchFolder('sent')}
          />
          <FolderButton
            icon={Star}
            label="Starred"
            active={!searching && !labelView && folder === 'starred'}
            onClick={() => switchFolder('starred')}
          />
          <FolderButton
            icon={Archive}
            label="Archive"
            active={!searching && !labelView && folder === 'archived'}
            onClick={() => switchFolder('archived')}
          />
          <FolderButton
            icon={FileText}
            label="Drafts"
            active={!searching && !labelView && folder === 'drafts'}
            onClick={() => switchFolder('drafts')}
          />
          <LabelRail
            labels={labelsQuery.data ?? []}
            activeId={labelView?.id ?? null}
            onOpen={openLabel}
            onChanged={(deletedId) => {
              if (deletedId && labelView?.id === deletedId) switchFolder('inbox');
            }}
          />
          {searching ? (
            <div className="relative mt-2 flex items-center justify-between rounded-md bg-white/5 px-3 py-2 text-xs text-sidebar-muted">
              <span className="truncate">Results for “{searchTerm}”</span>
              <button
                type="button"
                onClick={clearSearch}
                aria-label="Clear search"
                className="hover:text-sidebar-foreground"
              >
                <X className="size-3.5" />
              </button>
            </div>
          ) : null}
        </aside>

        {/* Mobile controls */}
        <div className="flex w-full flex-col md:hidden">
          <div className="flex items-center gap-2 border-b p-2">
            <Button
              variant={!searching && !labelView && folder === 'inbox' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('inbox')}
            >
              <Inbox />
              Inbox{unreadCount > 0 ? ` (${unreadCount})` : ''}
            </Button>
            <Button
              variant={!searching && !labelView && folder === 'sent' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('sent')}
            >
              <Send />
              Sent
            </Button>
            <Button
              variant={!searching && !labelView && folder === 'starred' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('starred')}
            >
              <Star />
              Starred
            </Button>
            <Button
              variant={!searching && !labelView && folder === 'archived' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => switchFolder('archived')}
            >
              <Archive />
              Archive
            </Button>
            <Button
              variant={!searching && !labelView && folder === 'drafts' ? 'secondary' : 'ghost'}
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
          <div className="flex items-center gap-2 border-b p-2">
            <form onSubmit={submitSearch} className="relative flex-1">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                type="search"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Search your mail"
                className="pl-8"
              />
            </form>
            <Button
              type="button"
              variant={showFilters || filtersActive ? 'secondary' : 'ghost'}
              size="sm"
              onClick={toggleFilters}
              className="shrink-0"
            >
              <SlidersHorizontal />
              {countActiveFilters(filters) > 0 ? countActiveFilters(filters) : ''}
            </Button>
          </div>
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

type ListMode = Folder | 'search' | 'label';

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
        'relative flex items-center gap-3 rounded-xl px-3.5 py-2.5 text-sm font-medium transition-colors duration-150',
        active
          ? 'bg-sidebar-active text-sidebar-active-foreground shadow-sm'
          : 'text-sidebar-muted hover:bg-white/5 hover:text-sidebar-foreground',
      )}
    >
      <Icon className="size-4 shrink-0" />
      <span className="flex-1 text-left">{label}</span>
      {badge && badge > 0 ? (
        <span
          className={cn(
            'rounded-full px-1.5 text-[11px] font-semibold',
            active ? 'bg-white/20 text-sidebar-active-foreground' : 'bg-primary text-primary-foreground',
          )}
        >
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
              : mode === 'label'
                ? Tag
                : Send;
    return (
      <EmptyState
        icon={emptyIcon}
        title={
          mode === 'search'
            ? 'No mail matches'
            : mode === 'inbox'
              ? 'No conversations yet'
              : mode === 'starred'
                ? 'No starred conversations'
                : mode === 'archived'
                  ? 'No archived conversations'
                  : mode === 'label'
                    ? 'No conversations with this label'
                    : 'Nothing sent yet'
        }
        description={
          mode === 'search'
            ? 'No mail matches your search and filters. Try broadening the text or removing a filter.'
            : mode === 'inbox'
              ? 'Conversations from your contacts will appear here.'
              : mode === 'starred'
                ? 'Star a conversation to keep it here. Only you can see your stars.'
                : mode === 'archived'
                  ? 'Archived conversations leave your Inbox but stay here (and in Sent/Search). A new reply brings one back.'
                  : mode === 'label'
                    ? 'Tag a conversation with this label (from a row or the reading pane) and it shows up here — the conversation stays in Inbox too.'
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
            : mode === 'label'
              ? 'Label'
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
        {row.labels.length > 0 ? (
          <div className="mt-1 flex flex-wrap items-center gap-1">
            <LabelChips labels={row.labels} />
          </div>
        ) : null}
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

      {/* Label + read + delete (appear on hover / focus-within; always tappable on touch) */}
      <div className="absolute bottom-2 right-2 flex gap-0.5 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
        <LabelPicker threadId={row.threadId} labels={row.labels}>
          <button
            type="button"
            aria-label="Label conversation"
            title="Label"
            className="rounded p-1.5 text-muted-foreground hover:bg-background hover:text-foreground"
          >
            <Tag className="size-4" />
          </button>
        </LabelPicker>
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
          <span className="rounded bg-warning/10 px-1.5 text-[10px] font-semibold uppercase tracking-wide text-warning">
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

// --- Search filters UI (§8) -------------------------------------------------

function countActiveFilters(f: MailFilters): number {
  let n = 0;
  if (f.from?.trim()) n++;
  if (f.after) n++;
  if (f.before) n++;
  if (f.hasAttachment) n++;
  if (f.unread) n++;
  if (f.starred) n++;
  if (f.scope && f.scope !== 'ALL') n++;
  return n;
}

function FilterBar({
  draft,
  setDraft,
  onApply,
  onClear,
  onClose,
}: {
  draft: MailFilters;
  setDraft: React.Dispatch<React.SetStateAction<MailFilters>>;
  onApply: () => void;
  onClear: () => void;
  onClose: () => void;
}) {
  const set = (patch: Partial<MailFilters>) => setDraft((d) => ({ ...d, ...patch }));
  return (
    <div className="border-b bg-muted/40 px-4 py-3 md:px-6">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          onApply();
        }}
        className="flex flex-wrap items-end gap-x-4 gap-y-3"
      >
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-muted-foreground">From (name or address)</span>
          <Input
            value={draft.from ?? ''}
            onChange={(e) => set({ from: e.target.value })}
            placeholder="e.g. steve"
            className="h-9 w-48"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-muted-foreground">After</span>
          <Input
            type="date"
            value={draft.after ?? ''}
            onChange={(e) => set({ after: e.target.value || undefined })}
            className="h-9 w-40"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-muted-foreground">Before</span>
          <Input
            type="date"
            value={draft.before ?? ''}
            onChange={(e) => set({ before: e.target.value || undefined })}
            className="h-9 w-40"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-muted-foreground">In</span>
          <select
            value={draft.scope ?? 'ALL'}
            onChange={(e) => set({ scope: e.target.value as MailScope })}
            className="h-9 rounded-md border border-input bg-background px-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {(Object.keys(SCOPE_LABELS) as MailScope[]).map((s) => (
              <option key={s} value={s}>
                {SCOPE_LABELS[s]}
              </option>
            ))}
          </select>
        </label>
        <div className="flex items-center gap-4 pb-2">
          <CheckboxLabel checked={!!draft.hasAttachment} onChange={(v) => set({ hasAttachment: v })}>
            Has attachment
          </CheckboxLabel>
          <CheckboxLabel checked={!!draft.unread} onChange={(v) => set({ unread: v })}>
            Unread
          </CheckboxLabel>
          <CheckboxLabel checked={!!draft.starred} onChange={(v) => set({ starred: v })}>
            Starred
          </CheckboxLabel>
        </div>
        <div className="ml-auto flex items-center gap-2 pb-1">
          <Button type="submit" size="sm">
            Apply
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={onClear}>
            Clear
          </Button>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close filters"
            className="rounded p-1.5 text-muted-foreground hover:bg-background hover:text-foreground"
          >
            <X className="size-4" />
          </button>
        </div>
      </form>
    </div>
  );
}

function CheckboxLabel({
  checked,
  onChange,
  children,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  children: React.ReactNode;
}) {
  return (
    <label className="flex cursor-pointer select-none items-center gap-1.5 text-sm">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="size-4 rounded border-input accent-[hsl(var(--primary))]"
      />
      {children}
    </label>
  );
}

function FilterChips({
  filters,
  onRemove,
  onClear,
}: {
  filters: MailFilters;
  onRemove: (patch: MailFilters) => void;
  onClear: () => void;
}) {
  const chips: { label: string; clear: MailFilters }[] = [];
  if (filters.from?.trim()) chips.push({ label: `From: ${filters.from}`, clear: { from: undefined } });
  if (filters.after) chips.push({ label: `After: ${filters.after}`, clear: { after: undefined } });
  if (filters.before) chips.push({ label: `Before: ${filters.before}`, clear: { before: undefined } });
  if (filters.hasAttachment) chips.push({ label: 'Has attachment', clear: { hasAttachment: false } });
  if (filters.unread) chips.push({ label: 'Unread', clear: { unread: false } });
  if (filters.starred) chips.push({ label: 'Starred', clear: { starred: false } });
  if (filters.scope && filters.scope !== 'ALL') {
    chips.push({ label: `In: ${SCOPE_LABELS[filters.scope]}`, clear: { scope: 'ALL' } });
  }
  if (chips.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-2 border-b bg-card px-4 py-2 md:px-6">
      <span className="text-xs text-muted-foreground">Filters:</span>
      {chips.map((c) => (
        <span
          key={c.label}
          className="inline-flex items-center gap-1 rounded-full bg-surface-tint px-2.5 py-0.5 text-xs font-medium text-primary"
        >
          {c.label}
          <button
            type="button"
            onClick={() => onRemove(c.clear)}
            aria-label={`Remove filter ${c.label}`}
            className="hover:text-foreground"
          >
            <X className="size-3" />
          </button>
        </span>
      ))}
      <button
        type="button"
        onClick={onClear}
        className="ml-1 text-xs text-muted-foreground underline-offset-2 hover:underline"
      >
        Clear all
      </button>
    </div>
  );
}

// --- Labels rail (author-private tags; §8) ----------------------------------

function LabelRail({
  labels,
  activeId,
  onOpen,
  onChanged,
}: {
  labels: MailLabel[];
  activeId: string | null;
  onOpen: (label: MailLabel) => void;
  onChanged: (deletedId?: string) => void;
}) {
  const queryClient = useQueryClient();
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: mailKeys.labels });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'label-threads'] });
  };
  const create = useApiMutation(
    async () => {
      const name = window.prompt('New label name')?.trim();
      return name ? createLabel(name) : null;
    },
    { onSuccess: (l) => (l ? invalidate() : undefined) },
  );
  const rename = useApiMutation(
    (v: { id: string; current: string }) => {
      const name = window.prompt('Rename label', v.current)?.trim();
      return name && name !== v.current ? renameLabel(v.id, name) : Promise.resolve(null);
    },
    { onSuccess: (l) => (l ? invalidate() : undefined) },
  );
  const remove = useApiMutation((id: string) => deleteLabel(id), {
    successMessage: 'Label deleted',
    onSuccess: (_d, id) => {
      invalidate();
      onChanged(id);
    },
  });

  return (
    <div className="relative mt-3 border-t border-sidebar-border pt-3">
      <div className="mb-1 flex items-center justify-between px-3">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-sidebar-muted">
          Labels
        </span>
        <button
          type="button"
          onClick={() => create.mutate()}
          aria-label="New label"
          title="New label"
          className="rounded p-1 text-sidebar-muted transition-colors hover:bg-white/5 hover:text-sidebar-foreground"
        >
          <Plus className="size-4" />
        </button>
      </div>
      {labels.length === 0 ? (
        <p className="px-3.5 py-1 text-xs text-sidebar-muted">No labels yet.</p>
      ) : (
        labels.map((l) => (
          <div
            key={l.id}
            className={cn(
              'group/label flex items-center rounded-xl',
              activeId === l.id ? 'bg-sidebar-active' : 'hover:bg-white/5',
            )}
          >
            <button
              type="button"
              onClick={() => onOpen(l)}
              aria-current={activeId === l.id ? 'page' : undefined}
              className={cn(
                'flex min-w-0 flex-1 items-center gap-3 px-3.5 py-2 text-sm',
                activeId === l.id
                  ? 'font-medium text-sidebar-active-foreground'
                  : 'text-sidebar-muted',
              )}
            >
              <Tag className="size-4 shrink-0" />
              <span className="flex-1 truncate text-left">{l.name}</span>
              {l.threadCount > 0 ? (
                <span
                  className={cn(
                    'text-[11px]',
                    activeId === l.id ? 'text-sidebar-active-foreground/80' : 'text-sidebar-muted',
                  )}
                >
                  {l.threadCount}
                </span>
              ) : null}
            </button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  aria-label={`Manage ${l.name}`}
                  className={cn(
                    'mr-1 rounded p-1 opacity-0 transition-opacity hover:bg-white/10 focus:opacity-100 group-hover/label:opacity-100',
                    activeId === l.id
                      ? 'text-sidebar-active-foreground/80 hover:text-sidebar-active-foreground'
                      : 'text-sidebar-muted hover:text-sidebar-foreground',
                  )}
                >
                  <MoreHorizontal className="size-4" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={() => rename.mutate({ id: l.id, current: l.name })}>
                  <Pencil className="size-4" />
                  Rename
                </DropdownMenuItem>
                <DropdownMenuItem
                  className="text-destructive focus:text-destructive"
                  onSelect={() => {
                    if (
                      window.confirm(
                        `Delete label “${l.name}”? Conversations keep their place — they just lose this tag.`,
                      )
                    )
                      remove.mutate(l.id);
                  }}
                >
                  <Trash2 className="size-4" />
                  Delete
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        ))
      )}
    </div>
  );
}

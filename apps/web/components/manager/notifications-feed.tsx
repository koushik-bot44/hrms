'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  Bell,
  CalendarDays,
  CheckCheck,
  ClipboardCheck,
  FileCheck2,
  ThumbsDown,
  ThumbsUp,
  UserPlus,
  type LucideIcon,
} from 'lucide-react';
import type { NotificationFeed, NotificationItem } from '@/lib/contract';
import { getNotifications, markAllNotificationsRead, markNotificationRead } from '@/lib/api/manager';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { cn } from '@/lib/utils';

const KEY = ['manager-notifications'] as const;

const ICONS: Record<string, LucideIcon> = {
  EMPLOYEE_ONBOARDED: UserPlus,
  EMPLOYEE_SUBMITTED: FileCheck2,
  APPROVAL_REQUESTED: ClipboardCheck,
  EMPLOYEE_APPROVED: ThumbsUp,
  EMPLOYEE_REJECTED: ThumbsDown,
  LEAVE_REQUESTED: CalendarDays,
};

function message(n: NotificationItem): string {
  const who = n.fullName ?? n.employeeCode ?? 'An employee';
  switch (n.type) {
    case 'EMPLOYEE_ONBOARDED':
      return `${who} was onboarded`;
    case 'EMPLOYEE_SUBMITTED':
      return `${who} submitted their record`;
    case 'APPROVAL_REQUESTED':
      return `${who} is ready for your approval`;
    case 'EMPLOYEE_APPROVED':
      return `${who} was approved`;
    case 'EMPLOYEE_REJECTED':
      return `${who} was rejected`;
    case 'LEAVE_REQUESTED':
      return `${who} requested leave`;
    default:
      return `Update for ${who}`;
  }
}

function formatTime(iso: string): string {
  const minutes = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

function patchRead(feed: NotificationFeed, id: string): NotificationFeed {
  let dropped = 0;
  const notifications = feed.notifications.map((n) => {
    if (n.id === id && !n.read) {
      dropped = 1;
      return { ...n, read: true };
    }
    return n;
  });
  return { notifications, unreadCount: Math.max(0, feed.unreadCount - dropped) };
}

export function NotificationsFeed() {
  const queryClient = useQueryClient();
  const query = useApiQuery(KEY, getNotifications);

  const markRead = useApiMutation((id: string) => markNotificationRead(id), {
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: KEY });
      const prev = queryClient.getQueryData<NotificationFeed>(KEY);
      if (prev) queryClient.setQueryData<NotificationFeed>(KEY, patchRead(prev, id));
      return { prev };
    },
    onError: (_error, _id, context) => {
      const ctx = context as { prev?: NotificationFeed } | undefined;
      if (ctx?.prev) queryClient.setQueryData(KEY, ctx.prev);
    },
  });

  const markAll = useApiMutation(() => markAllNotificationsRead(), {
    successMessage: 'Marked all as read',
    onSuccess: (feed) => queryClient.setQueryData(KEY, feed),
  });

  if (query.isLoading) return <FeedSkeleton />;

  const feed = query.data;
  if (!feed || feed.notifications.length === 0) {
    return (
      <EmptyState
        icon={Bell}
        title="You're all caught up"
        description="Onboarding and verification updates for your team will appear here."
      />
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          {feed.unreadCount > 0 ? `${feed.unreadCount} unread` : 'No unread notifications'}
        </p>
        <Button
          variant="ghost"
          size="sm"
          disabled={feed.unreadCount === 0 || markAll.isPending}
          onClick={() => markAll.mutate()}
        >
          <CheckCheck />
          Mark all read
        </Button>
      </div>
      <ul className="space-y-1.5">
        {feed.notifications.map((n) => {
          const Icon = ICONS[n.type] ?? Bell;
          return (
            <li key={n.id}>
              <button
                type="button"
                onClick={() => !n.read && markRead.mutate(n.id)}
                aria-label={n.read ? message(n) : `${message(n)} (unread — mark read)`}
                className={cn(
                  'flex w-full items-start gap-3 rounded-md border border-border p-3 text-left transition-colors',
                  n.read ? 'bg-background' : 'bg-accent/40 hover:bg-accent',
                )}
              >
                <span
                  className={cn(
                    'mt-1 size-2 shrink-0 rounded-full',
                    n.read ? 'bg-transparent' : 'bg-primary',
                  )}
                  aria-hidden
                />
                <Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className={cn('text-sm', n.read ? 'text-foreground' : 'font-medium')}>
                    {message(n)}
                  </p>
                  <p className="text-xs text-muted-foreground">{formatTime(n.createdAt)}</p>
                </div>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function FeedSkeleton() {
  return (
    <div className="space-y-1.5">
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-14 w-full" />
    </div>
  );
}

'use client';

import * as React from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { ArrowRight, Briefcase, Lock, LogIn, Mail, UserRound } from 'lucide-react';
import { BrandMark } from '@/components/brand-mark';
import { StaffLoginSchema, type LoginAudience, type StaffLoginInput } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { ApiError } from '@/lib/api/client';
import { AuthShell } from '@/components/auth/auth-shell';
import { Button } from '@/components/ui/button';
import { IconInput } from '@/components/ui/icon-input';

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

/** True when a failed login is the API's "right credential, wrong audience door" refusal (§6). */
function isPortalMismatch(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    typeof error.body === 'object' &&
    error.body !== null &&
    (error.body as { code?: string }).code === 'PORTAL_MISMATCH'
  );
}

/** The other-audience door to cross-link to (slugged). Null on the general top-level door. */
function otherDoor(audience: LoginAudience | undefined, slug?: string) {
  if (!slug) return null;
  if (audience === 'STAFF') return { href: `/${slug}/workspace/login`, label: 'Workspace employee? Sign in here' };
  if (audience === 'WORKSPACE') return { href: `/${slug}/login`, label: 'Staff member? Sign in here' };
  return null;
}

/**
 * The shared CREDENTIAL sign-in screen (email + password → session, routed by role/mailbox). Mounted by all
 * three credential doors — the general top-level `/login` (audience `undefined`; platform + legacy, no
 * restriction), the slugged STAFF door `/{slug}/login` (audience `STAFF`), and the slugged WORKSPACE door
 * `/{slug}/workspace/login` (audience `WORKSPACE`) — same component, no duplication. The audience is sent to the
 * API, which refuses a mismatched principal AFTER auth (§6); a mismatch surfaces here as an inline "wrong door"
 * notice with a cross-link to the correct door. The ONBOARDING door has its own OTP screen and no cross-links.
 */
export function StaffLoginScreen({
  slug,
  companyName,
  audience,
}: {
  slug?: string;
  companyName?: string;
  audience?: LoginAudience;
}) {
  const auth = useAuth();
  const router = useRouter();

  // Already signed in -> go to your area.
  React.useEffect(() => {
    if (auth.status === 'authenticated' && auth.session) {
      router.replace(homePathForSession(auth.session));
    }
  }, [auth.status, auth.session, router]);

  const cross = otherDoor(audience, slug);

  const framing =
    audience === 'WORKSPACE'
      ? {
          icon: <Briefcase className="size-6" />,
          title: 'Workspace sign-in',
          badge: 'Secure workspace access',
          description: companyName
            ? `Sign in to your ${companyName} workspace with your credentials.`
            : 'Sign in to your workspace with your credentials.',
        }
      : audience === 'STAFF'
        ? {
            icon: <UserRound className="size-6" />,
            title: 'Staff sign-in',
            badge: 'Secure staff access',
            description: companyName
              ? `Sign in to ${companyName} with your staff email and password.`
              : 'Sign in with your staff email and password.',
          }
        : {
            icon: <BrandMark size={48} rounded="rounded-2xl" />,
            title: 'Welcome to hrorg.in',
            badge: 'Secure staff access',
            description: 'Sign in to hrorg.in with your email and password.',
          };

  return (
    <AuthShell
      icon={framing.icon}
      title={framing.title}
      description={framing.description}
      badgeLabel={framing.badge}
      footer={
        cross ? (
          <Link href={cross.href} className="text-sm text-muted-foreground hover:text-foreground">
            {cross.label}
          </Link>
        ) : undefined
      }
    >
      <StaffSignInForm audience={audience} cross={cross} />
    </AuthShell>
  );
}

function StaffSignInForm({
  audience,
  cross,
}: {
  audience?: LoginAudience;
  cross: { href: string; label: string } | null;
}) {
  const auth = useAuth();
  const router = useRouter();
  const [mismatch, setMismatch] = React.useState<string | null>(null);
  const form = useForm<StaffLoginInput>({
    resolver: zodResolver(StaffLoginSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = form.handleSubmit(async (values) => {
    setMismatch(null);
    try {
      const session = await auth.login({ ...values, audience });
      toast.success('Signed in');
      router.replace(homePathForSession(session));
    } catch (error) {
      if (isPortalMismatch(error)) {
        // Right credential, wrong door — show the API's message inline + the cross-link (no toast).
        setMismatch(errorMessage(error, 'Please use the other sign-in door.'));
        return;
      }
      toast.error(errorMessage(error, 'Invalid email or password'));
    }
  });

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      {mismatch ? (
        <div
          role="alert"
          className="space-y-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3.5 py-3 text-sm"
        >
          <p className="text-foreground">{mismatch}</p>
          {cross ? (
            <Link
              href={cross.href}
              className="inline-flex items-center gap-1 font-medium text-primary hover:underline"
            >
              {audience === 'STAFF' ? 'Go to workspace sign-in' : 'Go to staff sign-in'}
              <ArrowRight className="size-3.5" />
            </Link>
          ) : null}
        </div>
      ) : null}
      <Field id="staff-email" label="Email" error={form.formState.errors.email?.message}>
        <IconInput
          icon={Mail}
          id="staff-email"
          type="text"
          inputMode="email"
          autoCapitalize="none"
          autoComplete="username"
          spellCheck={false}
          placeholder="you@company.com"
          aria-invalid={Boolean(form.formState.errors.email)}
          {...form.register('email')}
        />
      </Field>
      <Field id="staff-password" label="Password" error={form.formState.errors.password?.message}>
        <IconInput
          icon={Lock}
          id="staff-password"
          type="password"
          autoComplete="current-password"
          placeholder="••••••••"
          aria-invalid={Boolean(form.formState.errors.password)}
          {...form.register('password')}
        />
      </Field>
      <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
        <LogIn />
        {form.formState.isSubmitting ? 'Signing in…' : 'Sign in'}
      </Button>
    </form>
  );
}

function Field({
  id,
  label,
  error,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}

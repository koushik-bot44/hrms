'use client';

import * as React from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { Lock, LogIn, Mail } from 'lucide-react';
import { BrandMark } from '@/components/brand-mark';
import { StaffLoginSchema, type StaffLoginInput } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { ApiError } from '@/lib/api/client';
import { AuthShell } from '@/components/auth/auth-shell';
import { Button } from '@/components/ui/button';
import { IconInput } from '@/components/ui/icon-input';

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

/** Staff sign-in (§6): email + password → session, routed by role. Employees sign in elsewhere. */
export default function LoginPage() {
  const auth = useAuth();
  const router = useRouter();

  // Already signed in -> go to your area.
  React.useEffect(() => {
    if (auth.status === 'authenticated' && auth.session) {
      router.replace(homePathForSession(auth.session));
    }
  }, [auth.status, auth.session, router]);

  return (
    <AuthShell
      icon={<BrandMark size={48} rounded="rounded-2xl" />}
      title="Welcome to hrorg.in"
      description="Sign in to hrorg.in with your email and password."
      portalLabel="Integrated HR Management"
      badgeLabel="Secure staff access"
      footer={
        <Link href="/employee/login" className="text-sm text-muted-foreground hover:text-foreground">
          Onboarding employee? Sign in here
        </Link>
      }
    >
      <StaffSignInForm />
    </AuthShell>
  );
}

function StaffSignInForm() {
  const auth = useAuth();
  const router = useRouter();
  const form = useForm<StaffLoginInput>({
    resolver: zodResolver(StaffLoginSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const session = await auth.login(values);
      toast.success('Signed in');
      router.replace(homePathForSession(session));
    } catch (error) {
      toast.error(errorMessage(error, 'Invalid email or password'));
    }
  });

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
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

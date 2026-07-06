'use client';

import * as React from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { ShieldCheck } from 'lucide-react';
import { StaffLoginSchema, type StaffLoginInput } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { ApiError } from '@/lib/api/client';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Input } from '@/components/ui/input';

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
    <div className="flex min-h-dvh items-center justify-center bg-background p-6">
      <Card className="w-full max-w-md animate-fade-in">
        <CardHeader className="items-center text-center">
          <div className="mb-1 flex size-9 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <ShieldCheck className="size-5" />
          </div>
          <CardTitle className="text-lg">Staff sign-in</CardTitle>
          <CardDescription>Sign in to IHRMS with your email and password.</CardDescription>
        </CardHeader>
        <CardContent>
          <StaffSignInForm />
        </CardContent>
        <CardFooter className="justify-center">
          <Link
            href="/employee/login"
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            Onboarding employee? Sign in here
          </Link>
        </CardFooter>
      </Card>
    </div>
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
        <Input
          id="staff-email"
          type="email"
          autoComplete="email"
          placeholder="you@company.com"
          aria-invalid={Boolean(form.formState.errors.email)}
          {...form.register('email')}
        />
      </Field>
      <Field id="staff-password" label="Password" error={form.formState.errors.password?.message}>
        <Input
          id="staff-password"
          type="password"
          autoComplete="current-password"
          placeholder="••••••••"
          aria-invalid={Boolean(form.formState.errors.password)}
          {...form.register('password')}
        />
      </Field>
      <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
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

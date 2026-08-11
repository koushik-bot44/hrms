'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { ArrowLeft, KeyRound, Link2Off, Mail, Send, User, UserRound } from 'lucide-react';
import { OtpRequestSchema, OtpVerifySchema, type OtpRequestInput } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { ApiError } from '@/lib/api/client';
import { validateInvite } from '@/lib/api/auth';
import { AuthShell } from '@/components/auth/auth-shell';
import { Button } from '@/components/ui/button';
import { IconInput } from '@/components/ui/icon-input';

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

const OtpOnlySchema = OtpVerifySchema.pick({ otp: true });
type OtpOnlyInput = { otp: string };

type InviteState =
  | { status: 'validating' }
  | { status: 'valid'; token: string; email: string; companyName: string }
  | { status: 'invalid' };

/**
 * The shared employee sign-in screen (§3.2/§6). The onboarding door is now INVITE-LINK-ONLY: on mount it reads
 * the `token` from the emailed link and validates it against the API — a valid token reveals the OTP form
 * (full name + email → OTP → session); anything else (missing/expired/revoked/completed) shows an "invalid or
 * expired" state with NO form. The token is threaded into both OTP calls (the real gate — the UI alone is not
 * one). This ONBOARDING door lives solely at the slugged `/{slug}/employee/login?token=…` (the top-level
 * `/employee/login` is now the workspace-employee credential door, §6 two-door consolidation); `slug`/
 * `companyName` are display hints, and the validated context supplies the authoritative prefill email + name.
 */
export function EmployeeLoginScreen({ companyName }: { slug?: string; companyName?: string }) {
  const auth = useAuth();
  const router = useRouter();
  const [invite, setInvite] = React.useState<InviteState>({ status: 'validating' });

  // Already signed in → straight to the role home (no flash of the door).
  React.useEffect(() => {
    if (auth.status === 'authenticated' && auth.session) {
      router.replace(homePathForSession(auth.session));
    }
  }, [auth.status, auth.session, router]);

  // Validate the invite token from the URL before showing any form.
  React.useEffect(() => {
    const token = new URLSearchParams(window.location.search).get('token');
    if (!token) {
      setInvite({ status: 'invalid' });
      return;
    }
    let cancelled = false;
    validateInvite(token)
      .then((ctx) => {
        if (!cancelled) {
          setInvite({ status: 'valid', token, email: ctx.email, companyName: ctx.companyName });
        }
      })
      .catch(() => {
        if (!cancelled) setInvite({ status: 'invalid' });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // The onboarding door is link-only and audience-specific — it carries NO cross-links to the other doors
  // (it must not advertise itself, §6). The "invalid" state itself explains how to get a working link.

  if (invite.status === 'invalid') {
    return (
      <AuthShell
        icon={<Link2Off className="size-6" />}
        title="This invite link isn’t valid"
        description="Your onboarding link is invalid or has expired. Please open the most recent link your HR team emailed you — or ask them to resend your invitation."
        badgeLabel="Invite required"
      >
        <p className="rounded-md bg-muted px-3.5 py-3 text-sm text-muted-foreground">
          Onboarding sign-in is only available through the secure link in your invitation email. If you need a
          new one, your HR contact can resend it.
        </p>
      </AuthShell>
    );
  }

  if (invite.status === 'validating') {
    return (
      <AuthShell
        icon={<UserRound className="size-6" />}
        title="Welcome to hrorg.in"
        description="Checking your invitation…"
        badgeLabel="Secure employee access"
      >
        <div className="h-24 animate-pulse rounded-md bg-muted" role="status" aria-label="Validating invite" />
      </AuthShell>
    );
  }

  const shownCompany = invite.companyName || companyName;
  return (
    <AuthShell
      icon={<UserRound className="size-6" />}
      title="Welcome to hrorg.in"
      description={
        shownCompany
          ? `Enter your full name and email to sign in to ${shownCompany}.`
          : "Enter your full name and email and we'll send you a one-time code."
      }
      badgeLabel="Secure employee access"
    >
      <SignInForm token={invite.token} prefillEmail={invite.email} />
    </AuthShell>
  );
}

function SignInForm({ token, prefillEmail }: { token: string; prefillEmail: string }) {
  const auth = useAuth();
  const router = useRouter();
  const [step, setStep] = React.useState<'request' | 'verify'>('request');
  const [email, setEmail] = React.useState('');
  const [devOtp, setDevOtp] = React.useState<string | undefined>(undefined);

  const requestForm = useForm<OtpRequestInput>({
    resolver: zodResolver(OtpRequestSchema),
    defaultValues: { fullName: '', email: prefillEmail },
  });

  const verifyForm = useForm<OtpOnlyInput>({
    resolver: zodResolver(OtpOnlySchema),
    defaultValues: { otp: '' },
  });

  // The validated invite supplies the authoritative email — prefill it (locked-in below).
  React.useEffect(() => {
    if (prefillEmail) requestForm.setValue('email', prefillEmail);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefillEmail]);

  const onRequest = requestForm.handleSubmit(async (values) => {
    try {
      const result = await auth.requestOtp({ ...values, token });
      setEmail(values.email);
      setDevOtp(result.devOtp);
      setStep('verify');
      toast.success('If an account matches, a 6-digit code is on its way to your email.');
    } catch (error) {
      toast.error(errorMessage(error, 'Could not send a code'));
    }
  });

  const resend = async () => {
    try {
      const result = await auth.requestOtp({ ...requestForm.getValues(), token });
      setDevOtp(result.devOtp);
      toast.success('A new code is on its way.');
    } catch (error) {
      toast.error(errorMessage(error, 'Could not resend the code'));
    }
  };

  const onVerify = verifyForm.handleSubmit(async ({ otp }) => {
    try {
      const session = await auth.verifyOtp({ email, otp, token });
      toast.success('Signed in');
      router.replace(homePathForSession(session));
    } catch (error) {
      toast.error(errorMessage(error, 'Invalid or expired code'));
    }
  });

  if (step === 'request') {
    return (
      <form onSubmit={onRequest} className="space-y-4" noValidate>
        <Field id="emp-name" label="Full Name" error={requestForm.formState.errors.fullName?.message}>
          <IconInput
            icon={User}
            id="emp-name"
            autoComplete="name"
            placeholder="Alex Doe"
            aria-invalid={Boolean(requestForm.formState.errors.fullName)}
            {...requestForm.register('fullName')}
          />
        </Field>
        <Field id="emp-email" label="Email" error={requestForm.formState.errors.email?.message}>
          <IconInput
            icon={Mail}
            id="emp-email"
            type="email"
            autoComplete="email"
            placeholder="you@example.com"
            aria-invalid={Boolean(requestForm.formState.errors.email)}
            {...requestForm.register('email')}
          />
        </Field>
        <Button type="submit" className="w-full" disabled={requestForm.formState.isSubmitting}>
          <Send />
          {requestForm.formState.isSubmitting ? 'Sending…' : 'Send code'}
        </Button>
      </form>
    );
  }

  return (
    <form onSubmit={onVerify} className="space-y-4" noValidate>
      <p className="text-sm text-muted-foreground">
        Enter the 6-digit code sent to <span className="font-medium text-foreground">{email}</span>.
      </p>
      {devOtp ? (
        <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
          Dev code (no email configured): <span className="font-mono font-medium">{devOtp}</span>
        </p>
      ) : null}
      <Field id="emp-otp" label="6-Digit Code" error={verifyForm.formState.errors.otp?.message}>
        <IconInput
          icon={KeyRound}
          id="emp-otp"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          placeholder="000000"
          aria-invalid={Boolean(verifyForm.formState.errors.otp)}
          {...verifyForm.register('otp')}
        />
      </Field>
      <Button type="submit" className="w-full" disabled={verifyForm.formState.isSubmitting}>
        {verifyForm.formState.isSubmitting ? 'Verifying…' : 'Verify & sign in'}
      </Button>
      <div className="flex items-center justify-between">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => {
            setStep('request');
            verifyForm.reset();
          }}
        >
          <ArrowLeft className="size-4" />
          Change details
        </Button>
        <Button type="button" variant="ghost" size="sm" onClick={resend}>
          Resend code
        </Button>
      </div>
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

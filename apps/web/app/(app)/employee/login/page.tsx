'use client';

import * as React from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { ArrowLeft, KeyRound, Mail, Send, User, UserRound } from 'lucide-react';
import { OtpRequestSchema, OtpVerifySchema, type OtpRequestInput } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { ApiError } from '@/lib/api/client';
import { AuthShell } from '@/components/auth/auth-shell';
import { Button } from '@/components/ui/button';
import { IconInput } from '@/components/ui/icon-input';

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

const OtpOnlySchema = OtpVerifySchema.pick({ otp: true });
type OtpOnlyInput = { otp: string };

/** Employee sign-in (§6): full name + email → OTP → session. The HR invite link lands here. */
export default function EmployeeLoginPage() {
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
      icon={<UserRound className="size-6" />}
      title="Welcome to hrorg.in"
      description="Enter your full name and email and we'll send you a one-time code."
      portalLabel="Integrated HR Management"
      badgeLabel="Secure employee access"
      footer={
        <Link href="/login" className="text-sm text-muted-foreground hover:text-foreground">
          Staff member? Sign in here
        </Link>
      }
    >
      <SignInForm />
    </AuthShell>
  );
}

function SignInForm() {
  const auth = useAuth();
  const router = useRouter();
  const [step, setStep] = React.useState<'request' | 'verify'>('request');
  const [email, setEmail] = React.useState('');
  const [devOtp, setDevOtp] = React.useState<string | undefined>(undefined);

  const requestForm = useForm<OtpRequestInput>({
    resolver: zodResolver(OtpRequestSchema),
    defaultValues: { fullName: '', email: '' },
  });

  const verifyForm = useForm<OtpOnlyInput>({
    resolver: zodResolver(OtpOnlySchema),
    defaultValues: { otp: '' },
  });

  // The selection/invite email links here with ?email=… — prefill it.
  React.useEffect(() => {
    const prefill = new URLSearchParams(window.location.search).get('email');
    if (prefill) requestForm.setValue('email', prefill);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onRequest = requestForm.handleSubmit(async (values) => {
    try {
      const result = await auth.requestOtp(values);
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
      const result = await auth.requestOtp(requestForm.getValues());
      setDevOtp(result.devOtp);
      toast.success('A new code is on its way.');
    } catch (error) {
      toast.error(errorMessage(error, 'Could not resend the code'));
    }
  };

  const onVerify = verifyForm.handleSubmit(async ({ otp }) => {
    try {
      const session = await auth.verifyOtp({ email, otp });
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

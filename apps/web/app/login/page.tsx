'use client';

import * as React from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { ArrowLeft, ShieldCheck } from 'lucide-react';
import {
  EmployeeOtpRequestSchema,
  EmployeeOtpVerifySchema,
  StaffLoginSchema,
  type EmployeeOtpRequestInput,
  type StaffLoginInput,
} from '@ihrms/shared';
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

const OtpOnlySchema = EmployeeOtpVerifySchema.pick({ otp: true });
type OtpOnlyInput = { otp: string };

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
          <CardTitle className="text-lg">Sign in to IHRMS</CardTitle>
          <CardDescription>Staff sign in with a password; employees with their ID.</CardDescription>
        </CardHeader>
        <CardContent>
          <Tabs defaultValue="staff">
            <TabsList className="grid w-full grid-cols-2">
              <TabsTrigger value="staff">Staff</TabsTrigger>
              <TabsTrigger value="employee">Employee</TabsTrigger>
            </TabsList>
            <TabsContent value="staff">
              <StaffLoginForm />
            </TabsContent>
            <TabsContent value="employee">
              <EmployeeLoginForm />
            </TabsContent>
          </Tabs>
        </CardContent>
        <CardFooter className="justify-center">
          <Link href="/" className="text-sm text-muted-foreground hover:text-foreground">
            ← Back to home
          </Link>
        </CardFooter>
      </Card>
    </div>
  );
}

function StaffLoginForm() {
  const auth = useAuth();
  const router = useRouter();
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<StaffLoginInput>({
    resolver: zodResolver(StaffLoginSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      const session = await auth.loginStaff(values);
      toast.success('Signed in');
      router.replace(homePathForSession(session));
    } catch (error) {
      toast.error(errorMessage(error, 'Could not sign in'));
    }
  });

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <Field id="staff-email" label="Email" error={errors.email?.message}>
        <Input
          id="staff-email"
          type="email"
          autoComplete="email"
          placeholder="you@company.com"
          aria-invalid={Boolean(errors.email)}
          {...register('email')}
        />
      </Field>
      <Field id="staff-password" label="Password" error={errors.password?.message}>
        <Input
          id="staff-password"
          type="password"
          autoComplete="current-password"
          placeholder="••••••••"
          aria-invalid={Boolean(errors.password)}
          {...register('password')}
        />
      </Field>
      <Button type="submit" className="w-full" disabled={isSubmitting}>
        {isSubmitting ? 'Signing in…' : 'Sign in'}
      </Button>
    </form>
  );
}

function EmployeeLoginForm() {
  const auth = useAuth();
  const router = useRouter();
  const [step, setStep] = React.useState<'request' | 'verify'>('request');
  const [employeeCode, setEmployeeCode] = React.useState('');
  const [devOtp, setDevOtp] = React.useState<string | undefined>(undefined);

  const requestForm = useForm<EmployeeOtpRequestInput>({
    resolver: zodResolver(EmployeeOtpRequestSchema),
    defaultValues: { employeeCode: '', email: '' },
  });

  const verifyForm = useForm<OtpOnlyInput>({
    resolver: zodResolver(OtpOnlySchema),
    defaultValues: { otp: '' },
  });

  const onRequest = requestForm.handleSubmit(async (values) => {
    try {
      const result = await auth.requestEmployeeOtp(values);
      setEmployeeCode(values.employeeCode);
      setDevOtp(result.devOtp);
      setStep('verify');
      toast.success('If the details match, a 6-digit code is on its way to your email.');
    } catch (error) {
      toast.error(errorMessage(error, 'Could not send a code'));
    }
  });

  const onVerify = verifyForm.handleSubmit(async ({ otp }) => {
    try {
      const session = await auth.verifyEmployeeOtp({ employeeCode, otp });
      toast.success('Signed in');
      router.replace(homePathForSession(session));
    } catch (error) {
      toast.error(errorMessage(error, 'Invalid or expired code'));
    }
  });

  if (step === 'request') {
    return (
      <form onSubmit={onRequest} className="space-y-4" noValidate>
        <Field
          id="emp-code"
          label="Employee ID"
          error={requestForm.formState.errors.employeeCode?.message}
        >
          <Input
            id="emp-code"
            placeholder="ACME-EMP-000123"
            autoCapitalize="characters"
            aria-invalid={Boolean(requestForm.formState.errors.employeeCode)}
            {...requestForm.register('employeeCode')}
          />
        </Field>
        <Field id="emp-email" label="Email" error={requestForm.formState.errors.email?.message}>
          <Input
            id="emp-email"
            type="email"
            autoComplete="email"
            placeholder="you@personal.com"
            aria-invalid={Boolean(requestForm.formState.errors.email)}
            {...requestForm.register('email')}
          />
        </Field>
        <Button type="submit" className="w-full" disabled={requestForm.formState.isSubmitting}>
          {requestForm.formState.isSubmitting ? 'Sending…' : 'Send code'}
        </Button>
      </form>
    );
  }

  return (
    <form onSubmit={onVerify} className="space-y-4" noValidate>
      <p className="text-sm text-muted-foreground">
        Enter the 6-digit code sent for{' '}
        <span className="font-medium text-foreground">{employeeCode}</span>.
      </p>
      {devOtp ? (
        <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
          Dev code (no email configured): <span className="font-mono font-medium">{devOtp}</span>
        </p>
      ) : null}
      <Field id="emp-otp" label="6-digit code" error={verifyForm.formState.errors.otp?.message}>
        <Input
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
      <Button
        type="button"
        variant="ghost"
        className="w-full"
        onClick={() => {
          setStep('request');
          verifyForm.reset();
        }}
      >
        <ArrowLeft className="size-4" />
        Use a different ID
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

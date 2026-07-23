import * as React from 'react';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/**
 * The shared editorial chrome for the two sign-in doors (staff password + employee OTP). Presentation
 * only — a calm, mint-forward field with a floating white card; the forms + all auth logic live in the
 * pages. The decorative field is built from design tokens (a mint gradient + soft brand-tinted blobs),
 * no image assets. Works at mobile widths and in dark mode.
 */
export function AuthShell({
  icon,
  title,
  description,
  children,
  footer,
}: {
  icon: React.ReactNode;
  title: string;
  description: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  return (
    <div className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-background p-6">
      {/* Tokenized decorative mint field — no assets. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 bg-gradient-to-br from-surface-tint via-background to-surface-tint"
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -left-28 -top-28 size-80 rounded-full bg-primary-bright/10 blur-3xl"
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -bottom-28 -right-28 size-80 rounded-full bg-primary/10 blur-3xl"
      />

      <Card className="relative z-10 w-full max-w-md animate-rise">
        <CardHeader className="items-center gap-1 pb-4 text-center">
          <div className="mb-2 flex size-12 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-sm">
            {icon}
          </div>
          <CardTitle className="text-2xl">{title}</CardTitle>
          <CardDescription className="text-[15px] leading-relaxed">{description}</CardDescription>
        </CardHeader>
        <CardContent>{children}</CardContent>
        {footer ? <CardFooter className="justify-center pt-2">{footer}</CardFooter> : null}
      </Card>
    </div>
  );
}

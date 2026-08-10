'use client';

import * as React from 'react';
import { CheckCircle2, Send } from 'lucide-react';

/**
 * The public contact form — the ONE marketing island that reaches the API. A bare `fetch` to
 * `${NEXT_PUBLIC_API_URL}/public/contact` (the SAME base the app client reads); NO api client, NO react-query,
 * NO providers, NO session/auth. The rest of /contact stays static; only this component hydrates. Client-side
 * validation mirrors the server rules; a hidden honeypot + a time-on-page signal deter bots; success replaces
 * the form. Copy stays in the confidential register.
 */

// Same base the authenticated client uses — read directly so we don't pull in the api client.
const API_BASE = (process.env.NEXT_PUBLIC_API_URL ?? '').replace(/\/+$/, '');
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

type Values = { name: string; email: string; organization: string; message: string; website: string };
type Errors = Partial<Record<keyof Values, string>>;

const EMPTY: Values = { name: '', email: '', organization: '', message: '', website: '' };

function validate(v: Values): Errors {
  const e: Errors = {};
  const name = v.name.trim();
  if (!name) e.name = 'Name is required';
  else if (name.length < 2 || name.length > 80) e.name = 'Name must be 2–80 characters';

  const email = v.email.trim();
  if (!email) e.email = 'Work email is required';
  else if (!EMAIL_RE.test(email) || email.length > 120) e.email = 'Enter a valid email';

  const org = v.organization.trim();
  if (!org) e.organization = 'Organization is required';
  else if (org.length < 2 || org.length > 120) e.organization = 'Organization must be 2–120 characters';

  if (v.message.length > 2000) e.message = 'Message is too long (2000 characters max)';
  return e;
}

export function ContactForm() {
  const [values, setValues] = React.useState<Values>(EMPTY);
  const [errors, setErrors] = React.useState<Errors>({});
  const [showErrors, setShowErrors] = React.useState(false);
  const [status, setStatus] = React.useState<'idle' | 'submitting' | 'success' | 'error'>('idle');
  const renderedAt = React.useRef(0);

  React.useEffect(() => {
    renderedAt.current = Date.now();
  }, []);

  const set = (k: keyof Values) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setValues((v) => ({ ...v, [k]: e.target.value }));

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const found = validate(values);
    setErrors(found);
    setShowErrors(true);
    if (Object.keys(found).length > 0) return;

    setStatus('submitting');
    try {
      const res = await fetch(`${API_BASE}/public/contact`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: values.name.trim(),
          email: values.email.trim(),
          organization: values.organization.trim(),
          message: values.message.trim() || undefined,
          website: values.website, // honeypot — stays empty for real people
          elapsedMs: Date.now() - renderedAt.current,
        }),
      });
      setStatus(res.ok ? 'success' : 'error');
    } catch {
      setStatus('error');
    }
  };

  if (status === 'success') {
    return (
      <div
        role="status"
        className="flex flex-col items-center gap-3 rounded-2xl border border-success/30 bg-success/10 p-8 text-center"
      >
        <CheckCircle2 className="size-8 text-success" />
        <p className="text-lg font-semibold text-foreground">Thanks — we&apos;ll be in touch</p>
        <p className="text-sm text-muted-foreground">
          We&apos;ll reply at{' '}
          <span className="font-medium text-foreground">{values.email.trim()}</span> to arrange access.
        </p>
      </div>
    );
  }

  const submitting = status === 'submitting';

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4 text-left">
      {status === 'error' ? (
        <p role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 px-3.5 py-2.5 text-sm text-foreground">
          Something went wrong sending your message. Please try again, or email{' '}
          <a href="mailto:info@hrorg.in" className="font-medium text-primary-bright hover:underline">
            info@hrorg.in
          </a>
          .
        </p>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field id="cf-name" label="Name" value={values.name} onChange={set('name')} error={showErrors ? errors.name : undefined} autoComplete="name" required />
        <Field id="cf-email" label="Work email" type="email" value={values.email} onChange={set('email')} error={showErrors ? errors.email : undefined} autoComplete="email" required />
      </div>
      <Field id="cf-org" label="Organization" value={values.organization} onChange={set('organization')} error={showErrors ? errors.organization : undefined} autoComplete="organization" required />

      <div>
        <label htmlFor="cf-message" className="mb-1.5 block text-sm font-medium text-foreground">
          Message <span className="font-normal text-muted-foreground">(optional)</span>
        </label>
        <textarea
          id="cf-message"
          rows={4}
          value={values.message}
          onChange={set('message')}
          aria-invalid={showErrors && Boolean(errors.message)}
          aria-describedby={showErrors && errors.message ? 'cf-message-err' : undefined}
          className="w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm text-foreground outline-none transition-colors placeholder:text-muted-foreground/60 focus:border-primary focus:ring-2 focus:ring-primary/40"
          placeholder="Tell us a little about how your HR runs today."
        />
        {showErrors && errors.message ? (
          <p id="cf-message-err" className="mt-1 text-xs text-destructive">
            {errors.message}
          </p>
        ) : null}
      </div>

      {/* Honeypot: hidden from people (visually + a11y), a magnet for bots. Must stay empty. */}
      <div aria-hidden className="absolute left-[-9999px] top-[-9999px] h-0 w-0 overflow-hidden">
        <label htmlFor="cf-website">Website</label>
        <input
          id="cf-website"
          type="text"
          tabIndex={-1}
          autoComplete="off"
          value={values.website}
          onChange={set('website')}
        />
      </div>

      <button
        type="submit"
        disabled={submitting}
        className="inline-flex w-full items-center justify-center gap-2 rounded-full bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-70 disabled:hover:translate-y-0 sm:w-auto"
      >
        {submitting ? 'Sending…' : (<>Request access <Send className="size-4" /></>)}
      </button>
    </form>
  );
}

function Field({
  id,
  label,
  value,
  onChange,
  error,
  type = 'text',
  autoComplete,
  required,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  error?: string;
  type?: string;
  autoComplete?: string;
  required?: boolean;
}) {
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-foreground">
        {label}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        onChange={onChange}
        autoComplete={autoComplete}
        aria-required={required}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-err` : undefined}
        className="w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm text-foreground outline-none transition-colors placeholder:text-muted-foreground/60 focus:border-primary focus:ring-2 focus:ring-primary/40 aria-[invalid=true]:border-destructive/60"
      />
      {error ? (
        <p id={`${id}-err`} className="mt-1 text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

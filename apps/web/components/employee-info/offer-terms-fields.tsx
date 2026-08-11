'use client';

import { type FieldErrors, type FieldValues, type Path, type UseFormRegister } from 'react-hook-form';
import { Input } from '@/components/ui/input';

/**
 * The Offer Letter terms HR provides at invite (§3.2) — the offer opens onboarding, so the invited employee
 * must read + sign + accept it before any form unlocks. Salary is free text (HR controls the wording; seeded
 * "X,XX,XXX Per Annum"); location defaults to Hyderabad. Joining date, designation and name come from the
 * Employee-Info fields above — they are NOT repeated here. Generic over the form type (onboard + SA onboard).
 */
const OFFER_FIELDS = ['salary', 'location'] as const;
type OfferFieldName = (typeof OFFER_FIELDS)[number];

export function OfferTermsFields<T extends FieldValues>({
  register,
  errors,
  idPrefix = 'offer',
}: {
  register: UseFormRegister<T>;
  errors: FieldErrors<T>;
  idPrefix?: string;
}) {
  const reg = (name: OfferFieldName) => register(name as Path<T>);
  const err = (name: OfferFieldName) => (errors as FieldErrors)[name]?.message as string | undefined;
  const id = (name: string) => `${idPrefix}-${name}`;

  return (
    <section className="space-y-3">
      <div className="space-y-0.5">
        <h3 className="text-sm font-semibold">Offer letter</h3>
        <p className="text-xs text-muted-foreground">
          Sent with the invite. The employee reads and signs it before onboarding unlocks.
        </p>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1.5">
          <label htmlFor={id('salary')} className="text-sm font-medium">
            Salary (CTC)<span className="ml-0.5 text-destructive">*</span>
          </label>
          <Input
            id={id('salary')}
            placeholder="e.g. 5,40,000 Per Annum"
            aria-invalid={Boolean(err('salary'))}
            {...reg('salary')}
          />
          {err('salary') ? (
            <p className="text-xs text-destructive">{err('salary')}</p>
          ) : (
            <p className="text-xs text-muted-foreground">Free text — appears in the offer&apos;s Salary line.</p>
          )}
        </div>
        <div className="space-y-1.5">
          <label htmlFor={id('location')} className="text-sm font-medium">
            Location
          </label>
          <Input
            id={id('location')}
            placeholder="Hyderabad"
            aria-invalid={Boolean(err('location'))}
            {...reg('location')}
          />
          {err('location') ? (
            <p className="text-xs text-destructive">{err('location')}</p>
          ) : (
            <p className="text-xs text-muted-foreground">Posting location in clause A.1. Defaults to Hyderabad.</p>
          )}
        </div>
      </div>
    </section>
  );
}

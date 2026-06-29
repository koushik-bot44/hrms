'use client';

import * as React from 'react';
import { useForm, type FieldValues } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Check } from 'lucide-react';
import type { ZodTypeAny } from 'zod';
import type { SectionKey } from '@ihrms/shared';
import { saveSection } from '@/lib/api/onboarding';
import { useApiMutation } from '@/lib/api/hooks';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

export interface FieldConfig {
  name: string;
  label: string;
  type?: 'text' | 'date' | 'number' | 'tel' | 'textarea';
  placeholder?: string;
}

const TEXTAREA_CLASS =
  'flex min-h-20 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50';

export function SectionForm({
  sectionKey,
  schema,
  fields,
  initialData,
  disabled,
}: {
  sectionKey: SectionKey;
  schema: ZodTypeAny;
  fields: FieldConfig[];
  initialData?: Record<string, unknown>;
  disabled?: boolean;
}) {
  const queryClient = useQueryClient();
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FieldValues>({
    resolver: zodResolver(schema),
    defaultValues: initialData ?? {},
  });

  const mutation = useApiMutation(
    (data: Record<string, unknown>) => saveSection(sectionKey, data),
    {
      successMessage: 'Section saved',
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: ['onboarding'] });
      },
    },
  );

  const onSubmit = handleSubmit((values) => mutation.mutate(values as Record<string, unknown>));
  const saved = mutation.isSuccess && !isDirty;

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        {fields.map((field) => {
          const id = `${sectionKey}-${field.name}`;
          const error = errors[field.name];
          return (
            <div
              key={field.name}
              className={cn('space-y-1.5', field.type === 'textarea' && 'sm:col-span-2')}
            >
              <label htmlFor={id} className="text-sm font-medium">
                {field.label}
              </label>
              {field.type === 'textarea' ? (
                <textarea
                  id={id}
                  className={TEXTAREA_CLASS}
                  placeholder={field.placeholder}
                  disabled={disabled}
                  {...register(field.name)}
                />
              ) : (
                <Input
                  id={id}
                  type={field.type ?? 'text'}
                  placeholder={field.placeholder}
                  disabled={disabled}
                  aria-invalid={Boolean(error)}
                  {...register(field.name)}
                />
              )}
              {error ? (
                <p className="text-xs text-destructive">{String(error.message ?? 'Invalid')}</p>
              ) : null}
            </div>
          );
        })}
      </div>
      {!disabled ? (
        <div className="flex items-center gap-3">
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Saving…' : 'Save section'}
          </Button>
          {saved ? (
            <span className="flex items-center gap-1 text-sm text-success">
              <Check className="size-4" />
              Saved
            </span>
          ) : null}
        </div>
      ) : null}
    </form>
  );
}

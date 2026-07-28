import * as React from 'react';
import { cn } from '@/lib/utils';
import { Input } from '@/components/ui/input';

/**
 * Presentational leading-icon wrapper around {@link Input}: a muted icon pinned inside the field's left
 * padding. Forwards the ref (so react-hook-form's `register` works unchanged) and passes every input prop
 * straight through — purely visual, no behaviour of its own.
 */
export interface IconInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  icon: React.ComponentType<{ className?: string }>;
}

export const IconInput = React.forwardRef<HTMLInputElement, IconInputProps>(
  ({ icon: Icon, className, ...props }, ref) => (
    <div className="relative">
      <Icon
        aria-hidden
        className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
      />
      <Input ref={ref} className={cn('pl-10', className)} {...props} />
    </div>
  ),
);
IconInput.displayName = 'IconInput';

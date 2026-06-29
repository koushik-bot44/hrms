'use client';

import {
  useMutation,
  useQuery,
  type UseMutationOptions,
  type UseQueryOptions,
} from '@tanstack/react-query';
import { toast } from 'sonner';
import { ApiError } from './client';

/**
 * Typed react-query wrappers over the shared API client. `useApiMutation` integrates
 * toasts (UX bar: optimistic updates with toasts). Request/response types come from
 * the callers, which import them from @/lib/contract — no codegen.
 */
export function useApiQuery<T>(
  key: readonly unknown[],
  fetcher: (signal?: AbortSignal) => Promise<T>,
  options?: Omit<UseQueryOptions<T, ApiError>, 'queryKey' | 'queryFn'>,
) {
  return useQuery<T, ApiError>({
    queryKey: key,
    queryFn: ({ signal }) => fetcher(signal),
    ...options,
  });
}

export interface ApiMutationOptions<T, V>
  extends Omit<UseMutationOptions<T, ApiError, V>, 'mutationFn'> {
  successMessage?: string | ((data: T, vars: V) => string);
  errorMessage?: string;
}

export function useApiMutation<T, V = void>(
  fn: (vars: V) => Promise<T>,
  options: ApiMutationOptions<T, V> = {},
) {
  const { successMessage, errorMessage, onSuccess, onError, ...rest } = options;
  type OnSuccessArgs = Parameters<NonNullable<UseMutationOptions<T, ApiError, V>['onSuccess']>>;
  type OnErrorArgs = Parameters<NonNullable<UseMutationOptions<T, ApiError, V>['onError']>>;
  return useMutation<T, ApiError, V>({
    mutationFn: fn,
    ...rest,
    onSuccess: (...args: OnSuccessArgs) => {
      if (successMessage) {
        const [data, vars] = args;
        toast.success(
          typeof successMessage === 'function' ? successMessage(data, vars) : successMessage,
        );
      }
      onSuccess?.(...args);
    },
    onError: (...args: OnErrorArgs) => {
      const [err] = args;
      toast.error(errorMessage ?? err.message ?? 'Something went wrong');
      onError?.(...args);
    },
  });
}

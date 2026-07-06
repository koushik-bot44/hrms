'use client';

import * as React from 'react';
import { toast } from 'sonner';
import type {
  ChangePasswordInput,
  OtpRequestInput,
  OtpRequestResult,
  OtpVerifyInput,
  Session,
  StaffLoginInput,
} from '@/lib/contract';
import { setAuthHooks } from '@/lib/api/client';
import {
  changePassword as apiChangePassword,
  loginStaff as apiLoginStaff,
  logout as apiLogout,
  refreshSession,
  requestOtp as apiRequestOtp,
  verifyOtp as apiVerifyOtp,
} from '@/lib/api/auth';
import type { AuthResult } from '@/lib/contract';

type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated';

interface AuthContextValue {
  session: Session | null;
  status: AuthStatus;
  /** Staff sign-in (email + password). */
  login: (input: StaffLoginInput) => Promise<Session>;
  /** Staff self-service password change. */
  changePassword: (input: ChangePasswordInput) => Promise<void>;
  /** Employee sign-in start (full name + email -> OTP). */
  requestOtp: (input: OtpRequestInput) => Promise<OtpRequestResult>;
  verifyOtp: (input: OtpVerifyInput) => Promise<Session>;
  logout: () => Promise<void>;
}

const AuthContext = React.createContext<AuthContextValue | null>(null);

/**
 * Holds the access token in memory (never localStorage) and keeps the session in sync.
 * On mount it silently refreshes from the httpOnly cookie; it also feeds the fetch layer
 * a token getter + a deduped refresh so any 401 recovers transparently.
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const tokenRef = React.useRef<string | null>(null);
  const sessionRef = React.useRef<Session | null>(null);
  const refreshInFlight = React.useRef<Promise<string | null> | null>(null);

  const [session, setSession] = React.useState<Session | null>(null);
  const [status, setStatus] = React.useState<AuthStatus>('loading');

  const applyAuth = React.useCallback((result: AuthResult) => {
    tokenRef.current = result.accessToken;
    sessionRef.current = result.session;
    setSession(result.session);
    setStatus('authenticated');
  }, []);

  const clearAuth = React.useCallback(() => {
    tokenRef.current = null;
    sessionRef.current = null;
    setSession(null);
    setStatus('unauthenticated');
  }, []);

  // Deduped silent refresh — concurrent 401s share one in-flight request.
  const doRefresh = React.useCallback((): Promise<string | null> => {
    if (refreshInFlight.current) {
      return refreshInFlight.current;
    }
    const inflight = (async () => {
      try {
        const result = await refreshSession();
        applyAuth(result);
        return result.accessToken;
      } catch {
        const hadSession = sessionRef.current !== null;
        clearAuth();
        if (hadSession) {
          toast.error('Your session expired. Please sign in again.');
        }
        return null;
      } finally {
        refreshInFlight.current = null;
      }
    })();
    refreshInFlight.current = inflight;
    return inflight;
  }, [applyAuth, clearAuth]);

  // Bridge the token + refresh into the fetch layer for its lifetime.
  React.useEffect(() => {
    setAuthHooks({ getToken: () => tokenRef.current, refresh: doRefresh });
    return () => setAuthHooks(null);
  }, [doRefresh]);

  // Restore a session on first load (no-op when there is no cookie).
  React.useEffect(() => {
    void doRefresh();
  }, [doRefresh]);

  const login = React.useCallback(
    async (input: StaffLoginInput) => {
      const result = await apiLoginStaff(input);
      applyAuth(result);
      return result.session;
    },
    [applyAuth],
  );

  const changePassword = React.useCallback(
    (input: ChangePasswordInput) => apiChangePassword(input).then(() => undefined),
    [],
  );

  const verifyOtp = React.useCallback(
    async (input: OtpVerifyInput) => {
      const result = await apiVerifyOtp(input);
      applyAuth(result);
      return result.session;
    },
    [applyAuth],
  );

  const requestOtp = React.useCallback((input: OtpRequestInput) => apiRequestOtp(input), []);

  const logout = React.useCallback(async () => {
    try {
      await apiLogout();
    } finally {
      clearAuth();
    }
  }, [clearAuth]);

  const value = React.useMemo<AuthContextValue>(
    () => ({ session, status, login, changePassword, requestOtp, verifyOtp, logout }),
    [session, status, login, changePassword, requestOtp, verifyOtp, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = React.useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within <AuthProvider>');
  }
  return ctx;
}

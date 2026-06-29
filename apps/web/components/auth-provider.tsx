'use client';

import * as React from 'react';
import { toast } from 'sonner';
import type {
  EmployeeOtpRequestInput,
  EmployeeOtpVerifyInput,
  OtpRequestResult,
  Session,
  StaffLoginInput,
} from '@/lib/contract';
import { setAuthHooks } from '@/lib/api/client';
import {
  loginStaff as apiLoginStaff,
  logout as apiLogout,
  refreshSession,
  requestEmployeeOtp as apiRequestOtp,
  verifyEmployeeOtp as apiVerifyOtp,
} from '@/lib/api/auth';
import type { AuthResult } from '@/lib/contract';

type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated';

interface AuthContextValue {
  session: Session | null;
  status: AuthStatus;
  loginStaff: (input: StaffLoginInput) => Promise<Session>;
  requestEmployeeOtp: (input: EmployeeOtpRequestInput) => Promise<OtpRequestResult>;
  verifyEmployeeOtp: (input: EmployeeOtpVerifyInput) => Promise<Session>;
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

  const loginStaff = React.useCallback(
    async (input: StaffLoginInput) => {
      const result = await apiLoginStaff(input);
      applyAuth(result);
      return result.session;
    },
    [applyAuth],
  );

  const verifyEmployeeOtp = React.useCallback(
    async (input: EmployeeOtpVerifyInput) => {
      const result = await apiVerifyOtp(input);
      applyAuth(result);
      return result.session;
    },
    [applyAuth],
  );

  const requestEmployeeOtp = React.useCallback(
    (input: EmployeeOtpRequestInput) => apiRequestOtp(input),
    [],
  );

  const logout = React.useCallback(async () => {
    try {
      await apiLogout();
    } finally {
      clearAuth();
    }
  }, [clearAuth]);

  const value = React.useMemo<AuthContextValue>(
    () => ({ session, status, loginStaff, requestEmployeeOtp, verifyEmployeeOtp, logout }),
    [session, status, loginStaff, requestEmployeeOtp, verifyEmployeeOtp, logout],
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

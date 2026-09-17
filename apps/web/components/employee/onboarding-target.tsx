'use client';

import * as React from 'react';
import { SELF_ONBOARDING, type OnboardingTarget } from '@/lib/api/onboarding';

const OnboardingTargetContext = React.createContext<OnboardingTarget>(SELF_ONBOARDING);

/**
 * Points the shared onboarding form steps (Form 1 / Form 3 / documents / signature) at a record. Without a
 * provider they write the signed-in employee's own onboarding; HR wraps them to enter an EXISTING employee's
 * record (§3.2).
 */
export function OnboardingTargetProvider({
  target,
  children,
}: {
  target: OnboardingTarget;
  children: React.ReactNode;
}) {
  return <OnboardingTargetContext.Provider value={target}>{children}</OnboardingTargetContext.Provider>;
}

export function useOnboardingTarget(): OnboardingTarget {
  return React.useContext(OnboardingTargetContext);
}

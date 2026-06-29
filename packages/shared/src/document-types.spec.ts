import { describe, it, expect } from 'vitest';
import {
  DOCUMENT_TYPE_REGISTRY,
  DOCUMENT_TYPE_CODES,
  isIssuedClassCode,
  getProvisioningClass,
} from './document-types';

describe('document-type registry provisioning classes', () => {
  it('marks the seven company codes ISSUED and BGV REFERENCED', () => {
    for (const code of ['OFR', 'EXP', 'APT', 'APR', 'AGR', 'RTR', 'SVC'] as const) {
      expect(DOCUMENT_TYPE_REGISTRY[code].provisioningClass).toBe('ISSUED');
      expect(isIssuedClassCode(code)).toBe(true);
    }
    expect(DOCUMENT_TYPE_REGISTRY.BGV.provisioningClass).toBe('REFERENCED');
    expect(isIssuedClassCode('BGV')).toBe(false);
  });

  it('every registered code has a provisioning class', () => {
    for (const code of DOCUMENT_TYPE_CODES) {
      expect(getProvisioningClass(code)).toBeDefined();
    }
  });

  it('rejects unknown codes', () => {
    expect(isIssuedClassCode('ZZZ')).toBe(false);
    expect(getProvisioningClass('ZZZ')).toBeUndefined();
  });
});

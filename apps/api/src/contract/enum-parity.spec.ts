import { describe, expect, it } from 'vitest';
import * as PrismaClient from '@prisma/client';
import { SHARED_ENUMS, type SharedEnumName } from '@ihrms/shared';

/**
 * Parity guard: Prisma owns the DB enums, but they must equal the @ihrms/shared enums
 * member-for-member. Fails loudly if the two ever drift.
 */
const enumNames = Object.keys(SHARED_ENUMS) as SharedEnumName[];

describe('Prisma <-> @ihrms/shared enum parity', () => {
  for (const name of enumNames) {
    it(`${name} has identical members`, () => {
      const prismaEnum = (PrismaClient as Record<string, unknown>)[name];
      const sharedEnum = SHARED_ENUMS[name];

      expect(prismaEnum, `Prisma client is missing enum "${name}"`).toBeTruthy();

      const prismaValues = Object.values(prismaEnum as Record<string, string>).sort();
      const sharedValues = Object.values(sharedEnum).sort();
      expect(prismaValues).toEqual(sharedValues);

      // string-enum invariant: each shared key equals its value.
      for (const [key, value] of Object.entries(sharedEnum)) {
        expect(key).toBe(value);
      }
    });
  }

  it('covers exactly the 8 platform enums', () => {
    expect(enumNames.sort()).toEqual(
      [
        'ApprovalStatus',
        'DocumentStatus',
        'DocumentType',
        'EmployeeStatus',
        'NotificationType',
        'SectionKey',
        'SectionStatus',
        'UserRole',
      ].sort(),
    );
  });
});

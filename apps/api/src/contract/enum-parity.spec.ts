import { describe, expect, it } from 'vitest';
import * as PrismaClient from '@prisma/client';
import { SHARED_ENUMS, type SharedEnumName } from '@cdpp/shared';

/**
 * Parity guard: Prisma owns the DB enums, but they must equal the @cdpp/shared
 * enums member-for-member. This test fails loudly if the two ever drift, so a
 * change in one place can't silently desync the contract.
 *
 * Prisma generates each enum as a runtime object on `@prisma/client`
 * (e.g. `PrismaClient.Role === { SUPER_ADMIN: 'SUPER_ADMIN', ... }`).
 */
const enumNames = Object.keys(SHARED_ENUMS) as SharedEnumName[];

describe('Prisma <-> @cdpp/shared enum parity', () => {
  for (const name of enumNames) {
    it(`${name} has identical members`, () => {
      const prismaEnum = (PrismaClient as Record<string, unknown>)[name];
      const sharedEnum = SHARED_ENUMS[name];

      expect(prismaEnum, `Prisma client is missing enum "${name}"`).toBeTruthy();

      const prismaValues = Object.values(prismaEnum as Record<string, string>).sort();
      const sharedValues = Object.values(sharedEnum).sort();

      // Same set of members...
      expect(prismaValues).toEqual(sharedValues);
      // ...and string-enum invariant: each shared key equals its value.
      for (const [key, value] of Object.entries(sharedEnum)) {
        expect(key).toBe(value);
      }
    });
  }

  it('covers exactly the 8 platform enums', () => {
    expect(enumNames.sort()).toEqual(
      [
        'ConsentKind',
        'DocumentClass',
        'DocumentStatus',
        'EmploymentType',
        'OnboardingState',
        'RequirementStatus',
        'Role',
        'WorkAuthStatus',
      ].sort(),
    );
  });
});

import { describe, it, expect } from 'vitest';
import { istTodayIso } from '../../date';
import {
  OfferTermsSchema,
  OnboardEmployeeSchema,
  OnboardExistingEmployeeSchema,
  SuperAdminOnboardExistingSchema,
} from './employees';

// Item 6: the onboard dialog's salary starts EMPTY and is REQUIRED. This locks the client contract that
// backs that field (the server enforces the same via @NotBlank on OfferTermsRequest.salary).
describe('OfferTermsSchema.salary (required)', () => {
  it('rejects an empty salary', () => {
    expect(OfferTermsSchema.safeParse({ salary: '' }).success).toBe(false);
  });

  it('rejects a whitespace-only salary', () => {
    expect(OfferTermsSchema.safeParse({ salary: '   ' }).success).toBe(false);
  });

  it('accepts a real salary (location optional)', () => {
    const parsed = OfferTermsSchema.safeParse({ salary: '5,40,000 Per Annum' });
    expect(parsed.success).toBe(true);
  });
});

// Existing-employee onboarding (§3.2): Form 2 only, with the employee ID + official email they already have — a
// past joining date is fine, a future one is not, and there are no offer terms to require.
const form2 = {
  fullName: 'Priya Raman',
  personalEmail: 'priya@personal.test',
  designation: 'Accountant',
  dateOfJoining: '2019-06-01',
  employeeId: 'DI-1042',
  officialEmail: 'priya.raman@demo.test',
};

type Parsed = { success: boolean; error?: { issues: { path: (string | number)[] }[] } };
const fieldError = (result: Parsed, field: string) =>
  result.error?.issues.some((i) => i.path[0] === field) ?? false;
const dojError = (result: Parsed) => fieldError(result, 'dateOfJoining');

describe('OnboardExistingEmployeeSchema', () => {
  it('accepts a past date of joining', () => {
    expect(OnboardExistingEmployeeSchema.safeParse(form2).success).toBe(true);
  });

  it("accepts today's date (IST) as the latest joining date", () => {
    expect(OnboardExistingEmployeeSchema.safeParse({ ...form2, dateOfJoining: istTodayIso() }).success).toBe(
      true,
    );
  });

  it('rejects a future date of joining', () => {
    const result = OnboardExistingEmployeeSchema.safeParse({ ...form2, dateOfJoining: '2999-01-01' });
    expect(result.success).toBe(false);
    expect(dojError(result)).toBe(true);
  });

  it('still requires a date of joining', () => {
    const result = OnboardExistingEmployeeSchema.safeParse({ ...form2, dateOfJoining: '' });
    expect(result.success).toBe(false);
    expect(dojError(result)).toBe(true);
  });

  it('does not require salary or location', () => {
    const parsed = OnboardExistingEmployeeSchema.safeParse(form2);
    expect(parsed.success).toBe(true);
    expect(parsed.success && 'salary' in parsed.data).toBe(false);
  });

  it('requires the employee ID they already have', () => {
    const result = OnboardExistingEmployeeSchema.safeParse({ ...form2, employeeId: '  ' });
    expect(result.success).toBe(false);
    expect(fieldError(result, 'employeeId')).toBe(true);
  });

  it('rejects an employee ID with unsupported characters', () => {
    const result = OnboardExistingEmployeeSchema.safeParse({ ...form2, employeeId: 'DI 1042!' });
    expect(fieldError(result, 'employeeId')).toBe(true);
  });

  it('requires a valid official email', () => {
    expect(fieldError(OnboardExistingEmployeeSchema.safeParse({ ...form2, officialEmail: '' }), 'officialEmail')).toBe(
      true,
    );
    expect(
      fieldError(OnboardExistingEmployeeSchema.safeParse({ ...form2, officialEmail: 'not-an-email' }), 'officialEmail'),
    ).toBe(true);
  });

  it('leaves the new-hire schema unchanged (salary still required)', () => {
    expect(OnboardEmployeeSchema.safeParse(form2).success).toBe(false);
  });
});

describe('SuperAdminOnboardExistingSchema', () => {
  const sa = { ...form2, companyId: 'c1', teamId: 't1' };

  it('accepts company + team + Form 2 with a past joining date', () => {
    expect(SuperAdminOnboardExistingSchema.safeParse(sa).success).toBe(true);
  });

  it('requires a company and a team', () => {
    expect(SuperAdminOnboardExistingSchema.safeParse({ ...sa, companyId: '' }).success).toBe(false);
    expect(SuperAdminOnboardExistingSchema.safeParse({ ...sa, teamId: '' }).success).toBe(false);
  });

  it('rejects a future date of joining', () => {
    expect(SuperAdminOnboardExistingSchema.safeParse({ ...sa, dateOfJoining: '2999-01-01' }).success).toBe(false);
  });
});

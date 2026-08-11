import { describe, it, expect } from 'vitest';
import { OfferTermsSchema } from './employees';

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

import { describe, it, expect } from 'vitest';
import { formatUniqueId, parseUniqueId, isUniqueId } from './ids';

describe('unique-id helpers', () => {
  it('formats {ENTITY}-{TYPE}-{YYYY}-{NNNNNN} with zero-padded sequence', () => {
    expect(
      formatUniqueId({ entityCode: 'STELLAR', typeCode: 'OFR', year: 2026, sequence: 42 }),
    ).toBe('STELLAR-OFR-2026-000042');
  });

  it('round-trips through parse', () => {
    const id = formatUniqueId({ entityCode: 'STELLAR', typeCode: 'BGV', year: 2026, sequence: 7 });
    expect(parseUniqueId(id)).toEqual({
      entityCode: 'STELLAR',
      typeCode: 'BGV',
      year: 2026,
      sequence: 7,
    });
  });

  it('rejects unknown type codes and malformed ids', () => {
    expect(isUniqueId('STELLAR-ZZZ-2026-000001')).toBe(false);
    expect(isUniqueId('stellar-ofr-2026-000001')).toBe(false);
    expect(parseUniqueId('STELLAR-OFR-2026-42')).toBeNull();
  });

  it('throws on invalid parts', () => {
    expect(() => formatUniqueId({ entityCode: 'bad', typeCode: 'OFR', year: 2026, sequence: 1 })).toThrow();
    expect(() => formatUniqueId({ entityCode: 'STELLAR', typeCode: 'OFR', year: 2026, sequence: 0 })).toThrow();
  });
});

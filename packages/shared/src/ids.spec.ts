import { describe, it, expect } from 'vitest';
import { formatUniqueId, parseUniqueId, isUniqueId } from './ids';

describe('unique-id helpers', () => {
  it('formats {ENTITY}-{TYPE}-{YYYY}-{NNNNNN} with zero-padded sequence', () => {
    expect(
      formatUniqueId({ entityCode: 'NAME', typeCode: 'OFR', year: 2026, sequence: 42 }),
    ).toBe('NAME-OFR-2026-000042');
  });

  it('round-trips through parse', () => {
    const id = formatUniqueId({ entityCode: 'NAME', typeCode: 'BGV', year: 2026, sequence: 7 });
    expect(parseUniqueId(id)).toEqual({
      entityCode: 'NAME',
      typeCode: 'BGV',
      year: 2026,
      sequence: 7,
    });
  });

  it('rejects unknown type codes and malformed ids', () => {
    expect(isUniqueId('NAME-ZZZ-2026-000001')).toBe(false);
    expect(isUniqueId('name-ofr-2026-000001')).toBe(false);
    expect(parseUniqueId('NAME-OFR-2026-42')).toBeNull();
  });

  it('throws on invalid parts', () => {
    expect(() => formatUniqueId({ entityCode: 'bad', typeCode: 'OFR', year: 2026, sequence: 1 })).toThrow();
    expect(() => formatUniqueId({ entityCode: 'NAME', typeCode: 'OFR', year: 2026, sequence: 0 })).toThrow();
  });
});

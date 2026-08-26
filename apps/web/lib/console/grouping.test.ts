import { describe, expect, it } from 'vitest';
import {
  COLLAPSE_THRESHOLD,
  MAX_UNGROUPED_ROWS,
  groupItems,
  meetsConsoleStandard,
  visibleRowCount,
} from './grouping';

/**
 * GATE A ACCEPTANCE for the console UX standard.
 *
 * The criterion: no screen may render an ungrouped list longer than one viewport at 208 people, and
 * the Not-arrived-202 case is the named test. There is no DOM harness in this app — vitest runs in
 * node mode — so the assertion is made against the rule that DECIDES the layout rather than against
 * rendered output. That is not a weaker test: if 202 people would render as 202 rows, it is a property
 * of this function, and it fails here.
 *
 * SCOPE: the standard governs a screen's DEFAULT presentation. Operator-selected 'all' mode — the flat
 * ticker — is exempt by design: someone who asks for every row and gets every row is the feature
 * working, so meetsConsoleStandard returning false there is correct rather than a gate failure. The
 * "deliberately flat" test below pins both halves of that distinction side by side.
 */

interface Chip {
  name: string;
  company: string | null;
  lastAt: string | null;
}

function chip(name: string, company: string | null, lastAt: string | null = null): Chip {
  return { name, company, lastAt };
}

const byCompany = {
  keyOf: (c: Chip) => c.company,
  timeOf: (c: Chip) => c.lastAt,
};

describe('the Gate A case: 202 not-arrived people', () => {
  // The real shape at Orion Towers: 208 active people spread across five companies plus a company-less
  // remainder, almost all of whom have not arrived yet at the start of a shift.
  const companies = [
    'Screatives Software Services',
    'Sphinix Technologies',
    'Combino Information Technologies',
    'Spire Info Tech',
    'Charm Info Systems',
  ];
  const notArrived: Chip[] = Array.from({ length: 202 }, (_, i) =>
    chip(`Person ${String(i).padStart(3, '0')}`, i < 183 ? companies[i % 5] : null),
  );

  it('renders as a handful of collapsed groups, not 202 cards', () => {
    const groups = groupItems(notArrived, byCompany);
    const check = meetsConsoleStandard(groups);

    expect(check.ok, check.reason).toBe(true);
    // Six group headers — five companies plus the company-less bucket — and nothing else.
    expect(check.groups).toBe(6);
    expect(check.visibleRows).toBe(6);
    expect(check.visibleRows).toBeLessThan(MAX_UNGROUPED_ROWS);
  });

  it('accounts for every person exactly once', () => {
    const groups = groupItems(notArrived, byCompany);
    const total = groups.reduce((n, g) => n + g.count, 0);
    expect(total).toBe(202);
    const names = new Set(groups.flatMap((g) => g.items.map((c) => c.name)));
    expect(names.size).toBe(202);
  });

  it('never silently drops the people with no company', () => {
    const groups = groupItems(notArrived, byCompany);
    const ungrouped = groups.find((g) => g.label === 'No company');
    expect(ungrouped).toBeDefined();
    expect(ungrouped!.count).toBe(19);
    // And it sorts LAST — a cleanup queue, not a company.
    expect(groups[groups.length - 1].label).toBe('No company');
  });

  it('fails the standard if the grouping is bypassed', () => {
    // The negative: one group per person is what "flat" looks like to this function, and it must be
    // rejected. Without this, the positive tests above could pass on a rule that never groups anything.
    const flat = groupItems(notArrived, { keyOf: (c) => c.name, timeOf: (c) => c.lastAt });
    const check = meetsConsoleStandard(flat);
    expect(check.ok).toBe(false);
    // 404, not 202: a group of one sits below the collapse threshold, so it renders its header AND
    // its single item. Grouping by something with no repetition is worse than not grouping at all,
    // which is the point — the standard rejects it either way.
    expect(check.visibleRows).toBe(404);
    expect(check.groups).toBe(202);
    expect(check.reason).toContain('effectively flat');
  });
});

describe('the 139-card case: a large single group', () => {
  it('collapses a group that is too big to scan', () => {
    const many = Array.from({ length: 139 }, (_, i) => chip(`P${i}`, 'One Big Company'));
    const groups = groupItems(many, byCompany);

    expect(groups).toHaveLength(1);
    expect(groups[0].defaultCollapsed).toBe(true);
    expect(visibleRowCount(groups)).toBe(1); // just the header
    expect(meetsConsoleStandard(groups).ok).toBe(true);
  });

  it('leaves a small group open, because folding away three people helps nobody', () => {
    const few = Array.from({ length: COLLAPSE_THRESHOLD }, (_, i) => chip(`P${i}`, 'Small Co'));
    const groups = groupItems(few, byCompany);

    expect(groups[0].defaultCollapsed).toBe(false);
    expect(visibleRowCount(groups)).toBe(1 + COLLAPSE_THRESHOLD);
  });
});

describe('recency ordering (the addendum)', () => {
  it('sorts the group holding the freshest event first, then by count', () => {
    const items = [
      chip('old big 1', 'Big', '2026-08-26T10:00:00Z'),
      chip('old big 2', 'Big', '2026-08-26T10:01:00Z'),
      chip('old big 3', 'Big', '2026-08-26T10:02:00Z'),
      chip('fresh', 'Small', '2026-08-26T18:00:00Z'),
    ];
    const groups = groupItems(items, byCompany);

    // Small is smaller but fresher, and freshness wins.
    expect(groups.map((g) => g.label)).toEqual(['Small', 'Big']);
  });

  it('falls back to count when two groups are equally fresh', () => {
    const t = '2026-08-26T18:00:00Z';
    const items = [
      chip('a', 'Two', t),
      chip('b', 'Two', t),
      chip('c', 'One', t),
    ];
    expect(groupItems(items, byCompany).map((g) => g.label)).toEqual(['Two', 'One']);
  });

  it('puts untimed groups after timed ones', () => {
    const items = [
      chip('nobody here has punched', 'Quiet', null),
      chip('someone punched', 'Active', '2026-08-26T18:00:00Z'),
    ];
    expect(groupItems(items, byCompany).map((g) => g.label)).toEqual(['Active', 'Quiet']);
  });

  it('orders items inside a group newest-first, with untimed sinking', () => {
    const items = [
      chip('older', 'Co', '2026-08-26T10:00:00Z'),
      chip('never', 'Co', null),
      chip('newest', 'Co', '2026-08-26T20:00:00Z'),
      chip('middle', 'Co', '2026-08-26T15:00:00Z'),
    ];
    const [g] = groupItems(items, byCompany);
    expect(g.items.map((c) => c.name)).toEqual(['newest', 'middle', 'older', 'never']);
  });

  it('honours an explicit comparator for the exempt surfaces', () => {
    // The People directory and the day grid are exempt from newest-first; the rule must accept that
    // rather than force recency everywhere.
    const items = [
      chip('Charlie', 'Co', '2026-08-26T20:00:00Z'),
      chip('alice', 'Co', '2026-08-26T10:00:00Z'),
      chip('Bob', 'Co', '2026-08-26T15:00:00Z'),
    ];
    const [g] = groupItems(items, {
      ...byCompany,
      compare: (a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()),
    });
    expect(g.items.map((c) => c.name)).toEqual(['alice', 'Bob', 'Charlie']);
  });
});

describe('edges', () => {
  it('handles an empty listing without inventing a group', () => {
    const groups = groupItems([], byCompany);
    expect(groups).toEqual([]);
    expect(meetsConsoleStandard(groups).ok).toBe(true);
  });

  it('treats an empty-string key as ungrouped rather than as a company named ""', () => {
    const groups = groupItems([chip('x', '')], byCompany);
    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe('No company');
  });

  it('survives an unparseable timestamp instead of ordering by NaN', () => {
    const items = [chip('bad', 'Co', 'not-a-date'), chip('good', 'Co', '2026-08-26T20:00:00Z')];
    const [g] = groupItems(items, byCompany);
    expect(g.latest).toBe(new Date('2026-08-26T20:00:00Z').getTime());
    expect(g.items[0].name).toBe('good'); // the unparseable one sinks, it does not poison the sort
  });
});

describe('view mode — both modes are first-class and both are decided here', () => {
  const items: Chip[] = [
    chip('older', 'Alpha', '2026-08-26T10:00:00Z'),
    chip('newest', 'Beta', '2026-08-26T20:00:00Z'),
    chip('middle', 'Alpha', '2026-08-26T15:00:00Z'),
    chip('untimed', 'Beta', null),
  ];

  it('ALL mode is one flat list, newest-first, with ZERO headers', () => {
    const groups = groupItems(items, { ...byCompany, mode: 'all' });

    expect(groups).toHaveLength(1);
    expect(groups[0].showHeader).toBe(false);
    expect(groups[0].count).toBe(4);
    expect(groups[0].items.map((c) => c.name)).toEqual(['newest', 'middle', 'older', 'untimed']);
    // No headers render, so the visible rows ARE the items.
    expect(visibleRowCount(groups)).toBe(4);
  });

  it('ALL mode ignores the grouping axis entirely', () => {
    // Same items, different axis, identical result — the axis is carried but inert, so switching back
    // to Grouped restores the operator's choice instead of resetting it.
    const byCompanyAll = groupItems(items, { ...byCompany, mode: 'all' });
    const byNameAll = groupItems(items, { keyOf: (c) => c.name, timeOf: (c) => c.lastAt, mode: 'all' });
    expect(byNameAll.map((g) => g.items.map((c) => c.name))).toEqual(
      byCompanyAll.map((g) => g.items.map((c) => c.name)),
    );
    expect(byNameAll).toHaveLength(1);
  });

  it('GROUPED mode still shows headers, and is the default', () => {
    const explicit = groupItems(items, { ...byCompany, mode: 'grouped' });
    const implicit = groupItems(items, byCompany);

    expect(explicit.every((g) => g.showHeader)).toBe(true);
    expect(implicit.map((g) => g.label)).toEqual(explicit.map((g) => g.label));
    expect(explicit.length).toBeGreaterThan(1);
  });

  it('ALL mode of the 202 case is deliberately flat, and is EXEMPT from the standard', () => {
    // The ticker is SUPPOSED to be 202 rows. The standard governs DEFAULT presentation; an operator who
    // switched to All asked for every row, so a false here is the feature working. This pins both
    // halves side by side so nobody later "fixes" the ticker into groups and deletes the mode.
    const many = Array.from({ length: 202 }, (_, i) => chip(`P${i}`, 'Co', null));
    const flat = groupItems(many, { ...byCompany, mode: 'all' });

    expect(flat).toHaveLength(1);
    expect(visibleRowCount(flat)).toBe(202);
    expect(meetsConsoleStandard(flat).ok).toBe(false);

    // The same data in grouped mode passes, which is the acceptance criterion.
    expect(meetsConsoleStandard(groupItems(many, byCompany)).ok).toBe(true);
  });

  it('ALL mode of an empty listing invents nothing', () => {
    expect(groupItems([], { ...byCompany, mode: 'all' })).toEqual([]);
  });

  it('ALL mode honours an explicit comparator, like the exempt surfaces do', () => {
    const groups = groupItems(items, {
      ...byCompany,
      mode: 'all',
      compare: (a, b) => a.name.localeCompare(b.name),
    });
    expect(groups[0].items.map((c) => c.name)).toEqual(['middle', 'newest', 'older', 'untimed']);
  });
});

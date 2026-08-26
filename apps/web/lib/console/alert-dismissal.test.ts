import { describe, expect, it } from 'vitest';
import {
  dismissalKey,
  partitionAlerts,
  pruneDismissals,
  withDismissal,
  withoutDismissal,
} from './alert-dismissal';

type A = { personId: string; since: string; name?: string; elapsedMinutes?: number };

const shravani: A = { personId: 'p1', since: '2026-08-27T19:04:00Z', name: 'Shravani', elapsedMinutes: 100 };
const bipul: A = { personId: 'p2', since: '2026-08-27T19:36:00Z', name: 'Bipul', elapsedMinutes: 68 };
const chitturi: A = { personId: 'p3', since: '2026-08-27T20:19:00Z', name: 'Chitturi', elapsedMinutes: 25 };

describe('dismissalKey', () => {
  it('identifies the ABSENCE, not the person', () => {
    expect(dismissalKey(shravani)).toBe('p1@2026-08-27T19:04:00Z');
  });

  it('gives the same person two different keys for two different absences', () => {
    // The load-bearing property. Same human, stepped out twice.
    const first = { personId: 'p1', since: '2026-08-27T19:04:00Z' };
    const second = { personId: 'p1', since: '2026-08-27T22:40:00Z' };
    expect(dismissalKey(first)).not.toBe(dismissalKey(second));
  });
});

describe('partitionAlerts', () => {
  it('splits acknowledged from outstanding', () => {
    const p = partitionAlerts([shravani, bipul, chitturi], [dismissalKey(bipul)]);
    expect(p.active.map((a) => a.name)).toEqual(['Shravani', 'Chitturi']);
    expect(p.dismissed.map((a) => a.name)).toEqual(['Bipul']);
  });

  it('keeps the server elapsed-descending order in both lists', () => {
    // Never re-sorted here: newest-first would put the person who just stepped out above someone
    // who has been gone two hours, which inverts the point of the strip.
    const p = partitionAlerts([shravani, bipul, chitturi], [dismissalKey(shravani), dismissalKey(chitturi)]);
    expect(p.active.map((a) => a.elapsedMinutes)).toEqual([68]);
    expect(p.dismissed.map((a) => a.elapsedMinutes)).toEqual([100, 25]);
  });

  it('returns everything as active when nothing is dismissed', () => {
    const p = partitionAlerts([shravani, bipul, chitturi], []);
    expect(p.active).toHaveLength(3);
    expect(p.dismissed).toHaveLength(0);
  });

  it('never drops anybody — the two halves always account for the whole strip', () => {
    // The anti-silent-loss property. Dismissing must quieten, never delete.
    const alerts = [shravani, bipul, chitturi];
    for (const keys of [[], [dismissalKey(bipul)], alerts.map(dismissalKey)]) {
      const p = partitionAlerts(alerts, keys);
      expect(p.active.length + p.dismissed.length).toBe(alerts.length);
    }
  });

  it('ignores a stored key for somebody who is not currently alerting', () => {
    const p = partitionAlerts([shravani], ['p9@2026-01-01T00:00:00Z']);
    expect(p.active).toHaveLength(1);
    expect(p.dismissed).toHaveLength(0);
  });
});

describe('a dismissed person who returns and leaves again', () => {
  it('RE-ALERTS, because the new absence has a new key', () => {
    // THE CASE THE WHOLE KEY DESIGN EXISTS FOR. Bipul is dismissed at 19:36. He comes back, works,
    // and steps out again at 22:40 — a new absence the operator has never acknowledged.
    const dismissed = withDismissal([], bipul);
    const laterAbsence: A = { personId: 'p2', since: '2026-08-27T22:40:00Z', name: 'Bipul' };

    const p = partitionAlerts([laterAbsence], dismissed);

    expect(p.active.map((a) => a.name)).toEqual(['Bipul']);
    expect(p.dismissed).toHaveLength(0);
  });

  it('VACUITY NEGATIVE: keyed on personId alone it would stay silenced', () => {
    // If this ever passes as "still dismissed", the key has been weakened to the person and the test
    // above is proving nothing. Modelled here by deliberately keying the wrong way.
    const byPersonOnly = [bipul.personId];
    const laterAbsence: A = { personId: 'p2', since: '2026-08-27T22:40:00Z', name: 'Bipul' };

    const wrong = partitionAlerts([laterAbsence], byPersonOnly);
    expect(wrong.dismissed).toHaveLength(0);
    expect(wrong.active).toHaveLength(1);

    // And the real key genuinely differs from the person-only one, which is what makes it work.
    expect(dismissalKey(laterAbsence)).not.toBe(laterAbsence.personId);
  });
});

describe('pruneDismissals', () => {
  it('drops keys whose absence has ended', () => {
    const stored = [dismissalKey(shravani), dismissalKey(bipul)];
    expect(pruneDismissals(stored, [bipul])).toEqual([dismissalKey(bipul)]);
  });

  it('keeps the list from growing without bound across shifts', () => {
    const stale = Array.from({ length: 500 }, (_, i) => `p${i}@2026-01-01T00:00:0${i % 10}Z`);
    expect(pruneDismissals(stale, [])).toEqual([]);
  });

  it('is a no-op when every stored key is still live', () => {
    const stored = [dismissalKey(shravani), dismissalKey(chitturi)];
    expect(pruneDismissals(stored, [shravani, chitturi, bipul])).toEqual(stored);
  });
});

describe('withDismissal / withoutDismissal', () => {
  it('adds without duplicating', () => {
    const once = withDismissal([], shravani);
    expect(withDismissal(once, shravani)).toEqual(once);
    expect(once).toHaveLength(1);
  });

  it('restores exactly the one asked for', () => {
    const both = withDismissal(withDismissal([], shravani), bipul);
    expect(withoutDismissal(both, shravani)).toEqual([dismissalKey(bipul)]);
  });

  it('does not mutate the array it was given', () => {
    const original: string[] = [];
    withDismissal(original, shravani);
    expect(original).toHaveLength(0);
  });
});

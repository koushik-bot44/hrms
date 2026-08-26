/**
 * Dismissing an "exceeding break" alert — the decision, as a pure function.
 *
 * The strip fills up on a busy night, and an operator who has already walked over to someone, or
 * already knows why they are out, has no way to clear them. An alert nobody can clear is one they
 * stop reading, which costs more than the alert was ever worth.
 *
 * THE KEY IS THE ABSENCE, NOT THE PERSON. A dismissal is keyed on `personId@since` — the instant the
 * absence began — rather than on the person alone. That distinction is the whole design: if it were
 * keyed on the person, dismissing tonight's 100-minute absence would also silence the NEXT one, so
 * someone who stepped out, came back and left again would never alert again for the rest of the
 * shift. Because `since` moves when they punch, a genuinely new absence produces a new key and
 * re-alerts on its own, with no expiry rule to get wrong.
 *
 * Nothing is deleted. Dismissed people move to a collapsed group whose count stays on screen, so the
 * strip can be quietened but never silently emptied.
 */

export type DismissibleAlert = {
  personId: string;
  since: string;
};

/**
 * The identity of one absence.
 *
 * `since` is an ISO instant from the server, compared as an opaque string — parsing it into a Date
 * here would introduce a timezone question that this function has no reason to have an opinion about.
 */
export function dismissalKey(alert: DismissibleAlert): string {
  return `${alert.personId}@${alert.since}`;
}

export type AlertPartition<T> = {
  /** Still demanding attention, in the server's elapsed-descending order. */
  active: T[];
  /** Acknowledged by the operator. Counted and restorable, never hidden outright. */
  dismissed: T[];
};

/**
 * Splits the strip into what still needs attention and what has been acknowledged.
 *
 * Order within each list is preserved exactly as the server sent it — elapsed-descending, the
 * documented exception to the recency standard. Re-sorting here would quietly put the person who just
 * stepped out above the one who has been gone two hours, which inverts the only thing the strip does.
 */
export function partitionAlerts<T extends DismissibleAlert>(
  alerts: readonly T[],
  dismissedKeys: readonly string[],
): AlertPartition<T> {
  const dismissed = new Set(dismissedKeys);
  const out: AlertPartition<T> = { active: [], dismissed: [] };
  for (const alert of alerts) {
    if (dismissed.has(dismissalKey(alert))) {
      out.dismissed.push(alert);
    } else {
      out.active.push(alert);
    }
  }
  return out;
}

/**
 * Drops stored dismissals that no longer correspond to a live alert.
 *
 * Without this the list grows for every absence ever acknowledged, and a browser that has watched a
 * few hundred shifts carries a few thousand dead keys. Pruning against the CURRENT alerts is safe
 * precisely because a key encodes the absence: once an alert is gone — they came back, or the absence
 * passed the cap and retired — its key can never match again.
 */
export function pruneDismissals(
  dismissedKeys: readonly string[],
  currentAlerts: readonly DismissibleAlert[],
): string[] {
  const live = new Set(currentAlerts.map(dismissalKey));
  return dismissedKeys.filter((key) => live.has(key));
}

/** Adds a dismissal without duplicating one that is already stored. */
export function withDismissal(dismissedKeys: readonly string[], alert: DismissibleAlert): string[] {
  const key = dismissalKey(alert);
  return dismissedKeys.includes(key) ? [...dismissedKeys] : [...dismissedKeys, key];
}

/** Restores one dismissed alert. */
export function withoutDismissal(
  dismissedKeys: readonly string[],
  alert: DismissibleAlert,
): string[] {
  const key = dismissalKey(alert);
  return dismissedKeys.filter((k) => k !== key);
}

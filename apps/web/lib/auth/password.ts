/**
 * Generate a reasonably-strong initial staff password (client-side, for provisioning). The admin
 * sets it and shares it with the new staff member, who signs in and can change it (§6).
 */
export function generatePassword(length = 14): string {
  // Ambiguous glyphs (0/O, 1/l/I) omitted for legibility when read aloud/copied.
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789@#$%&';
  const bytes = new Uint32Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join('');
}

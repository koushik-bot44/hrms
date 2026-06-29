import { hash, verify } from '@node-rs/argon2';

/**
 * Argon2 hashing for staff passwords and employee OTPs (§6). Argon2id defaults from
 * @node-rs/argon2 are appropriate; never store or log the plaintext secret.
 */
export function hashSecret(secret: string): Promise<string> {
  return hash(secret);
}

export async function verifySecret(storedHash: string, secret: string): Promise<boolean> {
  try {
    return await verify(storedHash, secret);
  } catch {
    // Malformed/foreign hash -> treat as no match rather than throwing.
    return false;
  }
}

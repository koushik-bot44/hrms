/**
 * @cdpp/shared — the single source of truth for the CDPP cross-app contract.
 *
 * Exports: platform enums, unique-ID format helpers, the document-type registry,
 * and zod DTOs for API requests/responses. Both `apps/api` and `apps/web` import
 * from here; nothing duplicates these definitions.
 */
export * from './enums';
export * from './ids';
export * from './document-types';
export * from './dto';

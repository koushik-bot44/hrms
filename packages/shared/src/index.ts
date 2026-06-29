/**
 * @ihrms/shared — the single source of truth for the IHRMS cross-app contract:
 * enums (§4), the employee-ID helpers (§5), and zod DTOs. Both apps import from here;
 * nothing is duplicated.
 */
export * from './enums';
export * from './ids';
export * from './dto';

import { DOCUMENT_TYPE_REGISTRY, Role } from '@cdpp/shared';

/**
 * Phase 1 placeholder. Its only job is to prove the workspace wiring resolves:
 * it imports live values from @cdpp/shared and renders them. Real UI lands in Phase 2.
 */
export default function Home() {
  const documentTypes = Object.values(DOCUMENT_TYPE_REGISTRY);
  const roles = Object.values(Role);

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: '2rem', maxWidth: 720 }}>
      <h1>CDPP — Document Provisioning Platform</h1>
      <p>
        Web placeholder. The contract below is imported from <code>@cdpp/shared</code>,
        proving the pnpm workspace wiring resolves.
      </p>

      <h2>Roles</h2>
      <p>{roles.join(' · ')}</p>

      <h2>Document types</h2>
      <ul>
        {documentTypes.map((t) => (
          <li key={t.code}>
            <strong>{t.code}</strong> — {t.label} ({t.class})
          </li>
        ))}
      </ul>
    </main>
  );
}

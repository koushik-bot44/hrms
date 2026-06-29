export default function Home() {
  return (
    <div className="mx-auto max-w-2xl px-6 py-24 text-center">
      <h1 className="text-2xl font-medium tracking-tight">Scaffold ready</h1>
      <p className="mt-3 text-slate-600">
        Neutral deployable shell. The API-status indicator in the header reflects the live{' '}
        <code className="rounded bg-slate-100 px-1.5 py-0.5 text-sm">/health</code> round-trip to
        the backend.
      </p>
    </div>
  );
}

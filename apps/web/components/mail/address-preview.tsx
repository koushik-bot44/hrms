import { AtSign } from 'lucide-react';

/**
 * A live preview of the mailbox address a provisioning form will form (§8): `localpart@domain`, which
 * is also the login email. Renders a neutral hint while the local part is empty.
 */
export function AddressPreview({ address }: { address: string }) {
  return (
    <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
      <AtSign className="size-3.5 shrink-0" />
      {address ? (
        <>
          Address: <span className="font-mono text-foreground">{address}</span>
        </>
      ) : (
        <span>The mailbox name forms their address (and login email).</span>
      )}
    </p>
  );
}

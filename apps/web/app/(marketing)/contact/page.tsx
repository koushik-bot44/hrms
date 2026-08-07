import * as React from 'react';
import type { Metadata } from 'next';
import { Contact } from '@/components/marketing/contact';

/**
 * /contact — where "Not subscribed yet? Contact us" lands from every page. Access is by arrangement:
 * info@hrorg.in is the single call to action, no form/phone/socials. FULLY STATIC, provider-free, zero
 * backend calls.
 */
const DESCRIPTION =
  'Access to hrorg.in is by arrangement. Write to info@hrorg.in and our team will get back to you to arrange ' +
  'access for your organization.';

export const metadata: Metadata = {
  title: 'hrorg.in — Contact',
  description: DESCRIPTION,
  alternates: { canonical: '/contact' },
  openGraph: { title: 'hrorg.in — Contact', description: DESCRIPTION },
};

export default function ContactPage() {
  return <Contact />;
}

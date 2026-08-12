import * as React from 'react';
import Link from 'next/link';
import { ChevronDown, LifeBuoy, Mail } from 'lucide-react';
import { SUPPORT_EMAIL } from '@/lib/brand';
import { Eyebrow, SectionHeading } from '@/components/marketing/chrome';
import { Reveal } from '@/components/marketing/motion';

/**
 * /support content — a practical FAQ for people ALREADY using hrorg.in (employees, plus HR and administrators),
 * and support@ as the help channel. Confidential register throughout: answers solve real problems (email, codes,
 * devices, signing, uploads, documents) and NEVER describe internal workflow, roles, or architecture — where an
 * honest answer would need that, it redirects to the person's own HR contact or to support@. Fully STATIC: the
 * accordion is native <details>/<summary> (no client JS); only the shared <Reveal> entrance is a client island,
 * exactly like the other sub-pages.
 */

function Mailto({ children }: { children?: React.ReactNode }) {
  return (
    <a
      href={`mailto:${SUPPORT_EMAIL}`}
      className="font-medium text-primary-bright underline-offset-4 hover:underline"
    >
      {children ?? SUPPORT_EMAIL}
    </a>
  );
}

function ContactLink({ children }: { children: React.ReactNode }) {
  return (
    <Link href="/contact" className="font-medium text-primary-bright underline-offset-4 hover:underline">
      {children}
    </Link>
  );
}

type Faq = { q: string; a: React.ReactNode };

const EMPLOYEE_FAQS: Faq[] = [
  {
    q: 'I didn’t receive the invitation email.',
    a: (
      <>
        Check your spam or junk folder first, and confirm you’re looking at the same address your organization
        used to invite you. If it still isn’t there after a few minutes, ask your HR contact to send it again —
        or write to <Mailto /> and we’ll check delivery.
      </>
    ),
  },
  {
    q: 'My sign-in code isn’t arriving, or it says it’s expired.',
    a: (
      <>
        Sign-in codes come by email and are valid only for a short while. If yours has expired, request a fresh
        one from the sign-in page and enter the most recent code. Check your spam folder, and make sure the name
        and email you’re entering match what your organization has for you. Still stuck? Email <Mailto />.
      </>
    ),
  },
  {
    q: 'Can I use hrorg.in on my phone?',
    a: (
      <>
        Yes. Everything — signing in, filling in your details, signing, and uploading — works in an up-to-date
        mobile browser such as Safari or Chrome. If something looks cramped, turn your phone to portrait or
        refresh the page.
      </>
    ),
  },
  {
    q: 'My signature won’t capture, or I want to redo it.',
    a: (
      <>
        Sign inside the box with your finger, mouse, or trackpad, and use the clear option to start over if it
        didn’t come out right — do that before you move on. A slower, larger stroke captures best; on a phone,
        avoid pinch-zooming while you sign.
      </>
    ),
  },
  {
    q: 'I can’t upload a document.',
    a: (
      <>
        Uploads accept common document and image formats. If a file is refused, it’s usually the format or a very
        large file — try saving it as a PDF, or use a smaller or freshly scanned copy. If it still won’t upload,
        email <Mailto /> with the file type and roughly how large it is.
      </>
    ),
  },
  {
    q: 'I signed everything but don’t see my document.',
    a: (
      <>
        Give it a moment and refresh the page — your completed document appears on your own screen once it’s
        ready. If it still isn’t showing, sign out and back in; if that doesn’t help, contact <Mailto />.
      </>
    ),
  },
  {
    q: 'My email address has changed.',
    a: (
      <>
        Your sign-in is tied to the email your organization holds for you, so ask your HR contact to update it.
        Once they do, use your new address to sign in.
      </>
    ),
  },
  {
    q: 'I’ve finished my documents — what happens next?',
    a: (
      <>
        Nothing more is needed from you once you’ve submitted. If anyone needs something further, they’ll be in
        touch. For questions about what comes next, your HR contact is the best person to ask.
      </>
    ),
  },
];

const HR_FAQS: Faq[] = [
  {
    q: 'An employee says they never received their invitation.',
    a: (
      <>
        Ask them to check spam and to confirm the address is exactly the one on file. Resend the invitation to
        them; if it still doesn’t arrive, write to <Mailto /> with the person’s name and email and we’ll look
        into delivery.
      </>
    ),
  },
  {
    q: 'Someone on our team can’t sign in.',
    a: (
      <>
        Everyone signs in with the name and email your organization holds for them, so check those match what the
        person is typing — and that they’re using the newest code, since codes expire quickly. If their email has
        changed, update it for them. If they’re still locked out, email <Mailto />.
      </>
    ),
  },
  {
    q: 'I need to correct something after I’ve sent it.',
    a: (
      <>
        You can update a person’s basic details from your team view. If the change you need isn’t available to
        you there, write to <Mailto /> and we’ll help you put it right.
      </>
    ),
  },
  {
    q: 'The documents we use are our own templates — can they change?',
    a: (
      <>
        Yes. Your organization’s documents are yours. To adjust a template or add a new one, write to <Mailto />{' '}
        and we’ll arrange it for you.
      </>
    ),
  },
  {
    q: 'Can we see what’s been signed?',
    a: (
      <>
        Yes. Everything you send to a person and everything they send back is kept together on that person’s
        record, so you always have the complete picture in one place.
      </>
    ),
  },
  {
    q: 'How do I bring a new person onto our team?',
    a: (
      <>
        You can add a new person from your team view, and they’ll receive their own invitation to get started. If
        you need more users than your plan covers, <ContactLink>contact us</ContactLink>.
      </>
    ),
  },
  {
    q: 'Someone has left the organization.',
    a: (
      <>
        You can start a person’s exit from their record. If there’s a specific document or step you need prepared
        as part of it, write to <Mailto /> and we’ll help.
      </>
    ),
  },
  {
    q: 'Something doesn’t look right.',
    a: (
      <>
        Don’t force it. Email <Mailto /> with what you were doing, the device and browser you’re on, and a
        screenshot if you can — that’s usually all we need to sort it out quickly.
      </>
    ),
  },
];

const PRIVACY_FAQS: Faq[] = [
  {
    q: 'Who can see our data?',
    a: (
      <>
        Your organization’s information stays yours and walled off to your organization. Within it, people see
        only what concerns them — and no more.
      </>
    ),
  },
  {
    q: 'Is our information encrypted?',
    a: (
      <>
        Access is controlled and sensitive details are encrypted, with the most sensitive information shown
        masked. Signed documents are kept intact.
      </>
    ),
  },
  {
    q: 'Can employees see each other’s records?',
    a: (
      <>
        No. People see only what concerns them — an employee sees their own information, never a colleague’s.
      </>
    ),
  },
  {
    q: 'How do we add users or extend our access?',
    a: (
      <>
        To add users, extend your access, or bring another part of your organization on board,{' '}
        <ContactLink>contact us</ContactLink> and we’ll arrange it.
      </>
    ),
  },
];

function FaqItem({ item }: { item: Faq }) {
  return (
    <details className="group border-b border-white/10 last:border-b-0">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-4 py-4 text-left text-[15px] font-medium text-foreground [&::-webkit-details-marker]:hidden">
        <span>{item.q}</span>
        <ChevronDown
          aria-hidden
          className="size-4 shrink-0 text-muted-foreground transition-transform duration-200 group-open:rotate-180"
        />
      </summary>
      <div className="pb-4 pr-8 text-sm leading-relaxed text-muted-foreground">{item.a}</div>
    </details>
  );
}

function FaqGroup({ eyebrow, title, items }: { eyebrow: string; title: string; items: Faq[] }) {
  return (
    <Reveal className="mx-auto max-w-3xl">
      <Eyebrow>{eyebrow}</Eyebrow>
      <SectionHeading as="h2" className="mt-3 text-2xl sm:text-3xl">
        {title}
      </SectionHeading>
      <div className="mt-6 rounded-2xl border border-white/10 bg-white/[0.02] px-5 sm:px-6">
        {items.map((item) => (
          <FaqItem key={item.q} item={item} />
        ))}
      </div>
    </Reveal>
  );
}

export function SupportContent() {
  return (
    <section className="px-5 py-16 sm:px-8 md:py-20">
      <div className="mx-auto max-w-6xl space-y-16 md:space-y-20">
        <FaqGroup eyebrow="For employees" title="Using hrorg.in day to day" items={EMPLOYEE_FAQS} />
        <FaqGroup eyebrow="For HR and administrators" title="Looking after your team" items={HR_FAQS} />
        <FaqGroup eyebrow="Access, security & privacy" title="Your data, kept confidential" items={PRIVACY_FAQS} />

        <Reveal className="mx-auto max-w-3xl">
          <div className="m-glass overflow-hidden rounded-3xl p-7 text-center shadow-2xl shadow-black/40 sm:p-10">
            <span className="mx-auto flex size-12 items-center justify-center rounded-2xl bg-primary/15 text-primary-bright ring-1 ring-inset ring-primary/25">
              <LifeBuoy className="size-6" />
            </span>
            <h2 className="mt-5 text-2xl font-semibold tracking-tight text-foreground">Still need a hand?</h2>
            <p className="mx-auto mt-3 max-w-lg text-pretty text-muted-foreground">
              Email us and tell us what you were doing, the device and browser you’re on, and add a screenshot if
              you can — the more we can see, the faster we can help.
            </p>
            <div className="mt-7 flex justify-center">
              <a
                href={`mailto:${SUPPORT_EMAIL}`}
                className="inline-flex min-h-11 items-center gap-2 rounded-full bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:-translate-y-0.5"
              >
                <Mail className="size-4" />
                {SUPPORT_EMAIL}
              </a>
            </div>
            <p className="mt-5 text-sm text-muted-foreground">
              Looking for access rather than help? <ContactLink>Contact us</ContactLink>.
            </p>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

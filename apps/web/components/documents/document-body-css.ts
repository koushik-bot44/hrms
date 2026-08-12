/**
 * On-screen styles for an injected employee-facing document body (§3.2/§3.6). Shared by every read-and-sign
 * screen (agreements, offboarding documents, offer) via the InlineDocument component. `DOCUMENT_BODY_CSS`
 * mirrors the PDF layout at a screen-readable scale (moved here verbatim from the old per-component copy);
 * `INLINE_FIELD_CSS` styles the inline fill blanks + the inline signature slot so the document reads like a
 * filled form — underline-style inputs that flow WITH the prose, never block-level boxes that break paragraphs.
 */
export const DOCUMENT_BODY_CSS = `
.agreement-body { font-size: 14px; line-height: 1.6; color: inherit; }
.agreement-body h1.title { text-align:center; font-size:18px; font-weight:700; margin:0 0 16px; text-transform:uppercase; }
.agreement-body h2 { font-size:15px; font-weight:700; margin:18px 0 6px; }
.agreement-body h2.ack-head, .agreement-body h2.dd-head { text-align:center; margin-top:22px; }
.agreement-body h3 { font-size:13px; font-weight:700; margin:12px 0 4px; }
.agreement-body p { margin:7px 0; text-align:justify; }
.agreement-body p.g { margin:9px 0; }
.agreement-body ul { margin:6px 0; padding-left:22px; list-style:disc; }
.agreement-body li { margin:3px 0; }
.agreement-body .inl { font-weight:600; }
.agreement-body table.ack { width:100%; border-collapse:collapse; margin-top:12px; }
.agreement-body table.ack td { padding:7px 8px; vertical-align:middle; }
.agreement-body table.ack td.ackk { font-weight:600; width:40%; white-space:nowrap; }
.agreement-body table.ack td.ackv { border-bottom:1px solid currentColor; opacity:0.95; }
.agreement-body table.ack td.sigcell { height:44px; }
.agreement-body table.sig-block { width:100%; border-collapse:collapse; margin-top:18px; }
.agreement-body table.sig-block td { padding:5px 10px; vertical-align:bottom; width:50%; }
.agreement-body table.sig-block td.party { font-weight:700; }
.agreement-body table.sig-block td.ackk { font-weight:600; width:24%; white-space:nowrap; }
.agreement-body table.sig-block td.ackv { border-bottom:1px solid currentColor; }
.agreement-body .sigrule { border-bottom:1px solid currentColor; margin-top:2px; }
.agreement-body .siglabel { font-size:11px; opacity:0.7; margin-top:2px; }
.agreement-body .sigval { min-height:16px; }
.agreement-body .sigblank { min-height:40px; }
.agreement-body table.dosdonts { width:100%; border-collapse:collapse; margin-top:10px; }
.agreement-body table.dosdonts th, .agreement-body table.dosdonts td { border:1px solid currentColor; padding:6px 8px; font-size:13px; vertical-align:top; text-align:left; width:50%; }
.agreement-body table.dosdonts th { font-weight:700; text-align:center; }
.agreement-body p.note { margin-top:14px; font-style:italic; }
`;

export const INLINE_FIELD_CSS = `
.agreement-body .inline-slot { display:inline; }
.agreement-body input.inline-input {
  display:inline; font:inherit; color:inherit; line-height:inherit;
  border:0; border-bottom:1.5px solid hsl(var(--primary) / 0.55); border-radius:0;
  background:hsl(var(--primary) / 0.04); padding:0 3px; margin:0 1px; min-width:2ch; outline:none;
  vertical-align:baseline;
  /* Never let a long blank (e.g. Settlement's mid-sentence ADDRESS) exceed the line and push the page
     sideways on mobile — cap at the container width and scroll the value inside the input instead. */
  max-width:100%;
}
.agreement-body input.inline-input::placeholder { color:hsl(var(--muted-foreground)); opacity:0.7; }
.agreement-body input.inline-input:focus { border-bottom-color:hsl(var(--primary)); background:hsl(var(--primary) / 0.09); }
.agreement-body input.inline-input[aria-invalid="true"] { border-bottom-color:hsl(var(--destructive)); background:hsl(var(--destructive) / 0.06); }
.agreement-body .inline-error { display:block; color:hsl(var(--destructive)); font-size:11px; margin-top:1px; }
.agreement-body .inline-sign-btn {
  display:inline-flex; align-items:center; gap:4px; font:inherit; font-size:12px; cursor:pointer;
  border:1px dashed hsl(var(--primary) / 0.6); border-radius:6px; background:hsl(var(--primary) / 0.06);
  color:hsl(var(--primary)); padding:2px 8px; vertical-align:middle;
}
.agreement-body .inline-sign-btn:hover { background:hsl(var(--primary) / 0.12); }
.agreement-body .inline-sign-wrap { display:inline-flex; align-items:center; gap:6px; vertical-align:middle; }
.agreement-body img.inline-sig { height:38px; width:auto; max-width:200px; background:#fff; border-radius:2px; vertical-align:middle; }
.agreement-body .inline-resign {
  font-size:11px; color:hsl(var(--muted-foreground)); cursor:pointer; text-decoration:underline; background:none; border:0; padding:0;
}
/* On phones: a larger tap target for mid-sentence blanks, and font-size:16px so tapping one never triggers
   iOS Safari's zoom-on-focus (any focused input under 16px zooms the page). */
@media (max-width: 640px) {
  .agreement-body input.inline-input { padding:3px 5px; min-width:3ch; font-size:16px; }
  .agreement-body .inline-sign-btn { padding:6px 12px; font-size:13px; }
}
`;

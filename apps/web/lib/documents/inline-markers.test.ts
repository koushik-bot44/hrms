import { describe, it, expect } from 'vitest';
import {
  parseMarkers,
  isSignatureMarker,
  unknownMarkers,
  seedValues,
  isValidAadhaar,
  fieldError,
  allFieldsValid,
  toAgreementComplete,
  toOffboardingComplete,
  toOfferAccept,
  type DocField,
} from './inline-markers';

// A representative display body: a mid-sentence text blank + a signature slot (the Settlement worst case).
const OFFB_HTML =
  '<p>Mr. <span class="inl"><span data-field="FATHER_NAME" data-kind="text"></span></span> aged about ' +
  '<span data-field="AGE" data-kind="text"></span> Years, residing at: ' +
  '<span data-field="ADDRESS" data-kind="text"></span></p>' +
  '<table class="ack"><tr><td class="sigcell"><span data-field="__signature" data-kind="signature"></span></td></tr></table>';

const OFFB_FIELDS: DocField[] = [
  { key: 'FATHER_NAME', label: "Father's name", kind: 'TEXT', value: '', required: true },
  { key: 'AGE', label: 'Age', kind: 'TEXT', value: '30', required: true },
  { key: 'ADDRESS', label: 'Address', kind: 'TEXT', value: '12 Marine Drive', required: true },
];

const AUP_FIELDS: DocField[] = [
  { key: 'designation', label: 'Designation', kind: 'TEXT', value: 'Engineer', required: false },
  { key: 'aadhaar', label: 'Aadhaar No.', kind: 'TEXT', value: '', required: true },
];

describe('parseMarkers', () => {
  it('extracts every marker in order (fields + signature)', () => {
    expect(parseMarkers(OFFB_HTML)).toEqual([
      { field: 'FATHER_NAME', kind: 'text' },
      { field: 'AGE', kind: 'text' },
      { field: 'ADDRESS', kind: 'text' },
      { field: '__signature', kind: 'signature' },
    ]);
  });

  it('distinguishes the signature marker', () => {
    const markers = parseMarkers(OFFB_HTML);
    expect(markers.filter(isSignatureMarker)).toHaveLength(1);
    expect(markers.filter((m) => !isSignatureMarker(m))).toHaveLength(3);
  });

  it('returns nothing for a body with no markers (e.g. Notice/Exit)', () => {
    expect(parseMarkers('<p>Nothing to fill here.</p>')).toEqual([]);
  });
});

describe('unknownMarkers (fail loudly on drift)', () => {
  it('flags a field marker with no manifest entry, exempting the signature', () => {
    expect(unknownMarkers(OFFB_HTML, OFFB_FIELDS)).toEqual([]);
    const drift = OFFB_HTML.replace('data-field="AGE"', 'data-field="MYSTERY"');
    expect(unknownMarkers(drift, OFFB_FIELDS)).toEqual(['MYSTERY']);
  });

  it('the signature marker never counts as unknown even with an empty manifest', () => {
    expect(unknownMarkers('<span data-field="__signature" data-kind="signature"></span>', [])).toEqual([]);
  });
});

describe('seedValues', () => {
  it('seeds editable values from the manifest prefills (revision reopen carries prior values here)', () => {
    expect(seedValues(OFFB_FIELDS)).toEqual({ FATHER_NAME: '', AGE: '30', ADDRESS: '12 Marine Drive' });
    expect(seedValues(AUP_FIELDS)).toEqual({ designation: 'Engineer', aadhaar: '' });
  });
});

describe('validation (relocated, unchanged rules)', () => {
  it('aadhaar requires exactly 12 digits, tolerating spaces/dashes', () => {
    expect(isValidAadhaar('1234 5678 9012')).toBe(true);
    expect(isValidAadhaar('1234-5678-9012')).toBe(true);
    expect(isValidAadhaar('12345678901')).toBe(false);
    expect(isValidAadhaar('abcd')).toBe(false);
    const aadhaar = AUP_FIELDS[1];
    expect(fieldError(aadhaar, '')).toMatch(/12-digit/);
    expect(fieldError(aadhaar, '123')).toMatch(/exactly 12/);
    expect(fieldError(aadhaar, '1234 5678 9012')).toBeNull();
  });

  it('required text fields must be non-blank; optional ones never error', () => {
    expect(fieldError(OFFB_FIELDS[0], '')).toMatch(/required/);
    expect(fieldError(OFFB_FIELDS[0], 'Ravi')).toBeNull();
    expect(fieldError(AUP_FIELDS[0], '')).toBeNull(); // designation optional
  });

  it('allFieldsValid gates on every field', () => {
    expect(allFieldsValid(AUP_FIELDS, { designation: 'Engineer', aadhaar: '' })).toBe(false);
    expect(allFieldsValid(AUP_FIELDS, { designation: 'Engineer', aadhaar: '123456789012' })).toBe(true);
  });
});

describe('payload builders equal the old panel shapes (contracts unchanged)', () => {
  it('agreement: AUP sends designation+aadhaar, NDA sends designation+address+mobile, Notice sends neither', () => {
    const values = { designation: 'Engineer', aadhaar: '123456789012', address: '12 MG Rd', mobile: '9999999999' };
    expect(toAgreementComplete('AUP', values, 'data:sig')).toEqual({
      consentAccepted: true,
      designation: 'Engineer',
      aadhaar: '123456789012',
      address: undefined,
      mobile: undefined,
      signatureDataUrl: 'data:sig',
    });
    expect(toAgreementComplete('NDA', values, 'data:sig')).toEqual({
      consentAccepted: true,
      designation: 'Engineer',
      aadhaar: undefined,
      address: '12 MG Rd',
      mobile: '9999999999',
      signatureDataUrl: 'data:sig',
    });
    expect(toAgreementComplete('NOTICE_PERIOD', {}, 'data:sig')).toEqual({
      consentAccepted: true,
      designation: undefined,
      aadhaar: undefined,
      address: undefined,
      mobile: undefined,
      signatureDataUrl: 'data:sig',
    });
  });

  it('offboarding: the fillValues map is the collected values verbatim', () => {
    const values = { FATHER_NAME: 'Ravi', AGE: '30', ADDRESS: '12 Marine Drive' };
    expect(toOffboardingComplete(values, 'data:sig')).toEqual({
      consentAccepted: true,
      fillValues: values,
      signatureDataUrl: 'data:sig',
    });
  });

  it('offer: consent + signature only', () => {
    expect(toOfferAccept('data:sig')).toEqual({ consentAccepted: true, signatureDataUrl: 'data:sig' });
  });
});

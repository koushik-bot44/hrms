package com.ihrms.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.agreement.AgreementTemplates;
import com.ihrms.domain.enums.EmployeeAgreementType;
import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.offboarding.OffboardingDocumentTemplates;
import com.ihrms.onboarding.OfferTemplates;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The DISPLAY render (no Spring, no DB) must emit stable inline field markers for EXACTLY the employee-fill
 * tokens of each document family, leave the server-substituted tokens as escaped text, render the signature
 * slot as a signature marker, and never leak a raw {@code {{TOKEN}}}. One document per family (offboarding
 * SETTLEMENT / agreement AUP / offer). This mirrors what the *Service.renderReadView methods build; the PDF
 * render path is a separate overload, covered untouched by the existing *RenderTest fidelity suites.
 */
class DocumentInlineMarkerTest {

  private static String marker(String key) {
    return "data-field=\"" + key + "\"";
  }

  // --- Offboarding family (SETTLEMENT): employee fills FATHER_NAME/AGE/ADDRESS + signs the ack line ------
  @Test
  void offboardingDisplayEmitsMarkersForExactlyTheEmployeeFillTokens() {
    OffboardingDocumentTemplates templates = new OffboardingDocumentTemplates();

    // Server-substituted tokens (company / HR / HR-values) render as escaped TEXT.
    Map<String, String> text = new LinkedHashMap<>();
    text.put("COMPANY_NAME", "Globex Corporation");
    text.put("HR_NAME", "Asha Rao");
    text.put("EMPLOYEE_NAME", "Meera Nair");
    text.put("AGREEMENT_DATE", "01/07/2026");
    text.put("EMPLOYMENT_START", "01/08/2020");
    text.put("LAST_DATE", "30/09/2026");
    text.put("DESIGNATION", "Software Engineer");
    text.put("SETTLEMENT_TERMS_LINE", "Full and final salary settled.");
    text.put("SIGN_DATE", "");

    // Employee-fill tokens render as INLINE MARKERS keyed by the manifest key.
    Map<String, String> markers = new LinkedHashMap<>();
    for (String key : new String[] {"FATHER_NAME", "AGE", "ADDRESS"}) {
      markers.put(key, DocumentFieldMarkers.field(key, "text"));
    }

    String body =
        templates.render(OffboardingDocType.SETTLEMENT, text, markers, DocumentFieldMarkers.SIGNATURE);

    // Every employee-fill token became a marker.
    assertThat(body).contains(marker("FATHER_NAME")).contains(marker("AGE")).contains(marker("ADDRESS"));
    // The signature slot is a signature marker.
    assertThat(body).contains("data-field=\"__signature\"").contains("data-kind=\"signature\"");
    // Server tokens stay TEXT, never markers.
    assertThat(body).contains("Globex Corporation").contains("Asha Rao");
    assertThat(body).doesNotContain(marker("COMPANY_NAME")).doesNotContain(marker("DESIGNATION"));
    // No raw token leaks.
    assertThat(body).doesNotContain("{{");
  }

  // --- Agreement family (AUP): employee fills designation/aadhaar (LOWERCASED keys) + signs -------------
  @Test
  void agreementDisplayEmitsMarkersForExactlyTheEmployeeFillTokens() {
    AgreementTemplates templates = new AgreementTemplates();

    Map<String, String> text = new LinkedHashMap<>();
    text.put("COMPANY_NAME", "Globex Corporation");
    text.put("HR_NAME", "Asha Rao");
    text.put("EMPLOYEE_NAME", "Meera Nair");
    text.put("EMPLOYEE_ID", "GLBX-EMP-000001");
    text.put("SIGN_DATE", "");

    // The AUP fill tokens (DESIGNATION, AADHAAR) map to markers keyed by the LOWERCASED token — the payload key.
    Map<String, String> markers = new LinkedHashMap<>();
    markers.put("DESIGNATION", DocumentFieldMarkers.field("designation", "text"));
    markers.put("AADHAAR", DocumentFieldMarkers.field("aadhaar", "text"));

    String body = templates.render(EmployeeAgreementType.AUP, text, markers, DocumentFieldMarkers.SIGNATURE);

    assertThat(body).contains(marker("designation")).contains(marker("aadhaar"));
    assertThat(body).contains("data-field=\"__signature\"").contains("data-kind=\"signature\"");
    assertThat(body).contains("Globex Corporation");
    // The marker keys are lowercase, so the UPPERCASE fragment tokens never survive as data-field values.
    assertThat(body).doesNotContain(marker("DESIGNATION")).doesNotContain(marker("AADHAAR"));
    assertThat(body).doesNotContain("{{");
  }

  // --- Offer family: company-issued, no employee-fill fields — the only inline marker is the signature --
  @Test
  void offerDisplayHasOnlyTheSignatureMarkerAndNoFillMarkers() {
    OfferTemplates templates = new OfferTemplates();

    Map<String, String> text = new LinkedHashMap<>();
    text.put("COMPANY_NAME", "Globex Corporation");
    text.put("EMPLOYEE_NAME", "Meera Nair");
    text.put("DESIGNATION", "Software Engineer");
    text.put("JOINING_DATE", "01/08/2026");
    text.put("LOCATION", "Hyderabad");
    text.put("SALARY", "Rs. 12,00,000");
    text.put("DATE", "01/07/2026");
    text.put("ACCEPT_NAME", "");
    text.put("ACCEPT_DATE", "");

    String body = templates.render(text, Map.of(), DocumentFieldMarkers.SIGNATURE);

    // The signature slot is a marker; the offer has NO employee-fill (text/date) markers.
    assertThat(body).contains("data-field=\"__signature\"").contains("data-kind=\"signature\"");
    assertThat(body).contains("Software Engineer"); // server-substituted, as text
    assertThat(body).doesNotContain("data-kind=\"text\"").doesNotContain("data-kind=\"date\"");
    assertThat(body).doesNotContain("{{");
  }
}

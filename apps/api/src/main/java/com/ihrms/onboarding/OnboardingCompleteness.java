package com.ihrms.onboarding;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Signature;
import com.ihrms.onboarding.dto.OnboardingDtos.CharacterReference;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import java.util.ArrayList;
import java.util.List;

/**
 * The submission gate for the four-form stepper (§3.2): Form 1 + Form 2 filled, at least two
 * character references, the required identity documents uploaded, and a signature captured. Returns
 * the list of missing items ({@code empty} = ready to submit). The web mirrors these rules for its
 * progress/CTA state; this is the authoritative server-side gate.
 */
public final class OnboardingCompleteness {

  private OnboardingCompleteness() {}

  public static List<String> missing(
      Form1Personal form1, Form2Info form2, List<Document> documents, Signature signature) {
    List<String> missing = new ArrayList<>();

    if (form1 == null) {
      missing.add("Complete Form 1 — Personal Details");
    } else {
      Form1View v = FormMappers.form1View(form1, FormMappers.Mode.PLAIN);
      if (isBlank(v.name())) {
        missing.add("Form 1 — enter your name");
      }
      long refs =
          v.characterReferences() == null
              ? 0
              : v.characterReferences().stream().filter(OnboardingCompleteness::refFilled).count();
      if (refs < 2) {
        missing.add("Form 1 — add at least two character references");
      }
    }

    if (form2 == null || isBlank(str(form2))) {
      missing.add("Complete Form 2 — Employee Info");
    }

    if (!hasUploaded(documents, DocumentType.AADHAAR)) {
      missing.add("Form 4 — upload your Aadhaar");
    }
    if (!hasUploaded(documents, DocumentType.PAN)) {
      missing.add("Form 4 — upload your PAN");
    }

    if (signature == null) {
      missing.add("Sign to submit");
    }

    return missing;
  }

  public static boolean isComplete(
      Form1Personal form1, Form2Info form2, List<Document> documents, Signature signature) {
    return missing(form1, form2, documents, signature).isEmpty();
  }

  private static boolean refFilled(CharacterReference r) {
    return r != null && !isBlank(r.name());
  }

  private static String str(Form2Info form2) {
    Object v = form2.getData() == null ? null : form2.getData().get("fullName");
    return v == null ? null : v.toString();
  }

  private static boolean hasUploaded(List<Document> documents, DocumentType type) {
    return documents.stream()
        .anyMatch(d -> d.getDocType() == type && d.getStatus() != DocumentStatus.PENDING);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}

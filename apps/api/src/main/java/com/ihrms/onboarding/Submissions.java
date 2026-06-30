package com.ihrms.onboarding;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.ProfileSection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The submission gate (§3.7), mirroring the shared {@code evaluateSubmission}: required sections
 * saved + required documents in a done status. The web shares the same rules for its progress bar.
 */
public final class Submissions {

  private static final List<SectionKey> REQUIRED_SECTIONS =
      List.of(SectionKey.PERSONAL, SectionKey.GOVERNMENT);

  private static final List<RequiredDoc> REQUIRED_DOCUMENTS =
      List.of(new RequiredDoc(SectionKey.GOVERNMENT, DocumentType.PAN));

  private static final Set<DocumentStatus> DONE =
      EnumSet.of(DocumentStatus.UPLOADED, DocumentStatus.VERIFIED);

  private record RequiredDoc(SectionKey sectionKey, DocumentType docType) {}

  private Submissions() {}

  public static boolean isComplete(List<ProfileSection> sections, List<Document> documents) {
    Set<SectionKey> savedKeys =
        sections.stream().map(ProfileSection::getKey).collect(Collectors.toSet());
    boolean sectionsComplete = savedKeys.containsAll(REQUIRED_SECTIONS);
    boolean documentsComplete =
        REQUIRED_DOCUMENTS.stream()
            .allMatch(
                req ->
                    documents.stream()
                        .anyMatch(
                            d ->
                                d.getSectionKey() == req.sectionKey()
                                    && d.getDocType() == req.docType()
                                    && DONE.contains(d.getStatus())));
    return sectionsComplete && documentsComplete;
  }
}

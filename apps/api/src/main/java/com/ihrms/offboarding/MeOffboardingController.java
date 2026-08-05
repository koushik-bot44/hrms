package com.ihrms.offboarding;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.offboarding.dto.OffboardingDocDtos.CompleteDocRequest;
import com.ihrms.offboarding.dto.OffboardingDocDtos.CompleteDocResult;
import com.ihrms.offboarding.dto.OffboardingDocDtos.MyDocSummary;
import com.ihrms.offboarding.dto.OffboardingDocDtos.MyDocView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The employee's own offboarding documents (§3.6 stage 2), read + filled + signed in the workspace. EMPLOYEE-
 * only; scoped to the signed-in employee's own approved case. Completion notifies the sending HR post-commit.
 */
@RestController
@RequestMapping("/me/offboarding")
@PreAuthorize("hasRole('EMPLOYEE')")
public class MeOffboardingController {

  private final OffboardingDocumentService documents;

  public MeOffboardingController(OffboardingDocumentService documents) {
    this.documents = documents;
  }

  @GetMapping
  public List<MyDocSummary> myDocuments(@AuthenticationPrincipal IhrmsPrincipal.Employee emp) {
    return documents.myDocuments(emp);
  }

  @GetMapping("/{type}")
  public MyDocView myDocument(
      @PathVariable OffboardingDocType type, @AuthenticationPrincipal IhrmsPrincipal.Employee emp) {
    return documents.myDocument(emp, type);
  }

  @PostMapping("/{type}/complete")
  public CompleteDocResult complete(
      @PathVariable OffboardingDocType type,
      @RequestBody CompleteDocRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    CompleteDocResult result = documents.complete(emp, type, body, request.getRemoteAddr());
    documents.notifyHrAfterSubmit(emp.employeeId(), type); // post-commit, best-effort
    return result;
  }
}

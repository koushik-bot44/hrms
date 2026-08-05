package com.ihrms.agreement;

import com.ihrms.agreement.dto.AgreementDtos.CompleteAgreementRequest;
import com.ihrms.agreement.dto.AgreementDtos.CompleteAgreementResult;
import com.ihrms.agreement.dto.AgreementDtos.MyAgreementSummary;
import com.ihrms.agreement.dto.AgreementDtos.MyAgreementView;
import com.ihrms.agreement.dto.AgreementDtos.SendAgreementsRequest;
import com.ihrms.agreement.dto.AgreementDtos.SendAgreementsResult;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.EmployeeAgreementType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post-approval agreements (§Agreements). HR sends the standard pack to an APPROVED employee; the employee
 * reads, fills, signs, and submits each one under {@code /me/agreements}. Notifications fire post-commit
 * (best-effort), mirroring the approve/leave pattern — a failure there never undoes the committed change.
 */
@RestController
public class AgreementController {

  private final AgreementService agreements;

  public AgreementController(AgreementService agreements) {
    this.agreements = agreements;
  }

  // --- HR: send -------------------------------------------------------------

  @PostMapping("/employees/{id}/agreements/send")
  @PreAuthorize("hasRole('HR')")
  @ResponseStatus(HttpStatus.CREATED)
  public SendAgreementsResult send(
      @PathVariable String id,
      @RequestBody(required = false) SendAgreementsRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    SendAgreementsResult result =
        agreements.send(actor, id, body == null ? null : body.types(), request.getRemoteAddr());
    agreements.notifyEmployeeAfterSend(id); // post-commit, best-effort
    return result;
  }

  // --- Employee: read + complete --------------------------------------------

  @GetMapping("/me/agreements")
  @PreAuthorize("hasRole('EMPLOYEE')")
  public List<MyAgreementSummary> myAgreements(@AuthenticationPrincipal IhrmsPrincipal.Employee emp) {
    return agreements.myAgreements(emp);
  }

  @GetMapping("/me/agreements/{type}")
  @PreAuthorize("hasRole('EMPLOYEE')")
  public MyAgreementView myAgreement(
      @PathVariable EmployeeAgreementType type,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp) {
    return agreements.myAgreement(emp, type);
  }

  @PostMapping("/me/agreements/{type}/complete")
  @PreAuthorize("hasRole('EMPLOYEE')")
  public CompleteAgreementResult complete(
      @PathVariable EmployeeAgreementType type,
      @RequestBody CompleteAgreementRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    CompleteAgreementResult result = agreements.complete(emp, type, body, request.getRemoteAddr());
    agreements.notifyHrAfterComplete(emp.employeeId(), type); // post-commit, best-effort
    return result;
  }
}

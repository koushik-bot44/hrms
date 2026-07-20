package com.ihrms.requests;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.requests.dto.DocumentRequestDtos.DocumentRequestView;
import com.ihrms.requests.dto.DocumentRequestDtos.MyRequestsPage;
import com.ihrms.requests.dto.DocumentRequestDtos.PresignedView;
import com.ihrms.requests.dto.DocumentRequestDtos.RequestUpload;
import com.ihrms.requests.dto.DocumentRequestDtos.ResolveRequest;
import com.ihrms.requests.dto.DocumentRequestDtos.SubmitDocumentRequest;
import com.ihrms.requests.dto.DocumentRequestDtos.TeamRequestRow;
import com.ihrms.requests.dto.DocumentRequestDtos.TeamRequestsPage;
import com.ihrms.requests.dto.DocumentRequestDtos.UploadDocumentRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR/Accounts document requests — Accounts side (§8d). The employee endpoints ({@code POST /requests},
 * {@code /me}, {@code /{id}/cancel}) act as the credentialed employee — the service 403s a non-employee /
 * uncredentialed principal. The {@code /requests/team/**} endpoints are ACCOUNTANT-only (URL-gated in
 * SecurityConfig) and scoped in the service to the requests routed to the acting Accountant. The document
 * download is dual-role (the request's own employee OR the routed accountant) — authorized in the service.
 */
@RestController
@RequestMapping("/requests")
public class DocumentRequestController {

  private final DocumentRequestService requests;

  public DocumentRequestController(DocumentRequestService requests) {
    this.requests = requests;
  }

  // --- Employee ---

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentRequestView submit(
      @Valid @RequestBody SubmitDocumentRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    DocumentRequestView view = requests.submit(actor, body, request.getRemoteAddr());
    // Post-commit best-effort OS push to the routed accountant (never blocks the request). §8d
    requests.pushAccountantAfterSubmit(view.id());
    return view;
  }

  @GetMapping("/me")
  public MyRequestsPage me(
      @AuthenticationPrincipal IhrmsPrincipal actor, @PageableDefault(size = 20) Pageable pageable) {
    return requests.myRequests(actor, pageable);
  }

  @PostMapping("/{id}/cancel")
  public DocumentRequestView cancel(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return requests.cancel(actor, id, request.getRemoteAddr());
  }

  // --- Download (the request's own employee OR the routed accountant) ---

  @GetMapping("/{id}/documents/{docId}/download")
  public PresignedView download(
      @PathVariable String id,
      @PathVariable String docId,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return requests.download(actor, id, docId, request.getRemoteAddr());
  }

  // --- Accountant (team-scope) ---

  @GetMapping("/team")
  public TeamRequestsPage team(
      @RequestParam(required = false) String status,
      @AuthenticationPrincipal IhrmsPrincipal.User accountant,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    return requests.teamQueue(accountant, status, pageable);
  }

  @PostMapping("/team/{id}/pick-up")
  public TeamRequestRow pickUp(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User accountant,
      HttpServletRequest request) {
    return requests.pickUp(accountant, id, request.getRemoteAddr());
  }

  @PostMapping("/team/{id}/documents/upload-url")
  @ResponseStatus(HttpStatus.CREATED)
  public RequestUpload uploadUrl(
      @PathVariable String id,
      @Valid @RequestBody UploadDocumentRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User accountant,
      HttpServletRequest request) {
    return requests.requestDocumentUpload(accountant, id, body, request.getRemoteAddr());
  }

  @PostMapping("/team/{id}/resolve")
  public TeamRequestRow resolve(
      @PathVariable String id,
      @Valid @RequestBody ResolveRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User accountant,
      HttpServletRequest request) {
    TeamRequestRow row = requests.resolve(accountant, id, body, request.getRemoteAddr());
    // Post-commit best-effort employee email + OS push (never blocks the resolve). §8d
    requests.notifyEmployeeAfterResolve(id);
    return row;
  }
}

package com.ihrms.leave;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.leave.dto.LeaveDtos.LeaveDecisionRequest;
import com.ihrms.leave.dto.LeaveDtos.LeaveMinePage;
import com.ihrms.leave.dto.LeaveDtos.LeaveRequestView;
import com.ihrms.leave.dto.LeaveDtos.SubmitLeaveRequest;
import com.ihrms.leave.dto.LeaveDtos.TeamLeavePage;
import com.ihrms.leave.dto.LeaveDtos.TeamLeaveRow;
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
 * Leave requests (§8b). Employee endpoints ({@code POST /leave}, {@code /me}, {@code /{id}/cancel}) act as
 * the credentialed employee — the service 403s a non-employee / uncredentialed principal. The
 * {@code /leave/team/**} endpoints are MANAGER-only (URL-gated in SecurityConfig) and scoped to the requests
 * routed to the acting Manager.
 */
@RestController
@RequestMapping("/leave")
public class LeaveController {

  private final LeaveService leave;

  public LeaveController(LeaveService leave) {
    this.leave = leave;
  }

  // --- Employee ---

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LeaveRequestView submit(
      @Valid @RequestBody SubmitLeaveRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    LeaveRequestView view = leave.submit(actor, body, request.getRemoteAddr());
    // Post-commit courtesy mail to the Manager (best-effort; never blocks the request). §8b
    leave.mailManagerAfterSubmit(actor, view.id(), request.getRemoteAddr());
    return view;
  }

  @GetMapping("/me")
  public LeaveMinePage me(
      @AuthenticationPrincipal IhrmsPrincipal actor, @PageableDefault(size = 20) Pageable pageable) {
    return leave.myHistory(actor, pageable);
  }

  @PostMapping("/{id}/cancel")
  public LeaveRequestView cancel(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return leave.cancel(actor, id, request.getRemoteAddr());
  }

  // --- Manager (team-scope) ---

  @GetMapping("/team")
  public TeamLeavePage team(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @AuthenticationPrincipal IhrmsPrincipal.User manager,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    return leave.teamQueue(manager, status, from, to, pageable);
  }

  @PostMapping("/team/{id}/approve")
  public TeamLeaveRow approve(
      @PathVariable String id,
      @RequestBody(required = false) LeaveDecisionRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User manager,
      HttpServletRequest request) {
    TeamLeaveRow row =
        leave.decide(manager, id, true, body == null ? null : body.note(), request.getRemoteAddr());
    leave.mailEmployeeAfterDecision(manager, id, request.getRemoteAddr()); // best-effort §8b
    return row;
  }

  @PostMapping("/team/{id}/reject")
  public TeamLeaveRow reject(
      @PathVariable String id,
      @Valid @RequestBody LeaveDecisionRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User manager,
      HttpServletRequest request) {
    TeamLeaveRow row = leave.decide(manager, id, false, body.note(), request.getRemoteAddr());
    leave.mailEmployeeAfterDecision(manager, id, request.getRemoteAddr()); // best-effort §8b
    return row;
  }
}

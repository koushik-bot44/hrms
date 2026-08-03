package com.ihrms.manager;

import com.ihrms.accountant.dto.AccountantDtos.MyTeamView;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.manager.dto.ManagerDtos.ApprovalView;
import com.ihrms.manager.dto.ManagerDtos.NotificationFeed;
import com.ihrms.manager.dto.ManagerDtos.NotificationView;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
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
 * Manager inbox (contract §2/§3.3). MANAGER-only (URL rule + @PreAuthorize); scoped to the acting
 * Manager's own notifications + their team's approvals.
 */
@RestController
@RequestMapping("/manager")
@PreAuthorize("hasRole('MANAGER')")
public class ManagerController {

  private final ManagerService manager;

  public ManagerController(ManagerService manager) {
    this.manager = manager;
  }

  /** The Manager's own team descriptor — the {@code teamId} the attendance-analytics tab passes to the
   * shared viewer components (§8a). {@code null} when the Manager is not assigned to any team. */
  @GetMapping("/my-team")
  public MyTeamView myTeam(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.myTeam(actor);
  }

  @GetMapping("/notifications")
  public NotificationFeed notifications(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.notifications(actor);
  }

  @PostMapping("/notifications/{id}/read")
  public NotificationView markRead(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.markRead(actor, id);
  }

  @PostMapping("/notifications/read-all")
  public NotificationFeed markAllRead(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.markAllRead(actor);
  }

  /**
   * The Manager's READ-ONLY team-onboarding history (§3.3) — the employees decided onto their team
   * (approved, and any legacy manager-era rejects), most recent first. Approval authority now sits with HR;
   * the Manager has no approve/reject/pending-inbox action.
   */
  @GetMapping("/approvals/history")
  public List<ApprovalView> approvalHistory(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.approvalHistory(actor);
  }

  @GetMapping("/approvals/{id}/record")
  public EmployeeRecordView approvalRecord(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.recordForApproval(actor, id);
  }
}

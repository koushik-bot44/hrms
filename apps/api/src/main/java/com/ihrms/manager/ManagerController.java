package com.ihrms.manager;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.manager.dto.ManagerDtos.ApprovalView;
import com.ihrms.manager.dto.ManagerDtos.NotificationFeed;
import com.ihrms.manager.dto.ManagerDtos.NotificationView;
import com.ihrms.manager.dto.ManagerDtos.RejectApprovalRequest;
import jakarta.validation.Valid;
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

  @GetMapping("/approvals")
  public List<ApprovalView> approvals(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.pendingApprovals(actor);
  }

  @GetMapping("/approvals/history")
  public List<ApprovalView> approvalHistory(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.approvalHistory(actor);
  }

  @PostMapping("/approvals/{id}/approve")
  public ApprovalView approve(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.approve(actor, id);
  }

  @PostMapping("/approvals/{id}/reject")
  public ApprovalView reject(
      @PathVariable String id,
      @Valid @RequestBody RejectApprovalRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return manager.reject(actor, id, body);
  }
}

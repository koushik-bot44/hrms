package com.ihrms.push;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.push.dto.PushDtos.PruneResult;
import com.ihrms.push.dto.PushDtos.PublicKeyResponse;
import com.ihrms.push.dto.PushDtos.SubscribeRequest;
import com.ihrms.push.dto.PushDtos.SubscribeResult;
import com.ihrms.push.dto.PushDtos.TestResult;
import com.ihrms.push.dto.PushDtos.UnsubscribeRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web Push endpoints (§ Web Push, Stage N1). Any authenticated principal (staff, or a credentialed
 * employee) may register their browser subscription and fire a self-test. The service refuses an
 * uncredentialed employee (403). Subscriptions belong to the ACTING principal — no cross-user access.
 */
@RestController
@RequestMapping("/push")
public class PushController {

  private final PushService push;

  public PushController(PushService push) {
    this.push = push;
  }

  @Operation(summary = "The VAPID public key the browser needs to create a PushSubscription.")
  @GetMapping("/public-key")
  public PublicKeyResponse publicKey() {
    return new PublicKeyResponse(push.publicKey(), push.isEnabled());
  }

  @Operation(summary = "Register (upsert on endpoint) a browser push subscription for the caller.")
  @PostMapping("/subscribe")
  @ResponseStatus(HttpStatus.CREATED)
  public SubscribeResult subscribe(
      @Valid @RequestBody SubscribeRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return push.subscribe(actor, body, request.getRemoteAddr());
  }

  @Operation(summary = "Remove the caller's own push subscription (by endpoint).")
  @DeleteMapping("/unsubscribe")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unsubscribe(
      @Valid @RequestBody UnsubscribeRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    push.unsubscribe(actor, body.endpoint(), request.getRemoteAddr());
  }

  @Operation(summary = "N1 verification: send a test notification to the caller's OWN subscriptions.")
  @PostMapping("/test")
  public TestResult test(@AuthenticationPrincipal IhrmsPrincipal actor) {
    return push.sendTest(actor);
  }

  @Operation(
      summary =
          "SUPER_ADMIN one-off: prune push subscriptions older than N days (e.g. clear pre-migration,"
              + " old-origin subscriptions after a web-origin change). Users re-enable via the opt-in.")
  @PostMapping("/admin/prune-stale")
  public PruneResult pruneStale(
      @RequestParam(defaultValue = "1") int olderThanDays,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    int removed = push.pruneStale(actor, olderThanDays, request.getRemoteAddr());
    return new PruneResult(removed, "Pruned " + removed + " subscription(s).");
  }
}

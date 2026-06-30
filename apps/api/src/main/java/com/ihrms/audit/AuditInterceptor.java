package com.ihrms.audit;

import com.ihrms.auth.IhrmsPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Logs every SUCCESSFUL mutating request to the AuditLog (§7) with actor context. The
 * actor comes from the request attribute {@link #AUDIT_ACTOR_ATTR} (set by the @Public
 * auth handlers, which have no SecurityContext) or the authenticated principal.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

  /** Request attribute the auth controller sets so login/verify-otp log the real actor. */
  public static final String AUDIT_ACTOR_ATTR = "ihrms.auditActor";

  private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final AuditService audit;

  public AuditInterceptor(AuditService audit) {
    this.audit = audit;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    if (ex != null || !MUTATING.contains(request.getMethod()) || response.getStatus() >= 400) {
      return;
    }
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String path = pattern != null ? pattern.toString() : request.getRequestURI();
    audit.record(
        resolveActor(request),
        request.getMethod() + " " + path,
        null,
        null,
        Map.of("statusCode", response.getStatus()),
        request.getRemoteAddr());
  }

  private AuditActor resolveActor(HttpServletRequest request) {
    if (request.getAttribute(AUDIT_ACTOR_ATTR) instanceof AuditActor actor) {
      return actor;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof IhrmsPrincipal principal) {
      return AuditActor.from(principal);
    }
    return AuditActor.SYSTEM;
  }
}

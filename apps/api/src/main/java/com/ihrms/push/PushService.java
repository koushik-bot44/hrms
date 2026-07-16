package com.ihrms.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.config.AppProperties;
import com.ihrms.domain.model.PushSubscription;
import com.ihrms.domain.repository.PushSubscriptionRepository;
import com.ihrms.push.dto.PushDtos.SubscribeRequest;
import com.ihrms.push.dto.PushDtos.SubscribeResult;
import com.ihrms.push.dto.PushDtos.TestResult;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nl.martijndwars.webpush.Notification;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Web Push delivery (§ Web Push, Stage N1). Stores a principal's browser {@link PushSubscription}s and
 * POSTs VAPID-signed, encrypted payloads to their endpoints via the {@code nl.martijndwars.web-push}
 * library. Sends are BEST-EFFORT — failures are logged, never thrown to callers; a {@code 404/410} from
 * the push service prunes the now-dead subscription. The lib client is built from the VAPID env keys at
 * startup: absent keys DISABLE push in dev (logged) and REFUSE startup in the {@code prod} profile.
 */
@Service
public class PushService {

  private static final Logger log = LoggerFactory.getLogger(PushService.class);

  private final AppProperties props;
  private final PushSubscriptionRepository subscriptions;
  private final AuditService audit;
  private final ObjectMapper json;
  private final Environment env;

  /** The web-push library client — null when VAPID is not configured (push disabled). */
  private nl.martijndwars.webpush.PushService webPush;

  public PushService(
      AppProperties props,
      PushSubscriptionRepository subscriptions,
      AuditService audit,
      ObjectMapper json,
      Environment env) {
    this.props = props;
    this.subscriptions = subscriptions;
    this.audit = audit;
    this.json = json;
    this.env = env;
  }

  @PostConstruct
  void init() {
    AppProperties.Vapid v = props.vapid();
    if (!v.isConfigured()) {
      if (isProd()) {
        throw new IllegalStateException(
            "Web Push misconfigured: VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY and VAPID_SUBJECT must be set"
                + " in production. Generate a keypair with: npx web-push generate-vapid-keys");
      }
      log.warn(
          "Web Push DISABLED — VAPID_* not set. OS notifications will be skipped. Set VAPID_PUBLIC_KEY /"
              + " VAPID_PRIVATE_KEY / VAPID_SUBJECT to enable (npx web-push generate-vapid-keys).");
      return;
    }
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    try {
      this.webPush =
          new nl.martijndwars.webpush.PushService(v.publicKey(), v.privateKey(), v.subject());
      log.info("Web Push ENABLED (VAPID configured; subject {}).", v.subject());
    } catch (GeneralSecurityException e) {
      if (isProd()) {
        throw new IllegalStateException("Invalid VAPID keys: " + e.getMessage(), e);
      }
      log.warn("Web Push DISABLED — invalid VAPID keys: {}", e.getMessage());
    }
  }

  private boolean isProd() {
    return env.acceptsProfiles(Profiles.of("prod"));
  }

  /** True when the server can actually deliver Web Push (VAPID configured + client built). */
  public boolean isEnabled() {
    return webPush != null;
  }

  /** The VAPID public key the browser needs to subscribe (empty when push is not configured). */
  public String publicKey() {
    return props.vapid().publicKey() == null ? "" : props.vapid().publicKey();
  }

  // --- Subscription management ----------------------------------------------

  /** Register (upsert on endpoint) a subscription for the acting principal. Audited {@code PUSH_SUBSCRIBED}. */
  @Transactional
  public SubscribeResult subscribe(IhrmsPrincipal actor, SubscribeRequest req, String ip) {
    PrincipalRef ref = requireMailboxCapable(actor);
    Optional<PushSubscription> existing = subscriptions.findByEndpoint(req.endpoint());
    PushSubscription sub = existing.orElseGet(PushSubscription::new);
    // A browser == one endpoint: re-subscribing (or a different principal on the same browser) reassigns it.
    sub.setUserId(ref.userId());
    sub.setEmployeeId(ref.employeeId());
    sub.setCompanyId(ref.companyId());
    sub.setEndpoint(req.endpoint());
    sub.setP256dh(req.keys().p256dh());
    sub.setAuth(req.keys().auth());
    sub.setUserAgent(req.userAgent());
    subscriptions.save(sub);
    audit.record(
        AuditActor.from(actor),
        "PUSH_SUBSCRIBED",
        "PushSubscription",
        sub.getId(),
        Map.of("endpoint", req.endpoint()),
        ip);
    return new SubscribeResult(sub.getId(), existing.isPresent());
  }

  /** Remove the caller's own subscription (idempotent). Audited {@code PUSH_UNSUBSCRIBED}. */
  @Transactional
  public void unsubscribe(IhrmsPrincipal actor, String endpoint, String ip) {
    PrincipalRef ref = requireMailboxCapable(actor);
    Optional<PushSubscription> existing = subscriptions.findByEndpoint(endpoint);
    if (existing.isEmpty()) {
      return; // already gone — idempotent
    }
    PushSubscription sub = existing.get();
    if (!ref.owns(sub)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your subscription");
    }
    subscriptions.delete(sub);
    audit.record(
        AuditActor.from(actor),
        "PUSH_UNSUBSCRIBED",
        "PushSubscription",
        sub.getId(),
        Map.of("endpoint", endpoint),
        ip);
  }

  // --- Delivery -------------------------------------------------------------

  /**
   * Send a notification to every subscription owned by {@code ref}. Best-effort: never throws; a dead
   * endpoint ({@code 404/410}) is pruned. This is the seam N3 (mail events) will call.
   */
  public void sendToPrincipal(PrincipalRef ref, String title, String body, String url) {
    if (webPush == null) {
      log.debug("Web Push disabled — skipping notification '{}'.", title);
      return;
    }
    List<PushSubscription> subs = load(ref);
    if (subs.isEmpty()) {
      return;
    }
    String payload = buildPayload(title, body, url);
    for (PushSubscription sub : subs) {
      deliver(sub, payload);
    }
  }

  /** The N1 verification trigger: notify the CALLER's own subscriptions only. Replaced by mail events in N3. */
  public TestResult sendTest(IhrmsPrincipal actor) {
    PrincipalRef ref = requireMailboxCapable(actor);
    if (webPush == null) {
      return new TestResult(0, "Web Push is not configured on the server.");
    }
    List<PushSubscription> subs = load(ref);
    if (subs.isEmpty()) {
      return new TestResult(0, "No subscriptions yet — enable notifications first.");
    }
    sendToPrincipal(
        ref,
        "IHRMS test notification",
        "If you can see this, Web Push is working end to end 🎉",
        props.webAppUrl());
    return new TestResult(subs.size(), "Sent to " + subs.size() + " subscription(s).");
  }

  private void deliver(PushSubscription sub, String payload) {
    try {
      Notification notification =
          new Notification(
              sub.getEndpoint(),
              sub.getP256dh(),
              sub.getAuth(),
              payload.getBytes(StandardCharsets.UTF_8));
      HttpResponse response = webPush.send(notification);
      handleStatus(sub, response.getStatusLine().getStatusCode());
    } catch (Exception e) {
      // Best-effort: a single bad endpoint must never break the caller or the fan-out.
      log.warn("Push send failed for subscription {}: {}", sub.getId(), e.toString());
    }
  }

  /**
   * React to the push service's HTTP status: prune a GONE subscription (404/410), stamp last-used on
   * success (2xx), log anything else. Package-visible so the prune/stamp policy is unit-testable without
   * a live push service.
   */
  void handleStatus(PushSubscription sub, int status) {
    if (status == 404 || status == 410) {
      subscriptions.deleteById(sub.getId());
      log.info("Pruned stale push subscription {} (push service returned {}).", sub.getId(), status);
    } else if (status >= 200 && status < 300) {
      subscriptions
          .findById(sub.getId())
          .ifPresent(
              fresh -> {
                fresh.setLastUsedAt(Instant.now());
                subscriptions.save(fresh);
              });
    } else {
      log.warn("Push to subscription {} returned HTTP {}.", sub.getId(), status);
    }
  }

  private List<PushSubscription> load(PrincipalRef ref) {
    return ref.userId() != null
        ? subscriptions.findByUserId(ref.userId())
        : subscriptions.findByEmployeeId(ref.employeeId());
  }

  private String buildPayload(String title, String body, String url) {
    Map<String, String> data = new LinkedHashMap<>();
    data.put("title", title);
    data.put("body", body);
    data.put("url", url == null ? props.webAppUrl() : url);
    try {
      return json.writeValueAsString(data);
    } catch (Exception e) {
      // A plain-text fallback the Service Worker still renders.
      return "{\"title\":\"IHRMS\",\"body\":\"" + body + "\"}";
    }
  }

  private PrincipalRef requireMailboxCapable(IhrmsPrincipal actor) {
    if (actor instanceof IhrmsPrincipal.User u) {
      return PrincipalRef.forUser(u.userId(), u.companyId());
    }
    IhrmsPrincipal.Employee e = (IhrmsPrincipal.Employee) actor;
    if (e.mailAddress() == null) {
      // Only mailbox-capable principals may subscribe (staff always; employee only once credentialed).
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have a mailbox yet");
    }
    return PrincipalRef.forEmployee(e.employeeId(), e.companyId());
  }

  /** Which principal owns a subscription — a staff user OR a credentialed employee (never both). */
  public record PrincipalRef(String userId, String employeeId, String companyId) {
    public static PrincipalRef forUser(String userId, String companyId) {
      return new PrincipalRef(userId, null, companyId);
    }

    public static PrincipalRef forEmployee(String employeeId, String companyId) {
      return new PrincipalRef(null, employeeId, companyId);
    }

    boolean owns(PushSubscription sub) {
      return (userId != null && userId.equals(sub.getUserId()))
          || (employeeId != null && employeeId.equals(sub.getEmployeeId()));
    }
  }
}

package com.ihrms.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.email.Mailer;
import com.ihrms.email.OutboundEmail;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The PUBLIC contact endpoint (§8d): unauthenticated, bean-validated, anti-spam'd, rate-limited, and it emails
 * the submission (no persistence). Asserts on the spied Mailer — zero network (DevLogMailer under test).
 */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class ContactApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ContactRateLimiter limiter;
  @SpyBean Mailer mailer;

  @BeforeEach
  void resetLimiter() {
    limiter.reset();
  }

  private Map<String, Object> valid() {
    Map<String, Object> m = new HashMap<>();
    m.put("name", "Alex Prospect");
    m.put("email", "alex@acme.test");
    m.put("organization", "Acme Corp");
    m.put("message", "We run onboarding on spreadsheets and want it operated for us.");
    m.put("website", ""); // honeypot empty
    m.put("elapsedMs", 5000); // past the min fill time
    return m;
  }

  private MockHttpServletRequestBuilder submit(Map<String, Object> body) throws Exception {
    return post("/public/contact")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  // --- happy path: emailed with the right To / Reply-To / subject ------------
  @Test
  void validSubmissionEmailsTheEnquiryWithSubmitterAsReplyTo() throws Exception {
    mvc.perform(submit(valid())).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

    ArgumentCaptor<OutboundEmail> captor = ArgumentCaptor.forClass(OutboundEmail.class);
    verify(mailer).send(captor.capture());
    OutboundEmail sent = captor.getValue();
    assertThat(sent.to()).isEqualTo("info@hrorg.in");
    assertThat(sent.subject()).isEqualTo("New enquiry — Acme Corp");
    assertThat(sent.replyTo()).isEqualTo("alex@acme.test"); // reply reaches the prospect, not support@
  }

  // --- optional phone: carried into the email body when provided -------------
  @Test
  void phoneWhenProvidedAppearsInTheEmailBody() throws Exception {
    Map<String, Object> body = valid();
    body.put("phone", "+91 98765 43210");
    mvc.perform(submit(body)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

    ArgumentCaptor<OutboundEmail> captor = ArgumentCaptor.forClass(OutboundEmail.class);
    verify(mailer).send(captor.capture());
    assertThat(captor.getValue().text()).contains("Phone: +91 98765 43210");
  }

  @Test
  void badPhoneIsRejected() throws Exception {
    Map<String, Object> bad = valid();
    bad.put("phone", "call-me-maybe"); // letters aren't a phone number
    mvc.perform(submit(bad)).andExpect(status().isBadRequest());
    verify(mailer, never()).send(any());
  }

  // --- validation ------------------------------------------------------------
  @Test
  void validationRejectsMissingAndBadFields() throws Exception {
    Map<String, Object> bad = valid();
    bad.remove("name");
    bad.put("email", "not-an-email");
    bad.put("organization", "x"); // too short
    mvc.perform(submit(bad)).andExpect(status().isBadRequest());
    verify(mailer, never()).send(any());
  }

  // --- anti-spam: honeypot -> silent 200, no send ----------------------------
  @Test
  void honeypotFilledSilentlySucceedsWithoutSending() throws Exception {
    Map<String, Object> spam = valid();
    spam.put("website", "http://spam.example");
    mvc.perform(submit(spam)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    verify(mailer, never()).send(any());
  }

  // --- anti-spam: too-fast submit -> silent 200, no send ---------------------
  @Test
  void tooFastSubmitSilentlySucceedsWithoutSending() throws Exception {
    Map<String, Object> fast = valid();
    fast.put("elapsedMs", 500); // < 3s
    mvc.perform(submit(fast)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    verify(mailer, never()).send(any());
  }

  // --- rate limit: 429 after the per-IP threshold ----------------------------
  @Test
  void rateLimitReturns429AfterThreshold() throws Exception {
    RequestPostProcessor ip = remoteAddr("9.9.9.9");
    for (int i = 0; i < ContactRateLimiter.PER_IP_LIMIT; i++) {
      mvc.perform(submit(valid()).with(ip)).andExpect(status().isOk());
    }
    mvc.perform(submit(valid()).with(ip))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Too many requests")));
    verify(mailer, org.mockito.Mockito.times(ContactRateLimiter.PER_IP_LIMIT)).send(any());
  }

  // --- public reachability + nothing else widened ----------------------------
  @Test
  void endpointIsPublicButNoOtherPathWidened() throws Exception {
    // reachable with no auth (the happy-path test already proved 200); an authed-only path still 401s.
    mvc.perform(get("/companies")).andExpect(status().isUnauthorized());
  }

  /** Pin the client IP for the rate-limit test (the limiter keys on getRemoteAddr). */
  private static RequestPostProcessor remoteAddr(String ip) {
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }
}

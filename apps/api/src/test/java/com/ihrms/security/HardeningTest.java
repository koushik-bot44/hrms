package com.ihrms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security hardening (§6): the public auth endpoints are per-IP rate-limited (429 over the limit),
 * and Spring Security emits the hardened response headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class HardeningTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void authEndpointsAreRateLimitedPerIp() throws Exception {
    // A dedicated client IP gets its own budget; the first request is a normal 401, and once the
    // per-minute limit is exceeded the endpoint returns 429.
    String body = json.writeValueAsString(Map.of("email", "nobody@x.test", "password", "Password1"));

    int first =
        mvc.perform(
                post("/auth/login")
                    .with(remoteAddr("10.20.30.40"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(first).isNotEqualTo(429);

    int last = first;
    for (int i = 0; i < 65; i++) {
      last =
          mvc.perform(
                  post("/auth/login")
                      .with(remoteAddr("10.20.30.40"))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andReturn()
              .getResponse()
              .getStatus();
    }
    assertThat(last).isEqualTo(429);
  }

  @Test
  void responsesCarryHardenedSecurityHeaders() throws Exception {
    MvcResult res =
        mvc.perform(get("/health").with(remoteAddr("10.50.50.50")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(res.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(res.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(res.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(
      String ip) {
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }
}

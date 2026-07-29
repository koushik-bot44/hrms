package com.ihrms.companies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Stage 1 slug foundation (ARCHITECTURE.md §4): the create path mints a unique, reserved-safe,
 * name-derived slug that is PERMANENT across renames and resolvable by the by-slug endpoint with
 * per-role scope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class CompanySlugApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired JdbcTemplate jdbc;

  private String superToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                "super-1", "super@x.test", "Super", UserRole.SUPER_ADMIN, null, null));
  }

  @Test
  void mintsSlugFromNameOnCreate() throws Exception {
    assertThat(create("Acme Inc", "ACME").get("slug").asText()).isEqualTo("acme-inc");
  }

  @Test
  void sameNameCompaniesGetDistinctSlugs() throws Exception {
    assertThat(create("Acme", "ACME").get("slug").asText()).isEqualTo("acme");
    assertThat(create("Acme", "ACMETWO").get("slug").asText()).isEqualTo("acme-2");
    assertThat(create("Acme", "ACMETHREE").get("slug").asText()).isEqualTo("acme-3");
  }

  @Test
  void reservedNameIsSuffixed() throws Exception {
    // "Mail" would slug to the reserved top-level route "mail" -> bumped to "mail-2".
    assertThat(create("Mail", "MAILCO").get("slug").asText()).isEqualTo("mail-2");
  }

  @Test
  void renameDoesNotChangeSlug() throws Exception {
    JsonNode created = create("Acme", "ACME");
    String id = created.get("id").asText();
    assertThat(created.get("slug").asText()).isEqualTo("acme");

    mvc.perform(asSuper(patch("/companies/" + id), Map.of("name", "Renamed Corp")))
        .andExpect(status().isOk());

    JsonNode after = getCompany(id);
    assertThat(after.get("name").asText()).isEqualTo("Renamed Corp");
    assertThat(after.get("slug").asText()).isEqualTo("acme"); // PERMANENT — unchanged by rename
  }

  @Test
  void bySlugResolvesForSuperAdminAndOwnCompanyOnly() throws Exception {
    JsonNode a = create("Alpha", "ALPHA");
    JsonNode b = create("Beta", "BETA");
    String aId = a.get("id").asText();
    String aSlug = a.get("slug").asText();
    String bSlug = b.get("slug").asText();

    // SUPER_ADMIN resolves any slug.
    assertThat(resolve(aSlug, superToken, status().isOk()).get("id").asText()).isEqualTo(aId);

    // COMPANY_ADMIN of A resolves its OWN company ...
    String caA =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                "ca-a", "ca@a.test", "CA-A", UserRole.COMPANY_ADMIN, aId, null));
    assertThat(resolve(aSlug, caA, status().isOk()).get("slug").asText()).isEqualTo(aSlug);
    // ... but NOT another company (cross-company) -> 404 (no existence leak).
    mvc.perform(get("/companies/by-slug/" + bSlug).header("Authorization", "Bearer " + caA))
        .andExpect(status().isNotFound());

    // Unknown slug -> 404.
    mvc.perform(
            get("/companies/by-slug/does-not-exist").header("Authorization", "Bearer " + superToken))
        .andExpect(status().isNotFound());
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode create(String name, String code) throws Exception {
    MvcResult res =
        mvc.perform(asSuper(post("/companies"), Map.of("name", name, "code", code)))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private JsonNode getCompany(String id) throws Exception {
    MvcResult res =
        mvc.perform(get("/companies/" + id).header("Authorization", "Bearer " + superToken))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private JsonNode resolve(
      String slug,
      String token,
      org.springframework.test.web.servlet.ResultMatcher expected)
      throws Exception {
    MvcResult res =
        mvc.perform(get("/companies/by-slug/" + slug).header("Authorization", "Bearer " + token))
            .andExpect(expected)
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private MockHttpServletRequestBuilder asSuper(MockHttpServletRequestBuilder builder, Object body)
      throws Exception {
    return builder
        .header("Authorization", "Bearer " + superToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }
}

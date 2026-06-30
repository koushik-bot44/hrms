package com.ihrms.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints under each role-area path. Future phases provide the real handlers;
 * here they let {@link RbacMatrixTest} assert the URL-area authorization (contract §1.2)
 * returns 200 when allowed and 403 when not.
 */
@RestController
public class RbacProbeController {

  @GetMapping("/companies/_probe")
  String companies() {
    return "ok";
  }

  @GetMapping("/teams/_probe")
  String teams() {
    return "ok";
  }

  @GetMapping("/employees/_probe")
  String employees() {
    return "ok";
  }

  @GetMapping("/me/onboarding/_probe")
  String onboarding() {
    return "ok";
  }
}

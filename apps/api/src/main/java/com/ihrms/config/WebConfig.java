package com.ihrms.config;

import com.ihrms.audit.AuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the audit interceptor so every mutating request is logged (§7). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final AuditInterceptor auditInterceptor;

  public WebConfig(AuditInterceptor auditInterceptor) {
    this.auditInterceptor = auditInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // The public blob endpoint (db storage) carries no actor context and is already audited at the
    // onboarding/review service layer, so keep its raw PUT/GET out of the audit trail.
    registry.addInterceptor(auditInterceptor).excludePathPatterns("/storage/blobs/**");
  }
}

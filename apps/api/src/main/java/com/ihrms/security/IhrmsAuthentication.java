package com.ihrms.security;

import com.ihrms.auth.IhrmsPrincipal;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/** Authentication holding the verified {@link IhrmsPrincipal} (resolvable via @AuthenticationPrincipal). */
public class IhrmsAuthentication extends AbstractAuthenticationToken {

  private final transient IhrmsPrincipal principal;

  public IhrmsAuthentication(
      IhrmsPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.principal = principal;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public Object getPrincipal() {
    return principal;
  }

  @Override
  public String getName() {
    return principal.id();
  }
}

package com.ihrms.config;

import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Idempotently seeds the dev SUPER_ADMIN so sign-in works locally. Staff sign in with email +
 * password (§6): the admin uses the seeded email + password (both dev-logged below). Never runs under
 * the {@code prod} profile — production provisions accounts out of band.
 *
 * <p>Note: any staff created during the OTP era have no usable password and must be re-provisioned
 * (or re-seeded) before they can sign in with email + password.
 */
@Component
@Profile("!prod")
public class DataSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final String email;
  private final String password;

  public DataSeeder(
      UserRepository users,
      PasswordEncoder encoder,
      @Value("${app.seed.super-admin-email:superadmin@ihrms.local}") String email,
      @Value("${app.seed.super-admin-password:SuperAdmin@123}") String password) {
    this.users = users;
    this.encoder = encoder;
    this.email = email.toLowerCase();
    this.password = password;
  }

  @Override
  public void run(String... args) {
    if (users.findByEmail(email).isPresent()) {
      log.info("SUPER_ADMIN {} already present; skipping seed", email);
      return;
    }
    User admin = new User();
    admin.setEmail(email);
    admin.setName("Super Admin");
    admin.setRole(UserRole.SUPER_ADMIN);
    admin.setPasswordHash(encoder.encode(password));
    admin.setStatus("ACTIVE");
    users.save(admin);
    log.warn("[DEV SEED] SUPER_ADMIN sign-in -> {} / {}  (email + password at /login)", email, password);
  }
}

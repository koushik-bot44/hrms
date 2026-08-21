package com.ihrms.config;

import com.ihrms.support.FieldCrypto;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time re-encryption of the field-encrypted columns (§6) after {@code FIELD_ENC_KEY} is rotated. Runs ONLY
 * when {@code ihrms.reencrypt-field-keys=true} (env {@code REENCRYPT_FIELD_KEYS}); otherwise a no-op. For each
 * sensitive column it reads the RAW ciphertext (via JDBC, bypassing the JPA converter — which now uses the NEW
 * key), decrypts it with the previous passphrase ({@code ihrms.reencrypt-from-key} / {@code REENCRYPT_FROM_KEY},
 * default = the old committed dev key that the prod boot guard caught), and re-encrypts it with the current
 * {@code FIELD_ENC_KEY}.
 *
 * <p>IDEMPOTENT and SAFE: a value already under the current key is left untouched, and a value decryptable by
 * neither key is logged and left unchanged (never corrupted). Runbook: rotate FIELD_ENC_KEY + set
 * REENCRYPT_FIELD_KEYS=true for one deploy, confirm the "[FIELD REKEY] done" log summary, then set the flag
 * back to false and redeploy.
 */
@Component
public class FieldKeyReencryptor implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(FieldKeyReencryptor.class);

  /** {table, column} for every @Convert(EncryptedStringConverter) field (§6). Hardcoded — never user input. */
  private static final List<String[]> TARGETS =
      List.of(
          new String[] {"employees", "aadhaarNumber"},
          new String[] {"form1_personal", "offeredCtc"},
          new String[] {"form2_info", "panNumber"},
          new String[] {"form2_info", "axisAccountNumber"},
          new String[] {"form3_prev_employment", "lastDrawnSalary"});

  private final JdbcTemplate jdbc;
  private final boolean enabled;
  private final String fromPassphrase;

  public FieldKeyReencryptor(
      JdbcTemplate jdbc,
      @Value("${ihrms.reencrypt-field-keys:false}") boolean enabled,
      @Value("${ihrms.reencrypt-from-key:}") String fromKey) {
    this.jdbc = jdbc;
    this.enabled = enabled;
    this.fromPassphrase = (fromKey == null || fromKey.isBlank()) ? FieldCrypto.DEV_DEFAULT_KEY : fromKey;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }
    log.warn(
        "[FIELD REKEY] Re-encrypting sensitive columns from the previous key onto the current FIELD_ENC_KEY. "
            + "Set REENCRYPT_FIELD_KEYS=false and redeploy once this completes.");
    int migrated = 0;
    int already = 0;
    int failed = 0;
    for (String[] t : TARGETS) {
      int[] r = reencryptColumn(t[0], t[1]);
      migrated += r[0];
      already += r[1];
      failed += r[2];
    }
    log.warn(
        "[FIELD REKEY] done: reencrypted={} alreadyCurrentKey={} undecryptable(left unchanged)={}",
        migrated,
        already,
        failed);
    if (failed > 0) {
      log.warn(
          "[FIELD REKEY] {} value(s) were decryptable by NEITHER the previous nor the current key — check "
              + "REENCRYPT_FROM_KEY. They were NOT modified.",
          failed);
    }
  }

  /** @return {reencrypted, alreadyCurrentKey, undecryptable} for one column. */
  private int[] reencryptColumn(String table, String column) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT \"id\", \"" + column + "\" AS v FROM \"" + table + "\" WHERE \"" + column + "\" LIKE ?",
            FieldCrypto.PREFIX + "%");
    int migrated = 0;
    int already = 0;
    int failed = 0;
    for (Map<String, Object> row : rows) {
      String id = String.valueOf(row.get("id"));
      String raw = (String) row.get("v");
      String plaintext;
      try {
        plaintext = FieldCrypto.decryptWithKey(raw, fromPassphrase);
      } catch (RuntimeException previousKeyFailed) {
        // Not the previous key. If the CURRENT key decrypts it, it is already migrated — skip; else leave it.
        try {
          FieldCrypto.decrypt(raw);
          already++;
        } catch (RuntimeException currentKeyFailed) {
          failed++;
          log.warn(
              "[FIELD REKEY] {}.{} id={} undecryptable by previous or current key — left unchanged",
              table,
              column,
              id);
        }
        continue;
      }
      jdbc.update(
          "UPDATE \"" + table + "\" SET \"" + column + "\" = ? WHERE \"id\" = ?",
          FieldCrypto.encrypt(plaintext), // current (new) key
          id);
      migrated++;
    }
    if (migrated > 0 || already > 0 || failed > 0) {
      log.info(
          "[FIELD REKEY] {}.{}: reencrypted={} alreadyCurrent={} undecryptable={}",
          table,
          column,
          migrated,
          already,
          failed);
    }
    return new int[] {migrated, already, failed};
  }
}

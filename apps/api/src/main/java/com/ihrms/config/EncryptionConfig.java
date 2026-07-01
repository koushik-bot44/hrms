package com.ihrms.config;

import com.ihrms.support.FieldCrypto;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the field-encryption key from configuration (§6). {@link FieldCrypto} also self-initialises
 * from {@code FIELD_ENC_KEY}/dev-default, but this makes the yml-bound value authoritative and runs
 * before any persistence happens.
 */
@Configuration
public class EncryptionConfig {

  private final AppProperties props;

  public EncryptionConfig(AppProperties props) {
    this.props = props;
  }

  @PostConstruct
  void configureFieldCrypto() {
    FieldCrypto.configure(props.fieldEncKey());
  }
}

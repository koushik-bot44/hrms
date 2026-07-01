package com.ihrms.domain.support;

import com.ihrms.support.FieldCrypto;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that encrypts a String column at rest via {@link FieldCrypto} (AES-256-GCM). Applied
 * explicitly with {@code @Convert} on the sensitive columns (§6): {@code offeredCtc}, {@code panNumber},
 * {@code axisAccountNumber}, {@code lastDrawnSalary}. Not {@code autoApply} — only flagged columns are
 * encrypted; everything else stays queryable plaintext.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

  @Override
  public String convertToDatabaseColumn(String attribute) {
    return FieldCrypto.encrypt(attribute);
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    return FieldCrypto.decrypt(dbData);
  }
}

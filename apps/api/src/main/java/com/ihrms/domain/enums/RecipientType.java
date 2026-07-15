package com.ihrms.domain.enums;

/** How a message reaches a recipient (§8). PG enum type {@code "RecipientType"}. BCC is hidden from others. */
public enum RecipientType {
  TO,
  CC,
  BCC
}

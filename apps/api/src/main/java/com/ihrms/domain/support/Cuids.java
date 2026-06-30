package com.ihrms.domain.support;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates collision-resistant, cuid-shaped string ids (the archived Prisma schema used
 * {@code @default(cuid())} for every TEXT primary key). Format: {@code "c" + base36(time)
 * + base36(counter,4) + base36(random,8)} — opaque + monotonic-ish + unique under
 * concurrency. Ids are treated as opaque strings by the contract.
 */
public final class Cuids {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final AtomicLong COUNTER = new AtomicLong(RANDOM.nextInt(1 << 24));
  private static final long RAND_SPACE = 2_821_109_907_456L; // 36^8

  private Cuids() {}

  public static String newId() {
    String time = Long.toString(System.currentTimeMillis(), 36);
    String counter = pad(Long.toString(COUNTER.incrementAndGet() & 0xFFFFFFL, 36), 4);
    String random = pad(Long.toString(Math.floorMod(RANDOM.nextLong(), RAND_SPACE), 36), 8);
    return "c" + time + counter + random;
  }

  private static String pad(String value, int length) {
    if (value.length() >= length) {
      return value.substring(value.length() - length);
    }
    StringBuilder sb = new StringBuilder(length);
    for (int i = value.length(); i < length; i++) {
      sb.append('0');
    }
    return sb.append(value).toString();
  }
}

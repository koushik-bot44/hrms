package com.ihrms.iclock;

/**
 * Builds the plain-text options block returned from the handshake (GET /iclock/cdata). Pure and
 * static so it is unit-testable without a Spring context.
 *
 * <p>Lines are CRLF-separated, which is what ADMS firmware emits and is the safer of the two
 * choices for a device whose parser we have not seen. {@code Realtime=1} is the key that switches
 * the terminal from scheduled batch upload to live push — without it, punches only arrive at the
 * {@code TransTimes} slots.
 */
final class IclockOptions {

  private IclockOptions() {}

  /**
   * @param serialNumber the device's {@code ?SN=}; echoed back in the first line, which is the shape
   *     the firmware expects to confirm the server recognised it
   * @param stamp monotonic cursor the device uses to resume; seconds since epoch is sufficient in P0
   */
  static String block(String serialNumber, long stamp, IclockProperties.Options options) {
    StringBuilder sb = new StringBuilder(256);
    line(sb, "GET OPTION FROM: " + (serialNumber == null ? "" : serialNumber));
    line(sb, "Stamp=" + stamp);
    line(sb, "OpStamp=" + stamp);
    // Per-table cursors. Firmware varies in which of these it reads; sending all three is harmless
    // and covers the common variants.
    line(sb, "ATTLOGStamp=" + stamp);
    line(sb, "OPERLOGStamp=" + stamp);
    line(sb, "ATTPHOTOStamp=" + stamp);
    line(sb, "ErrorDelay=" + options.errorDelay());
    line(sb, "Delay=" + options.delay());
    line(sb, "TransTimes=" + options.transTimes());
    line(sb, "TransInterval=" + options.transInterval());
    line(sb, "TransFlag=" + options.transFlag());
    line(sb, "TimeZone=" + options.timeZone());
    line(sb, "Realtime=" + options.realtime());
    line(sb, "Encrypt=" + options.encrypt());
    return sb.toString();
  }

  private static void line(StringBuilder sb, String text) {
    sb.append(text).append("\r\n");
  }
}

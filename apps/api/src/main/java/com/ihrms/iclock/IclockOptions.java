package com.ihrms.iclock;

/**
 * Builds the plain-text options block returned from the handshake (GET /iclock/cdata[.aspx]). Pure and
 * static so it is unit-testable without a Spring context.
 *
 * <p>Lines are CRLF-separated, which is what ADMS firmware emits. {@code Realtime=1} is the key that
 * switches the terminal from scheduled batch upload to live push — during P0 this block was never
 * actually delivered (the handshake fell through to the catch-all because the firmware calls
 * {@code cdata.aspx}), which is why the observed device sat in ~30s batch mode.
 *
 * <p><b>THE FLEET IS TWO PLATFORMS, and the split is by device family, not by building.</b> Confirmed
 * from handshake INFO across all six claimed terminals:
 *
 * <pre>
 *   ZAM180-NF50VD-4.0.12   the four ZHM... GATE units - BOTH buildings
 *                          (2A: ...000230, ...000434 | B9: ...000441, ...400653)
 *   ZAM230-NF40VA-1.0.27   the two NES... CAFETERIA units - Building 9 only
 * </pre>
 *
 * <p>Every one of them handshakes {@code pushver=2.4.1&options=all&DeviceType=att}, so the options
 * block below is common to both. Anything that has to be proven per platform — the command channel
 * above all — must be proven on one ZHM and one NES, which is NOT the same thing as one per building.
 *
 * <p><b>On the registry stamps.</b> In the classic protocol these are per-table cursors: the device
 * uploads records newer than the stamp the server returns. Both platforms here instead send a
 * CONSTANT {@code Stamp=9999} / {@code OpStamp=9999} on every push, so on this fleet they are not a
 * reliable high-water mark. They are persisted per device and echoed back for consistency, but what
 * actually prevents duplicate history being stored is the {@code OK: <n>} acknowledgement (which
 * advances the device's own internal pointer) plus the content-dedupe unique index from V42.
 */
final class IclockOptions {

  /** What we echo when a device has never reported a stamp — the value this firmware itself sends. */
  static final String DEFAULT_STAMP = "9999";

  private IclockOptions() {}

  /**
   * @param serialNumber the device's {@code ?SN=}; echoed in the first line, which is the shape the
   *     firmware expects in order to confirm the server recognised it
   * @param attlogStamp cursor for ATTLOG; null falls back to {@link #DEFAULT_STAMP}
   * @param opStamp cursor for OPERLOG/BIODATA/ATTPHOTO; null falls back to {@link #DEFAULT_STAMP}
   */
  static String block(
      String serialNumber, String attlogStamp, String opStamp, IclockProperties.Options options) {
    String att = blankTo(attlogStamp, DEFAULT_STAMP);
    String op = blankTo(opStamp, DEFAULT_STAMP);

    StringBuilder sb = new StringBuilder(384);
    line(sb, "GET OPTION FROM: " + (serialNumber == null ? "" : serialNumber));
    // Both the bare and per-registry spellings are sent: firmware differs in which it reads, and an
    // unrecognised key is ignored rather than rejected.
    line(sb, "Stamp=" + att);
    line(sb, "OpStamp=" + op);
    line(sb, "ATTLOGStamp=" + att);
    line(sb, "OPERLOGStamp=" + op);
    // BIODATA is pushed by this firmware (observed) — give it a cursor of its own spelling too.
    line(sb, "BIODATAStamp=" + op);
    line(sb, "ATTPHOTOStamp=" + op);
    line(sb, "ErrorDelay=" + options.errorDelay());
    line(sb, "Delay=" + options.delay());
    line(sb, "TransTimes=" + options.transTimes());
    line(sb, "TransInterval=" + options.transInterval());
    line(sb, "TransFlag=" + options.transFlag());
    line(sb, "TimeZone=" + options.timeZone());
    line(sb, "Realtime=" + options.realtime());
    line(sb, "Encrypt=" + options.encrypt());
    if (options.serverVer() != null && !options.serverVer().isBlank()) {
      // pushver 2.4.1 terminals commonly look for a server version line.
      line(sb, "ServerVer=" + options.serverVer());
    }
    return sb.toString();
  }

  private static String blankTo(String value, String fallback) {
    return (value == null || value.isBlank()) ? fallback : value;
  }

  private static void line(StringBuilder sb, String text) {
    sb.append(text).append("\r\n");
  }
}

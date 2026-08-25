package com.ihrms.iclock;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Serves an already-read request body from memory, as many times as anything asks for it.
 *
 * <p>This replaces Spring's {@code ContentCachingRequestWrapper} on purpose. That class caches bytes
 * only <em>as the handler reads them</em>, so its cache is empty for any request the handler ignores
 * — and it cannot capture anything <em>before</em> the handler runs, which is exactly what the
 * "raw capture is the commit point" rule requires. Here the filter reads the body up front and this
 * wrapper replays it downstream.
 *
 * <p>Returning a fresh stream on every call also makes the request immune to double-reads: if the
 * device POSTs {@code application/x-www-form-urlencoded} and anything touches
 * {@code getParameter()}, Tomcat's parameter parsing consumes a stream — and the handler can still
 * read the body afterwards.
 */
class IclockCachedBodyRequest extends HttpServletRequestWrapper {

  private final byte[] body;

  IclockCachedBodyRequest(HttpServletRequest request, byte[] body) {
    super(request);
    this.body = body;
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream source = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public int read() {
        return source.read();
      }

      @Override
      public int read(byte[] b, int off, int len) {
        return source.read(b, off, len);
      }

      @Override
      public int available() {
        return source.available();
      }

      @Override
      public boolean isFinished() {
        return source.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException("async reads are not used on /iclock");
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(
        new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.ISO_8859_1));
  }

  @Override
  public int getContentLength() {
    return body.length;
  }

  @Override
  public long getContentLengthLong() {
    return body.length;
  }
}

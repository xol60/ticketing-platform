package com.ticketing.common.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Request wrapper that pre-reads the body into a byte array so it can be
 * consumed multiple times.
 *
 * <p>The standard servlet contract only lets you read the request body once.
 * {@link IdempotencyFilter} needs it twice: once to hash for the dedup
 * check, and again to forward to the controller. {@code ContentCachingRequestWrapper}
 * from Spring caches as the body is consumed, but that means the cache is
 * empty until the controller reads — which is too late for a pre-flight
 * Redis lookup. So we eager-buffer here instead.
 *
 * <p>The cost is one extra in-memory copy of the request body per request.
 * For our case (1 MB cap, single-digit-KB POST bodies in practice) this is
 * negligible.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = request.getInputStream().readAllBytes();
    }

    /** Bytes of the request body, available before, during, and after handler execution. */
    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return byteArrayInputStream.available() == 0; }
            @Override public boolean isReady()    { return true; }
            @Override public void setReadListener(ReadListener readListener) {
                // Async I/O not supported on this wrapper — the controller stack is blocking anyway.
            }
            @Override public int read() { return byteArrayInputStream.read(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset cs = getCharacterEncoding() != null
                ? Charset.forName(getCharacterEncoding())
                : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), cs));
    }
}

package com.jujin.freeway.http.undertow;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.sse.SseEmitter;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class UndertowHttpContext extends HttpContext {

    private HttpServerExchange exchange;
    private RequestContext requestContext;
    private Map<String, List<String>> queryParams;
    private String method;
    private String path;
    private Map<String, List<String>> requestHeaders;
    private byte[] cachedBody;
    private int responseStatus = 200;
    private boolean responded;

    /** Pooled constructor — call {@link #reset} before use. */
    UndertowHttpContext(JsonCodec jsonCodec, Coercer coercer) {
        super(jsonCodec, coercer);
    }

    /** Reinitializes all per-request state for object reuse. */
    void reset(HttpServerExchange exchange, RequestContext requestContext) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
        this.queryParams = null;       // lazy — PING never accesses
        this.method = exchange.getRequestMethod() != null
            ? exchange.getRequestMethod().toString() : "";
        String rel = exchange.getRelativePath();
        this.path = rel != null ? rel : "/";
        this.requestHeaders = null;    // lazy — PING never accesses
        this.cachedBody = null;
        this.responseStatus = 200;
        this.responded = false;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String queryParam(String name) {
        if (queryParams == null) {
            queryParams = snapshotQuery(exchange.getQueryParameters());
        }
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    @Override
    public List<String> queryParams(String name) {
        if (queryParams == null) {
            queryParams = snapshotQuery(exchange.getQueryParameters());
        }
        return queryParams.getOrDefault(name, List.of());
    }

    @Override
    public Map<String, List<String>> queryParams() {
        if (queryParams == null) {
            queryParams = snapshotQuery(exchange.getQueryParameters());
        }
        return queryParams;
    }

    @Override
    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    @Override
    public List<String> headers(String name) {
        Deque<String> values = exchange.getRequestHeaders().get(name);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() == 1) {
            return List.of(values.getFirst());
        }
        return new ArrayList<>(values);
    }

    @Override
    public Map<String, List<String>> headers() {
        if (requestHeaders == null) {
            var headerMap = new LinkedHashMap<String, List<String>>();
            for (HttpString name : exchange.getRequestHeaders().getHeaderNames()) {
                Deque<String> values = exchange.getRequestHeaders().get(name);
                headerMap.put(name.toString().toLowerCase(Locale.ROOT), List.copyOf(values));
            }
            requestHeaders = Map.copyOf(headerMap);
        }
        return requestHeaders;
    }

    @Override
    public byte[] body() throws IOException {
        if (cachedBody == null) {
            if (!exchange.isBlocking()) {
                exchange.startBlocking();
            }
            try (InputStream in = exchange.getInputStream()) {
                cachedBody = readBodyLimited(in);
            }
        }
        return cachedBody;
    }

    @Override
    public SseEmitter sse() throws IOException {
        exchange.setStatusCode(200);
        setupSseHeaders();
        if (!exchange.isBlocking()) {
            exchange.startBlocking();
        }
        responded = true;
        return new SseEmitter(exchange.getOutputStream());
    }

    @Override
    public RequestContext requestContext() {
        return requestContext;
    }

    @Override
    public HttpContext status(int status) {
        this.responseStatus = status;
        exchange.setStatusCode(status);
        return this;
    }

    @Override
    public int status() {
        return responseStatus;
    }

    @Override
    protected String responseHeader(String name) {
        return exchange.getResponseHeaders().getFirst(name);
    }

    @Override
    public HttpContext headerSet(String name, String value) {
        validateHeaderValue(value);
        exchange.getResponseHeaders().put(
            HttpString.tryFromString(name.toLowerCase(Locale.ROOT)), value);
        return this;
    }

    @Override
    public HttpContext output(byte[] data) throws IOException {
        if (responded) {
            return this;
        }
        boolean head = "HEAD".equalsIgnoreCase(method);
        boolean noBody = head || responseStatus == 204 || responseStatus == 304;
        if (!noBody) {
            exchange.setResponseContentLength(data.length);
        }
        responded = true;
        if (!noBody && data.length > 0) {
            exchange.getResponseSender().send(ByteBuffer.wrap(data));
        } else {
            exchange.endExchange();
        }
        return this;
    }

    private static Map<String, List<String>> snapshotQuery(
        Map<String, Deque<String>> source
    ) {
        if (source.isEmpty()) {
            return Map.of();
        }
        var params = new LinkedHashMap<String, List<String>>(source.size());
        for (var entry : source.entrySet()) {
            params.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(params);
    }
}

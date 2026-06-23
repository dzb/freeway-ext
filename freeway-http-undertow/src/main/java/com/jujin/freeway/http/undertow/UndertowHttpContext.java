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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class UndertowHttpContext extends HttpContext {

    private final HttpServerExchange exchange;
    private final RequestContext requestContext;
    private final Map<String, List<String>> queryParams;
    private final String method;  // cached — allocated once per request
    private final String path;    // cached — allocated once per request
    private byte[] cachedBody;
    private int responseStatus = 200;
    private boolean responded;

    UndertowHttpContext(
        HttpServerExchange exchange,
        JsonCodec jsonCodec,
        Coercer coercer,
        RequestContext requestContext
    ) {
        super(jsonCodec, coercer);
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.requestContext = Objects.requireNonNull(
            requestContext,
            "requestContext"
        );
        this.queryParams = snapshotQuery(exchange.getQueryParameters());
        this.method = exchange.getRequestMethod() != null
            ? exchange.getRequestMethod().toString() : "";
        String rel = exchange.getRelativePath();
        this.path = rel != null ? rel : "/";
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
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    @Override
    public List<String> queryParams(String name) {
        return queryParams.getOrDefault(name, List.of());
    }

    @Override
    public Map<String, List<String>> queryParams() {
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
    public byte[] body() throws IOException {
        if (cachedBody == null) {
            exchange.startBlocking();
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
        exchange.startBlocking();
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
            try (OutputStream os = exchange.getOutputStream()) {
                os.write(data);
            }
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

package com.labos.boot.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 迁 Spring MVC 的适配层：把 Servlet 请求/响应包装成 JDK
 * {@link HttpExchange}，让现有 {@code ApiServer.route()} 及其 9 个
 * {@code *Api} 分发器一行不改就能跑在 Tomcat 上。
 *
 * <p>只实现 route 真正用到的语义（请求方法/URI/头/体、响应头、状态码、
 * Content-Length、响应体）；{@code getHttpContext()} 等 JDK HttpServer 专属
 * 概念返回 null —— 现有路由代码不读它们。
 *
 * <p>这是**迁移期的适配层**，不是长期架构：后续把每个 {@code *Api} 拆成
 * {@code @RestController} 后即可删除本类。
 */
public final class ServletHttpExchange extends HttpExchange {

    private final HttpServletRequest req;
    private final HttpServletResponse resp;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final Map<String, Object> attributes = new HashMap<>();

    private InputStream bodyIn;
    private OutputStream bodyOut;
    private int responseCode = -1;

    public ServletHttpExchange(HttpServletRequest req, HttpServletResponse resp) {
        this.req = req;
        this.resp = resp;
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new ArrayList<>();
            Enumeration<String> vs = req.getHeaders(name);
            while (vs != null && vs.hasMoreElements()) {
                values.add(vs.nextElement());
            }
            requestHeaders.put(name, values);
        }
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        String uri = req.getRequestURI();
        String query = req.getQueryString();
        return URI.create(query == null || query.isBlank() ? uri : uri + "?" + query);
    }

    @Override
    public String getRequestMethod() {
        return req.getMethod();
    }

    @Override
    public HttpContext getHttpContext() {
        return null;   // JDK HttpServer 专属，现有路由不读
    }

    @Override
    public void close() {
        try {
            if (bodyOut != null) {
                bodyOut.flush();
            }
            resp.flushBuffer();
        } catch (IOException ignore) {
            // 客户端断链时无须再写
        }
    }

    @Override
    public InputStream getRequestBody() {
        if (bodyIn != null) {
            return bodyIn;
        }
        try {
            return req.getInputStream();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public OutputStream getResponseBody() {
        if (bodyOut == null) {
            try {
                // 路由里的 try-with-resources 会 close() 这个流；
                // 真关掉 ServletOutputStream 后续 flush 会失败，所以 close 只 flush。
                bodyOut = new FilterOutputStream(resp.getOutputStream()) {
                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                };
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return bodyOut;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        this.responseCode = rCode;
        resp.setStatus(rCode);
        for (Map.Entry<String, List<String>> e : responseHeaders.entrySet()) {
            String key = e.getKey();
            // Spring 的 CORS 处理器可能已设过同名头；再 add 会变成两个
            // Access-Control-Allow-Origin，浏览器直接拒绝响应。CORS 头统一覆盖。
            boolean corsHeader = key != null
                    && (key.toLowerCase().startsWith("access-control-")
                        || key.equalsIgnoreCase("Vary"));
            for (String v : e.getValue()) {
                if (corsHeader) {
                    resp.setHeader(key, v);
                } else {
                    resp.addHeader(key, v);
                }
            }
        }
        if (responseLength == 0) {
            // JDK HttpServer: 0 = chunked
            resp.setHeader("Transfer-Encoding", "chunked");
        } else if (responseLength > 0) {
            resp.setContentLengthLong(responseLength);
        } else {
            // -1 = 无响应体
            resp.setContentLength(0);
        }
        resp.flushBuffer();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(req.getRemoteAddr(), req.getRemotePort());
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress(req.getLocalAddr(), req.getLocalPort());
    }

    @Override
    public String getProtocol() {
        return req.getProtocol();
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
        this.bodyIn = i;
        this.bodyOut = o;
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }
}

package com.labos.boot.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static com.labos.kernel.api.ApiServer.err;

/**
 * 兜底 Controller：{@code /health}、静态 UI（{@code /}、{@code /designer.html}、
 * {@code /vendor/**}）与 404。
 *
 * <p>旧 {@code LegacyRouteController + ServletHttpExchange} 适配层**已删除**，
 * 这里就是它的替代者：原 {@code ApiServer.route()} 里的
 * 静态资源/健康检查/404 三段现在用 Servlet API 直出。
 * 处理集合与原 route() 完全一致（多一个都不接）。
 */
@RestController
public class FallbackController {

    private final com.labos.boot.web.service.MetaService metaService;

    public FallbackController(com.labos.boot.web.service.MetaService metaService) {
        this.metaService = metaService;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
    }

    @GetMapping(value = "/health", produces = "application/json; charset=utf-8")
    public String health() throws SQLException {
        return metaService.health();
    }

    @GetMapping(value = {"/", "/index.html"})
    public ResponseEntity<byte[]> index() throws IOException {
        byte[] body = null;
        try (var in = getClass().getClassLoader().getResourceAsStream("web/index.html")) {
            if (in != null) {
                body = in.readAllBytes();
            }
        }
        if (body == null) {
            Path p = Path.of("web/index.html");
            if (Files.exists(p)) {
                body = Files.readAllBytes(p);
            }
        }
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(err("web/index.html not found (run from labos-kernel dir)")
                            .getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=utf-8"))
                .body(body);
    }

    @GetMapping(value = {"/vendor/**", "/designer.html"})
    public ResponseEntity<byte[]> assets(HttpServletRequest req) throws IOException {
        // 兜底集合与原 route() 一致：/vendor/* 与 designer.html；/assets/* 之前
        // 就不在 serveStatic 的分支里（由前端 dev server 提供），不新增。
        String path = req.getRequestURI();
        if (!"/designer.html".equals(path) && !path.startsWith("/vendor/")) {
            return bytes(notFound(req));
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.contains("..")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(err("bad path").getBytes(StandardCharsets.UTF_8));
        }
        Path file = Path.of("web", rel);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return bytes(notFound(req));
        }
        byte[] body = Files.readAllBytes(file);
        String ct = rel.endsWith(".js") ? "application/javascript; charset=utf-8"
                : rel.endsWith(".css") ? "text/css; charset=utf-8"
                : rel.endsWith(".html") ? "text/html; charset=utf-8"
                : rel.endsWith(".woff2") ? "font/woff2"
                : rel.endsWith(".woff") ? "font/woff"
                : rel.endsWith(".ttf") ? "font/ttf"
                : rel.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ct))
                .body(body);
    }

    /** 其余全部 404 —— 与原 route() 的兜底行为一致（鉴权仍由拦截器先行）。 */
    @RequestMapping("/**")
    public ResponseEntity<String> notFound(HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(err("no route: " + req.getMethod() + " " + req.getRequestURI()));
    }

    private static ResponseEntity<byte[]> bytes(ResponseEntity<String> r) {
        return ResponseEntity.status(r.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(r.getBody() == null ? new byte[0]
                        : r.getBody().getBytes(StandardCharsets.UTF_8));
    }
}

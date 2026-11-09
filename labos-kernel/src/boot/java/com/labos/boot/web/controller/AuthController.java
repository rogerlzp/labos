package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.Jsons;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Map;

/**
 * 从 {@code AuthApi} 拆出的注解式 Controller（3 个端点）。
 *
 * <p>与原实现的差异只有一处：原 {@code route()} 用 {@code jsonField(body, "k")}
 * 手撕 JSON，这里用 Spring 的 {@code @RequestBody Map}；鉴权交给
 * {@link ApiAuthInterceptor}，Session 通过 request attribute 取。
 * 响应体仍是同一套 {@link Jsons#obj}，字段与状态码逐字保持一致。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody Map<String, String> body,
                                        HttpServletRequest req) throws SQLException {
        AuthService.Session sess = auth.login(
                body.get("username"), body.get("password"), req.getRemoteAddr());
        if (sess == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Jsons.obj("error", "用户名或密码错误"));
        }
        return ResponseEntity.ok(Jsons.obj(
                "token", sess.token(),
                "username", sess.username(),
                "displayName", sess.displayName(),
                "role", sess.role(),
                "expiresAt", sess.expiresAt().toString()));
    }

    @GetMapping("/me")
    public ResponseEntity<String> me(
            @RequestAttribute(name = ApiAuthInterceptor.SESSION_ATTR, required = false)
            AuthService.Session s0) {
        if (s0 == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Jsons.obj("error", "未登录"));
        }
        return ResponseEntity.ok(Jsons.obj(
                "username", s0.username(),
                "displayName", s0.displayName(),
                "role", s0.role(),
                "expiresAt", s0.expiresAt().toString()));
    }

    @PostMapping("/logout")
    public String logout(@RequestHeader(value = "Authorization", required = false) String h) {
        String token = h == null ? null : (h.startsWith("Bearer ") ? h.substring(7).trim() : h.trim());
        auth.logout(token);
        return Jsons.obj("ok", true);
    }
}

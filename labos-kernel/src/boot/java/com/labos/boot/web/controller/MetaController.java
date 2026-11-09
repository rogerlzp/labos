package com.labos.boot.web.controller;

import com.labos.kernel.api.QueryService;
import com.labos.kernel.workflow.StatusVocabulary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

/**
 * 第一个从 {@code *Api} 拆出来的注解式 Controller（P-04/P-05 迁移范式）。
 *
 * <p>选 MetaApi 里三个纯读端点先拆：它们不需要写库、不涉及设备，风险最低。
 * 鉴权由 {@code ApiAuthInterceptor} 接管（规则与 route() 一致），
 * 其余端点仍走 {@code LegacyRouteController}，行为不变。
 */
@RestController
public class MetaController {

    private final QueryService query;

    public MetaController(QueryService query) {
        this.query = query;
    }

    @GetMapping(value = "/api/meta/status-vocabulary",
            produces = "application/json; charset=utf-8")
    public String statusVocabulary() {
        return StatusVocabulary.toJson();
    }

    @GetMapping(value = "/api/capabilities",
            produces = "application/json; charset=utf-8")
    public String capabilities() throws SQLException {
        return query.capabilitiesJson();
    }

    @GetMapping(value = "/api/capabilities/{code}/methods",
            produces = "application/json; charset=utf-8")
    public String capabilityMethods(@PathVariable String code) throws SQLException {
        return query.methodsJson(code);
    }
}

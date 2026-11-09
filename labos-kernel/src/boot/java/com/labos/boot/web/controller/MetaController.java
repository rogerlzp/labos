package com.labos.boot.web.controller;

import com.labos.kernel.api.QueryService;
import com.labos.kernel.device.DeviceModels;
import com.labos.kernel.persistence.MetaMapper;
import com.labos.kernel.workflow.StatusVocabulary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第一个从 {@code *Api} 拆出来的注解式 Controller（P-04/P-05 迁移范式）。
 *
 * <p>选 MetaApi 里三个纯读端点先拆：它们不需要写库、不涉及设备，风险最低。
 * 鉴权由 {@code ApiAuthInterceptor} 接管（规则与 route() 一致），
 * 其余端点仍走 {@code LegacyRouteController}，行为不变。
 */
@RestController
public class MetaController {

    private static final long TENANT = 1L;

    private final QueryService query;
    private final MetaMapper metaMapper;

    public MetaController(QueryService query, MetaMapper metaMapper) {
        this.query = query;
        this.metaMapper = metaMapper;
    }

    @GetMapping(value = "/api/meta/status-vocabulary",
            produces = "application/json; charset=utf-8")
    public String statusVocabulary() {
        return StatusVocabulary.toJson();
    }

    /**
     * M10 先行：型号库适配状态。现场/评审据此回答"哪些型号能对接真机、
     * 哪些只有占位、产线上有几台在跑却在等手册"。
     *
     * <p>部署台数一次性 GROUP BY（{@code MetaMapper.countDevicesByModel}），
     * 不在循环里逐型号查库。
     */
    @GetMapping(value = "/api/meta/device-models",
            produces = "application/json; charset=utf-8")
    public String deviceModels() {
        Map<String, Integer> deployedByModel = new LinkedHashMap<>();
        for (Map<String, Object> row : metaMapper.countDevicesByModel(TENANT)) {
            Object n = row.get("n");
            deployedByModel.put(String.valueOf(row.get("model")),
                    n instanceof Number num ? num.intValue() : 0);
        }
        return DeviceModels.libraryJson(deployedByModel);
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

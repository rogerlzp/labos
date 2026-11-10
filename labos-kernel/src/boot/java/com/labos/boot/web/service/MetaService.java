package com.labos.boot.web.service;

import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.MetaMapper;
import com.labos.kernel.persistence.QJson;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 元数据域的 Service：{@code MetaController} 的能力清单/方法名端点。
 *
 * <p>按「一个 Controller → 一个 Service → 一个 Mapper」的域划分，这两个方法
 * 原先放在 3566 行的 {@code QueryService} 里、SQL 塞在 {@code QueryMapper}
 * 中；现在逻辑与 SQL 都归到 Meta 域，Controller 不再依赖 QueryService。
 *
 * <p>纯只读，无事务。TENANT 与其余 Service/Controller 保持一致（后续统一
 * 从配置注入时一并替换）。
 */
@Service
public class MetaService {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final MetaMapper metaMapper;

    public MetaService(DbConfig db, MetaMapper metaMapper) {
        this.db = db;
        this.metaMapper = metaMapper;
    }

    /** /health 探测：库版本 + 连接 URL，与内核共活判定同源。 */
    public String health() throws java.sql.SQLException {
        java.util.Map<String, Object> row = metaMapper.health();
        return Jsons.obj(
                "status", "ok",
                "database", row.get("db"),
                "version", QJson.shortVersion(String.valueOf(row.get("ver"))),
                "url", db.url(),
                "time", java.time.OffsetDateTime.now().toString());
    }

    /** 能力清单（含每个能力绑定了多少资源）。 */
    public String capabilitiesJson() {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> row : metaMapper.selectCapabilities(TENANT)) {
            Object n = row.get("devices");
            items.add(Jsons.obj(
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "devices", n instanceof Number num ? num.intValue() : 0));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 某能力下已登记的方法名（去重排序）。 */
    public String capabilityMethods(String capability) {
        List<String> items = new ArrayList<>();
        for (String m : metaMapper.selectMethodsByCapability(TENANT, capability)) {
            items.add(Jsons.quote(m));
        }
        return Jsons.obj("capability", capability,
                "methods", new Jsons.Raw(Jsons.arr(items)));
    }
}

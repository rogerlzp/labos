package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * P2 阶段 D · L3 轨道分区数据访问。
 */
public interface RailZoneMapper {

    /** 搬运路径 → 分区集（按 seq 升序）；未配置返回空列表。 */
    List<Long> selectZonesByPath(@Param("tenantId") long tenantId,
                                 @Param("pathKey") String pathKey);

    /** 某分区是否存在（配置完整性校验用）。 */
    Long selectZoneResource(@Param("tenantId") long tenantId,
                            @Param("zoneResourceId") long zoneResourceId);

    /** 路径是否已豁免（rail_zone_exemption 有该 path_key）；豁免返回 1，否则 0。 */
    Long selectExemption(@Param("tenantId") long tenantId,
                         @Param("pathKey") String pathKey);
}

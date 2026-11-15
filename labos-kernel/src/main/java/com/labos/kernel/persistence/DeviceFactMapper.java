package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * P2 阶段 C · 设备事实（状态版本）+ 有效等待投影 数据访问。
 */
public interface DeviceFactMapper {

    /** 覆盖写一个设备事实（每个 device+fact_type 只留最新）。 */
    int upsertFact(@Param("tenantId") long tenantId,
                   @Param("deviceResourceId") long deviceResourceId,
                   @Param("factType") String factType,
                   @Param("factValue") String factValue,
                   @Param("stateVersion") String stateVersion,
                   @Param("observedAt") String observedAt);

    /** 查当前设备事实；不存在返回 null。 */
    Map<String, Object> selectFact(@Param("tenantId") long tenantId,
                                   @Param("deviceResourceId") long deviceResourceId,
                                   @Param("factType") String factType);
}

package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * P2 阶段 C · L4 搅拌—分液配对数据访问。
 */
public interface StirrerMapper {

    /** 分液器 → 配对搅拌单元 resource_id；未配对返回 null。 */
    Long selectStirrerByDispenser(@Param("tenantId") long tenantId,
                                  @Param("dispenserResourceId") long dispenserResourceId);
}

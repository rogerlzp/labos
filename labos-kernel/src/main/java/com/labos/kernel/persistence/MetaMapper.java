package com.labos.kernel.persistence;

import java.util.List;
import java.util.Map;

/** 元数据/型号库的只读查询，语句见 {@code mapper/MetaMapper.xml}。 */
public interface MetaMapper {

    /** 每个型号当前部署了几台（一次 GROUP BY，避免逐型号 N+1）。 */
    List<Map<String, Object>> countDevicesByModel(long tenantId);
}

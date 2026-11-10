package com.labos.kernel.persistence;

import java.util.List;
import java.util.Map;

/**
 * 驱动表（driver）的只读查询，供两个契约/端到端演示脚本使用。
 *
 * <p>演示脚本是独立 {@code main()}，用 {@link Mappers#get} 取代理；
 * 之前它们各自手写一条 SQL，列集不同（一个要 protocol 文本，一个还要
 * driver_kind），所以这里保留两个方法而不是硬合并。
 */
public interface DriverMapper {

    /** id / code / endpoint / protocol（文本）。 */
    List<Map<String, Object>> selectDrivers(long tenantId);

    /** 同上 + driver_kind（manifest 里的类型，缺省 unknown）。 */
    List<Map<String, Object>> selectDriversWithKind(long tenantId);
}

package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * 运行期可调参数（{@code sys_setting}，心跳间隔/离线阈值）的数据访问
 * （原 {@code RuntimeSettings} 里的裸 SQL，阶段3第25批收编）。
 *
 * <p>线上 {@code sys_setting} 没有 {@code (tenant_id, scope, setting_key)} 唯一约束
 * （历史表结构缺），所以写路径保留「先 UPDATE、没中再 INSERT」两段式，不用 ON CONFLICT。
 * value 是 jsonb 数字，读侧统一 {@code ::text}（数字或带引号的字符串都由调用方剥引号）。
 */
public interface SysSettingMapper {

    /** 读一条 global 设置的 value 文本（jsonb ::text）；无行返回 null。 */
    String selectSettingValue(@Param("tenantId") long tenantId, @Param("key") String key);

    /** 更新一条设置；返回 0 = 该行不存在（调用方接着 INSERT，两段式幂等 upsert）。 */
    int updateSetting(@Param("tenantId") long tenantId,
                      @Param("key") String key,
                      @Param("value") long value);

    /** 新建一条设置（description 固定为运行期参数说明，与原语句一致）。 */
    int insertSetting(@Param("tenantId") long tenantId,
                      @Param("key") String key,
                      @Param("value") long value);
}

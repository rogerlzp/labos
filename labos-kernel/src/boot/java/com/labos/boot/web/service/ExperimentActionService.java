package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.ExperimentMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;

/**
 * 实验动作域的写 Service：{@code ExperimentActionController} 的交接确认端点。
 *
 * <p>按「Controller → Service → Mapper」从 {@code QueryService} 拆出，
 * 四条 SQL 归到已有的 {@link ExperimentMapper}（同域，不另起 Mapper）。
 *
 * <p>确认交接会连带落一条介入记录和一条已决策的人工窗口（N-08 联动），
 * 三者必须在同一个事务里；事务边界沿用
 * {@code try (Connection c = db.open())}，Mapper 经 Spring
 * {@code DataSourceUtils} 取同一条绑定连接。
 */
@Service
public class ExperimentActionService {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final ExperimentMapper experimentMapper;

    public ExperimentActionService(DbConfig db, ExperimentMapper experimentMapper) {
        this.db = db;
        this.experimentMapper = experimentMapper;
    }

    /** 确认一条工位交接，返回受影响行数；0 表示状态不允许确认。 */
    public int confirmHandoff(long experimentId, long fromWorkcellId, long toWorkcellId,
                              String operator, String note) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                String op = operator == null ? "api" : operator;
                int n = experimentMapper.confirmHandoffStatus(
                        TENANT, experimentId, fromWorkcellId, toWorkcellId);
                if (n > 0) {
                    Long recordId = experimentMapper.insertHandoffIntervention(
                            TENANT, op, String.valueOf(fromWorkcellId),
                            String.valueOf(toWorkcellId), note);
                    // N-08：确认动作同时落一条已决策 manual_window，形成直接联动
                    Long windowId = experimentMapper.insertHandoffWindow(
                            TENANT,
                            "handoff:" + experimentId + ":" + fromWorkcellId + ":"
                                    + toWorkcellId,
                            note == null ? "Handoff 人工确认" : note, op, note);
                    experimentMapper.linkHandoffRecords(TENANT, experimentId,
                            fromWorkcellId, toWorkcellId,
                            recordId == null ? 0L : recordId,
                            windowId == null ? 0L : windowId);
                }
                return n;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }
}

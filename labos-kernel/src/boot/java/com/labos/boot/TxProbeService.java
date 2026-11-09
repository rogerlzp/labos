package com.labos.boot;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.WorkflowMapper;
import com.labos.kernel.persistence.WorkflowRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * A 批自测专用：验证 {@code @Transactional} 真的经过 AOP 代理生效。
 *
 * <p>不是业务代码，只用于 {@link SpringTxSelfTest}；生产包里它无害且不被调用。
 */
@Service
public class TxProbeService {

    private final DbConfig db;
    private final WorkflowMapper mapper;

    public TxProbeService(DbConfig db, WorkflowMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void insertThenThrow(String codeA, String codeB, boolean checked) throws Exception {
        insert(codeA);
        insert(codeB);
        if (checked) {
            throw new java.sql.SQLException("故意抛 checked 异常");
        }
        throw new IllegalStateException("故意失败");
    }

    @Transactional(rollbackFor = Exception.class)
    public void insertBoth(String codeA, String codeB) {
        insert(codeA);
        insert(codeB);
    }

    /** 事务内两次 open() 必须是同一个物理连接。 */
    @Transactional(rollbackFor = Exception.class)
    public int[] twoConnectionsPid() throws Exception {
        return new int[]{pid(), pid()};
    }

    private int pid() throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT pg_backend_pid()");
             java.sql.ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** C 批：Mapper 写入 + 裸 JDBC 读取，必须是同一个物理连接（同一 Spring 事务）。 */
    @Transactional(rollbackFor = Exception.class)
    public int[] mapperAndJdbcSamePid(String code) throws Exception {
        WorkflowRow row = new WorkflowRow();
        row.setTenantId(1L);
        row.setCode(code);
        row.setName("MyBatis 事务自测");
        row.setCategory("choreography");
        mapper.insertWorkflow(row);
        return new int[]{pid(), pid(), (int) (long) row.getId()};
    }

    /** C 批：事务抛异常时，Mapper 的插入也必须一起回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public void mapperInsertThenThrow(String code) {
        WorkflowRow row = new WorkflowRow();
        row.setTenantId(1L);
        row.setCode(code);
        row.setName("MyBatis 回滚自测");
        row.setCategory("choreography");
        mapper.insertWorkflow(row);
        throw new IllegalStateException("故意失败");
    }

    public int countByCode(String code) {
        return mapper.countByCode(1L, code);
    }

    private void insert(String code) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO workflow (tenant_id, code, name, category, version, status)
                     VALUES (1, ?, '事务自测', 'choreography', 1, 1)
                     """)) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

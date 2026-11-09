package com.labos.kernel.persistence;

/**
 * C 批：workflow 表写入参数（MyBatis 需要可变 JavaBean 以回填自增 id）。
 */
public final class WorkflowRow {

    private Long id;
    private long tenantId;
    private String code;
    private String name;
    private String category;
    private Long islandId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getTenantId() {
        return tenantId;
    }

    public void setTenantId(long tenantId) {
        this.tenantId = tenantId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Long getIslandId() {
        return islandId;
    }

    public void setIslandId(Long islandId) {
        this.islandId = islandId;
    }
}

package com.labos.kernel.persistence;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PG 数组列 → Java List 的**统一摊平**。
 *
 * <p><b>为什么需要</b>：PG 的数组列（{@code bigint[]} / {@code text[]} /
 * {@code array_agg(...)}）在不同取法下回来的形态**不一样**：
 * <ul>
 *   <li>{@code ResultSet.getArray(...)} → {@link java.sql.Array}；</li>
 *   <li>{@code ResultSet.getObject(...)} → **对应的 Java 数组**
 *       （{@code Long[]} / {@code String[]}）。MyBatis 走
 *       {@code resultType=java.util.Map} 时走的就是这一条。</li>
 * </ul>
 *
 * <p>2026-11-10 实测踩到：能力候选池那条 SQL 用 {@code array_agg}，代码按
 * {@code java.sql.Array} 强转 ⇒ **每次执行工艺都 500**
 * （{@code ClassCastException: [Ljava.lang.Long; cannot be cast to java.sql.Array}）。
 * 同一处错误假设在人工窗口的 {@code toList} 里也有，只是它静默返回空列表
 * （板位可能位置恒为空），不报错所以一直没被发现。
 *
 * <p>⇒ 三种形态都认；**不要再在任何地方直接强转 {@code java.sql.Array}**。
 */
public final class DbArrays {
    private DbArrays() {
    }

    /** 数组列 → List&lt;Object&gt;（元素原样保留：Long / String / …）。 */
    public static List<Object> objects(Object raw) {
        List<Object> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof java.sql.Array arr) {
            try {
                Object inner = arr.getArray();
                if (inner instanceof Object[] os) {
                    for (Object o : os) {
                        out.add(o);
                    }
                }
            } catch (SQLException e) {
                // 取不到就当空数组：调用方多数是"可选信息"，不该因此整条失败
                return out;
            }
            return out;
        }
        if (raw instanceof Object[] os) {
            for (Object o : os) {
                out.add(o);
            }
        }
        return out;
    }

    /** 数组列 → List&lt;Long&gt;（非数字元素跳过）。 */
    public static List<Long> longs(Object raw) {
        List<Long> out = new ArrayList<>();
        for (Object o : objects(raw)) {
            if (o instanceof Number n) {
                out.add(n.longValue());
            }
        }
        return out;
    }
}

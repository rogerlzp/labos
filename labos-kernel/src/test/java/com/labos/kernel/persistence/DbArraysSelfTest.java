package com.labos.kernel.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * PG 数组列摊平的回归自检（2026-11-10）。
 *
 * <p><b>为什么单独钉死</b>：PG 数组列回来的是什么形态，取决于取法 ——
 * {@code getArray(...)} 给 {@link java.sql.Array}，{@code getObject(...)} 给
 * **Java 数组**（{@code Long[]} / {@code String[]}）。MyBatis 走
 * {@code resultType=java.util.Map} 时走的是后者。
 * <p>原来的代码按 {@code java.sql.Array} 强转 ⇒
 * {@code ClassCastException: [Ljava.lang.Long; cannot be cast to java.sql.Array}
 * ⇒ **每次执行工艺都 500**；同一个错误假设在人工窗口那边是静默的
 * （板位"可能位置"恒为空），不报错所以一直没被发现。
 * <p>⇒ 本自检把三种形态（{@code java.sql.Array} / Java 数组 / null）
 * 加上异常路径全部钉死；**谁再写 {@code (java.sql.Array) x} 就该被这里抓住**。
 */
public final class DbArraysSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        // ---- objects()：三种形态 ----
        eqList("objects(null) → 空", List.of(), DbArrays.objects(null));
        eqList("objects(Long[]) → 元素原样保留",
                List.of(1L, 2L), DbArrays.objects(new Long[] { 1L, 2L }));
        eqList("objects(String[]) → 元素原样保留",
                List.of("a", "b"), DbArrays.objects(new String[] { "a", "b" }));
        eqList("objects(Object[]) → 元素原样保留",
                List.of(1L, "x"), DbArrays.objects(new Object[] { 1L, "x" }));

        // ---- objects()：java.sql.Array 也要认（显式 getArray 取到的形态）----
        eqList("objects(java.sql.Array) → 摊平内层数组",
                List.of(1L, 2L), DbArrays.objects(sqlArray(new Long[] { 1L, 2L })));
        eqList("objects(java.sql.Array 内层 null) → 空",
                List.of(), DbArrays.objects(sqlArray(null)));
        eqList("objects(java.sql.Array 取值抛错) → 空（不炸）",
                List.of(), DbArrays.objects(sqlArrayThrows()));

        // ---- objects()：完全认不出的形态 → 空（不能抛）----
        eqList("objects(不可识别形态) → 空",
                List.of(), DbArrays.objects(123));

        // ---- longs()：非数字跳过 ----
        eqList("longs(Long[]) → 全部转 Long",
                List.of(1L, 2L), DbArrays.longs(new Long[] { 1L, 2L }));
        eqList("longs(String[]) → 非数字全跳过",
                List.of(), DbArrays.longs(new String[] { "a", "b" }));
        eqList("longs(混合) → 只留数字",
                List.of(1L, 2L), DbArrays.longs(new Object[] { 1L, "x", 2.0d }));
        eqList("longs(null) → 空",
                List.of(), DbArrays.longs(null));
        eqList("longs(java.sql.Array) → 摊平后取数字",
                List.of(7L), DbArrays.longs(sqlArray(new Long[] { 7L })));

        // ---- 回归本体：原来那行强转会炸，现在不能炸 ----
        // （原写法：java.sql.Array arr = (java.sql.Array) m.get("ids");）
        eqList("回归：Long[] 直接喂给 longs() 不抛 ClassCastException",
                List.of(11L, 12L), DbArrays.longs(new Long[] { 11L, 12L }));

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------- 断言 ----------------

    private static void eqList(String name, List<?> expect, List<?> actual) {
        if (expect.equals(actual)) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "：期望 " + expect + "，实际 " + actual);
        }
    }

    // ---------------- 假 java.sql.Array ----------------

    /** 模拟 {@code ResultSet.getArray(...)} 的返回：内层是 Java 数组。 */
    private static Array sqlArray(Object inner) {
        return new FakeArray(inner, false);
    }

    /** 模拟取内层数组时报错（连接断了 / 驱动异常）。 */
    private static Array sqlArrayThrows() {
        return new FakeArray(null, true);
    }

    private static final class FakeArray implements Array {
        private final Object inner;
        private final boolean boom;

        FakeArray(Object inner, boolean boom) {
            this.inner = inner;
            this.boom = boom;
        }

        @Override
        public Object getArray() throws SQLException {
            if (boom) {
                throw new SQLException("模拟驱动异常");
            }
            return inner;
        }

        @Override
        public Object getArray(java.util.Map<String, Class<?>> map) throws SQLException {
            return getArray();
        }

        @Override
        public Object getArray(long index, int count) throws SQLException {
            return getArray();
        }

        @Override
        public Object getArray(long index, int count, java.util.Map<String, Class<?>> map)
                throws SQLException {
            return getArray();
        }

        @Override
        public int getBaseType() {
            return java.sql.Types.BIGINT;
        }

        @Override
        public String getBaseTypeName() {
            return "bigint";
        }

        @Override
        public void free() {
        }

        @Override
        public ResultSet getResultSet() {
            return null;
        }

        @Override
        public ResultSet getResultSet(java.util.Map<String, Class<?>> map) {
            return null;
        }

        @Override
        public ResultSet getResultSet(long index, int count) {
            return null;
        }

        @Override
        public ResultSet getResultSet(long index, int count, java.util.Map<String, Class<?>> map) {
            return null;
        }

        @Override
        public String toString() {
            return "FakeArray" + Arrays.toString(new Object[] { inner });
        }
    }
}

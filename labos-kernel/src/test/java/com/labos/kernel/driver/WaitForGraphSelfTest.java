package com.labos.kernel.driver;

/**
 * 等待图单测。
 *
 * <p>核心场景就是用户问的那个：3 个流程走 A→B→C→A 环，第三个必须被拒。
 */
public final class WaitForGraphSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        testSimpleChain();
        testThreeWayCycle();
        testReleaseBreaksCycle();
        testSelfWait();
        testFiveWayCycle();

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 链：1 等 2、2 等 3。都不成环。 */
    private static void testSimpleChain() {
        WaitForGraph g = new WaitForGraph();
        boolean r1 = g.waitFor(1, 2);
        boolean r2 = g.waitFor(2, 3);
        check("链 1→2 无环", !r1, r1);
        check("链 2→3 无环", !r2, r2);
        check("等待者数 2", g.waitingCount() == 2, g.waitingCount());
    }

    /** 用户提的场景：A→B→C→A，第三个被拒。 */
    private static void testThreeWayCycle() {
        WaitForGraph g = new WaitForGraph();
        boolean r1 = g.waitFor(1, 2);
        boolean r2 = g.waitFor(2, 3);
        boolean r3 = g.waitFor(3, 1);      // 这条会成环

        check("① 流程1 等流程2 允许", !r1, r1);
        check("② 流程2 等流程3 允许", !r2, r2);
        check("③ 流程3 等流程1 **被拒**（成环）", r3, r3);
        check("被拒计数 1", g.rejectedCount() == 1, g.rejectedCount());
        check("拒绝后图上没有 3→1 的边",
                !g.snapshot().containsKey(3) || !g.snapshot().get(3).contains(1L),
                g.snapshot());
    }

    /** 流程1 结束后释放，流程3 才能等流程1。 */
    private static void testReleaseBreaksCycle() {
        WaitForGraph g = new WaitForGraph();
        g.waitFor(1, 2);
        g.waitFor(2, 3);
        boolean rejected = g.waitFor(3, 1);
        check("先被拒", rejected, rejected);

        // 流程1 拿到资源了，不再等
        g.release(1);
        boolean now = g.waitFor(3, 1);
        check("流程1 释放后 3→1 可以了", !now, now);
    }

    /** 自己等自己：状态机出错，当环处理。 */
    private static void testSelfWait() {
        WaitForGraph g = new WaitForGraph();
        boolean r = g.waitFor(1, 1);
        check("自己等自己被拒", r, r);
    }

    /** 五元环：前四条允许，第五条拒绝。 */
    private static void testFiveWayCycle() {
        WaitForGraph g = new WaitForGraph();
        boolean ok = true;
        for (int i = 1; i <= 4; i++) {
            if (g.waitFor(i, i + 1)) {
                ok = false;
            }
        }
        check("五元环前 4 条都允许", ok, ok);
        boolean last = g.waitFor(5, 1);
        check("第五条第拒（成环）", last, last);
    }

    private static void check(String name, boolean expect, Object actual) {
        if (expect) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "（实际：" + actual + "）");
        }
    }
}

package com.labos.kernel.engine;

/**
 * 睡眠抽象：让退避等待可被测试替换，避免单测真的睡 2 秒。
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;

    /** 默认实现：真实阻塞。 */
    Sleeper REAL = Thread::sleep;

    /** 测试实现：只记录，不真睡。 */
    static Sleeper recording(java.util.List<Long> sink) {
        return millis -> sink.add(millis);
    }
}

package com.labos.kernel.recovery;

/**
 * 错误发生的层级。
 *
 * <p>同一错误码在不同层级下的处理方式可能完全不同。例如「通讯超时」：
 * 在 command 层重发即可，在 node 层可能要换设备，在 run 层则必须人工介入。
 */
public enum RecoveryScope {

    /** 单条指令（一次设备调用）。粒度最细，重试成本最低。 */
    COMMAND,

    /** 单个流程节点（可能包含多次指令）。 */
    NODE,

    /** 整个执行实例（一次实验/一个流程）。 */
    RUN,

    /** 设备维度（设备离线、驱动崩溃）。影响面最大。 */
    DEVICE
}

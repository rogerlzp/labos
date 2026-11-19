# Workflow Runtime Worker 执行事实与单实例恢复协议 v1.2

日期：2026-11-19  
适用架构：LabOS Workflow Runtime V1 单实例规范  
状态：目标协议；代码落地和 PG17 验证尚未完成

## 1. 设计边界

V1 只部署一个 Runtime Host 进程，由部署配置保证唯一，不在 PostgreSQL 中实现 Host 锁、租约或选举。V1 不使用数据库 Worker Lease，不使用 leaseEpoch、leaseVersion、dispatchEpoch 或设备 fencing token。Worker 执行登记保存在该进程的有界 WorkerPool 和 Attempt registry 中。

数据库仍保存 Attempt 状态和 append-only 执行事实。进程内执行登记用于取消和防止同一进程重复调度；数据库事务/version 用于持久化状态转换。二者均不宣称能够终止物理设备动作。

## 2. 执行事实

worker_execution_fact 保留为 append-only 证据账本，记录 HANDLER_STARTED、SIDE_EFFECT_STARTED、SIDE_EFFECT_COMPLETED、SIDE_EFFECT_UNKNOWN、EXECUTION_RIGHT_RELEASED。事实按 Attempt 归属，使用 factKey 幂等；同 key 不同内容拒绝。factSeq 在锁定 Attempt 的事务内分配，禁止 UPDATE/DELETE。

外部调用前必须先持久化 SIDE_EFFECT_STARTED。Adapter 调用结果不确定时写 SIDE_EFFECT_UNKNOWN，并将 Command/Attempt 转为 UNKNOWN；禁止自动重发。

## 3. Worker 超时

Host 存活时由本地 Attempt registry 发出取消请求。只有同时证明以下条件才允许 ABANDONED_SAFE：没有 SIDE_EFFECT_STARTED、没有 SEND_STARTED、没有未决/不确定 Command。否则转 UNKNOWN/MANUAL_REQUIRED，关闭 Run control gate，并要求按 commandId 查询或人工核验。

线程中断、Future cancel 成功、Worker 心跳停止均不是物理动作停止证据。

## 4. Runtime 重启恢复

唯一 Host 启动时执行恢复扫描，不存在多实例 Recovery 竞争：
- 未越过外部副作用边界：可 ABANDONED_SAFE，释放容量预留并创建新的 Attempt；
- 已有 SIDE_EFFECT_STARTED / SEND_STARTED / ACKNOWLEDGED / RUNNING 或无法判断：先按 commandId 查询并回读设备状态；
- 设备确认完成：按设备回执完成原 Attempt，不重新执行；
- 结果仍不确定：Command/Attempt UNKNOWN，Run MANUAL_REQUIRED，controlGate CLOSED。

## 5. 设备命令安全

稳定 commandId、设备回执回显 commandId、命令查询、Inbox 去重和物理状态回读仍是 V1 必需。ACK 不等于物理动作完成。数据库不能撤回已经发送的请求，也不能终止设备正在执行的动作。

## 6. 实施状态

当前代码仍存在旧 Worker Lease 和 epoch 实现。必须删除对应 DDL、Repository 方法、RecoveryService 和测试，并以单实例 Host 启动恢复及 Worker 超时测试替代。完成 PG17 CI 前，本协议仅为目标设计，不得标记为已实现。

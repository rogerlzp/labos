/**
 * 领域 code → 英文文案表。
 *
 * <p><b>为什么不放在 messages 包里</b>：状态、暂停类型、现场扣留这些文案，
 * 中文侧的**权威来源是后端**（{@code GET /api/meta/status-vocabulary}，
 * 由 {@code StatusVocabulary.java} 从 DB 词表给出）。所以它们不是"界面文案"，
 * 而是"数据字典"：中文跟着后端走，英文必须自己维护一份。
 *
 * <p><b>铁律：英文界面里绝不出现中文字符</b>。后端词表给的是中文 label，
 * 直接拿来用就会把"已完成"混进英文界面。所以英文侧一律按 code 查这张表，
 * 查不到就显示 code 本身（机器可读，不误导人）。
 *
 * <p>key 命名：
 * <ul>
 *   <li>{@code status.<scope>.<code>} — scope ∈ run/node/step/experiment/batch/control</li>
 *   <li>{@code pause.<code>} / {@code pause.<code>.hint} — 暂停类型与处置提示</li>
 *   <li>{@code hold.<code>} — 现场扣留类型</li>
 *   <li>{@code holdScope.<code>} — 现场扣留作用域</li>
 * </ul>
 *
 * <p>完整性由 {@code scripts/scan-i18n.py} 校验：后端词表里的每个 code
 * 都必须在这里有条目，缺一条就报错（否则新状态在英文界面显示成裸 code）。
 */

/** code → 英文文案（中文侧不在这里：走后端词表）。 */
export const CODE_TEXT_EN: Record<string, string> = {
  // ------------------------------------------------------------ run
  'status.run.draft': 'Draft',
  'status.run.pending': 'Pending',
  'status.run.planned': 'Scheduled',
  'status.run.queued': 'Queued',
  'status.run.running': 'Running',
  'status.run.paused': 'Paused',
  'status.run.completed': 'Completed',
  'status.run.failed': 'Failed',
  'status.run.aborted': 'Aborted',
  'status.run.cancelled': 'Cancelled',
  'status.run.skipped': 'Skipped',

  // ------------------------------------------------------------ node
  'status.node.pending': 'Not started',
  'status.node.ready': 'Ready',
  'status.node.running': 'Running',
  'status.node.completed': 'Completed',
  'status.node.failed': 'Failed',
  'status.node.manual': 'Awaiting operator',
  'status.node.skipped': 'Skipped',
  'status.node.aborted': 'Aborted',
  // 异或网关选了别的路 —— 不能和「Not started」混为一谈
  'status.node.bypassed': 'Branch not taken',

  // ------------------------------------------------------------ step
  'status.step.pending': 'Not started',
  'status.step.running': 'Running',
  'status.step.completed': 'Executed',
  'status.step.failed': 'Failed',
  'status.step.skipped': 'Skipped',
  'status.step.paused': 'Paused',

  // ------------------------------------------------------------ experiment
  'status.experiment.created': 'Created',
  'status.experiment.planned': 'Scheduled',
  'status.experiment.queued': 'Queued',
  'status.experiment.running': 'Running',
  'status.experiment.paused': 'Paused',
  'status.experiment.completed': 'Completed',
  // 库存里的完成态是 finished，和 completed 是同一件事
  'status.experiment.finished': 'Completed',
  'status.experiment.failed': 'Failed',
  'status.experiment.aborted': 'Aborted',
  'status.experiment.cancelled': 'Cancelled',
  'status.experiment.skipped': 'Skipped',
  'status.experiment.deleted': 'Deleted',

  // ------------------------------------------------------------ batch
  'status.batch.pending': 'Pending',
  'status.batch.queued': 'Queued',
  'status.batch.running': 'Running',
  'status.batch.paused': 'Paused',
  'status.batch.completed': 'Completed',
  'status.batch.failed': 'Failed',
  'status.batch.aborted': 'Aborted',

  // ------------------------------------------------------------ control
  // 暂停控制过程（experiment_run.control_state，与 experiment 生命周期正交）
  'status.control.NONE': 'None',
  'status.control.PAUSE_REQUESTED': 'Pause requested',
  'status.control.PAUSING': 'Pausing',
  'status.control.RESUMING': 'Resuming',

  // ------------------------------------------------------------ 暂停类型
  'pause.paused_fault': 'Device fault',
  'pause.paused_fault.hint':
    'Resumes on its own once the device heartbeat recovers; usually no action is needed. If it has not recovered after 10 minutes, check the device.',
  'pause.waiting_human': 'Waiting for operator',
  'pause.waiting_human.hint':
    'Retries are exhausted or a decision is required — confirm on site, then press continue.',
  'pause.paused_unknown': 'Plate position unknown',
  'pause.paused_unknown.hint':
    'The plate location is unknown; it must be counted on site and the actual position recorded.',

  // ------------------------------------------------------------ 现场扣留类型
  'hold.fault': 'Device fault',
  'hold.plate_unknown': 'Plate position unknown',
  'hold.human': 'Awaiting operator confirmation',
  'hold.manual': 'Manual hold',

  // ------------------------------------------------------------ 现场扣留作用域
  'holdScope.site': 'Whole site',
  'holdScope.island': 'Island',
  'holdScope.experiment': 'This experiment',
};

/**
 * 取 code 的英文文案；表里没有就返回 code 本身。
 *
 * <p>回落成 code 而不是回落成中文 label：中文 label 只存在于后端词表里，
 * 拿它当回落等于把中文塞进英文界面。
 */
export function codeTextEn(key: string, code: string): string {
  return CODE_TEXT_EN[key] ?? code;
}

/**
 * 展示用格式化（只做显示，不参与调度计算）。
 *
 * <p>为什么抽成公共文件：实验列表的「预计等待」列与调度器页的排队表必须同一口径。
 * 各写一份迟早会漂成"列表说 30 分、调度器说 31 分"，值班员只会怀疑数据是错的。
 */
import { translate } from '../i18n/core';

/**
 * 预计等待秒数 → 「约 N 秒 / 约 N 分 / 约 N 小时 M 分」。
 *
 * <p>分三档的理由：1 分钟内报秒（"马上就轮到"值得精确到秒）；1 小时内报分
 * （分钟级已经够做计划）；超过 1 小时报"X 小时 Y 分"（夜班排程动辄几小时，
 * 光报 267 分没法心算）。
 *
 * <p>分钟**向下取整**：估算本身来自未完成节点的时长，再报小数只是假装精确。
 */
export function formatWait(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  if (s < 60) return translate('format.wait.seconds', { n: s });
  if (s < 3600) return translate('format.wait.minutes', { n: Math.floor(s / 60) });
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return translate('format.wait.hours', { h, m });
}

/**
 * 时间串 → 本地时区 `YYYY-MM-DD HH:mm:ss`。
 *
 * <p>后端有两套时间串（与 Experiments.tsx 同口径）：多数接口给 JDBC Timestamp 的
 * **本地**时间（`2026-11-03 18:50:40.0`），调度器状态给 `Instant.toString()` 的
 * **UTC**（`…T10:50:40Z`）。一律 `slice(0,19)` 会把 UTC 当本地显示，
 * 岛锁的"开始/到期"会差 8 小时，值班员会误判锁已经过期。
 * 这里两种都扛，且对本地时间不做无谓换算。
 */
export function formatTime(s?: string | null): string {
  if (!s) return '—';
  const naive = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}/.test(s)
    && !/(?:Z|[+-]\d{2}:?\d{2})$/.test(s);
  if (naive) return s.slice(0, 19).replace('T', ' ');
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s.slice(0, 19).replace('T', ' ');
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

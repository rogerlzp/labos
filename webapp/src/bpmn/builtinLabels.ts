/**
 * 内置逻辑节点的方法名 → 中文标签。
 *
 * <p>为什么要有：`__wait` / `__sleep` 这种内部名出现在台账、节点清单上，
 * 现场根本读不出它是什么。设备方法名（`Home` / `Seal` …）是设备协议里的名字，
 * **不该改** ⇒ 只给内置方法配标签。
 */
export const BUILTIN_LABEL: Record<string, string> = {
  __sleep: '延时',
  __wait: '等待',
  __waitAny: '等待任一',
  __setVar: '赋值',
  __expr: '表达式',
};

/**
 * 方法名的显示文本：内置方法给中文，设备方法原样。
 *
 * @param method 方法名（可能是内置方法，也可能是设备方法）
 * @param withRaw 是否同时带上原始名（台账的「方法」列用：中文 + 原名）
 */
export function methodLabel(method?: string | null, withRaw = false): string {
  if (!method) return '—';
  const zh = BUILTIN_LABEL[method];
  if (!zh) return method;
  return withRaw ? `${zh}（${method}）` : zh;
}

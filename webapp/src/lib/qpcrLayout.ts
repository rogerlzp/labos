/**
 * qPCR 96 → 384 孔板布局生成器（确定性纯函数）。
 *
 * <p>设计口径对齐 B 站视频《Gemini4 AI: 96 -> 384 qPCR SetUp Use Indepent 8 Channel》
 * 里那套「Hamilton STAR qPCR Worklist & Traceability System」的三张表：
 * MasterMix / TransferSample / Trace。本模块不生成一次性 AI 结果，而是把
 * 「样品数 × 技术重复 × 靶点」展开成**可校验、可追溯、可复现**的孔级映射。
 *
 * <p>布局规则（默认，页面上有说明）：
 * <ul>
 *   <li>源板 96 孔 = 8 行(A–H) × 12 列；样品按行优先 A1…H12 摆放。</li>
 *   <li>目标板 384 孔 = 16 行(A–P) × 24 列，分上下两区：
 *       区 0 = A–H，区 1 = I–P（对应视频「两侧区隔 2 区」）。</li>
 *   <li>每个靶点占连续 N 列，N = ceil(样品数 / 8)；靶点依次从左向右排布。</li>
 *   <li>技术重复 1 → 只放区 0；技术重复 2 → 同一列号在区 0 / 区 1 各一份。</li>
 *   <li>一个 (样品, 靶点, 重复) 组合占一个 384 孔；孔内含 MasterMix + 样品两份体积。</li>
 * </ul>
 *
 * <p>为什么不放 AI 进来直接产 worklist：AI 幻觉在这里等于「机器撞针 + 试剂浪费」。
 * 本模块只做确定性展开，校验交给 checks，执行交给后续命令编译层。
 */

export interface QpcrTarget {
  /** 稳定 key（表格 rowKey 用） */
  key: string;
  name: string;
  dye: string;
}

export interface QpcrLayoutParams {
  /** 样品 / 对照数，1–96 */
  sampleCount: number;
  /** 技术重复：1 = 上半区；2 = 上下两区各一份 */
  replicates: 1 | 2;
  targets: QpcrTarget[];
  /** MasterMix 每孔体积 µL */
  masterMixVol: number;
  /** 样品每孔转移体积 µL */
  sampleVol: number;
  /** 被动参考染料（如 ROX），仅作记录 */
  passiveRef: string;
  sourcePlateId: string;
  targetPlateId: string;
}

/** 与视频 TransferSample sheet 对齐的一行 */
export interface TransferRow {
  seq: number;
  sourcePlate: string;
  sourceWell: string;
  targetPlate: string;
  targetWell: string;
  sampleIdx: number;
  replicate: number;
  targetName: string;
  dye: string;
  masterMixVol: number;
  sampleVol: number;
  note: string;
}

/** 384 板上的一格（含空格，便于整板渲染） */
export interface WellCell {
  well: string;
  row: number;
  col: number;
  zone: 0 | 1;
  sourceWell?: string;
  sampleIdx?: number;
  replicate?: number;
  targetName?: string;
  dye?: string;
  masterMixVol?: number;
  sampleVol?: number;
}

export interface LayoutCheck {
  level: 'ok' | 'warn' | 'error';
  code: string;
  text: string;
}

export interface QpcrLayoutResult {
  params: QpcrLayoutParams;
  perTargetCols: number;
  requiredCols: number;
  transfers: TransferRow[];
  sourceWells: Array<{ well: string; sampleIdx: number }>;
  plate384: WellCell[];
  used384: number;
  checks: LayoutCheck[];
  ok: boolean;
}

const ROWS_PER_ZONE = 8;
const COLS_384 = 24;
const COLS_96 = 12;
const ROWS_96 = 8;
const WELL_CAPACITY_UL = 100;

export const DYE_COLORS: Record<string, string> = {
  FAM: '#2f9e44',
  VIC: '#1971c2',
  HEX: '#0c8599',
  ROX: '#e8590c',
  TAMRA: '#c2255c',
  CY5: '#6741d9',
};

export function dyeColor(dye?: string): string {
  return DYE_COLORS[(dye || '').trim().toUpperCase()] ?? '#868e96';
}

/** 0-based 行号 → A/B/…/P */
export const rowLabel = (row0: number): string => String.fromCharCode(65 + row0);

/** 样品序号（0-based）→ 96 板孔位 A1…H12 */
export const well96 = (sampleIdx0: number): string =>
  `${rowLabel(Math.floor(sampleIdx0 / COLS_96))}${(sampleIdx0 % COLS_96) + 1}`;

/** 384 板 0-based 行列 → 孔位 A1…P24 */
export const well384 = (row0: number, col0: number): string =>
  `${rowLabel(row0)}${col0 + 1}`;

/** 默认靶点（视频里同时跑了 GAPDH / APOE / Genew） */
export const DEFAULT_TARGETS: QpcrTarget[] = [
  { key: 't1', name: 'GAPDH', dye: 'VIC' },
  { key: 't2', name: 'APOE', dye: 'FAM' },
  { key: 't3', name: 'Genew', dye: 'FAM' },
];

export const DEFAULT_PARAMS: QpcrLayoutParams = {
  sampleCount: 20,
  replicates: 1,
  targets: DEFAULT_TARGETS,
  masterMixVol: 15,
  sampleVol: 5,
  passiveRef: 'ROX',
  sourcePlateId: 'Sample_96_01',
  targetPlateId: 'qPCR_384_01',
};

/**
 * 生成布局。纯函数：相同入参永远得到相同结果。
 *
 * <p>校验分三类：error = 物理上不可执行（孔位越界/超容量/靶点为 0），
 * warn = 可执行但需人工确认（未用满板、单靶点跨列较多），ok = 通过。
 */
export function generateLayout(p: QpcrLayoutParams): QpcrLayoutResult {
  const checks: LayoutCheck[] = [];
  const n = Math.max(0, Math.floor(p.sampleCount));
  const targets = p.targets.filter((x) => x.name.trim().length > 0);
  const perTargetCols = n > 0 ? Math.ceil(n / ROWS_PER_ZONE) : 0;
  const requiredCols = perTargetCols * targets.length;

  if (n === 0) {
    checks.push({ level: 'error', code: 'NO_SAMPLE', text: '样品数为 0：没有可布局的样品。' });
  } else if (n > ROWS_96 * COLS_96) {
    checks.push({ level: 'error', code: 'SAMPLE_OVERFLOW', text: `样品数 ${n} 超过单块 96 板容量（96）。` });
  }
  if (targets.length === 0) {
    checks.push({ level: 'error', code: 'NO_TARGET', text: '靶点为空：请至少配置一个靶点。' });
  }
  if (requiredCols > COLS_384) {
    checks.push({
      level: 'error',
      code: 'COL_OVERFLOW',
      text: `384 板 24 列放不下：${targets.length} 靶点 × ${perTargetCols} 列 = ${requiredCols} 列。请减少靶点或样品数。`,
    });
  }
  const total = n * targets.length * p.replicates;
  if (total > ROWS_PER_ZONE * 2 * COLS_384) {
    checks.push({ level: 'error', code: 'WELL_OVERFLOW', text: `需要 ${total} 孔，超过 384 板容量。` });
  }
  const wellVol = p.masterMixVol + p.sampleVol;
  if (wellVol > WELL_CAPACITY_UL) {
    checks.push({
      level: 'error',
      code: 'VOL_OVERFLOW',
      text: `单孔总体积 ${wellVol} µL 超过 384 孔推荐上限 ${WELL_CAPACITY_UL} µL。`,
    });
  }
  if (p.masterMixVol <= 0 || p.sampleVol <= 0) {
    checks.push({ level: 'error', code: 'VOL_NONPOSITIVE', text: 'MasterMix 与样品体积都必须大于 0。' });
  }

  const cells: WellCell[][] = Array.from({ length: ROWS_PER_ZONE * 2 }, (_, row0) =>
    Array.from({ length: COLS_384 }, (_, col0): WellCell => ({
      well: well384(row0, col0),
      row: row0,
      col: col0,
      zone: row0 < ROWS_PER_ZONE ? 0 : 1,
    })),
  );

  const sourceWells: Array<{ well: string; sampleIdx: number }> = [];
  for (let i = 0; i < Math.min(n, ROWS_96 * COLS_96); i += 1) {
    sourceWells.push({ well: well96(i), sampleIdx: i + 1 });
  }

  const transfers: TransferRow[] = [];
  if (checks.every((c) => c.level !== 'error')) {
    let seq = 1;
    targets.forEach((target, targetIdx) => {
      const colStart = targetIdx * perTargetCols;
      for (let sampleIdx = 0; sampleIdx < n; sampleIdx += 1) {
        const colInZone = Math.floor(sampleIdx / ROWS_PER_ZONE);
        const rowInZone = sampleIdx % ROWS_PER_ZONE;
        const src = well96(sampleIdx);
        for (let rep = 0; rep < p.replicates; rep += 1) {
          const row0 = rep === 0 ? rowInZone : ROWS_PER_ZONE + rowInZone;
          const col0 = colStart + colInZone;
          const dst = well384(row0, col0);
          const cell = cells[row0][col0];
          cell.sourceWell = src;
          cell.sampleIdx = sampleIdx + 1;
          cell.replicate = rep + 1;
          cell.targetName = target.name;
          cell.dye = target.dye;
          cell.masterMixVol = p.masterMixVol;
          cell.sampleVol = p.sampleVol;
          transfers.push({
            seq,
            sourcePlate: p.sourcePlateId,
            sourceWell: src,
            targetPlate: p.targetPlateId,
            targetWell: dst,
            sampleIdx: sampleIdx + 1,
            replicate: rep + 1,
            targetName: target.name,
            dye: target.dye,
            masterMixVol: p.masterMixVol,
            sampleVol: p.sampleVol,
            note: `S${sampleIdx + 1}(${src}) + ${target.name}(${target.dye}) · rep${rep + 1}`,
          });
          seq += 1;
        }
      }
    });
  }

  const used384 = transfers.filter((r) => r.replicate === 1).length;
  const ok = !checks.some((c) => c.level === 'error');

  if (ok) {
    checks.push({ level: 'ok', code: 'LAYOUT_OK', text: `布局生成成功：${transfers.length} 条转移，占用 ${used384}/${ROWS_PER_ZONE * 2 * COLS_384} 孔。` });
    if (used384 < ROWS_PER_ZONE * 2 * COLS_384) {
      checks.push({
        level: 'warn',
        code: 'PLATE_NOT_FULL',
        text: `目标板只用了 ${((used384 / 384) * 100).toFixed(1)}% 孔位；确认这不是漏加样品。`,
      });
    }
    if (perTargetCols > 1) {
      checks.push({
        level: 'warn',
        code: 'MULTI_COL_TARGET',
        text: `每靶点占 ${perTargetCols} 列（样品数 ${n} > 8）：同一靶点跨列，上机前请核对列号。`,
      });
    }
    const dyeByTarget = new Map(targets.map((x) => [x.name.trim().toUpperCase(), x.dye.toUpperCase()]));
    if (dyeByTarget.has(p.passiveRef.toUpperCase())) {
      checks.push({ level: 'warn', code: 'DYE_CONFLICT', text: `被动参考 ${p.passiveRef} 与某个靶点染料相同，可能干扰通道判读。` });
    }
  }

  return {
    params: p,
    perTargetCols,
    requiredCols,
    transfers,
    sourceWells,
    plate384: cells.flat(),
    used384,
    checks,
    ok,
  };
}

/** 导出 Hamilton 风格 worklist（A=吸样 / D=分液，空枪头位让机器自选）。 */
export function toWorklist(result: QpcrLayoutResult): string {
  const p = result.params;
  const head = [
    'C; LabOS qPCR 96->384 worklist',
    `C; source=${p.sourcePlateId} target=${p.targetPlateId}`,
    `C; samples=${p.sampleCount} replicates=${p.replicates} targets=${p.targets.map((t) => `${t.name}:${t.dye}`).join(',')}`,
    'C; columns: cmd;tipslot;well;volume',
  ];
  const body = result.transfers.flatMap((r) => [
    `A;;${r.sourceWell};;${r.sampleVol}`,
    `D;;${r.targetWell};;${r.sampleVol}`,
  ]);
  return [...head, ...body].join('\r\n');
}

/** 导出 Trace 表（逐孔来源→目标，可追溯到样品与靶点）。 */
export function toTraceCsv(result: QpcrLayoutResult): string {
  const head = 'seq,sourcePlate,sourceWell,targetPlate,targetWell,sampleIdx,replicate,targetName,dye,masterMixVol,sampleVol,note';
  const body = result.transfers.map((r) => [
    r.seq, r.sourcePlate, r.sourceWell, r.targetPlate, r.targetWell,
    r.sampleIdx, r.replicate, r.targetName, r.dye, r.masterMixVol, r.sampleVol, `"${r.note.replace(/"/g, '""')}"`,
  ].join(','));
  return [head, ...body].join('\r\n');
}

/** 触发浏览器下载（页面内导出按钮用）。 */
export function downloadText(filename: string, text: string, mime = 'text/plain;charset=utf-8'): void {
  const blob = new Blob([`\ufeff${text}`], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

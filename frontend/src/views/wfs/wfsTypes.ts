import type {
  DetailDrawerSection,
  ImportBatchRecord,
  ImportErrorRow,
  ImportFeedbackState,
} from '../../components';

export interface WfsPeriod {
  start: string;
  end: string;
}

export interface WfsPageResult<Row = Record<string, unknown>> {
  rows: Row[];
  total: number;
  page: number;
  size: number;
  appliedPeriod: WfsPeriod | null;
  periods: WfsPeriod[];
  summary: Record<string, unknown>;
  officialSummary: Record<string, unknown>;
}

export interface WfsImportResult {
  batchId?: number;
  status?: string;
  fileName?: string;
  periodStart?: string | null;
  periodEnd?: string | null;
  totalRows?: number;
  importedRows?: number;
  duplicateRows?: number;
  failedRows?: number;
  alreadyImported?: boolean;
  message?: string;
  errors?: ImportErrorRow[];
}

export interface WfsDeleteResult {
  batchId: number;
  removedLinks: number;
  removedRows: number;
}

export type WfsBatchView = ImportBatchRecord;

export type WfsViewKind = 'settlement' | 'storage';

export interface WfsDrawerState {
  open: boolean;
  loading: boolean;
  eyebrow: string;
  title: string;
  subtitle: string;
  sections: DetailDrawerSection[];
}

export const EMPTY_WFS_DRAWER: WfsDrawerState = {
  open: false,
  loading: false,
  eyebrow: '',
  title: '',
  subtitle: '',
  sections: [],
};

type WfsLoadHandler = () => void;

const wfsLoadHandlers: Partial<Record<WfsViewKind, WfsLoadHandler>> = {};
const pendingWfsLoads: Record<WfsViewKind, boolean> = {
  settlement: false,
  storage: false,
};

export function registerWfsLoader(kind: WfsViewKind, handler: WfsLoadHandler): () => void {
  wfsLoadHandlers[kind] = handler;
  if (pendingWfsLoads[kind]) {
    pendingWfsLoads[kind] = false;
    handler();
  }
  return () => {
    if (wfsLoadHandlers[kind] === handler) {
      delete wfsLoadHandlers[kind];
    }
  };
}

export function triggerWfsLoad(kind: WfsViewKind): void {
  const handler = wfsLoadHandlers[kind];
  if (handler) {
    pendingWfsLoads[kind] = false;
    handler();
  } else {
    pendingWfsLoads[kind] = true;
  }
}

export function getField<T = unknown>(
  source: Record<string, unknown> | null | undefined,
  ...keys: string[]
): T | null {
  if (!source) return null;
  for (const key of keys) {
    const val = source[key];
    if (val !== undefined && val !== null) {
      return val as T;
    }
  }
  return null;
}

export function getObjectValue<T = unknown>(
  source: Record<string, unknown> | null | undefined,
  normalizedKey: string,
): T | null {
  if (!source) return null;
  for (const [key, val] of Object.entries(source)) {
    if (key.trim().toLowerCase().replace(/\s+/g, ' ') === normalizedKey) {
      return (val as T) ?? null;
    }
  }
  return null;
}

export function formatNumber(value: unknown, digits = 2): string {
  if (value === null || value === undefined || value === '') return '—';
  const parsed = Number(value);
  return Number.isFinite(parsed)
    ? parsed.toLocaleString('zh-CN', {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      })
    : '—';
}

export function formatMoney(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return '—';
  const absFormatted = Math.abs(parsed).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return parsed < 0 ? `-$${absFormatted}` : `$${absFormatted}`;
}

export function formatDateTime(value: unknown): string {
  if (!value) return '—';
  const normalized = String(value).replace('T', ' ');
  return normalized.length > 19 ? normalized.slice(0, 19) : normalized;
}

export function formatPeriodLabel(period: Partial<WfsPeriod> | null | undefined): string {
  return period?.start && period?.end ? `${period.start} 至 ${period.end}` : '暂无报表周期';
}

export async function uploadWfsReport(
  apiSegment: 'settlements' | 'storage',
  file: File,
): Promise<{ ok: boolean; feedback: ImportFeedbackState }> {
  const body = new FormData();
  body.append('file', file);
  const response = await fetch(`/api/wfs/${apiSegment}/import`, {
    method: 'POST',
    body,
  });
  let payload: { success?: boolean; message?: string; data?: WfsImportResult } | null = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  const ok = Boolean(response.ok && payload?.success);
  const result = payload?.data || { message: payload?.message, errors: [] };
  return {
    ok,
    feedback: {
      isError: !ok,
      alreadyImported: Boolean(result.alreadyImported),
      message: result.message || payload?.message || (ok ? '导入完成' : `上传失败：HTTP ${response.status}`),
      errors: result.errors || [],
    },
  };
}

export function buildBatchAuditDrawer(batch: WfsBatchView): WfsDrawerState {
  const audit: Record<string, unknown> = {
    文件哈希: batch.fileHash,
    文件大小: batch.fileSize,
    新增行: batch.importedRows,
    重复行: batch.duplicateRows,
    失败行: batch.failedRows,
    导入人: batch.createdBy,
    导入时间: batch.importedAt,
    错误信息: batch.errorMessage,
  };
  const errorMap: Record<string, unknown> = Object.fromEntries(
    (batch.errors || []).map((err, index) => [
      `错误 ${index + 1} · 第 ${err.rowNumber ?? '?'} 行`,
      err.message || JSON.stringify(err),
    ]),
  );
  return {
    open: true,
    loading: false,
    eyebrow: 'WFS IMPORT AUDIT',
    title: batch.fileName,
    subtitle: `${batch.status} · ${batch.periodStart || '—'} 至 ${batch.periodEnd || '—'}`,
    sections: [
      { title: '批次审计', data: audit },
      { title: '行错误', data: errorMap },
    ],
  };
}

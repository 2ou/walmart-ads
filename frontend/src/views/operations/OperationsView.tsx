import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Collapse,
  ConfigProvider,
  Empty,
  Input,
  Modal,
  Space,
} from 'antd';
import { CheckOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import {
  HeroButton,
  HeroCard,
  HeroCardBody,
  HeroCardHeader,
  HeroChip,
  HeroSpinner,
  MetricCardGrid,
  PageHeaderCard,
  SkuLinkTag,
  SyncStatusBanner,
} from '../../components';
import type { MetricCardItem } from '../../components';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';

const ACCENT = WORKBENCH_COLORS.walmartBlue;

const ACTION_COPY: Record<string, string> = {
  NOTE: '仅备注',
  LISTING_TITLE: 'Listing 标题',
  LISTING_IMAGES: 'Listing 图片',
  LISTING_CONTENT: '五点 / 描述 / 属性',
  PRICE: '售价调整',
  AD_BID: '广告出价',
  AD_BUDGET: '广告预算',
  AD_KEYWORDS: '广告关键词',
  AD_STATUS: '广告启停',
  AD_OPTIMIZE: '广告优化',
  PROMOTION: '促销活动',
  INVENTORY: '库存动作',
  OTHER: '其他',
};

type HeroChipColor = 'default' | 'primary' | 'success' | 'warning' | 'danger';

const REMINDER_COPY: Record<string, { label: string; color: HeroChipColor }> = {
  PENDING: { label: '待到期', color: 'default' },
  DUE: { label: '待复盘', color: 'warning' },
  OVERDUE: { label: '已逾期', color: 'danger' },
  DONE: { label: '已复盘', color: 'success' },
};

const IMPACT_COPY: Record<string, { label: string; color: HeroChipColor }> = {
  READY: { label: '完整窗口', color: 'success' },
  COLLECTING: { label: '观察中', color: 'warning' },
  WAITING: { label: '等待数据', color: 'primary' },
  NOT_EXECUTED: { label: '暂不追踪', color: 'default' },
};

const STATUS_COPY: Record<string, { label: string; color: HeroChipColor }> = {
  EXECUTED: { label: '已执行', color: 'success' },
  PLANNED: { label: '计划中', color: 'primary' },
  CANCELLED: { label: '已取消', color: 'danger' },
};

const SOURCE_COPY: Record<string, string> = {
  MANUAL: '人工',
  SYSTEM: '系统',
  AI: 'AI',
};

interface CheckpointItem {
  id: number;
  label?: string | null;
  dueDate?: string | null;
  computedStatus?: string | null;
}

interface ImpactWindow {
  startDate?: string | null;
  endDate?: string | null;
  salesPerDay?: number | null;
  unitsPerDay?: number | null;
  adSpendPerDay?: number | null;
  roas?: number | null;
}

interface OperationImpact {
  state?: string | null;
  message?: string | null;
  before?: ImpactWindow | null;
  after?: ImpactWindow | null;
  overlappingOperations?: number | null;
}

interface OperationRecord {
  id: number;
  title: string;
  status: string;
  sourceType?: string | null;
  actionType: string;
  targetType?: string | null;
  targetKey?: string | null;
  targetName?: string | null;
  targetKeys?: string[] | null;
  beforeValue?: string | null;
  afterValue?: string | null;
  note?: string | null;
  occurredAt: string;
}

interface OperationTask {
  id: number;
  title: string;
  status: string;
  goal?: string | null;
  conclusion?: string | null;
  startedAt: string;
  closedAt?: string | null;
  progress?: { done: number; total: number };
  checkpoints?: CheckpointItem[];
  records?: Array<{ record: OperationRecord; impact?: OperationImpact | null }>;
}

interface OperationGroup {
  parentSku: string;
  productName?: string | null;
  recordCount: number;
  activeTaskCount?: number;
  lastActivityAt?: string | null;
  tasks?: OperationTask[];
}

interface OperationsBoardResponse {
  meta: {
    recordCount: number;
    executedCount: number;
    activeTaskCount: number;
    dueCheckpointCount: number;
    latestDataDate?: string | null;
  };
  groups: OperationGroup[];
}

function intText(val: unknown): string {
  const n = Number(val || 0);
  return Math.round(Number.isFinite(n) ? n : 0).toLocaleString('zh-CN');
}

function moneyText(val: unknown): string {
  const n = Number(val || 0);
  return `$${(Number.isFinite(n) ? n : 0).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function ratioText(val: unknown): string {
  const n = Number(val || 0);
  return `${(Number.isFinite(n) ? n : 0).toFixed(2)}x`;
}

function calcDelta(before: unknown, after: unknown): string {
  if (after === null || after === undefined) return '等待后续数据';
  const left = Number(before || 0);
  const right = Number(after || 0);
  if (!left) return right ? '由 0 新增' : '持平';
  const change = (right - left) / Math.abs(left);
  if (Math.abs(change) < 0.005) return '基本持平';
  return `${change > 0 ? '↑' : '↓'} ${Math.abs(change * 100).toFixed(1)}%`;
}

let operationsLoadHandler: (() => void) | null = null;
let pendingOperationsLoad = false;

export function triggerOperationsLoad(): void {
  if (operationsLoadHandler) {
    pendingOperationsLoad = false;
    operationsLoadHandler();
  } else {
    pendingOperationsLoad = true;
  }
}

export const OperationsView: React.FC = () => {
  const [board, setBoard] = useState<OperationsBoardResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [resolvingId, setResolvingId] = useState<number | null>(null);

  const [closingTask, setClosingTask] = useState<OperationTask | null>(null);
  const [conclusion, setConclusion] = useState('');
  const [closingLoading, setClosingLoading] = useState(false);

  const searchRef = useRef('');
  const searchTimerRef = useRef<number | undefined>(undefined);
  const activeRequestRef = useRef<AbortController | null>(null);

  const loadData = useCallback(async (queryOverride?: string) => {
    window.clearTimeout(searchTimerRef.current);
    activeRequestRef.current?.abort();
    const request = new AbortController();
    activeRequestRef.current = request;
    setLoading(true);
    setError(null);
    try {
      const qVal = (queryOverride ?? searchRef.current).trim();
      const queryStr = workbenchBridge.getAnalysisQuery({ limit: '200' });
      const searchSuffix = qVal ? `&query=${encodeURIComponent(qVal)}` : '';
      const data = await workbenchBridge.apiFetch<OperationsBoardResponse>(
        `/api/operations/board?${queryStr}${searchSuffix}`,
        { signal: request.signal },
      );
      if (!request.signal.aborted) setBoard(data);
    } catch (err) {
      if (!request.signal.aborted) {
        setError(err instanceof Error ? err.message : '运营记录读取失败');
      }
    } finally {
      if (activeRequestRef.current === request) {
        activeRequestRef.current = null;
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    operationsLoadHandler = () => {
      void loadData();
    };
    if (pendingOperationsLoad) {
      pendingOperationsLoad = false;
      void loadData();
    }
    return () => {
      operationsLoadHandler = null;
      window.clearTimeout(searchTimerRef.current);
      activeRequestRef.current?.abort();
      activeRequestRef.current = null;
    };
  }, [loadData]);

  const handleSearchChange = (value: string) => {
    searchRef.current = value;
    setSearchQuery(value);
    window.clearTimeout(searchTimerRef.current);
    searchTimerRef.current = window.setTimeout(() => {
      void loadData();
    }, 260);
  };

  const handleResolveReminder = async (checkpointId: number) => {
    setResolvingId(checkpointId);
    try {
      await workbenchBridge.resolveOperationReminder(checkpointId);
      await loadData();
    } catch (err) {
      workbenchBridge.showToast(err instanceof Error ? err.message : '操作失败');
    } finally {
      setResolvingId(null);
    }
  };

  const handleCloseTask = async () => {
    if (!closingTask) return;
    const text = conclusion.trim();
    if (!text) {
      workbenchBridge.showToast('请填写复盘结论再结束任务。');
      return;
    }
    setClosingLoading(true);
    try {
      await workbenchBridge.apiFetch(`/api/operations/tasks/${closingTask.id}/close`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ conclusion: text }),
      });
      workbenchBridge.showToast('复盘任务已结束并保存结论');
      setClosingTask(null);
      setConclusion('');
      await workbenchBridge.refreshOperationReminders();
      await loadData();
    } catch (err) {
      workbenchBridge.showToast(
        `结束复盘任务失败：${err instanceof Error ? err.message : '未知错误'}`,
      );
    } finally {
      setClosingLoading(false);
    }
  };

  const meta = board?.meta || {
    recordCount: 0,
    executedCount: 0,
    activeTaskCount: 0,
    dueCheckpointCount: 0,
    latestDataDate: null,
  };
  const groups = board?.groups || [];

  const metrics: MetricCardItem[] = [
    {
      key: 'recordCount',
      label: '当前记录',
      value: intText(meta.recordCount),
      note: '每条都归属复盘任务',
      accent: true,
      loading: loading && !board,
    },
    {
      key: 'executedCount',
      label: '已执行',
      value: intText(meta.executedCount),
      note: '进入效果追踪',
      valueColor: WORKBENCH_COLORS.productionGreen,
      loading: loading && !board,
    },
    {
      key: 'activeTaskCount',
      label: '进行中任务',
      value: intText(meta.activeTaskCount),
      note: '等待复盘结论',
      loading: loading && !board,
    },
    {
      key: 'dueCheckpointCount',
      label: '待复盘',
      value: intText(meta.dueCheckpointCount),
      note: '检查点已到期',
      valueColor: meta.dueCheckpointCount > 0 ? WORKBENCH_COLORS.signalOrange : undefined,
      loading: loading && !board,
    },
  ];

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <PageHeaderCard
        eyebrow="OPERATION AUDIT TRAIL"
        title="记录每一次调整，再用数据验证方向"
        description="按「款 › 复盘任务 › 运营记录」组织：每条记录都归属一个复盘任务，任务下挂 7 / 14 天等复盘检查点，结束时沉淀结论。"
        accentColor={ACCENT}
        extra={
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Space size="small">
              <HeroButton
                variant="flat"
                radius="full"
                size="sm"
                startContent={!loading ? <ReloadOutlined /> : undefined}
                isLoading={loading}
                onPress={() => void loadData()}
                className="font-semibold"
              >
                刷新
              </HeroButton>
              <HeroButton
                color="primary"
                radius="full"
                size="sm"
                startContent={<PlusOutlined />}
                onPress={() => workbenchBridge.openOperationDialog('PARENT_SKU', '')}
                className="font-semibold"
              >
                新增运营记录
              </HeroButton>
            </Space>
          </div>
        }
      />

      <SyncStatusBanner
        tone={
          error
            ? 'danger'
            : loading && !board
              ? 'loading'
              : meta.recordCount > 0
                ? 'success'
                : 'warning'
        }
        headline={
          error
            ? '运营记录读取失败'
            : loading && !board
              ? '正在读取运营记录'
              : meta.recordCount > 0
                ? '运营审计链已建立'
                : '还没有运营记录'
        }
        detail={
          error
            ? error
            : meta.recordCount > 0
              ? `${intText(meta.executedCount)} 条已执行，${intText(
                  meta.activeTaskCount,
                )} 个复盘任务进行中，${intText(meta.dueCheckpointCount)} 个检查点已到期。`
              : '先新建一个复盘任务，再记录一次真实调整，系统会在后续数据导入后自动追踪。'
        }
        metaLabel="数据截至"
        metaValue={meta.latestDataDate ? `数据截至 ${meta.latestDataDate}` : '暂无业务数据'}
      />

      <MetricCardGrid items={metrics} columns={4} accentColor={ACCENT} />

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="操作时间线与效果追踪">
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">操作时间线与效果追踪</h3>
            <p className="wb-panel-subtitle">
              按款分组，组内按复盘任务排列；展开单条记录可看前后窗口效果
            </p>
          </div>
          <Input.Search
            allowClear
            aria-label="搜索运营记录"
            placeholder="模糊搜索款号、任务、操作摘要或备注"
            value={searchQuery}
            onChange={(e) => handleSearchChange(e.target.value)}
            onSearch={(value, _event, info) => {
              if (info?.source !== 'clear') void loadData(value);
            }}
            style={{ width: 290, maxWidth: '100%' }}
          />
        </HeroCardHeader>

        <HeroCardBody aria-busy={loading} style={{ padding: 16, display: 'grid', gap: 14 }}>
          {loading && !board ? (
            <div style={{ padding: '48px 0', textAlign: 'center' }}>
              <HeroSpinner color="primary" size="md" />
              <div style={{ marginTop: 10, color: '#86868b', fontSize: 12 }}>
                正在读取运营记录…
              </div>
            </div>
          ) : error ? (
            <Alert type="error" showIcon message="读取失败" description={error} />
          ) : groups.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="还没有运营记录，点右上角「新增运营记录」先建一个复盘任务。"
            />
          ) : (
            groups.map((group) => (
              <HeroCard
                key={group.parentSku}
                shadow="none"
                radius="lg"
                className="border border-black/[0.07] bg-white"
              >
                <HeroCardHeader className="flex flex-wrap items-center justify-between gap-3 border-b border-black/[0.05] px-4 py-3">
                  <Space size="small" wrap>
                    <SkuLinkTag sku={group.parentSku} parentSku={group.parentSku} />
                    {group.productName ? (
                      <span style={{ fontWeight: 400, color: '#515154', fontSize: 12 }}>
                        {group.productName}
                      </span>
                    ) : null}
                  </Space>
                  <Space size="small" wrap>
                    <HeroChip size="sm" variant="flat">{intText(group.recordCount)} 条记录</HeroChip>
                    <HeroChip
                      size="sm"
                      variant="flat"
                      color={(group.activeTaskCount || 0) > 0 ? 'success' : 'default'}
                    >
                      {intText(group.activeTaskCount || 0)} 个进行中任务
                    </HeroChip>
                    {group.lastActivityAt ? (
                      <span className="wb-mono" style={{ fontSize: 11, color: '#86868b' }}>
                        最近 {String(group.lastActivityAt).slice(0, 10)}
                      </span>
                    ) : null}
                    <Button
                      type="link"
                      size="small"
                      icon={<PlusOutlined />}
                      style={{ padding: 0 }}
                      onClick={() => workbenchBridge.openOperationDialog('PARENT_SKU', group.parentSku)}
                    >
                      新增记录
                    </Button>
                  </Space>
                </HeroCardHeader>
                <HeroCardBody style={{ padding: 14, display: 'grid', gap: 12 }}>
                  {(group.tasks || []).map((task) => {
                    const active = task.status === 'ACTIVE';
                    const progress = task.progress || { done: 0, total: 0 };
                    const checkpoints = task.checkpoints || [];
                    const records = task.records || [];

                    const collapseItems = records.map(({ record, impact }) => {
                      const impState = impact?.state || 'WAITING';
                      const impMeta = IMPACT_COPY[impState] || IMPACT_COPY.WAITING;
                      const delta = impact?.before
                        ? calcDelta(impact.before.salesPerDay, impact.after?.salesPerDay)
                        : '—';
                      const skus = record.targetKeys || [];
                      const scopeLabel = skus.length
                        ? record.targetType === 'PARENT_SKU'
                          ? `整款 · ${skus.length}`
                          : `${skus.length} 个 SKU`
                        : record.targetKey || '-';
                      const stMeta = STATUS_COPY[record.status] || {
                        label: record.status,
                        color: 'default' as HeroChipColor,
                      };

                      const before = impact?.before;
                      const after = impact?.after;
                      const rangeLabel = before
                        ? after
                          ? `${before.startDate}—${before.endDate} 对比 ${after.startDate}—${after.endDate}`
                          : `操作前基线 ${before.startDate}—${before.endDate}`
                        : null;

                      return {
                        key: String(record.id),
                        label: (
                          <div
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'space-between',
                              flexWrap: 'wrap',
                              gap: 8,
                            }}
                          >
                            <Space size="small" wrap>
                              <span className="wb-mono" style={{ fontSize: 11, color: '#86868b' }}>
                                {new Date(record.occurredAt).toLocaleString('zh-CN')}
                              </span>
                              <HeroChip size="sm" variant="flat" color="primary">
                                {ACTION_COPY[record.actionType] || record.actionType}
                              </HeroChip>
                              <HeroChip size="sm" variant="flat" color={stMeta.color}>{stMeta.label}</HeroChip>
                              {record.sourceType ? (
                                <HeroChip size="sm" variant="flat">{SOURCE_COPY[record.sourceType] || record.sourceType}</HeroChip>
                              ) : null}
                              <strong style={{ color: '#1d1d1f' }}>{record.title}</strong>
                            </Space>
                            <Space size="small" wrap>
                              <span className="wb-mono" style={{ fontSize: 11, color: '#515154' }}>
                                {scopeLabel}
                              </span>
                              <HeroChip size="sm" variant="flat" color={impMeta.color}>{impMeta.label}</HeroChip>
                              <span className="wb-mono" style={{ fontSize: 11, fontWeight: 650 }}>
                                销售额日均 {delta}
                              </span>
                            </Space>
                          </div>
                        ),
                        children: (
                          <div style={{ display: 'grid', gap: 10 }}>
                            {record.beforeValue || record.afterValue ? (
                              <div
                                style={{
                                  display: 'flex',
                                  alignItems: 'center',
                                  gap: 12,
                                  padding: '8px 12px',
                                  borderRadius: 10,
                                  background: '#f5f5f7',
                                  border: '1px solid rgba(0, 0, 0, 0.05)',
                                  flexWrap: 'wrap',
                                }}
                              >
                                <div>
                                  <span style={{ fontSize: 11, color: '#86868b', marginRight: 6 }}>
                                    调整前
                                  </span>
                                  <strong>{record.beforeValue || '未填写'}</strong>
                                </div>
                                <span style={{ color: '#86868b' }}>→</span>
                                <div>
                                  <span style={{ fontSize: 11, color: '#86868b', marginRight: 6 }}>
                                    调整后
                                  </span>
                                  <strong>{record.afterValue || '未填写'}</strong>
                                </div>
                              </div>
                            ) : null}

                            {record.note ? (
                              <p style={{ margin: 0, fontSize: 12, color: '#515154' }}>
                                {record.note}
                              </p>
                            ) : null}

                            {before ? (
                              <div
                                style={{
                                  padding: 12,
                                  borderRadius: 12,
                                  background: '#f5f5f7',
                                  border: '1px solid rgba(0, 0, 0, 0.05)',
                                }}
                              >
                                <div
                                  style={{
                                    display: 'flex',
                                    justifyContent: 'space-between',
                                    alignItems: 'center',
                                    marginBottom: 8,
                                    fontSize: 11,
                                    color: '#515154',
                                  }}
                                >
                                  <span>{rangeLabel} · 排除操作当天</span>
                                  <HeroChip size="sm" variant="flat" color={impMeta.color}>{impMeta.label}</HeroChip>
                                </div>
                                <MetricCardGrid
                                  columns={4}
                                  accentColor={ACCENT}
                                  items={[
                                    {
                                      key: 'salesPerDay',
                                      label: '销售额日均',
                                      value: after
                                        ? `${moneyText(before.salesPerDay)} → ${moneyText(
                                            after.salesPerDay,
                                          )}`
                                        : moneyText(before.salesPerDay),
                                      note: calcDelta(before.salesPerDay, after?.salesPerDay),
                                    },
                                    {
                                      key: 'unitsPerDay',
                                      label: '订单件数日均',
                                      value: after
                                        ? `${Number(before.unitsPerDay || 0).toFixed(2)} → ${Number(
                                            after.unitsPerDay || 0,
                                          ).toFixed(2)} 件`
                                        : `${Number(before.unitsPerDay || 0).toFixed(2)} 件`,
                                      note: calcDelta(before.unitsPerDay, after?.unitsPerDay),
                                    },
                                    {
                                      key: 'adSpendPerDay',
                                      label: '广告费日均',
                                      value: after
                                        ? `${moneyText(before.adSpendPerDay)} → ${moneyText(
                                            after.adSpendPerDay,
                                          )}`
                                        : moneyText(before.adSpendPerDay),
                                      note: calcDelta(before.adSpendPerDay, after?.adSpendPerDay),
                                    },
                                    {
                                      key: 'roas',
                                      label: '广告 ROAS',
                                      value: after
                                        ? `${ratioText(before.roas)} → ${ratioText(after.roas)}`
                                        : ratioText(before.roas),
                                      note: calcDelta(before.roas, after?.roas),
                                    },
                                  ]}
                                />
                                <div style={{ fontSize: 11, color: '#86868b', lineHeight: 1.5 }}>
                                  {impact?.message || ''}
                                  {impact?.overlappingOperations ? (
                                    <div style={{ color: WORKBENCH_COLORS.signalOrange }}>
                                      同款比较窗口内另有 {intText(impact.overlappingOperations)}{' '}
                                      次已执行操作，结果可能受到共同影响。
                                    </div>
                                  ) : null}
                                  <div>这是数据相关性复盘，不直接证明单一操作造成了变化。</div>
                                </div>
                              </div>
                            ) : impact?.message ? (
                              <div style={{ fontSize: 11, color: '#86868b' }}>
                                {impact.message}
                              </div>
                            ) : null}

                            {skus.length > 0 ? (
                              <div>
                                <div style={{ fontSize: 11, color: '#86868b', marginBottom: 4 }}>
                                  作用 SKU 清单（{skus.length}）
                                </div>
                                <div
                                  style={{
                                    display: 'flex',
                                    flexWrap: 'wrap',
                                    gap: 4,
                                    maxHeight: 100,
                                    overflow: 'auto',
                                  }}
                                >
                                  {skus.map((sku) => (
                                    <HeroChip key={sku} size="sm" variant="flat" className="wb-mono">
                                      {sku}
                                    </HeroChip>
                                  ))}
                                </div>
                              </div>
                            ) : null}
                          </div>
                        ),
                      };
                    });

                    return (
                      <div
                        key={task.id}
                        style={{
                          padding: 12,
                          borderRadius: 12,
                          border: active
                            ? '1px solid rgba(36, 138, 61, 0.25)'
                            : '1px solid rgba(0, 0, 0, 0.06)',
                          background: active ? 'rgba(36, 138, 61, 0.03)' : '#f5f5f7',
                          display: 'grid',
                          gap: 8,
                        }}
                      >
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            flexWrap: 'wrap',
                            gap: 8,
                          }}
                        >
                          <Space size="small" wrap>
                            <HeroChip size="sm" variant="flat" color={active ? 'success' : 'default'}>
                              {active ? '进行中' : '已结束'}
                            </HeroChip>
                            <strong style={{ fontSize: 13, color: '#1d1d1f' }}>{task.title}</strong>
                            <span className="wb-mono" style={{ fontSize: 11, color: '#86868b' }}>
                              {new Date(task.startedAt).toLocaleDateString('zh-CN')}
                              {task.closedAt
                                ? ` → ${new Date(task.closedAt).toLocaleDateString('zh-CN')}`
                                : ''}
                            </span>
                          </Space>
                          <Space size="small">
                            <HeroChip size="sm" variant="flat">
                              复盘 {progress.done}/{progress.total}
                            </HeroChip>
                            {active ? (
                              <Button
                                type="link"
                                size="small"
                                style={{ padding: 0 }}
                                onClick={() => {
                                  setClosingTask(task);
                                  setConclusion('');
                                }}
                              >
                                结束复盘
                              </Button>
                            ) : null}
                          </Space>
                        </div>

                        {task.goal ? (
                          <div style={{ fontSize: 12, color: '#515154' }}>目标：{task.goal}</div>
                        ) : null}

                        {checkpoints.length > 0 ? (
                          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
                            {checkpoints.map((cp) => {
                              const cpMeta =
                                REMINDER_COPY[cp.computedStatus || 'PENDING'] ||
                                REMINDER_COPY.PENDING;
                              const done = cp.computedStatus === 'DONE';
                              return (
                                <Space
                                  key={cp.id}
                                  size={4}
                                  style={{
                                    padding: '2px 8px',
                                    borderRadius: 8,
                                    background: '#ffffff',
                                    border: '1px solid rgba(0, 0, 0, 0.06)',
                                  }}
                                >
                                  <HeroChip size="sm" variant="flat" color={cpMeta.color}>
                                    {cpMeta.label}
                                  </HeroChip>
                                  <span style={{ fontSize: 11 }}>{cp.label || ''}</span>
                                  <span className="wb-mono" style={{ fontSize: 11, color: '#86868b' }}>
                                    {String(cp.dueDate || '')}
                                  </span>
                                  {!done ? (
                                    <Button
                                      type="link"
                                      size="small"
                                      icon={<CheckOutlined />}
                                      loading={resolvingId === cp.id}
                                      style={{ padding: 0, height: 'auto' }}
                                      onClick={() => void handleResolveReminder(cp.id)}
                                    >
                                      标记已复盘
                                    </Button>
                                  ) : null}
                                </Space>
                              );
                            })}
                          </div>
                        ) : null}

                        {!active && task.conclusion ? (
                          <div style={{ fontSize: 12, color: WORKBENCH_COLORS.productionGreen, fontWeight: 600 }}>
                            结论：{task.conclusion}
                          </div>
                        ) : null}

                        {collapseItems.length > 0 ? (
                          <Collapse size="small" items={collapseItems} />
                        ) : (
                          <div style={{ fontSize: 12, color: '#86868b' }}>
                            该任务还没有运营记录。
                          </div>
                        )}
                      </div>
                    );
                  })}
                </HeroCardBody>
              </HeroCard>
            ))
          )}
        </HeroCardBody>
      </HeroCard>

      <Modal
        open={Boolean(closingTask)}
        title={`结束复盘任务${closingTask ? ` · ${closingTask.title}` : ''}`}
        okText="结束任务并保存结论"
        cancelText="取消"
        confirmLoading={closingLoading}
        onCancel={() => setClosingTask(null)}
        onOk={() => void handleCloseTask()}
      >
        <div style={{ marginTop: 8 }}>
          <Input.TextArea
            rows={4}
            placeholder="填写本次复盘结论（如：出价下调后 ACOS 降至 24%，保持当前策略）…"
            value={conclusion}
            onChange={(e) => setConclusion(e.target.value)}
          />
        </div>
      </Modal>
    </ConfigProvider>
  );
};

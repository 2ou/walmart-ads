import React, { useEffect, useMemo, useState } from 'react';
import { ConfigProvider, Input, InputNumber, Select } from 'antd';
import type { TableProps } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {
  FileUploadDropzone,
  HeroButton,
  HeroChip,
  ImportBatchPanel,
  ImportFeedbackAlert,
  MetricCardGrid,
  PageHeaderCard,
  RecordDetailDrawer,
  SkuLinkTag,
  WorkbenchFilterTable,
} from '../../components';
import type { ImportBatchRecord, ImportFeedbackState, MetricCardItem } from '../../components';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';
import { useWfsReportData } from './useWfsReportData';
import {
  EMPTY_WFS_DRAWER,
  buildBatchAuditDrawer,
  formatDateTime,
  formatMoney,
  formatNumber,
  formatPeriodLabel,
  getField,
  registerWfsLoader,
  uploadWfsReport,
} from './wfsTypes';
import type { WfsDeleteResult, WfsDrawerState } from './wfsTypes';

const ACCENT = WORKBENCH_COLORS.walmartBlue;

interface SettlementFilters {
  period: string;
  transactionFrom: string;
  transactionTo: string;
  type: string;
  reason: string;
  match: string;
  search: string;
  feeMin: string;
  feeMax: string;
}

const EMPTY_FILTERS: SettlementFilters = {
  period: '',
  transactionFrom: '',
  transactionTo: '',
  type: '',
  reason: '',
  match: '',
  search: '',
  feeMin: '',
  feeMax: '',
};

interface QueryState {
  page: number;
  size: number;
  sort: string;
  direction: 'ASC' | 'DESC';
  filters: SettlementFilters;
}

const INITIAL_QUERY: QueryState = {
  page: 0,
  size: 50,
  sort: 'transactionAt',
  direction: 'DESC',
  filters: { ...EMPTY_FILTERS },
};

function buildSettlementSearchParams(q: QueryState): URLSearchParams {
  const params = new URLSearchParams({
    page: String(q.page),
    size: String(q.size),
    sort: q.sort,
    direction: q.direction,
  });
  if (q.filters.period) {
    const [periodStart = '', periodEnd = ''] = q.filters.period.split('|');
    if (periodStart) params.set('periodStart', periodStart);
    if (periodEnd) params.set('periodEnd', periodEnd);
  }
  if (q.filters.transactionFrom) params.set('transactionFrom', q.filters.transactionFrom);
  if (q.filters.transactionTo) params.set('transactionTo', q.filters.transactionTo);
  if (q.filters.type) params.set('type', q.filters.type);
  if (q.filters.reason) params.set('reason', q.filters.reason);
  if (q.filters.match) params.set('match', q.filters.match);
  if (q.filters.search.trim()) params.set('search', q.filters.search.trim());
  if (q.filters.feeMin !== '') params.set('feeMin', q.filters.feeMin);
  if (q.filters.feeMax !== '') params.set('feeMax', q.filters.feeMax);
  return params;
}

export const WfsSettlementView: React.FC = () => {
  const { query, queryRef, data, error, loading, batches, batchesLoading, loadData } =
    useWfsReportData(INITIAL_QUERY, 'settlements', buildSettlementSearchParams);
  const [draftFilters, setDraftFilters] = useState<SettlementFilters>(EMPTY_FILTERS);
  const [uploading, setUploading] = useState(false);
  const [feedback, setFeedback] = useState<ImportFeedbackState | null>(null);
  const [drawer, setDrawer] = useState<WfsDrawerState>(EMPTY_WFS_DRAWER);
  const [knownTypes, setKnownTypes] = useState<string[]>([]);
  const [knownReasons, setKnownReasons] = useState<string[]>([]);

  useEffect(() => {
    if (!data) return;
    const breakdown = (data.summary?.breakdown as Array<Record<string, unknown>> | undefined) || [];
    const nextTypes = breakdown
      .map((row) => getField<string>(row, 'transaction_type', 'transactionType'))
      .filter((v): v is string => Boolean(v));
    const nextReasons = breakdown
      .map((row) => getField<string>(row, 'reason_code', 'reasonCode'))
      .filter((v): v is string => Boolean(v));

    setKnownTypes((prev) => Array.from(new Set([...prev, ...nextTypes])).sort((a, b) => a.localeCompare(b)));
    setKnownReasons((prev) => Array.from(new Set([...prev, ...nextReasons])).sort((a, b) => a.localeCompare(b)));
  }, [data]);

  useEffect(() => registerWfsLoader('settlement', () => {
    void loadData();
  }), [loadData]);

  const handleUploadFile = async (file: File) => {
    setUploading(true);
    setFeedback(null);
    try {
      const { ok, feedback: resultFeedback } = await uploadWfsReport('settlements', file);
      setFeedback(resultFeedback);
      if (ok) {
        const nextQuery: QueryState = { ...queryRef.current, page: 0 };
        await loadData(nextQuery, true);
      }
    } catch (error) {
      setFeedback({
        isError: true,
        message: error instanceof Error ? error.message : '上传失败',
        errors: [],
      });
    } finally {
      setUploading(false);
    }
  };

  const handleSearchSubmit = (searchOverride?: string) => {
    const nextFilters =
      searchOverride !== undefined
        ? { ...draftFilters, search: searchOverride }
        : { ...draftFilters };
    if (searchOverride !== undefined) {
      setDraftFilters(nextFilters);
    }
    const nextQuery: QueryState = {
      ...queryRef.current,
      page: 0,
      filters: nextFilters,
    };
    void loadData(nextQuery, false);
  };

  const handleReset = () => {
    const empty: SettlementFilters = { ...EMPTY_FILTERS };
    setDraftFilters(empty);
    const nextQuery: QueryState = {
      ...queryRef.current,
      page: 0,
      filters: empty,
    };
    void loadData(nextQuery, false);
  };

  const handlePageChange = (nextPage: number, nextSize: number) => {
    const nextQuery: QueryState = {
      ...queryRef.current,
      page: nextPage,
      size: nextSize,
    };
    void loadData(nextQuery, false);
  };

  const handleSortChange = (field: string, direction: 'ASC' | 'DESC') => {
    const nextQuery: QueryState = {
      ...queryRef.current,
      page: 0,
      sort: field,
      direction,
    };
    void loadData(nextQuery, false);
  };

  const handleOpenRowDetail = async (id: number) => {
    setDrawer({
      open: true,
      loading: true,
      eyebrow: 'WFS SETTLEMENT RECORD',
      title: '结算明细',
      subtitle: '正在读取字段…',
      sections: [],
    });
    try {
      const row = await workbenchBridge.apiFetch<Record<string, unknown>>(`/api/wfs/settlements/${id}`);
      const raw = (row.rawFields || row.raw_fields || {}) as Record<string, unknown>;
      const structured = Object.fromEntries(
        Object.entries(row).filter(([key]) => key !== 'rawFields' && key !== 'raw_fields'),
      );
      setDrawer({
        open: true,
        loading: false,
        eyebrow: 'WFS SETTLEMENT RECORD',
        title: String(row.transaction_type || row.transactionType || '结算明细'),
        subtitle: `${row.period_start || row.periodStart || ''} 至 ${row.period_end || row.periodEnd || ''} · ${row.order_id || row.orderId || '周期汇总行'}`,
        sections: [
          { title: '结构化字段', data: structured },
          { title: '文件原始字段', data: raw },
        ],
      });
    } catch (error) {
      setDrawer(EMPTY_WFS_DRAWER);
      workbenchBridge.showToast(error instanceof Error ? error.message : '明细读取失败');
    }
  };

  const handleDeleteBatch = async (batch: ImportBatchRecord) => {
    try {
      const result = await workbenchBridge.apiFetch<WfsDeleteResult>(`/api/wfs/imports/${batch.id}`, {
        method: 'DELETE',
      });
      workbenchBridge.showToast(`批次已删除，同时移除 ${result.removedRows || 0} 行无引用明细`);
      await loadData();
    } catch (error) {
      workbenchBridge.showToast(error instanceof Error ? error.message : '批次删除失败');
    }
  };

  const summary = data?.summary || {};
  const total = Number(data?.total || 0);
  const periodLabel = formatPeriodLabel(data?.appliedPeriod);

  const metrics: MetricCardItem[] = [
    {
      key: 'total',
      label: '筛选结果',
      value: total.toLocaleString('zh-CN'),
      note: '当前条件下的全部明细',
      accent: true,
      loading: loading && !data,
    },
    {
      key: 'net',
      label: '净额合计',
      value: formatMoney(getField(summary, 'total_amount', 'totalAmount')),
      note: '保留沃尔玛原始正负号',
      loading: loading && !data,
    },
    {
      key: 'positive',
      label: '正向金额',
      value: formatMoney(getField(summary, 'positive_amount', 'positiveAmount')),
      note: '大于 0 的金额合计',
      loading: loading && !data,
    },
    {
      key: 'negative',
      label: '负向金额',
      value: formatMoney(getField(summary, 'negative_amount', 'negativeAmount')),
      note: '小于 0 的金额合计',
      loading: loading && !data,
    },
  ];

  const periodOptions = useMemo(
    () => [
      { value: '', label: '最新周期' },
      ...(data?.periods || []).map((period) => ({
        value: `${period.start}|${period.end}`,
        label: formatPeriodLabel(period),
      })),
    ],
    [data?.periods],
  );

  const typeOptions = useMemo(() => {
    const merged = draftFilters.type && !knownTypes.includes(draftFilters.type)
      ? [draftFilters.type, ...knownTypes]
      : knownTypes;
    return [
      { value: '', label: '全部类型' },
      ...merged.map((v) => ({ value: v, label: v })),
    ];
  }, [knownTypes, draftFilters.type]);

  const reasonOptions = useMemo(() => {
    const merged = draftFilters.reason && !knownReasons.includes(draftFilters.reason)
      ? [draftFilters.reason, ...knownReasons]
      : knownReasons;
    return [
      { value: '', label: '全部原因' },
      ...merged.map((v) => ({ value: v, label: v })),
    ];
  }, [knownReasons, draftFilters.reason]);

  const sortOrderOf = (field: string): 'ascend' | 'descend' | null =>
    query.sort === field ? (query.direction === 'ASC' ? 'ascend' : 'descend') : null;

  const columns: TableProps<Record<string, unknown>>['columns'] = [
    {
      title: '交易时间',
      key: 'transactionAt',
      sorter: true,
      sortOrder: sortOrderOf('transactionAt'),
      width: 175,
      render: (_, row) => (
        <div>
          <span className="wb-cell-primary wb-mono">
            {formatDateTime(getField(row, 'transactionAt', 'transaction_at'))}
          </span>
          <span className="wb-cell-secondary">
            {formatPeriodLabel({
              start: getField<string>(row, 'periodStart', 'period_start') || undefined,
              end: getField<string>(row, 'periodEnd', 'period_end') || undefined,
            })}
          </span>
        </div>
      ),
    },
    {
      title: '类型 / 原因',
      key: 'type',
      sorter: true,
      sortOrder: sortOrderOf('type'),
      width: 190,
      render: (_, row) => (
        <div>
          <span className="wb-cell-primary">
            {String(getField(row, 'transactionType', 'transaction_type') || '—')}
          </span>
          <span className="wb-cell-secondary">
            {String(getField(row, 'reasonCode', 'reason_code') || '无原因码')}
          </span>
        </div>
      ),
    },
    {
      title: '订单 / 行',
      key: 'orderId',
      width: 175,
      render: (_, row) => (
        <div>
          <span className="wb-cell-primary wb-mono">
            {String(getField(row, 'orderId', 'order_id') || '周期汇总行')}
          </span>
          <span className="wb-cell-secondary">
            行 {String(getField(row, 'orderLine', 'order_line') || '—')}
          </span>
        </div>
      ),
    },
    {
      title: 'SKU / 款号',
      key: 'sku',
      sorter: true,
      sortOrder: sortOrderOf('sku'),
      width: 190,
      render: (_, row) => {
        const matched = getField<string>(row, 'matchStatus', 'match_status') === 'MATCHED';
        return (
          <SkuLinkTag
            sku={row.sku as string | null}
            parentSku={getField<string>(row, 'parentSku', 'parent_sku')}
            matched={matched}
          />
        );
      },
    },
    {
      title: 'GTIN',
      key: 'partnerGtin',
      width: 150,
      render: (_, row) => (
        <span className="wb-mono">
          {String(getField(row, 'partnerGtin', 'partner_gtin') || '—')}
        </span>
      ),
    },
    {
      title: '数量',
      key: 'quantity',
      sorter: true,
      sortOrder: sortOrderOf('quantity'),
      align: 'right',
      width: 90,
      render: (_, row) => <span className="wb-num">{formatNumber(row.quantity, 2)}</span>,
    },
    {
      title: '净额',
      key: 'amount',
      sorter: true,
      sortOrder: sortOrderOf('amount'),
      align: 'right',
      width: 110,
      render: (_, row) => {
        const raw = getField(row, 'netPayable', 'net_payable');
        const amount = Number(raw);
        const color =
          Number.isFinite(amount) && amount > 0
            ? WORKBENCH_COLORS.productionGreen
            : Number.isFinite(amount) && amount < 0
              ? WORKBENCH_COLORS.signalRed
              : undefined;
        return (
          <span className="wb-num" style={{ fontWeight: 650, color }}>
            {formatMoney(raw)}
          </span>
        );
      },
    },
    {
      title: '匹配',
      key: 'matchStatus',
      width: 90,
      render: (_, row) => {
        const matched = getField<string>(row, 'matchStatus', 'match_status') === 'MATCHED';
        return (
          <HeroChip size="sm" variant="flat" color={matched ? 'success' : 'warning'}>
            {matched ? '已匹配' : '未匹配'}
          </HeroChip>
        );
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 74,
      align: 'right',
      render: (_, row) => (
        <HeroButton
          variant="light"
          color="primary"
          size="sm"
          className="min-w-0 px-2"
          onPress={() => void handleOpenRowDetail(Number(row.id))}
        >
          详情
        </HeroButton>
      ),
    },
  ];

  const filterContent = (
    <>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-period">报表周期</label>
        <Select
          id="wfs-settlement-period"
          value={draftFilters.period}
          options={periodOptions}
          onChange={(val) => setDraftFilters((prev) => ({ ...prev, period: val ?? '' }))}
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-transactionFrom">交易开始日</label>
        <Input
          id="wfs-settlement-transactionFrom"
          name="transactionFrom"
          type="date"
          value={draftFilters.transactionFrom}
          onChange={(e) => setDraftFilters((prev) => ({ ...prev, transactionFrom: e.target.value }))}
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-transactionTo">交易结束日</label>
        <Input
          id="wfs-settlement-transactionTo"
          name="transactionTo"
          type="date"
          value={draftFilters.transactionTo}
          onChange={(e) => setDraftFilters((prev) => ({ ...prev, transactionTo: e.target.value }))}
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-type">交易类型</label>
        <Select
          id="wfs-settlement-type"
          showSearch
          optionFilterProp="label"
          value={draftFilters.type}
          options={typeOptions}
          onChange={(val) => setDraftFilters((prev) => ({ ...prev, type: val ?? '' }))}
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-reason">原因</label>
        <Select
          id="wfs-settlement-reason"
          showSearch
          optionFilterProp="label"
          value={draftFilters.reason}
          options={reasonOptions}
          onChange={(val) => setDraftFilters((prev) => ({ ...prev, reason: val ?? '' }))}
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-match">产品匹配</label>
        <Select
          id="wfs-settlement-match"
          value={draftFilters.match}
          options={[
            { value: '', label: '全部' },
            { value: 'MATCHED', label: '已匹配' },
            { value: 'UNMATCHED', label: '未匹配' },
          ]}
          onChange={(val) => setDraftFilters((prev) => ({ ...prev, match: val ?? '' }))}
        />
      </div>
      <div className="wb-filter-field wb-filter-field-wide">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-search">订单 / SKU / GTIN / 名称</label>
        <Input
          id="wfs-settlement-search"
          name="search"
          allowClear
          enterKeyHint="search"
          onClear={() => handleSearchSubmit('')}
          placeholder="输入订单号、款号、SKU 或 GTIN"
          value={draftFilters.search}
          onChange={(e) => setDraftFilters((prev) => ({ ...prev, search: e.target.value }))}
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-feeMin">金额下限</label>
        <InputNumber
          id="wfs-settlement-feeMin"
          name="feeMin"
          style={{ width: '100%' }}
          step={0.01}
          placeholder="不限"
          value={draftFilters.feeMin === '' ? null : Number(draftFilters.feeMin)}
          onChange={(val) =>
            setDraftFilters((prev) => ({ ...prev, feeMin: val === null ? '' : String(val) }))
          }
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-settlement-feeMax">金额上限</label>
        <InputNumber
          id="wfs-settlement-feeMax"
          name="feeMax"
          style={{ width: '100%' }}
          step={0.01}
          placeholder="不限"
          value={draftFilters.feeMax === '' ? null : Number(draftFilters.feeMax)}
          onChange={(val) =>
            setDraftFilters((prev) => ({ ...prev, feeMax: val === null ? '' : String(val) }))
          }
        />
      </div>
    </>
  );

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <PageHeaderCard
        eyebrow="WFS FINANCE"
        title="Settlement 结算明细"
        description="按报表周期查询全部交易与调整项。Partner GTIN 自动匹配产品库 SKU 和款号，正负金额保持沃尔玛原始口径。"
        accentColor={ACCENT}
        extra={
          <FileUploadDropzone
            title="拖拽 Settlement 文件到这里"
            hint="或点击选择 CSV / XLSX · 原文件不落盘"
            accentColor={ACCENT}
            uploading={uploading}
            onUploadFile={handleUploadFile}
            onValidationError={(msg) => setFeedback({ isError: true, message: msg, errors: [] })}
          />
        }
      />

      <ImportFeedbackAlert feedback={feedback} onClose={() => setFeedback(null)} />

      <MetricCardGrid items={metrics} columns={4} accentColor={ACCENT} />

      <WorkbenchFilterTable<Record<string, unknown>>
        filterTitle="筛选结算数据"
        filterSubtitle={`当前报表周期：${periodLabel}`}
        filterContent={filterContent}
        onSearchSubmit={handleSearchSubmit}
        onReset={handleReset}
        tableTitle="结算交易"
        tableSubtitle={
          data
            ? `共 ${total.toLocaleString('zh-CN')} 行 · ${periodLabel}`
            : undefined
        }
        rowKey="id"
        columns={columns}
        dataSource={data?.rows || []}
        loading={loading}
        error={error}
        page={query.page}
        pageSize={query.size}
        total={total}
        onPageChange={handlePageChange}
        onSortChange={handleSortChange}
        emptyText="当前筛选条件下没有 Settlement 数据"
      />

      <ImportBatchPanel
        batches={batches}
        loading={batchesLoading}
        subtitle="查看文件哈希、重复行和错误明细"
        onInspectBatchErrors={(batch) => setDrawer(buildBatchAuditDrawer(batch))}
        onDeleteBatch={handleDeleteBatch}
      />

      <RecordDetailDrawer
        open={drawer.open}
        loading={drawer.loading}
        eyebrow={drawer.eyebrow}
        title={drawer.title}
        subtitle={drawer.subtitle}
        sections={drawer.sections}
        onClose={() => setDrawer(EMPTY_WFS_DRAWER)}
      />
    </ConfigProvider>
  );
};

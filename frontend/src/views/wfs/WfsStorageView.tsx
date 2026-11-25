import React, { useEffect, useMemo, useState } from 'react';
import { ConfigProvider, Input, InputNumber, Select } from 'antd';
import type { TableProps } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {
  FileUploadDropzone,
  HeroButton,
  HeroChip,
  HeroTooltip,
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
  formatMoney,
  formatNumber,
  formatPeriodLabel,
  getField,
  getObjectValue,
  registerWfsLoader,
  uploadWfsReport,
} from './wfsTypes';
import type { WfsDeleteResult, WfsDrawerState } from './wfsTypes';

const ACCENT = WORKBENCH_COLORS.productionGreen;

interface StorageFilters {
  period: string;
  match: string;
  search: string;
  feeMin: string;
  feeMax: string;
}

const EMPTY_FILTERS: StorageFilters = {
  period: '',
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
  filters: StorageFilters;
}

const INITIAL_QUERY: QueryState = {
  page: 0,
  size: 50,
  sort: 'fee',
  direction: 'DESC',
  filters: { ...EMPTY_FILTERS },
};

function buildStorageSearchParams(q: QueryState): URLSearchParams {
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
  if (q.filters.match) params.set('match', q.filters.match);
  if (q.filters.search.trim()) params.set('search', q.filters.search.trim());
  if (q.filters.feeMin !== '') params.set('feeMin', q.filters.feeMin);
  if (q.filters.feeMax !== '') params.set('feeMax', q.filters.feeMax);
  return params;
}

export const WfsStorageView: React.FC = () => {
  const { query, queryRef, data, error, loading, batches, batchesLoading, loadData } =
    useWfsReportData(INITIAL_QUERY, 'storage', buildStorageSearchParams);
  const [draftFilters, setDraftFilters] = useState<StorageFilters>(EMPTY_FILTERS);
  const [uploading, setUploading] = useState(false);
  const [feedback, setFeedback] = useState<ImportFeedbackState | null>(null);
  const [drawer, setDrawer] = useState<WfsDrawerState>(EMPTY_WFS_DRAWER);

  useEffect(() => registerWfsLoader('storage', () => {
    void loadData();
  }), [loadData]);

  const handleUploadFile = async (file: File) => {
    setUploading(true);
    setFeedback(null);
    try {
      const { ok, feedback: resultFeedback } = await uploadWfsReport('storage', file);
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
    const empty: StorageFilters = { ...EMPTY_FILTERS };
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
      eyebrow: 'WFS STORAGE RECORD',
      title: '仓储明细',
      subtitle: '正在读取字段…',
      sections: [],
    });
    try {
      const row = await workbenchBridge.apiFetch<Record<string, unknown>>(`/api/wfs/storage/${id}`);
      const raw = (row.rawFields || row.raw_fields || {}) as Record<string, unknown>;
      const structured = Object.fromEntries(
        Object.entries(row).filter(([key]) => key !== 'rawFields' && key !== 'raw_fields'),
      );
      setDrawer({
        open: true,
        loading: false,
        eyebrow: 'WFS STORAGE RECORD',
        title: String(row.sku || '仓储明细'),
        subtitle: `${row.period_start || row.periodStart || ''} 至 ${row.period_end || row.periodEnd || ''} · ${row.item_id || row.itemId || '未标注 Item ID'}`,
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
  const official = data?.officialSummary || {};
  const officialTotal = getObjectValue(official, 'total storage fees');
  const detailTotal =
    getField(official, 'detailRowSum') ?? getField(summary, 'total_amount', 'totalAmount');
  const difference = getField(official, 'difference');
  const longTermTotal =
    Number(getField(summary, 'long_term_units', 'longTermUnits') || 0) +
    Number(getField(summary, 'over_450_units', 'over450Units') || 0);
  const total = Number(data?.total || 0);
  const periodLabel = formatPeriodLabel(data?.appliedPeriod);

  const metrics: MetricCardItem[] = [
    {
      key: 'official',
      label: '官方汇总费用',
      value: formatMoney(officialTotal),
      note: officialTotal == null ? '该周期没有页面导入批次' : 'Storage 文件顶部汇总',
      accent: true,
      loading: loading && !data,
    },
    {
      key: 'detail',
      label: '明细费用合计',
      value: formatMoney(detailTotal),
      note: `${total.toLocaleString('zh-CN')} 行筛选结果`,
      loading: loading && !data,
    },
    {
      key: 'diff',
      label: '汇总差额',
      value: difference == null ? '—' : formatMoney(difference),
      note: '官方汇总减导入明细',
      loading: loading && !data,
    },
    {
      key: 'longTerm',
      label: '长期库存',
      value: formatNumber(longTermTotal, 2),
      note: '366 天及以上平均件数',
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

  const sortOrderOf = (field: string): 'ascend' | 'descend' | null =>
    query.sort === field ? (query.direction === 'ASC' ? 'ascend' : 'descend') : null;

  const columns: TableProps<Record<string, unknown>>['columns'] = [
    {
      title: 'SKU / 款号',
      key: 'sku',
      sorter: true,
      sortOrder: sortOrderOf('sku'),
      width: 200,
      render: (_, row) => {
        const matched = getField<string>(row, 'matchStatus', 'match_status') === 'MATCHED';
        return (
          <SkuLinkTag
            sku={row.sku as string | null}
            parentSku={getField<string>(row, 'parentSku', 'parent_sku')}
            matched={matched}
            unmatchedSkuLabel="—"
          />
        );
      },
    },
    {
      title: 'Item ID / GTIN',
      key: 'item',
      sorter: true,
      sortOrder: sortOrderOf('item'),
      width: 170,
      render: (_, row) => (
        <div>
          <span className="wb-cell-primary wb-mono">
            {String(getField(row, 'itemId', 'item_id') || '—')}
          </span>
          <span className="wb-cell-secondary wb-mono">{String(row.gtin || '—')}</span>
        </div>
      ),
    },
    {
      title: '商品名称',
      key: 'itemName',
      width: 240,
      render: (_, row) => {
        const itemName = String(getField(row, 'itemName', 'item_name') || '');
        if (!itemName) return <span className="wb-cell-primary">—</span>;
        return (
          <HeroTooltip content={itemName}>
            <span className="wb-cell-primary">{itemName}</span>
          </HeroTooltip>
        );
      },
    },
    {
      title: '标准库存',
      key: 'standardUnits',
      sorter: true,
      sortOrder: sortOrderOf('standardUnits'),
      align: 'right',
      width: 105,
      render: (_, row) => (
        <span className="wb-num">
          {formatNumber(getField(row, 'standardAverageUnits', 'standard_average_units'), 2)}
        </span>
      ),
    },
    {
      title: '长期库存',
      key: 'longTermUnits',
      sorter: true,
      sortOrder: sortOrderOf('longTermUnits'),
      align: 'right',
      width: 105,
      render: (_, row) => {
        const val =
          Number(getField(row, 'longTermAverageUnits', 'long_term_average_units') || 0) +
          Number(getField(row, 'over450AverageUnits', 'over450_average_units') || 0);
        return <span className="wb-num">{formatNumber(val, 2)}</span>;
      },
    },
    {
      title: '周期天数',
      key: 'daysInPeriod',
      align: 'right',
      width: 90,
      render: (_, row) => (
        <span className="wb-num">{String(getField(row, 'daysInPeriod', 'days_in_period') ?? '—')}</span>
      ),
    },
    {
      title: '最终费用',
      key: 'fee',
      sorter: true,
      sortOrder: sortOrderOf('fee'),
      align: 'right',
      width: 115,
      render: (_, row) => (
        <span className="wb-num" style={{ fontWeight: 650 }}>
          {formatMoney(getField(row, 'finalStorageFee', 'final_storage_fee'))}
        </span>
      ),
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
        <label className="wb-filter-field-label" htmlFor="wfs-storage-period">报表周期</label>
        <Select
          id="wfs-storage-period"
          value={draftFilters.period}
          options={periodOptions}
          onChange={(val) => setDraftFilters((prev) => ({ ...prev, period: val ?? '' }))}
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-storage-match">产品匹配</label>
        <Select
          id="wfs-storage-match"
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
        <label className="wb-filter-field-label" htmlFor="wfs-storage-search">SKU / Item ID / GTIN / 名称</label>
        <Input
          id="wfs-storage-search"
          name="search"
          allowClear
          enterKeyHint="search"
          onClear={() => handleSearchSubmit('')}
          placeholder="输入款号、SKU、Item ID 或名称"
          value={draftFilters.search}
          onChange={(e) => setDraftFilters((prev) => ({ ...prev, search: e.target.value }))}
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-storage-feeMin">费用下限</label>
        <InputNumber
          id="wfs-storage-feeMin"
          name="feeMin"
          style={{ width: '100%' }}
          min={0}
          step={0.01}
          placeholder="不限"
          value={draftFilters.feeMin === '' ? null : Number(draftFilters.feeMin)}
          onChange={(val) =>
            setDraftFilters((prev) => ({ ...prev, feeMin: val === null ? '' : String(val) }))
          }
        />
      </div>
      <div className="wb-filter-field">
        <label className="wb-filter-field-label" htmlFor="wfs-storage-feeMax">费用上限</label>
        <InputNumber
          id="wfs-storage-feeMax"
          name="feeMax"
          style={{ width: '100%' }}
          min={0}
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
        eyebrow="WFS INVENTORY COST"
        title="Storage 仓储费用"
        description="按 WFS 报表周期查询 SKU 级仓储费用，同时对照官方汇总、明细合计和差额。"
        accentColor={ACCENT}
        extra={
          <FileUploadDropzone
            title="拖拽 Storage 文件到这里"
            hint="或点击选择 CSV / XLSX · 自动过滤重复行"
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
        filterTitle="筛选仓储费用"
        filterSubtitle={`当前报表周期：${periodLabel}`}
        filterContent={filterContent}
        onSearchSubmit={handleSearchSubmit}
        onReset={handleReset}
        tableTitle="仓储明细"
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
        emptyText="当前筛选条件下没有 Storage 数据"
      />

      <ImportBatchPanel
        batches={batches}
        loading={batchesLoading}
        subtitle="删除批次只移除不再被其他批次引用的数据"
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

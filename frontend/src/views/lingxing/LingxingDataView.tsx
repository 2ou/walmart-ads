import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Button,
  ConfigProvider,
  Drawer,
  Empty,
  Input,
  Progress,
  Select,
  Space,
  Table,
} from 'antd';
import type { TableProps } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import {
  AppleSegmentedTabs,
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
import type { MetricCardItem, SyncBannerTone } from '../../components';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';

const ACCENT = WORKBENCH_COLORS.walmartBlue;

type LingxingTab = 'domestic' | 'wfs' | 'logs';
type LingxingSort = 'latest' | 'units' | 'parent' | 'skuCount';

interface DomesticSkuItem {
  sku?: string | null;
  color?: string | null;
  size?: string | null;
  units?: number | null;
  warehouse?: string | null;
  store?: string | null;
}

interface DomesticStyleRow {
  parentSku?: string | null;
  itemName?: string | null;
  skuCount?: number | null;
  units?: number | null;
  skus?: DomesticSkuItem[];
}

interface DomesticSnapshot {
  source?: string | null;
  sourceLabel?: string | null;
  sourceAt?: string | null;
  skuCount?: number | null;
  styleCount?: number | null;
  totalUnits?: number | null;
  styles?: DomesticStyleRow[];
}

interface WfsShipmentRow {
  shipmentNo?: string | null;
  localStatus?: string | null;
  cargoStatus?: string | null;
  createdAt?: string | null;
  warehouse?: string | null;
  store?: string | null;
  city?: string | null;
  state?: string | null;
  wmsOrderNo?: string | null;
  styleCount?: number | null;
  skuCount?: number | null;
  declaredUnits?: number | null;
  shippedUnits?: number | null;
  receivedUnits?: number | null;
  transitUnits?: number | null;
}

interface WfsShipmentsSnapshot {
  source?: string | null;
  sourceLabel?: string | null;
  syncedAt?: string | null;
  latestShipmentAt?: string | null;
  shipmentCount?: number | null;
  skuCount?: number | null;
  styleCount?: number | null;
  declaredUnits?: number | null;
  shippedUnits?: number | null;
  receivedUnits?: number | null;
  transitUnits?: number | null;
  shipments?: WfsShipmentRow[];
  styles?: Array<{ parentSku?: string | null; units?: number | null }>;
}

interface WfsShipmentDetail {
  shipmentNo?: string | null;
  localStatus?: string | null;
  cargoStatus?: string | null;
  createdAt?: string | null;
  warehouse?: string | null;
  store?: string | null;
  city?: string | null;
  state?: string | null;
  wmsOrderNo?: string | null;
  styleCount?: number | null;
  skuCount?: number | null;
  declaredUnits?: number | null;
  shippedUnits?: number | null;
  receivedUnits?: number | null;
  transitUnits?: number | null;
  styles?: Array<{ parentSku?: string | null; declaredUnits?: number | null; skuCount?: number | null }>;
  lines?: Array<{
    sku?: string | null;
    parentSku?: string | null;
    color?: string | null;
    size?: string | null;
    declaredUnits?: number | null;
    shippedUnits?: number | null;
    receivedUnits?: number | null;
    transitUnits?: number | null;
  }>;
}

interface SyncLogRow {
  id?: number | string;
  startedAt?: string | null;
  dataset?: string | null;
  datasetLabel?: string | null;
  status?: string | null;
  recordCount?: number | null;
  detail?: string | null;
  message?: string | null;
  source?: string | null;
  triggeredBy?: string | null;
}

const TAB_META: Record<LingxingTab, { title: string; subtitle: string }> = {
  domestic: {
    title: '国内仓库存',
    subtitle: '仅显示可用库存大于 0 的 SKU；按款号汇总，点击款号展开明细。',
  },
  wfs: {
    title: 'WFS 货件',
    subtitle:
      '领星当前生效的 WFS 货件快照，以领星货件单号为一行，展示申报量 / 已发货 / 签收量与少收差异；点击任意货件打开数据仪表盘。',
  },
  logs: {
    title: '同步日志',
    subtitle: '国内仓库存与 WFS 货件两条自动同步链路的执行记录，按开始时间倒序。',
  },
};

function endpointForTab(tab: LingxingTab): string {
  if (tab === 'wfs') return '/api/lingxing-data/wfs-shipments';
  if (tab === 'logs') return '/api/lingxing-data/sync-logs';
  return '/api/lingxing-data/domestic';
}

function intText(value: unknown): string {
  const num = Number(value || 0);
  return Math.round(Number.isFinite(num) ? num : 0).toLocaleString('zh-CN');
}

function formatLxTime(value: unknown): string {
  if (!value) return '—';
  const text = String(value);
  const parsed = new Date(text);
  if (Number.isNaN(parsed.getTime())) return text.replace('T', ' ').slice(0, 16);
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}

function uniqueJoin(values: Array<string | null | undefined>): string {
  const items = Array.from(
    new Set(values.map((val) => (val == null ? '' : String(val).trim())).filter(Boolean)),
  );
  return items.length ? items.join('、') : '—';
}

let lingxingLoadHandler: (() => void) | null = null;
let pendingLingxingLoad = false;

export function triggerLingxingLoad(): void {
  if (lingxingLoadHandler) {
    pendingLingxingLoad = false;
    lingxingLoadHandler();
  } else {
    pendingLingxingLoad = true;
  }
}

export const LingxingDataView: React.FC = () => {
  const [tab, setTab] = useState<LingxingTab>('domestic');
  const [sort, setSort] = useState<LingxingSort>('latest');
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedRowKeys, setExpandedRowKeys] = useState<React.Key[]>([]);

  const [domestic, setDomestic] = useState<DomesticSnapshot | null>(null);
  const [wfs, setWfs] = useState<WfsShipmentsSnapshot | null>(null);
  const [logs, setLogs] = useState<SyncLogRow[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerLoading, setDrawerLoading] = useState(false);
  const [shipmentTitle, setShipmentTitle] = useState('货件详情');
  const [shipmentDetail, setShipmentDetail] = useState<WfsShipmentDetail | null>(null);
  const [drawerError, setDrawerError] = useState<string | null>(null);

  const stateRef = useRef({ tab, domestic, wfs, logs, loading });
  stateRef.current = { tab, domestic, wfs, logs, loading };

  const loadData = useCallback(async (targetTab?: LingxingTab, force = false) => {
    const activeTab = targetTab ?? stateRef.current.tab;
    if (stateRef.current.loading) return;

    const currentDataMap: Record<LingxingTab, unknown> = {
      domestic: stateRef.current.domestic,
      wfs: stateRef.current.wfs,
      logs: stateRef.current.logs,
    };

    const targets = Array.from(new Set<LingxingTab>(['domestic', activeTab])).filter(
      (t) => force || currentDataMap[t] === null,
    );
    if (targets.length === 0) return;

    setLoading(true);
    setError(null);
    try {
      for (const target of targets) {
        if (target === 'domestic') {
          const snap = await workbenchBridge.apiFetch<DomesticSnapshot>(endpointForTab('domestic'));
          setDomestic(snap);
        } else if (target === 'wfs') {
          const snap = await workbenchBridge.apiFetch<WfsShipmentsSnapshot>(endpointForTab('wfs'));
          setWfs(snap);
        } else {
          const rows = await workbenchBridge.apiFetch<SyncLogRow[]>(endpointForTab('logs'));
          setLogs(rows || []);
        }
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : '领星数据读取失败';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    lingxingLoadHandler = () => {
      void loadData(stateRef.current.tab, false);
    };
    if (pendingLingxingLoad) {
      pendingLingxingLoad = false;
      void loadData(stateRef.current.tab, false);
    }
    return () => {
      lingxingLoadHandler = null;
    };
  }, [loadData]);

  const handleTabChange = (nextTab: LingxingTab) => {
    setTab(nextTab);
    setExpandedRowKeys([]);
    if (nextTab !== 'wfs' && sort === 'latest') {
      setSort('units');
    }
    void loadData(nextTab, false);
  };

  const handleOpenShipment = async (shipmentNo?: string | null) => {
    if (!shipmentNo) return;
    setShipmentTitle(shipmentNo);
    setShipmentDetail(null);
    setDrawerError(null);
    setDrawerOpen(true);
    setDrawerLoading(true);
    try {
      const detail = await workbenchBridge.apiFetch<WfsShipmentDetail>(
        `/api/lingxing-data/wfs-shipments/${encodeURIComponent(shipmentNo)}`,
      );
      setShipmentDetail(detail);
      setShipmentTitle(detail.shipmentNo || shipmentNo);
    } catch (err) {
      setDrawerError(err instanceof Error ? err.message : '无法读取货件明细');
    } finally {
      setDrawerLoading(false);
    }
  };

  const bannerProps = useMemo((): {
    tone: SyncBannerTone;
    headline: string;
    detail: string;
    metaValue: string;
  } => {
    if (error) {
      return {
        tone: 'danger',
        headline: '领星数据读取失败',
        detail: error,
        metaValue: '—',
      };
    }
    if (loading && ((tab === 'domestic' && !domestic) || (tab === 'wfs' && !wfs) || (tab === 'logs' && !logs))) {
      return {
        tone: 'loading',
        headline: '正在读取领星数据',
        detail: '请稍候。',
        metaValue: '—',
      };
    }
    if (tab === 'logs') {
      const list = logs || [];
      return {
        tone: list.length ? 'success' : 'warning',
        headline: list.length ? '自动同步日志' : '暂无自动同步日志',
        detail: list.length
          ? `共 ${intText(list.length)} 条记录 · 覆盖国内仓库存与 WFS 货件两条自动同步链路`
          : '国内仓库存与 WFS 货件的自动同步任务还没有执行记录。',
        metaValue: '本地日志',
      };
    }
    if (tab === 'wfs') {
      if (!wfs) {
        return { tone: 'warning', headline: '正在读取快照', detail: '请稍候。', metaValue: '—' };
      }
      const ready = Boolean(wfs.syncedAt) && (wfs.shipmentCount || 0) > 0;
      return {
        tone: ready ? (wfs.source === 'PROJECT_CONNECTOR' ? 'success' : 'warning') : 'warning',
        headline: ready
          ? wfs.source === 'PROJECT_CONNECTOR'
            ? 'WFS 货件来自工作台自动同步'
            : 'WFS 货件来自文件上传快照'
          : '暂无 WFS 货件数据',
        detail: ready
          ? `同步 ${formatLxTime(wfs.syncedAt)} · ${intText(wfs.shipmentCount)} 张货件单 · ${intText(
              wfs.skuCount,
            )} 个 SKU · ${intText(wfs.styleCount)} 款 · 申报 ${intText(wfs.declaredUnits)} 件 · 申收差异 ${intText(
              wfs.transitUnits,
            )} 件`
          : '领星自动同步还没有生成 WFS 货件快照，也没有可用的文件上传快照。',
        metaValue: wfs.sourceLabel || '—',
      };
    }
    if (!domestic) {
      return { tone: 'warning', headline: '正在读取快照', detail: '请稍候。', metaValue: '—' };
    }
    const source = domestic.source || 'NONE';
    return {
      tone: ['PROJECT_CONNECTOR', 'LINGXING_MCP'].includes(source) ? 'success' : 'warning',
      headline:
        source === 'LINGXING_MCP'
          ? '国内仓来自领星 MCP 自动同步'
          : source === 'PROJECT_CONNECTOR'
            ? '国内仓来自工作台自动同步'
            : source === 'FILE_UPLOAD'
              ? '国内仓来自文件上传快照'
              : '国内仓暂无数据',
      detail:
        source === 'NONE'
          ? '领星自动同步还没有生成数据，也没有可用的上传快照。'
          : `${formatLxTime(domestic.sourceAt)} · ${intText(domestic.skuCount)} 个 SKU · ${intText(
              domestic.styleCount,
            )} 款 · 合计 ${intText(domestic.totalUnits)} 件${
              source === 'FILE_UPLOAD' ? ' · 上传源没有仓库 / 店铺字段' : ''
            }`,
      metaValue: domestic.sourceLabel || '—',
    };
  }, [error, loading, tab, domestic, wfs, logs]);

  const metrics = useMemo((): MetricCardItem[] => {
    if (error) return [];
    const isInitialLoad =
      loading && ((tab === 'domestic' && !domestic) || (tab === 'wfs' && !wfs) || (tab === 'logs' && !logs));

    if (tab === 'logs') {
      const list = logs || [];
      const failed = list.filter((row) => row.status && row.status !== 'SUCCESS').length;
      const datasets = new Set(list.map((row) => row.dataset)).size;
      const latest = list[0];
      return [
        {
          key: 'count',
          label: '日志条数',
          value: intText(list.length),
          note: '两条自动同步链路最近 200 条',
          loading: isInitialLoad,
        },
        {
          key: 'datasets',
          label: '覆盖链路',
          value: intText(datasets),
          note:
            Array.from(new Set(list.map((row) => row.datasetLabel).filter(Boolean))).join(' / ') ||
            '国内仓库存 / WFS 货件',
          loading: isInitialLoad,
        },
        {
          key: 'failed',
          label: '失败执行',
          value: intText(failed),
          note: failed ? '存在非 SUCCESS 记录' : '全部执行成功',
          valueColor: failed ? WORKBENCH_COLORS.signalOrange : WORKBENCH_COLORS.productionGreen,
          loading: isInitialLoad,
        },
        {
          key: 'recent',
          label: '最近执行',
          value: latest ? formatLxTime(latest.startedAt).slice(5) : '—',
          note: latest ? `${latest.datasetLabel || ''} · ${latest.status || '—'}` : '暂无记录',
          loading: isInitialLoad,
        },
      ];
    }

    if (tab === 'wfs') {
      const data = wfs || {};
      const transit = Number(data.transitUnits || 0);
      return [
        {
          key: 'declared',
          label: '申报件数',
          value: intText(data.declaredUnits || 0),
          note: `已发货 ${intText(data.shippedUnits || 0)} · 签收 ${intText(data.receivedUnits || 0)}`,
          accent: true,
          loading: isInitialLoad,
        },
        {
          key: 'shipments',
          label: '货件单数',
          value: intText(data.shipmentCount || 0),
          note: `${intText(data.skuCount || 0)} 个 SKU · 覆盖 ${intText(data.styleCount || 0)} 款`,
          loading: isInitialLoad,
        },
        {
          key: 'transit',
          label: '少收差异',
          value: intText(transit),
          note: '逐行 申报−签收 正数累加',
          valueColor: transit > 0 ? WORKBENCH_COLORS.signalOrange : WORKBENCH_COLORS.productionGreen,
          loading: isInitialLoad,
        },
        {
          key: 'latest',
          label: '最近货件',
          value: data.latestShipmentAt ? formatLxTime(data.latestShipmentAt).slice(0, 10) : '—',
          note: `同步 ${formatLxTime(data.syncedAt)} · ${data.sourceLabel || '—'}`,
          loading: isInitialLoad,
        },
      ];
    }

    const data = domestic || {};
    const styles = data.styles || [];
    const top = styles.find((s) => s.parentSku !== '（未映射）') || styles[0];
    const source = data.source || 'NONE';
    const isAuto = ['PROJECT_CONNECTOR', 'LINGXING_MCP'].includes(source);
    return [
      {
        key: 'units',
        label: '国内仓可用',
        value: intText(data.totalUnits || 0),
        note: '全部 SKU 合计（件）',
        accent: true,
        loading: isInitialLoad,
      },
      {
        key: 'skus',
        label: 'SKU 数',
        value: intText(data.skuCount || 0),
        note: `覆盖 ${intText(data.styleCount || 0)} 款`,
        loading: isInitialLoad,
      },
      {
        key: 'source',
        label: '数据来源',
        value:
          source === 'LINGXING_MCP'
            ? '领星 MCP'
            : source === 'PROJECT_CONNECTOR'
              ? '自动同步'
              : source === 'FILE_UPLOAD'
                ? '上传'
                : '无',
        note: formatLxTime(data.sourceAt),
        valueColor: isAuto ? WORKBENCH_COLORS.productionGreen : WORKBENCH_COLORS.signalOrange,
        loading: isInitialLoad,
      },
      {
        key: 'top',
        label: '最大单款',
        value: top ? intText(top.units) : '0',
        note: top?.parentSku || '暂无数据',
        loading: isInitialLoad,
      },
    ];
  }, [error, loading, tab, domestic, wfs, logs]);

  const effectiveSort: LingxingSort = tab !== 'wfs' && sort === 'latest' ? 'units' : sort;

  const sortOptions = useMemo(() => {
    const labels =
      tab === 'wfs'
        ? {
            latest: '最新货件优先',
            units: '申报件数降序',
            parent: '货件单号升序',
            skuCount: 'SKU 数降序',
          }
        : {
            units: '可用件数降序',
            parent: '款号升序',
            skuCount: 'SKU 数降序',
          };
    return Object.entries(labels).map(([value, label]) => ({ value, label }));
  }, [tab]);

  const filteredStyles = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    const list = (domestic?.styles || []).filter((style) => {
      if (!q) return true;
      if (String(style.parentSku || '').toLowerCase().includes(q)) return true;
      if (String(style.itemName || '').toLowerCase().includes(q)) return true;
      return (style.skus || []).some((sku) => String(sku.sku || '').toLowerCase().includes(q));
    });
    if (effectiveSort === 'parent') {
      return [...list].sort((a, b) =>
        String(a.parentSku || '').localeCompare(String(b.parentSku || ''), 'zh-CN', { numeric: true }),
      );
    }
    if (effectiveSort === 'skuCount') {
      return [...list].sort((a, b) => (b.skuCount || 0) - (a.skuCount || 0));
    }
    return [...list].sort((a, b) => (b.units || 0) - (a.units || 0));
  }, [domestic?.styles, searchQuery, effectiveSort]);

  const filteredShipments = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    const list = (wfs?.shipments || []).filter((row) => {
      if (!q) return true;
      return [
        row.shipmentNo,
        row.warehouse,
        row.store,
        row.localStatus,
        row.cargoStatus,
        row.city,
        row.state,
        row.wmsOrderNo,
      ].some((val) => String(val || '').toLowerCase().includes(q));
    });
    if (effectiveSort === 'parent') {
      return [...list].sort((a, b) =>
        String(a.shipmentNo || '').localeCompare(String(b.shipmentNo || ''), 'zh-CN', { numeric: true }),
      );
    }
    if (effectiveSort === 'skuCount') {
      return [...list].sort((a, b) => (b.skuCount || 0) - (a.skuCount || 0));
    }
    if (effectiveSort === 'latest') {
      return [...list].sort(
        (a, b) => new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime(),
      );
    }
    return [...list].sort((a, b) => (b.declaredUnits || 0) - (a.declaredUnits || 0));
  }, [wfs?.shipments, searchQuery, effectiveSort]);

  const filteredLogs = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return (logs || []).filter(
      (row) =>
        !q ||
        String(row.datasetLabel || '').toLowerCase().includes(q) ||
        String(row.detail || '').toLowerCase().includes(q) ||
        String(row.message || '').toLowerCase().includes(q) ||
        String(row.status || '').toLowerCase().includes(q),
    );
  }, [logs, searchQuery]);

  const domesticColumns: TableProps<DomesticStyleRow>['columns'] = [
    {
      title: '款号与商品',
      key: 'parentSku',
      width: 260,
      render: (_, row) => {
        const validParent =
          row.parentSku && row.parentSku !== '（未映射）' ? row.parentSku : null;
        return (
          <div>
            <SkuLinkTag
              sku={row.parentSku || '—'}
              parentSku={validParent}
              unmatchedParentLabel="未映射款号"
            />
            {row.itemName ? <span className="wb-cell-secondary">{row.itemName}</span> : null}
          </div>
        );
      },
    },
    {
      title: 'SKU 数',
      dataIndex: 'skuCount',
      key: 'skuCount',
      align: 'right',
      width: 90,
      render: (val?: number | null) => <span className="wb-num">{intText(val)}</span>,
    },
    {
      title: '颜色',
      key: 'colors',
      width: 170,
      render: (_, row) => uniqueJoin((row.skus || []).map((s) => s.color)),
    },
    {
      title: '尺码',
      key: 'sizes',
      width: 170,
      render: (_, row) => uniqueJoin((row.skus || []).map((s) => s.size)),
    },
    {
      title: '可用件数',
      dataIndex: 'units',
      key: 'units',
      align: 'right',
      width: 105,
      render: (val?: number | null) => (
        <span className="wb-num" style={{ fontWeight: 700, color: '#1d1d1f' }}>
          {intText(val)}
        </span>
      ),
    },
    {
      title: '仓库',
      key: 'warehouses',
      width: 150,
      render: (_, row) => uniqueJoin((row.skus || []).map((s) => s.warehouse)),
    },
    {
      title: '店铺',
      key: 'stores',
      width: 140,
      render: (_, row) => uniqueJoin((row.skus || []).map((s) => s.store)),
    },
  ];

  const shipmentColumns: TableProps<WfsShipmentRow>['columns'] = [
    {
      title: '货件单号',
      key: 'shipmentNo',
      width: 220,
      render: (_, row) => (
        <div>
          <Button
            type="link"
            size="small"
            className="wb-mono"
            style={{ padding: 0, height: 'auto', fontWeight: 700 }}
            onClick={() => void handleOpenShipment(row.shipmentNo)}
          >
            {row.shipmentNo || '—'}
          </Button>
          <span className="wb-cell-secondary">
            {row.store || '—'} · {[row.city, row.state].filter(Boolean).join(', ') || '—'}
          </span>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'localStatus',
      key: 'localStatus',
      width: 110,
      render: (status?: string | null) => (
        <HeroChip size="sm" variant="flat" color={status === '已完成' ? 'success' : status ? 'warning' : 'default'}>
          {status || '—'}
        </HeroChip>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 145,
      render: (val?: string | null) => <span className="wb-mono">{formatLxTime(val)}</span>,
    },
    {
      title: '物流中心',
      dataIndex: 'warehouse',
      key: 'warehouse',
      width: 130,
      render: (val?: string | null) => <span className="wb-mono">{val || '—'}</span>,
    },
    {
      title: '款数',
      dataIndex: 'styleCount',
      key: 'styleCount',
      align: 'right',
      width: 75,
      render: (val?: number | null) => <span className="wb-num">{intText(val)}</span>,
    },
    {
      title: 'SKU 数',
      dataIndex: 'skuCount',
      key: 'skuCount',
      align: 'right',
      width: 85,
      render: (val?: number | null) => <span className="wb-num">{intText(val)}</span>,
    },
    {
      title: '申报件数',
      dataIndex: 'declaredUnits',
      key: 'declaredUnits',
      align: 'right',
      width: 95,
      render: (val?: number | null) => (
        <span className="wb-num" style={{ fontWeight: 700 }}>
          {intText(val)}
        </span>
      ),
    },
    {
      title: '已发货',
      dataIndex: 'shippedUnits',
      key: 'shippedUnits',
      align: 'right',
      width: 90,
      render: (val?: number | null) => <span className="wb-num">{intText(val)}</span>,
    },
    {
      title: '签收量',
      dataIndex: 'receivedUnits',
      key: 'receivedUnits',
      align: 'right',
      width: 90,
      render: (val?: number | null) => <span className="wb-num">{intText(val)}</span>,
    },
    {
      title: '申收差异',
      dataIndex: 'transitUnits',
      key: 'transitUnits',
      align: 'right',
      width: 95,
      render: (val?: number | null) => (
        <span
          className="wb-num"
          style={{
            color: Number(val || 0) > 0 ? WORKBENCH_COLORS.signalOrange : undefined,
            fontWeight: Number(val || 0) > 0 ? 700 : undefined,
          }}
        >
          {intText(val)}
        </span>
      ),
    },
    {
      title: '操作',
      key: 'action',
      align: 'right',
      width: 70,
      render: (_, row) => (
        <Button
          type="link"
          size="small"
          style={{ padding: 0 }}
          onClick={() => void handleOpenShipment(row.shipmentNo)}
        >
          详情
        </Button>
      ),
    },
  ];

  const logColumns: TableProps<SyncLogRow>['columns'] = [
    {
      title: '开始时间',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 155,
      render: (val?: string | null) => <span className="wb-mono">{formatLxTime(val)}</span>,
    },
    {
      title: '数据集',
      dataIndex: 'datasetLabel',
      key: 'datasetLabel',
      width: 140,
      render: (val?: string | null) => val || '—',
    },
    {
      title: '结果',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status?: string | null) => (
        <HeroChip size="sm" variant="flat" color={status === 'SUCCESS' ? 'success' : status ? 'danger' : 'default'}>
          {status || '—'}
        </HeroChip>
      ),
    },
    {
      title: '记录数',
      dataIndex: 'recordCount',
      key: 'recordCount',
      align: 'right',
      width: 95,
      render: (val?: number | null) => <span className="wb-num">{intText(val)}</span>,
    },
    {
      title: '明细',
      key: 'detail',
      render: (_, row) => (
        <div>
          <span className="wb-cell-primary" style={{ maxWidth: 420 }}>
            {row.detail || '—'}
          </span>
          {row.message ? (
            <span className="wb-cell-secondary" style={{ maxWidth: 420 }}>
              {row.message}
            </span>
          ) : null}
        </div>
      ),
    },
    {
      title: '来源 / 触发',
      key: 'source',
      width: 190,
      render: (_, row) => [row.source, row.triggeredBy].filter(Boolean).join(' · ') || '—',
    },
  ];

  const tableFooterText = useMemo(() => {
    if (error) return '0 条记录';
    if (tab === 'domestic') {
      const skuTotal = filteredStyles.reduce((acc, s) => acc + (s.skuCount || 0), 0);
      const unitTotal = filteredStyles.reduce((acc, s) => acc + (s.units || 0), 0);
      return `${filteredStyles.length} 款 · ${intText(skuTotal)} 个 SKU · 合计 ${intText(unitTotal)} 件`;
    }
    if (tab === 'wfs') {
      const declaredTotal = filteredShipments.reduce((acc, s) => acc + (s.declaredUnits || 0), 0);
      return `${filteredShipments.length} 张货件 · 申报 ${intText(declaredTotal)} 件`;
    }
    return `${filteredLogs.length} 条记录`;
  }, [error, tab, filteredStyles, filteredShipments, filteredLogs]);

  const shipmentDrawerContent = useMemo(() => {
    if (!shipmentDetail) return null;
    const declared = shipmentDetail.declaredUnits || 0;
    const shipped = shipmentDetail.shippedUnits || 0;
    const received = shipmentDetail.receivedUnits || 0;
    const transit = shipmentDetail.transitUnits || 0;
    const pending = Math.max(0, shipped - received);
    const pendingNote =
      pending > 0
        ? `在途 ${intText(pending)} 件待签收`
        : shipped === 0 && received > 0
          ? '领星未记录已发货，按签收量计'
          : '已发货全部签收';
    const pctText = (val: number) => (declared > 0 ? `${Math.round((val / declared) * 100)}%` : '—');
    const pctNum = (val: number) => (declared > 0 ? Math.max(0, Math.min(100, (val / declared) * 100)) : 0);

    const kpis: MetricCardItem[] = [
      {
        key: 'declared',
        label: '申报件数',
        value: intText(declared),
        note: `${intText(shipmentDetail.styleCount)} 款 · ${intText(shipmentDetail.skuCount)} 个 SKU`,
        accent: true,
      },
      {
        key: 'shipped',
        label: '已发货',
        value: intText(shipped),
        note: `占申报 ${pctText(shipped)}`,
      },
      {
        key: 'received',
        label: '签收量',
        value: intText(received),
        note: `签收率 ${pctText(received)}`,
        valueColor: received >= declared && declared > 0 ? WORKBENCH_COLORS.productionGreen : undefined,
      },
      {
        key: 'transit',
        label: '申收差异',
        value: intText(transit),
        note: transit > 0 ? '逐行 申报−签收 正数累加' : '无差异',
        valueColor: transit > 0 ? WORKBENCH_COLORS.signalOrange : WORKBENCH_COLORS.productionGreen,
      },
    ];

    const styles = shipmentDetail.styles || [];
    const maxShare = Math.max(...styles.map((s) => s.declaredUnits || 0), 1);

    const lineColumns: TableProps<NonNullable<WfsShipmentDetail['lines']>[number]>['columns'] = [
      { title: 'SKU', dataIndex: 'sku', key: 'sku', render: (v?: string | null) => <span className="wb-mono">{v || '—'}</span> },
      {
        title: '款号',
        dataIndex: 'parentSku',
        key: 'parentSku',
        render: (v?: string | null) =>
          v ? <SkuLinkTag sku={v} parentSku={v} /> : '—',
      },
      { title: '颜色', dataIndex: 'color', key: 'color', render: (v?: string | null) => v || '—' },
      { title: '尺码', dataIndex: 'size', key: 'size', render: (v?: string | null) => v || '—' },
      { title: '申报', dataIndex: 'declaredUnits', key: 'declaredUnits', align: 'right', render: (v?: number | null) => <span className="wb-num">{intText(v)}</span> },
      { title: '已发货', dataIndex: 'shippedUnits', key: 'shippedUnits', align: 'right', render: (v?: number | null) => <span className="wb-num">{intText(v)}</span> },
      { title: '签收', dataIndex: 'receivedUnits', key: 'receivedUnits', align: 'right', render: (v?: number | null) => <span className="wb-num">{intText(v)}</span> },
      { title: '申收差异', dataIndex: 'transitUnits', key: 'transitUnits', align: 'right', render: (v?: number | null) => <span className="wb-num">{intText(v)}</span> },
    ];

    return (
      <>
        <MetricCardGrid items={kpis} columns={4} accentColor={ACCENT} />

        <div className="wb-dash-grid-2">
          <HeroCard shadow="sm" radius="lg" className="border border-black/[0.06] bg-white">
            <HeroCardHeader className="border-b border-black/[0.05] px-4 py-2.5 text-[13px] font-semibold text-[#1d1d1f]">
              货件进度
            </HeroCardHeader>
            <HeroCardBody className="p-4">
              <div style={{ display: 'grid', gap: 10 }}>
                {[
                  { label: '申报', value: declared, color: ACCENT },
                  { label: '已发货', value: shipped, color: '#2b7de9' },
                  { label: '签收', value: received, color: WORKBENCH_COLORS.productionGreen },
                ].map((row) => (
                  <div key={row.label}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 2 }}>
                      <span>{row.label}</span>
                      <span className="wb-num">
                        {intText(row.value)} ({pctText(row.value)})
                      </span>
                    </div>
                    <Progress percent={Number(pctNum(row.value).toFixed(1))} showInfo={false} strokeColor={row.color} size="small" />
                  </div>
                ))}
                <div style={{ fontSize: 11, color: '#86868b', marginTop: 4 }}>
                  签收率 {pctText(received)} · {pendingNote}
                </div>
              </div>
            </HeroCardBody>
          </HeroCard>

          <HeroCard shadow="sm" radius="lg" className="border border-black/[0.06] bg-white">
            <HeroCardHeader className="border-b border-black/[0.05] px-4 py-2.5 text-[13px] font-semibold text-[#1d1d1f]">
              按款申报分布
            </HeroCardHeader>
            <HeroCardBody className="p-4">
              {styles.length > 0 ? (
                <div style={{ display: 'grid', gap: 10 }}>
                  {styles.map((style, idx) => (
                    <div key={style.parentSku || idx}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 2 }}>
                        <strong className="wb-mono">{style.parentSku || '—'}</strong>
                        <span className="wb-num">
                          {intText(style.declaredUnits)} 件 · {intText(style.skuCount)} SKU
                        </span>
                      </div>
                      <Progress
                        percent={Number((((style.declaredUnits || 0) / maxShare) * 100).toFixed(1))}
                        showInfo={false}
                        strokeColor={ACCENT}
                        size="small"
                      />
                    </div>
                  ))}
                </div>
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无款号数据" />
              )}
            </HeroCardBody>
          </HeroCard>
        </div>

        <HeroCard shadow="sm" radius="lg" className="border border-black/[0.06] bg-white">
          <HeroCardHeader className="border-b border-black/[0.05] px-4 py-2.5 text-[13px] font-semibold text-[#1d1d1f]">
            {`SKU 明细（${intText((shipmentDetail.lines || []).length)} 行）`}
          </HeroCardHeader>
          <HeroCardBody className="p-3">
            <Table
              size="small"
              rowKey={(line, idx) => `${line.sku || 'line'}-${idx}`}
              columns={lineColumns}
              dataSource={shipmentDetail.lines || []}
              pagination={false}
              scroll={{ x: 740 }}
            />
          </HeroCardBody>
        </HeroCard>
      </>
    );
  }, [shipmentDetail]);

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <PageHeaderCard
        eyebrow="LINGXING DATA"
        title="领星同步的库存数据"
        description="页面读取数据库快照，不会反复请求领星。国内仓通过领星 MCP 自动同步，也支持文件上传兜底；WFS 货件仍通过数据连接同步。"
        accentColor={ACCENT}
        extra={
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <HeroButton
              color="primary"
              radius="full"
              size="md"
              startContent={!loading ? <ReloadOutlined /> : undefined}
              isLoading={loading}
              onPress={() => void loadData(tab, true)}
              className="font-semibold"
            >
              刷新数据
            </HeroButton>
          </div>
        }
      />

      <SyncStatusBanner
        tone={bannerProps.tone}
        headline={bannerProps.headline}
        detail={bannerProps.detail}
        metaLabel="数据来源"
        metaValue={bannerProps.metaValue}
      />

      {metrics.length > 0 ? <MetricCardGrid items={metrics} columns={4} accentColor={ACCENT} /> : null}

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label={TAB_META[tab].title}>
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">{TAB_META[tab].title}</h3>
            <p className="wb-panel-subtitle">{TAB_META[tab].subtitle}</p>
          </div>
          <AppleSegmentedTabs<LingxingTab>
            ariaLabel="领星数据视图切换"
            value={tab}
            options={[
              { label: '国内仓库存', value: 'domestic' },
              { label: 'WFS 货件', value: 'wfs' },
              { label: '同步日志', value: 'logs' },
            ]}
            onChange={(val) => handleTabChange(val)}
          />
        </HeroCardHeader>

        <HeroCardBody className="p-0">
          <div className="wb-toolbar">
            <Input.Search
              allowClear
              placeholder="搜索款号、SKU、货件单号"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onSearch={(val) => setSearchQuery(val)}
              style={{ width: 320, maxWidth: '100%' }}
            />
            {tab !== 'logs' ? (
              <Space size="small">
                <span style={{ fontSize: 12, color: '#86868b' }}>排序</span>
                <Select<LingxingSort>
                  value={effectiveSort}
                  options={sortOptions}
                  style={{ width: 165 }}
                  onChange={(val) => setSort(val)}
                />
              </Space>
            ) : null}
          </div>

          <div style={{ padding: '0 12px 12px' }}>
          {tab === 'domestic' ? (
            <Table<DomesticStyleRow>
              size="small"
              rowKey={(row, idx) => row.parentSku || `style-${idx}`}
              columns={domesticColumns}
              dataSource={filteredStyles}
              loading={loading && !domestic}
              pagination={false}
              scroll={{ x: 1080 }}
              expandable={{
                expandedRowKeys,
                onExpandedRowsChange: (keys) => setExpandedRowKeys([...keys]),
                rowExpandable: (row) => Boolean(row.skus && row.skus.length > 0),
                expandedRowRender: (row) => (
                  <Table<DomesticSkuItem>
                    size="small"
                    rowKey={(item, idx) => `${item.sku || 'sku'}-${idx}`}
                    pagination={false}
                    dataSource={row.skus || []}
                    columns={[
                      {
                        title: 'SKU',
                        dataIndex: 'sku',
                        key: 'sku',
                        render: (v?: string | null) => <span className="wb-mono">{v || '—'}</span>,
                      },
                      { title: '颜色', dataIndex: 'color', key: 'color', render: (v?: string | null) => v || '—' },
                      { title: '尺码', dataIndex: 'size', key: 'size', render: (v?: string | null) => v || '—' },
                      {
                        title: '可用件数',
                        dataIndex: 'units',
                        key: 'units',
                        align: 'right',
                        render: (v?: number | null) => <span className="wb-num">{intText(v)}</span>,
                      },
                      { title: '仓库', dataIndex: 'warehouse', key: 'warehouse', render: (v?: string | null) => v || '—' },
                      { title: '店铺', dataIndex: 'store', key: 'store', render: (v?: string | null) => v || '—' },
                    ]}
                  />
                ),
              }}
              locale={{
                emptyText: (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={
                      error
                        ? `暂时无法读取数据：${error}`
                        : searchQuery
                          ? '没有匹配的记录'
                          : '暂无国内仓数据'
                    }
                  />
                ),
              }}
            />
          ) : tab === 'wfs' ? (
            <Table<WfsShipmentRow>
              size="small"
              rowKey={(row, idx) => row.shipmentNo || `shipment-${idx}`}
              columns={shipmentColumns}
              dataSource={filteredShipments}
              loading={loading && !wfs}
              pagination={false}
              scroll={{ x: 1180 }}
              locale={{
                emptyText: (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={
                      error
                        ? `暂时无法读取数据：${error}`
                        : searchQuery
                          ? '没有匹配的货件'
                          : '暂无 WFS 货件'
                    }
                  />
                ),
              }}
            />
          ) : (
            <Table<SyncLogRow>
              size="small"
              rowKey={(row, idx) => String(row.id ?? `log-${idx}`)}
              columns={logColumns}
              dataSource={filteredLogs}
              loading={loading && !logs}
              pagination={false}
              scroll={{ x: 960 }}
              locale={{
                emptyText: (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={
                      error
                        ? `暂时无法读取数据：${error}`
                        : searchQuery
                          ? '没有匹配的日志'
                          : '暂无自动同步日志'
                    }
                  />
                ),
              }}
            />
          )}

          <div
            style={{
              display: 'flex',
              justifyContent: 'flex-end',
              paddingTop: 10,
              fontSize: 12,
              color: '#86868b',
            }}
          >
            {tableFooterText}
          </div>
        </div>
        </HeroCardBody>
      </HeroCard>

      <Drawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={840}
        className="wb-detail-drawer"
        title={
          <div>
            <Space size="small" align="center" wrap>
              <span className="wb-mono" style={{ fontSize: 16, fontWeight: 700, color: '#1d1d1f' }}>
                {shipmentTitle}
              </span>
              {shipmentDetail?.localStatus ? (
                <HeroChip
                  size="sm"
                  variant="flat"
                  color={shipmentDetail.localStatus === '已完成' ? 'success' : 'warning'}
                >
                  {shipmentDetail.localStatus}
                </HeroChip>
              ) : null}
              {shipmentDetail?.cargoStatus ? (
                <HeroChip size="sm" variant="flat">
                  {shipmentDetail.cargoStatus}
                </HeroChip>
              ) : null}
            </Space>
            <div style={{ fontSize: 12, fontWeight: 400, color: '#515154', marginTop: 4 }}>
              {drawerLoading
                ? '正在读取货件明细…'
                : drawerError
                  ? drawerError
                  : [
                      shipmentDetail ? `创建 ${formatLxTime(shipmentDetail.createdAt)}` : null,
                      shipmentDetail?.warehouse ? `物流中心 ${shipmentDetail.warehouse}` : null,
                      shipmentDetail?.store || null,
                      [shipmentDetail?.city, shipmentDetail?.state].filter(Boolean).join(', ') || null,
                      shipmentDetail?.wmsOrderNo ? `入库单 ${shipmentDetail.wmsOrderNo}` : null,
                    ]
                      .filter(Boolean)
                      .join(' · ')}
            </div>
          </div>
        }
      >
        {drawerLoading ? (
          <div style={{ padding: '48px 0', textAlign: 'center' }}>
            <HeroSpinner color="primary" size="md" />
            <div style={{ marginTop: 10, color: '#86868b', fontSize: 12 }}>正在读取货件明细...</div>
          </div>
        ) : drawerError ? (
          <Empty description={`无法读取货件明细：${drawerError}`} />
        ) : (
          shipmentDrawerContent
        )}
      </Drawer>
    </ConfigProvider>
  );
};

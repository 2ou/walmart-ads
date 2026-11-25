import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  ConfigProvider,
  Empty,
  Progress,
  Space,
  Table,
} from 'antd';
import type { TableProps } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import {
  HeroButton,
  HeroCard,
  HeroCardBody,
  HeroCardHeader,
  HeroChip,
  HeroSpinner,
  HeroTooltip,
  MetricCardGrid,
  PageHeaderCard,
  SkuLinkTag,
  SyncStatusBanner,
} from '../../components';
import type { MetricCardItem } from '../../components';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';

const ACCENT = WORKBENCH_COLORS.walmartBlue;

interface DailyRow {
  date: string;
  orders: number;
  revenue: number;
  avgOrderValue: number;
  impressions: number;
  clicks: number;
  spend: number;
  adOrders: number;
  ctr: number;
  cpc: number;
  conversionRate: number;
}

interface RecentDailyResponse {
  days: number;
  start: string;
  end: string;
  summary: {
    totalOrders: number;
    totalRevenue: number;
    avgOrderValue: number;
    totalImpressions: number;
    totalClicks: number;
    totalSpend: number;
    totalAdOrders: number;
    ctr: number;
    cpc: number;
    conversionRate: number;
  };
  daily: DailyRow[];
}

interface StyleSalesResponse {
  days: number;
  grandTotal: number;
  dates: string[];
  series: Record<string, number[]>;
  ranking: Array<{
    style: string;
    totalQty: number;
    share: number;
    imageUrl?: string | null;
  }>;
}

interface AdProductRow {
  style: string;
  imageUrl?: string | null;
  spend: number;
  sales: number;
  roas: number;
  acos: number;
  orders: number;
  clicks: number;
  impressions: number;
  ctr: number;
  cpc: number;
  conversionRate: number;
}

interface AdProductsResponse {
  latest?: boolean;
  end?: string;
  summary: {
    totalSpend: number;
    totalSales: number;
    roas: number;
    acos: number;
    totalOrders: number;
    totalImpressions: number;
  };
  products: AdProductRow[];
}

interface PivotTableRow {
  key: string;
  date: string;
  total: number;
  [styleKey: string]: string | number;
}

function numVal(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

function intText(v: unknown): string {
  return Math.round(numVal(v)).toLocaleString('zh-CN');
}

function moneyText(v: unknown, digits = 2): string {
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return `$${n.toLocaleString('en-US', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })}`;
}

function pctText(v: unknown, digits = 1): string {
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return `${(n * 100).toFixed(digits)}%`;
}

function ratioText(v: unknown): string {
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return `${n.toFixed(2)}x`;
}

function shortDate(iso: string): string {
  const parts = String(iso || '').split('-');
  return parts.length === 3 ? `${parts[1]}-${parts[2]}` : iso;
}

function weekdayCN(dateStr: string): string {
  const d = new Date(dateStr);
  if (Number.isNaN(d.getTime())) return '';
  return `周${'日一二三四五六'.charAt(d.getDay())}`;
}

function placeholderSvgUrl(code: string): string {
  const clean = (code.match(/\d+$/)?.[0] || code.replace(/[^A-Za-z0-9]/g, '') || 'SKU').slice(0, 8);
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">` +
    `<rect width="100" height="100" rx="8" fill="#f4f1ea"/>` +
    `<text x="50" y="56" font-family="system-ui,sans-serif" font-size="18" font-weight="700" fill="#8a8576" text-anchor="middle">${clean}</text>` +
    `</svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

const StyleThumb: React.FC<{ url?: string | null; code: string; size?: number }> = ({
  url,
  code,
  size = 30,
}) => {
  const [src, setSrc] = useState<string>(() => (url || '').trim() || placeholderSvgUrl(code));
  useEffect(() => {
    setSrc((url || '').trim() || placeholderSvgUrl(code));
  }, [url, code]);
  return (
    <img
      src={src}
      alt={code}
      width={size}
      height={size}
      loading="lazy"
      onError={() => setSrc(placeholderSvgUrl(code))}
      style={{
        width: size,
        height: size,
        borderRadius: 6,
        objectFit: 'cover',
        border: '1px solid rgba(16, 32, 58, 0.08)',
        flexShrink: 0,
      }}
    />
  );
};

let recentLoadHandler: (() => void) | null = null;
let pendingRecentLoad = false;

export function triggerRecentLoad(): void {
  if (recentLoadHandler) {
    pendingRecentLoad = false;
    recentLoadHandler();
  } else {
    pendingRecentLoad = true;
  }
}

export const RecentSalesView: React.FC = () => {
  const [dailyData, setDailyData] = useState<RecentDailyResponse | null>(null);
  const [styleSales, setStyleSales] = useState<StyleSalesResponse | null>(null);
  const [adProducts, setAdProducts] = useState<AdProductsResponse | null>(null);
  const [adProductsLatest, setAdProductsLatest] = useState<AdProductsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hoverTrendIdx, setHoverTrendIdx] = useState<number | null>(null);

  const requestIdRef = useRef(0);

  const loadData = useCallback(async () => {
    const reqId = ++requestIdRef.current;
    setLoading(true);
    setError(null);
    setHoverTrendIdx(null);
    try {
      const query = workbenchBridge.getAnalysisQuery();
      const [dData, sData, aData, aLatest] = await Promise.all([
        workbenchBridge.apiFetch<RecentDailyResponse>(`/api/recent/daily?${query}`),
        workbenchBridge.apiFetch<StyleSalesResponse>(`/api/recent/styles?${query}`),
        workbenchBridge.apiFetch<AdProductsResponse>(`/api/recent/ad-products?${query}`),
        workbenchBridge
          .apiFetch<AdProductsResponse>('/api/recent/ad-products?days=1&latest=true')
          .catch(() => null),
      ]);
      if (reqId !== requestIdRef.current) return;
      setDailyData(dData);
      setStyleSales(sData);
      setAdProducts(aData);
      setAdProductsLatest(aLatest);
    } catch (err) {
      if (reqId !== requestIdRef.current) return;
      setError(err instanceof Error ? err.message : '每日销售数量统计读取失败');
    } finally {
      if (reqId === requestIdRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    recentLoadHandler = () => {
      void loadData();
    };
    if (pendingRecentLoad) {
      pendingRecentLoad = false;
      void loadData();
    }
    return () => {
      recentLoadHandler = null;
    };
  }, [loadData]);

  const overviewMetrics: MetricCardItem[] = useMemo(() => {
    const s = dailyData?.summary;
    const isLoad = loading && !dailyData;
    return [
      { key: 'units', label: '总销量', value: s ? `${intText(s.totalOrders)} 件` : '—', note: '窗口累计', loading: isLoad },
      { key: 'revenue', label: '总销售额', value: s ? moneyText(s.totalRevenue) : '—', note: '窗口累计', loading: isLoad },
      { key: 'revenuePerUnit', label: '件均销售额', value: s ? moneyText(s.avgOrderValue) : '—', note: '销售额 / 销量', loading: isLoad },
      { key: 'imp', label: '曝光量', value: s ? intText(s.totalImpressions) : '—', note: '窗口累计', loading: isLoad },
      { key: 'clicks', label: '点击量', value: s ? intText(s.totalClicks) : '—', note: '窗口累计', loading: isLoad },
      { key: 'spend', label: '广告费', value: s ? moneyText(s.totalSpend) : '—', note: s ? `CPC ${moneyText(s.cpc)}` : '窗口累计', accent: true, loading: isLoad },
      { key: 'adOrders', label: '广告归因订单', value: s ? `${intText(s.totalAdOrders)} 单` : '—', note: '窗口累计', loading: isLoad },
      { key: 'ctr', label: 'CTR', value: s ? pctText(s.ctr, 2) : '—', note: '点击 / 曝光', loading: isLoad },
      { key: 'cpc', label: 'CPC', value: s ? moneyText(s.cpc) : '—', note: '广告费 / 点击', accent: true, loading: isLoad },
      { key: 'cvr', label: '广告转化率', value: s ? pctText(s.conversionRate, 2) : '—', note: '归因订单 / 点击', loading: isLoad },
    ];
  }, [dailyData, loading]);

  const dailyColumns: TableProps<DailyRow>['columns'] = [
    { title: '星期', key: 'weekday', width: 70, render: (_, r) => weekdayCN(r.date) },
    { title: '日期', key: 'date', width: 85, render: (_, r) => <span className="wb-mono">{shortDate(r.date)}</span> },
    { title: '销量（件）', dataIndex: 'orders', align: 'right', width: 95, render: (v) => <span className="wb-num">{intText(v)}</span> },
    { title: '销售额(USD)', dataIndex: 'revenue', align: 'right', width: 110, render: (v) => <span className="wb-num">{moneyText(v)}</span> },
    { title: '件均销售额', dataIndex: 'avgOrderValue', align: 'right', width: 95, render: (v) => <span className="wb-num">{moneyText(v)}</span> },
    { title: '曝光量', dataIndex: 'impressions', align: 'right', width: 95, render: (v) => <span className="wb-num">{intText(v)}</span> },
    { title: '点击量', dataIndex: 'clicks', align: 'right', width: 80, render: (v) => <span className="wb-num">{intText(v)}</span> },
    { title: '广告费(USD)', dataIndex: 'spend', align: 'right', width: 105, render: (v) => <span className="wb-num">{moneyText(v)}</span> },
    { title: '广告归因订单', dataIndex: 'adOrders', align: 'right', width: 105, render: (v) => <span className="wb-num">{intText(v)}</span> },
    { title: 'CTR', dataIndex: 'ctr', align: 'right', width: 75, render: (v) => <span className="wb-num">{pctText(v, 2)}</span> },
    { title: 'CPC(USD)', dataIndex: 'cpc', align: 'right', width: 85, render: (v) => <span className="wb-num">{moneyText(v)}</span> },
    { title: '广告转化率', dataIndex: 'conversionRate', align: 'right', width: 95, render: (v) => <span className="wb-num">{pctText(v, 2)}</span> },
    {
      title: '件均广告费',
      key: 'spendPerUnit',
      align: 'right',
      width: 95,
      render: (_, r) => (
        <span className="wb-num">{numVal(r.orders) > 0 ? moneyText(numVal(r.spend) / numVal(r.orders)) : '—'}</span>
      ),
    },
    {
      title: '广告费 / 归因订单',
      key: 'spendPerAdOrder',
      align: 'right',
      width: 115,
      render: (_, r) => (
        <span className="wb-num">{numVal(r.adOrders) > 0 ? moneyText(numVal(r.spend) / numVal(r.adOrders)) : '—'}</span>
      ),
    },
  ];

  const pivotModel = useMemo(() => {
    if (!styleSales?.dates?.length || !styleSales.series) {
      return { orderedStyles: [] as string[], rows: [] as PivotTableRow[], colTotals: {} as Record<string, number>, grandTotal: 0 };
    }
    const series = styleSales.series;
    const orderedStyles = styleSales.ranking.map(({ style }) => style).filter((style) => style in series);

    const colTotals: Record<string, number> = Object.fromEntries(orderedStyles.map((st) => [st, 0]));
    let grandTotal = 0;

    const rows: PivotTableRow[] = styleSales.dates.map((dateStr, i) => {
      let rowTotal = 0;
      const item: PivotTableRow = { key: dateStr, date: dateStr, total: 0 };
      orderedStyles.forEach((st) => {
        const val = (series[st] || [])[i] || 0;
        item[st] = val;
        rowTotal += val;
        colTotals[st] += val;
      });
      item.total = rowTotal;
      grandTotal += rowTotal;
      return item;
    });

    return { orderedStyles, rows, colTotals, grandTotal };
  }, [styleSales]);

  const pivotColumns: TableProps<PivotTableRow>['columns'] = useMemo(() => {
    const imageMap = new Map((styleSales?.ranking || []).map((r) => [r.style, r.imageUrl || '']));
    return [
      {
        title: '日期',
        dataIndex: 'date',
        key: 'date',
        fixed: 'left',
        width: 82,
        render: (d: string) => <strong className="wb-mono">{shortDate(d)}</strong>,
      },
      ...pivotModel.orderedStyles.map((st) => ({
        title: (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
            <StyleThumb url={imageMap.get(st)} code={st} size={28} />
            <SkuLinkTag sku={st} parentSku={st} />
          </div>
        ),
        dataIndex: st,
        key: st,
        align: 'right' as const,
        width: 112,
        render: (val: number) => <span className="wb-num">{intText(val)}</span>,
      })),
      {
        title: '合计',
        dataIndex: 'total',
        key: 'total',
        fixed: 'right',
        align: 'right',
        width: 88,
        render: (val: number) => (
          <strong className="wb-num" style={{ color: ACCENT }}>
            {intText(val)}
          </strong>
        ),
      },
    ];
  }, [pivotModel.orderedStyles, styleSales?.ranking]);

  const styleMetrics: MetricCardItem[] = useMemo(() => {
    const isLoad = loading && !styleSales;
    const days = Math.max(1, styleSales?.days || 1);
    const grand = styleSales?.grandTotal || 0;
    const head = styleSales?.ranking?.[0];
    return [
      { key: 'activeStyles', label: '在售款数', value: styleSales ? intText(styleSales.ranking.length) : '—', note: '窗口内有销量', loading: isLoad },
      { key: 'totalUnits', label: '窗口总销量', value: styleSales ? `${intText(grand)} 件` : '—', note: '累计销量件数', accent: true, loading: isLoad },
      { key: 'avgDaily', label: '日均销量', value: styleSales ? `${intText(grand / days)} 件` : '—', note: '总量 / 天数', loading: isLoad },
      { key: 'topShare', label: '头部款占比', value: head ? pctText(head.share, 0) : '—', note: head ? `Top1 ${head.style}` : '—', loading: isLoad },
    ];
  }, [styleSales, loading]);

  const adMetrics: MetricCardItem[] = useMemo(() => {
    const s = adProducts?.summary;
    const isLoad = loading && !adProducts;
    return [
      { key: 'spend', label: '广告费', value: s ? moneyText(s.totalSpend) : '—', note: '窗口累计', accent: true, loading: isLoad },
      { key: 'sales', label: '归因销售', value: s ? moneyText(s.totalSales) : '—', note: '广告带来 GMV', loading: isLoad },
      { key: 'roas', label: 'RoAS', value: s ? ratioText(s.roas) : '—', note: '销售 / 广告费', accent: true, loading: isLoad },
      { key: 'acos', label: 'ACOS', value: s ? pctText(s.acos, 1) : '—', note: '广告费 / 销售', loading: isLoad },
      { key: 'orders', label: '广告归因订单', value: s ? `${intText(s.totalOrders)} 单` : '—', note: '窗口累计', loading: isLoad },
      { key: 'impressions', label: '曝光量', value: s ? intText(s.totalImpressions) : '—', note: '窗口累计', loading: isLoad },
    ];
  }, [adProducts, loading]);

  const adDetailSource = adProductsLatest?.products ? adProductsLatest : adProducts;
  const adDetailDay = adProductsLatest?.latest ? adProductsLatest.end : null;

  const adColumns: TableProps<AdProductRow>['columns'] = [
    {
      title: '产品',
      key: 'style',
      width: 175,
      render: (_, row) => (
        <Space size="small">
          <StyleThumb url={row.imageUrl} code={row.style} size={32} />
          <SkuLinkTag sku={row.style} parentSku={row.style} />
        </Space>
      ),
    },
    { title: '广告支出', dataIndex: 'spend', align: 'right', width: 105, sorter: (a, b) => a.spend - b.spend, render: (v) => <span className="wb-num">{moneyText(v)}</span> },
    { title: '归因销售总额', dataIndex: 'sales', align: 'right', width: 115, sorter: (a, b) => a.sales - b.sales, render: (v) => <span className="wb-num">{moneyText(v)}</span> },
    { title: 'RoAS', dataIndex: 'roas', align: 'right', width: 85, sorter: (a, b) => a.roas - b.roas, render: (v) => <span className="wb-num" style={{ fontWeight: 700 }}>{ratioText(v)}</span> },
    { title: 'ACOS', dataIndex: 'acos', align: 'right', width: 80, sorter: (a, b) => a.acos - b.acos, render: (v) => <span className="wb-num">{pctText(v, 1)}</span> },
    { title: '归因订单', dataIndex: 'orders', align: 'right', width: 85, sorter: (a, b) => a.orders - b.orders, render: (v) => <span className="wb-num">{intText(v)}</span> },
    { title: '点击次数', dataIndex: 'clicks', align: 'right', width: 85, render: (v) => <span className="wb-num">{intText(v)}</span> },
    { title: '展示次数', dataIndex: 'impressions', align: 'right', width: 95, render: (v) => <span className="wb-num">{intText(v)}</span> },
    { title: '点击率', dataIndex: 'ctr', align: 'right', width: 80, render: (v) => <span className="wb-num">{pctText(v, 2)}</span> },
    { title: '平均 CPC', dataIndex: 'cpc', align: 'right', width: 85, render: (v) => <span className="wb-num">{moneyText(v)}</span> },
    { title: '转化率(订单)', dataIndex: 'conversionRate', align: 'right', width: 100, render: (v) => <span className="wb-num">{pctText(v, 2)}</span> },
  ];

  const trendSvg = useMemo(() => {
    const rows = dailyData?.daily || [];
    if (!rows.length) return null;
    const width = 680;
    const height = 250;
    const left = 54;
    const right = 92;
    const top = 16;
    const bottom = 30;
    const plotW = width - left - right;
    const plotH = height - top - bottom;
    const maxSales = Math.max(...rows.map((r) => numVal(r.revenue)), 1) * 1.12;
    const maxSpend = Math.max(...rows.map((r) => numVal(r.spend)), 1) * 1.12;
    const maxUnits = Math.max(...rows.map((r) => numVal(r.orders)), 1) * 1.12;
    const xFor = (i: number) => left + (rows.length === 1 ? plotW / 2 : (i * plotW) / (rows.length - 1));
    const ySales = (v: number) => top + plotH - (v / maxSales) * plotH;
    const ySpend = (v: number) => top + plotH - (v / maxSpend) * plotH;
    const yUnits = (v: number) => top + plotH - (v / maxUnits) * plotH;

    const linePath = (pts: Array<{ x: number; y: number }>) =>
      pts.map((pt, i) => `${i ? 'L' : 'M'}${pt.x.toFixed(1)},${pt.y.toFixed(1)}`).join(' ');

    const salesPts = rows.map((r, i) => ({ x: xFor(i), y: ySales(numVal(r.revenue)) }));
    const spendPts = rows.map((r, i) => ({ x: xFor(i), y: ySpend(numVal(r.spend)) }));
    const unitPts = rows.map((r, i) => ({ x: xFor(i), y: yUnits(numVal(r.orders)) }));
    const areaPath = `${linePath(salesPts)} L${salesPts[salesPts.length - 1].x.toFixed(1)},${
      top + plotH
    } L${salesPts[0].x.toFixed(1)},${top + plotH} Z`;
    const labelEvery = Math.max(1, Math.ceil(rows.length / 7));

    return (
      <div style={{ position: 'relative' }}>
        <svg viewBox={`0 0 ${width} ${height}`} style={{ width: '100%', height: 245, display: 'block' }}>
          {[0, 0.25, 0.5, 0.75, 1].map((t) => {
            const y = top + plotH * t;
            return (
              <g key={t}>
                <line x1={left} x2={left + plotW} y1={y} y2={y} stroke="rgba(16, 32, 58, 0.08)" />
                <text x={left - 8} y={y + 3} textAnchor="end" fontSize="10" fill="#667994">
                  ${Math.round(maxSales * (1 - t))}
                </text>
                <text x={left + plotW + 8} y={y + 3} textAnchor="start" fontSize="10" fill="#a9710b">
                  ${Math.round(maxSpend * (1 - t))}
                </text>
                <text x={left + plotW + 54} y={y + 3} textAnchor="start" fontSize="10" fill="#0b7d5f">
                  {Math.round(maxUnits * (1 - t))}
                </text>
              </g>
            );
          })}
          <path d={areaPath} fill="rgba(22, 104, 214, 0.08)" />
          <path d={linePath(salesPts)} fill="none" stroke="#1668d6" strokeWidth="2.2" />
          <path d={linePath(spendPts)} fill="none" stroke="#a9710b" strokeWidth="2" />
          <path d={linePath(unitPts)} fill="none" stroke="#0b7d5f" strokeWidth="2" />
          {rows.map((r, i) => (
            <g key={r.date}>
              {(i % labelEvery === 0 || i === rows.length - 1) && (
                <text x={salesPts[i].x} y={height - 8} textAnchor="middle" fontSize="10" fill="#667994">
                  {shortDate(r.date)}
                </text>
              )}
              <circle cx={salesPts[i].x} cy={salesPts[i].y} r={hoverTrendIdx === i ? 5 : 3.2} fill="#1668d6" />
              <circle cx={spendPts[i].x} cy={spendPts[i].y} r={hoverTrendIdx === i ? 5 : 3} fill="#a9710b" />
              <circle cx={unitPts[i].x} cy={unitPts[i].y} r={hoverTrendIdx === i ? 5 : 2.8} fill="#0b7d5f" />
              <rect
                x={salesPts[i].x - plotW / Math.max(1, rows.length) / 2}
                y={top}
                width={Math.max(12, plotW / Math.max(1, rows.length))}
                height={plotH}
                fill="transparent"
                onMouseEnter={() => setHoverTrendIdx(i)}
                onMouseLeave={() => setHoverTrendIdx(null)}
              />
            </g>
          ))}
        </svg>
        {hoverTrendIdx !== null && rows[hoverTrendIdx] ? (
          <div
            style={{
              position: 'absolute',
              top: 8,
              right: 12,
              padding: '8px 12px',
              borderRadius: 8,
              background: '#ffffff',
              border: '1px solid rgba(16, 32, 58, 0.14)',
              boxShadow: '0 8px 24px rgba(16, 32, 58, 0.12)',
              fontSize: 12,
              pointerEvents: 'none',
            }}
          >
            <strong className="wb-mono">{rows[hoverTrendIdx].date}</strong>
            <div style={{ color: '#1668d6' }}>销售额：{moneyText(rows[hoverTrendIdx].revenue)}</div>
            <div style={{ color: '#a9710b' }}>广告费：{moneyText(rows[hoverTrendIdx].spend)}</div>
            <div style={{ color: '#0b7d5f' }}>销量：{intText(rows[hoverTrendIdx].orders)} 件</div>
          </div>
        ) : null}
      </div>
    );
  }, [dailyData?.daily, hoverTrendIdx]);

  const scatterSvg = useMemo(() => {
    const pts = (adProducts?.products || []).filter((p) => p.spend > 0 && p.sales > 0);
    if (!pts.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有广告数据" />;
    const width = 560;
    const height = 250;
    const left = 54;
    const right = 18;
    const top = 16;
    const bottom = 32;
    const plotW = width - left - right;
    const plotH = height - top - bottom;
    const maxSpend = Math.max(...pts.map((p) => p.spend), 1) * 1.12;
    const maxSales = Math.max(...pts.map((p) => p.sales), 1) * 1.12;
    const maxOrders = Math.max(...pts.map((p) => p.orders), 1);

    return (
      <svg viewBox={`0 0 ${width} ${height}`} style={{ width: '100%', height: 245, display: 'block' }}>
        {[0, 0.5, 1].map((t) => {
          const y = top + plotH * t;
          const x = left + plotW * t;
          return (
            <g key={t}>
              <line x1={left} x2={left + plotW} y1={y} y2={y} stroke="rgba(16, 32, 58, 0.08)" />
              <text x={left - 8} y={y + 3} textAnchor="end" fontSize="10" fill="#667994">
                {moneyText(maxSales * (1 - t), 0)}
              </text>
              <line x1={x} x2={x} y1={top} y2={top + plotH} stroke="rgba(16, 32, 58, 0.08)" />
              <text x={x} y={height - 14} textAnchor="middle" fontSize="10" fill="#667994">
                {moneyText(maxSpend * t, 0)}
              </text>
            </g>
          );
        })}
        {pts.map((p) => {
          const cx = left + (p.spend / maxSpend) * plotW;
          const cy = top + plotH - (p.sales / maxSales) * plotH;
          const r = 4.5 + Math.sqrt(p.orders / maxOrders) * 11;
          return (
            <HeroTooltip
              key={p.style}
              content={`${p.style} · 广告费 ${moneyText(p.spend, 0)} · 归因销售 ${moneyText(
                p.sales,
                0,
              )} · 归因订单 ${intText(p.orders)} 单 · RoAS ${ratioText(p.roas)}`}
            >
              <circle
                cx={cx}
                cy={cy}
                r={r}
                fill="rgba(255, 149, 0, 0.5)"
                stroke="#d97706"
                strokeWidth="1.5"
                role="button"
                tabIndex={0}
                aria-label={`查看 ${p.style} 产品详情`}
                className="cursor-pointer focus-visible:stroke-[#0071e3] focus-visible:stroke-[3px]"
                onClick={() => workbenchBridge.openProductDashboard(p.style)}
                onKeyDown={(event) => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  if (!event.repeat) workbenchBridge.openProductDashboard(p.style);
                }}
              />
            </HeroTooltip>
          );
        })}
        <text x={left + plotW / 2} y={height - 2} textAnchor="middle" fontSize="10" fill="#667994">
          广告费 (USD)
        </text>
      </svg>
    );
  }, [adProducts?.products]);

  const reversedDaily = useMemo(() => [...(dailyData?.daily || [])].reverse(), [dailyData?.daily]);
  const topAdBars = useMemo(() => (adProducts?.products || []).slice(0, 15), [adProducts?.products]);
  const maxAdSales = Math.max(...topAdBars.map((p) => p.sales), 1);

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <PageHeaderCard
        eyebrow="DAILY SALES & AD ANALYTICS"
        title="每日销售数量与各款广告表现"
        description="查看全店每日销量、广告归因订单、各款销量矩阵和父 SKU 级广告表现。"
        accentColor={ACCENT}
        extra={
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <HeroButton
              color="primary"
              variant="flat"
              radius="full"
              size="sm"
              startContent={!loading ? <ReloadOutlined /> : undefined}
              isLoading={loading}
              onPress={() => void loadData()}
              className="font-semibold"
            >
              刷新数据
            </HeroButton>
          </div>
        }
      />

      <SyncStatusBanner
        tone={error ? 'danger' : loading && !dailyData ? 'loading' : 'success'}
        headline={
          error
            ? '读取失败'
            : dailyData
              ? `近 ${dailyData.days} 天经营概览`
              : '正在读取每日销售数量统计'
        }
        detail={
          error
            ? error
            : dailyData
              ? `窗口 ${dailyData.start} ~ ${dailyData.end}，共 ${dailyData.daily.length} 个有数据的日期；北京时间 16:00 前显示至昨日`
              : '请稍候。'
        }
        metaLabel="最新业务日"
        metaValue={dailyData?.end || '—'}
      />

      {error ? (
        <div style={{ marginBottom: 14 }}>
          <Alert type="error" showIcon message="数据加载失败" description={error} />
        </div>
      ) : null}

      <h3 className="wb-detail-section-title">经营概览</h3>
      <MetricCardGrid items={overviewMetrics} columns={4} accentColor={ACCENT} />

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="近期趋势">
        <HeroCardHeader className="wb-panel-header">
          <h3 className="wb-panel-title">近期趋势</h3>
          <Space size="small" wrap>
            <HeroChip size="sm" variant="dot" color="primary">销售额</HeroChip>
            <HeroChip size="sm" variant="dot" color="warning">广告投入</HeroChip>
            <HeroChip size="sm" variant="dot" color="success">销量（件）</HeroChip>
          </Space>
        </HeroCardHeader>
        <HeroCardBody className="p-4">
          {loading && !dailyData ? (
            <div style={{ padding: 64, textAlign: 'center' }}>
              <HeroSpinner color="primary" size="md" />
            </div>
          ) : trendSvg ? (
            trendSvg
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有趋势数据" />
          )}
        </HeroCardBody>
      </HeroCard>

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="每日明细">
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">每日明细</h3>
            <p className="wb-panel-subtitle">全店销量按件计，广告归因订单按单计</p>
          </div>
        </HeroCardHeader>
        <HeroCardBody style={{ padding: '0 12px 12px' }}>
          <Table<DailyRow>
            size="small"
            rowKey="date"
            columns={dailyColumns}
            dataSource={reversedDaily}
            loading={loading && !dailyData}
            pagination={{ pageSize: 20, showSizeChanger: false, size: 'small' }}
            scroll={{ x: 1300 }}
            locale={{ emptyText: '没有每日明细' }}
          />
        </HeroCardBody>
      </HeroCard>

      <h3 className="wb-detail-section-title">各款销量</h3>
      <MetricCardGrid items={styleMetrics} columns={4} accentColor={ACCENT} />

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="各款每日销量明细">
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">各款每日销量明细</h3>
            <p className="wb-panel-subtitle">
              行=日期，列=款号（按原始报表列序），含合计；自适应宽度
            </p>
          </div>
        </HeroCardHeader>
        <HeroCardBody style={{ padding: '0 12px 12px' }}>
          <Table<PivotTableRow>
            size="small"
            rowKey="key"
            columns={pivotColumns}
            dataSource={pivotModel.rows}
            loading={loading && !styleSales}
            pagination={false}
            scroll={{ x: 'max-content', y: 460 }}
            locale={{ emptyText: '没有各款销量数据' }}
            summary={() =>
              pivotModel.rows.length > 0 ? (
                <Table.Summary fixed>
                  <Table.Summary.Row>
                    <Table.Summary.Cell index={0}>
                      <strong>合计</strong>
                    </Table.Summary.Cell>
                    {pivotModel.orderedStyles.map((st, idx) => (
                      <Table.Summary.Cell key={st} index={idx + 1} align="right">
                        <strong className="wb-num">{intText(pivotModel.colTotals[st])}</strong>
                      </Table.Summary.Cell>
                    ))}
                    <Table.Summary.Cell index={pivotModel.orderedStyles.length + 1} align="right">
                      <strong className="wb-num" style={{ color: ACCENT }}>
                        {intText(pivotModel.grandTotal)}
                      </strong>
                    </Table.Summary.Cell>
                  </Table.Summary.Row>
                </Table.Summary>
              ) : null
            }
          />
        </HeroCardBody>
      </HeroCard>

      <h3 className="wb-detail-section-title">广告表现</h3>
      <MetricCardGrid items={adMetrics} columns={3} accentColor={ACCENT} />

      <div className="wb-dash-grid-2">
        <HeroCard shadow="sm" radius="lg" className="border border-black/[0.06] bg-white">
          <HeroCardHeader className="border-b border-black/[0.05] px-4 py-3 text-[14px] font-semibold text-[#1d1d1f]">
            产品归因销售排行（Top 15）
          </HeroCardHeader>
          <HeroCardBody className="p-4">
            {topAdBars.length > 0 ? (
              <div style={{ display: 'grid', gap: 8 }}>
                {topAdBars.map((p) => (
                  <div key={p.style}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                      <strong className="wb-mono">{p.style}</strong>
                      <span className="wb-num">
                        {moneyText(p.sales, 0)} · RoAS {ratioText(p.roas)}
                      </span>
                    </div>
                    <Progress
                      percent={Number(((p.sales / maxAdSales) * 100).toFixed(1))}
                      showInfo={false}
                      strokeColor={WORKBENCH_COLORS.productionGreen}
                      size="small"
                    />
                  </div>
                ))}
              </div>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有广告数据" />
            )}
          </HeroCardBody>
        </HeroCard>

        <HeroCard shadow="sm" radius="lg" className="border border-black/[0.06] bg-white">
          <HeroCardHeader className="border-b border-black/[0.05] px-4 py-3 text-[14px] font-semibold text-[#1d1d1f]">
            广告费与归因销售（气泡大小代表归因订单数）
          </HeroCardHeader>
          <HeroCardBody className="p-4">{scatterSvg}</HeroCardBody>
        </HeroCard>
      </div>

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="广告产品明细">
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">广告产品明细</h3>
            <p className="wb-panel-subtitle">
              {adDetailDay ? `最新一天（${adDetailDay}）· 按广告费降序` : '按广告费降序，父 SKU 级聚合'}
            </p>
          </div>
        </HeroCardHeader>
        <HeroCardBody style={{ padding: '0 12px 12px' }}>
          <Table<AdProductRow>
            size="small"
            rowKey="style"
            columns={adColumns}
            dataSource={adDetailSource?.products || []}
            loading={loading && !adProducts}
            pagination={{ pageSize: 20, showSizeChanger: false, size: 'small' }}
            scroll={{ x: 1180 }}
            locale={{ emptyText: '没有广告产品明细' }}
          />
        </HeroCardBody>
      </HeroCard>
    </ConfigProvider>
  );
};

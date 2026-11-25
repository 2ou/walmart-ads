import { HeroChip, MetricCardGrid, type MetricCardItem } from '../../components';
import type { DashboardMetric } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS } from '../../theme/workbenchTheme';

const DASHBOARD_METRICS = [
  { key: 'revenue', label: '总销售额' },
  { key: 'orders', label: '销售件数' },
  { key: 'spend', label: '广告费' },
  { key: 'roas', label: '广告 ROAS' },
];

const SALES_METRICS = [
  { key: 'units', label: '销售件数' },
  { key: 'revenue', label: '订单销售额' },
  { key: 'dailyUnits', label: '日均销量' },
  { key: 'averageUnitPrice', label: '成交均价' },
  { key: 'activeSkus', label: '动销 SKU' },
  { key: 'orders', label: '订单数' },
];

const PRODUCT_METRICS = [
  { key: 'parents', label: '产品组' },
  { key: 'variants', label: 'SKU 变体' },
  { key: 'clearance', label: '清货款' },
  { key: 'seasonal', label: '春夏 / 秋冬' },
  { key: 'allSeason', label: '四季款' },
];

const CHANGE_COLOR = {
  up: 'success',
  down: 'danger',
  flat: 'default',
  new: 'primary',
} as const;

const TONE_COLOR: Record<string, string | undefined> = {
  good: WORKBENCH_COLORS.productionGreen,
  alert: WORKBENCH_COLORS.signalRed,
  focus: WORKBENCH_COLORS.walmartBlue,
  warning: WORKBENCH_COLORS.signalOrange,
  default: undefined,
};

function toCardItem(metric: DashboardMetric, fallbackLabel?: string, accentKey?: string): MetricCardItem {
  const tone = metric.tone || 'default';
  return {
    key: metric.key,
    label: metric.label || fallbackLabel || metric.key,
    value: metric.value ?? '—',
    accent: tone === 'focus' || (accentKey ? metric.key === accentKey : false),
    valueColor: TONE_COLOR[tone],
    loading: false,
    clickable: metric.clickable,
    disabled: metric.disabled,
    dataAttributes: metric.dataAttributes,
    ariaLabel: metric.ariaLabel,
    ariaHaspopup: metric.ariaHaspopup,
    ariaControls: metric.ariaControls,
    actionLabel: metric.actionLabel,
    note: metric.change ? (
      <span className="wb-dashboard-metric-note">
        <span>{metric.note}</span>
        <HeroChip
          as="span"
          size="sm"
          variant="flat"
          color={CHANGE_COLOR[metric.change.cls] ?? 'default'}
        >
          {metric.change.arrow ? `${metric.change.arrow} ` : ''}
          {metric.change.text}
        </HeroChip>
      </span>
    ) : (
      metric.note
    ),
  };
}

function GenericMetricsIsland({
  ariaLabel,
  metrics,
  columns,
  skeletonCount,
  placeholders,
  accentKey,
}: {
  ariaLabel: string;
  metrics: DashboardMetric[] | null;
  columns: number;
  skeletonCount?: number;
  placeholders?: Array<{ key: string; label: string }>;
  accentKey?: string;
}) {
  const items: MetricCardItem[] = metrics === null
    ? (placeholders
        ? placeholders.map((p) => ({
            key: p.key,
            label: p.label,
            value: '—',
            accent: p.key === accentKey,
            loading: true,
          }))
        : Array.from({ length: skeletonCount ?? columns }, (_, idx) => ({
            key: `sk-${idx}`,
            label: '读取中',
            value: '—',
            loading: true,
          })))
    : metrics.map((m) => toCardItem(m, m.label, accentKey));

  return (
    <div aria-label={ariaLabel} aria-busy={metrics === null}>
      <MetricCardGrid columns={columns} items={items} />
    </div>
  );
}

export function DashboardMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  const items: MetricCardItem[] = DASHBOARD_METRICS.map(({ key, label }) => {
    const metric = metrics?.find((item) => item.key === key);
    if (!metric) {
      return {
        key,
        label,
        value: '—',
        accent: key === 'revenue',
        loading: metrics === null,
      };
    }
    return toCardItem(metric, label, 'revenue');
  });
  return (
    <div aria-label="经营指标" aria-busy={metrics === null}>
      <MetricCardGrid items={items} />
    </div>
  );
}

export function SalesMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="销量汇总指标"
      metrics={metrics}
      columns={6}
      placeholders={SALES_METRICS}
      accentKey="revenue"
    />
  );
}

export function ProductMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="产品库统计指标"
      metrics={metrics}
      columns={5}
      placeholders={PRODUCT_METRICS}
      accentKey="parents"
    />
  );
}

export function DiagnosisMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="广告诊断汇总指标"
      metrics={metrics}
      columns={6}
      skeletonCount={6}
    />
  );
}

export function ManualKeywordMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="手动关键词汇总指标"
      metrics={metrics}
      columns={6}
      skeletonCount={6}
    />
  );
}

export function LxAdMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="领星广告汇总指标"
      metrics={metrics}
      columns={4}
      skeletonCount={7}
    />
  );
}

export function LxAdQualityMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="广告数据质量指标"
      metrics={metrics}
      columns={4}
      skeletonCount={4}
    />
  );
}

export function InventoryMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="库存汇总指标"
      metrics={metrics}
      columns={4}
      skeletonCount={7}
    />
  );
}

export function SupplyChainMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="供应链生产数据概况"
      metrics={metrics}
      columns={6}
      skeletonCount={6}
    />
  );
}

export function ForecastMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="需求预测汇总指标"
      metrics={metrics}
      columns={6}
      skeletonCount={6}
    />
  );
}

export function DataCenterMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="数据中心概览指标"
      metrics={metrics}
      columns={4}
      skeletonCount={4}
    />
  );
}

export function ReturnMetrics({ metrics }: { metrics: DashboardMetric[] | null }) {
  return (
    <GenericMetricsIsland
      ariaLabel="退款率概览指标"
      metrics={metrics}
      columns={5}
      skeletonCount={5}
    />
  );
}

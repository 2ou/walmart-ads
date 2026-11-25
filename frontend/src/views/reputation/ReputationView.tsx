import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  ConfigProvider,
  Drawer,
  Empty,
  Input,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import type { TableProps } from 'antd';
import {
  BellOutlined,
  HistoryOutlined,
  ReloadOutlined,
  StarFilled,
  WarningOutlined,
} from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import {
  AppleSegmentedTabs,
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
import type { MetricCardItem, SyncBannerTone } from '../../components';
import { workbenchBridge, type ProductCatalogSummary } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';

const ACCENT = WORKBENCH_COLORS.walmartBlue;
const REP_LOW_REMINDER_KEY = 'rep_lowrating_reminder_v2';
const REP_LOW_LEGACY_SEEN_KEY = 'rep_lowrating_seen';
const REP_LOW_MAX_KNOWN_IDS = 2000;

const PAIN_HIGHLIGHT =
  /(\bsee[\s-]?through\b|\bsee\s+thru\b|\bsheer\b|\btoo\s+thin\b|\bcheap\b|\bpolyester\b|\bnot\s+rayon\b|\btoo\s+small\b|\bruns?\s+small\b|\btoo\s+large\b|\bruns?\s+large\b|\btoo\s+big\b|\bboxy\b|\btoo\s+tight\b|\btoo\s+hot\b|\bnever\s+(?:arrived|received)\b|\bwrong\s+(?:size|color|item)\b|\bshrink\w*\b|\bfad\w*\b|\bdefect\w*\b)/gi;

const REP_RISK_TEXT: Record<string, string> = {
  RISK: '高风险',
  WATCH: '需关注',
  HEALTHY: '健康',
  INSUFFICIENT: '样本不足',
  NONE: '暂无评价',
};

const REP_RISK_COLOR: Record<string, 'danger' | 'warning' | 'success' | 'default'> = {
  RISK: 'danger',
  WATCH: 'warning',
  HEALTHY: 'success',
  INSUFFICIENT: 'default',
  NONE: 'default',
};

const REP_CONFIDENCE_TEXT: Record<string, string> = {
  HIGH: '高置信',
  MEDIUM: '中置信',
  LOW: '低置信',
  NONE: '无文本样本',
};

const REP_TREND_TEXT: Record<string, string> = {
  WORSENING: '近期恶化',
  IMPROVING: '近期改善',
  STABLE: '近期稳定',
  INSUFFICIENT: '趋势样本不足',
};

const REP_GROUP_TEXT: Record<string, string> = {
  PRODUCT: '产品质量',
  SIZE: '尺码版型',
  EXPECTATION: '描述预期',
  COMFORT: '穿着体验',
  FULFILLMENT: '履约物流',
  FIT: '合身',
  STYLE: '外观风格',
  QUALITY: '质量',
  VALUE: '性价比',
  FUNCTION: '功能细节',
};

const REP_GROUP_ANT_COLOR: Record<string, string> = {
  PRODUCT: 'red',
  SIZE: 'orange',
  EXPECTATION: 'purple',
  COMFORT: 'gold',
  FULFILLMENT: 'blue',
  FIT: 'orange',
  STYLE: 'cyan',
  QUALITY: 'red',
  VALUE: 'green',
  FUNCTION: 'geekblue',
};

interface ReputationWindowStats {
  avgStar?: number;
  count?: number;
  lowRatingCount?: number;
}

interface ReputationOverview {
  platformReviewCount?: number;
  reportedReviewCount?: number;
  ratedStyleCount?: number;
  totalStyleCount?: number;
  platformAverageRating?: number;
  sampleCount?: number;
  sampleCoverageRate?: number;
  unmatchedCount?: number;
  lowRatingCount?: number;
  negativeCount?: number;
  criticalCount?: number;
  mixedCount?: number;
  lowRatingRate?: number;
  sampleAverageRating?: number;
  sampleConfidence?: string;
  windows?: Record<string, ReputationWindowStats>;
}

interface ReputationQuality {
  rawRowCount?: number;
  uniqueReviewCount?: number;
  collapsedCount?: number;
  anonymousCollapsedCount?: number;
  mappingRate?: number;
  analyzableCoverageRate?: number;
  sourceLocalDelta?: number;
  lastFullSnapshotCount?: number | null;
  lastFullSnapshotAt?: string | null;
}

interface MonthlyTrendPoint {
  month: string;
  count: number;
  avgStar: number;
  lowRatingRate?: number;
}

interface ThemePoint {
  label: string;
  hits: number;
  styleCount: number;
  group?: string;
  styles?: string[];
  samples?: string[];
  action?: string;
}

interface StyleStatRow {
  parentSku: string;
  platformReviewCount?: number;
  reportedReviewCount?: number;
  riskLevel?: string;
  riskScore?: number;
  displayAverageRating?: number;
  platformAverageRating?: number;
  sampleAverageRating?: number;
  sampleCount?: number;
  lowRatingRate?: number;
  sampleConfidence?: string;
  trend?: string;
  latestCommentDate?: string;
  fiveStar?: number;
  fourStar?: number;
  threeStar?: number;
  twoStar?: number;
  oneStar?: number;
}

interface ReviewItem {
  id?: number | string;
  commentId?: number | string;
  commentDate?: string;
  parentSku?: string | null;
  platformItemId?: string | null;
  star?: number;
  buyerName?: string | null;
  title?: string | null;
  content?: string | null;
  issueGroups?: string[];
  incentivized?: boolean;
}

interface ReputationDataPayload {
  overview?: ReputationOverview;
  sync?: {
    latestCommentDate?: string | null;
    lastLog?: {
      finishedAt?: string;
      status?: string;
      fetchedCount?: number;
      insertedCount?: number;
    } | null;
  };
  dataQuality?: ReputationQuality;
  monthlyTrend?: MonthlyTrendPoint[];
  positiveThemes?: ThemePoint[];
  issueThemes?: ThemePoint[];
  painPoints?: ThemePoint[];
  styles?: StyleStatRow[];
  recentNegatives?: ReviewItem[];
  lowRatingReviews?: ReviewItem[];
}

interface StyleDetailPayload {
  summary?: ReputationOverview;
  windows?: Record<string, ReputationWindowStats>;
  monthlyTrend?: MonthlyTrendPoint[];
  positiveThemes?: ThemePoint[];
  issueThemes?: ThemePoint[];
  painPoints?: ThemePoint[];
  negatives?: ReviewItem[];
}

interface SyncActionResult {
  status?: string;
  fetchedCount?: number;
  insertedCount?: number;
  updatedCount?: number;
  matchedSkuCount?: number;
  avgStar?: number;
  durationMs?: number;
}

interface ReminderState {
  version: number;
  initialized: boolean;
  knownIds: string[];
  unreadIds: string[];
}

const num = (v: unknown) => (Number.isFinite(Number(v)) ? Number(v) : 0);
const intText = (v: unknown) => Math.round(num(v)).toLocaleString('zh-CN');
const reviewTotal = (row?: { platformReviewCount?: number; reportedReviewCount?: number } | null) =>
  num(row?.platformReviewCount ?? row?.reportedReviewCount ?? 0);
const repRiskLevel = (row?: StyleStatRow | null) =>
  String(row?.riskLevel || (reviewTotal(row) > 0 ? 'INSUFFICIENT' : 'NONE')).toUpperCase();

function normalizeSearch(value: string): string {
  return String(value ?? '').normalize('NFKC').toLocaleLowerCase('zh-CN').trim();
}

function matchesQuery(query: string, ...fields: Array<unknown>): boolean {
  const q = normalizeSearch(query);
  if (!q) return true;
  const hay = normalizeSearch(fields.filter((x) => x !== null && x !== undefined).join(' '));
  return q.split(/\s+/).every((part) => hay.includes(part));
}

function repLowReviewKey(review: ReviewItem): string {
  const stableId = String(review.commentId ?? review.id ?? '').trim();
  if (stableId) return `id:${stableId}`;
  return `fallback:${[
    review.platformItemId,
    review.parentSku,
    review.commentDate,
    review.star,
    review.buyerName,
    review.title,
    review.content,
  ]
    .map((v) => String(v ?? '').trim())
    .join('|')}`;
}

function readReminderStorage(): ReminderState {
  try {
    const raw = localStorage.getItem(REP_LOW_REMINDER_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<ReminderState>;
      if (parsed.version === 2 && Array.isArray(parsed.knownIds) && Array.isArray(parsed.unreadIds)) {
        return {
          version: 2,
          initialized: true,
          knownIds: parsed.knownIds,
          unreadIds: parsed.unreadIds,
        };
      }
    }
  } catch {
    // fallback below
  }
  return { version: 2, initialized: false, knownIds: [], unreadIds: [] };
}

function saveReminderStorage(next: ReminderState): void {
  try {
    localStorage.setItem(REP_LOW_REMINDER_KEY, JSON.stringify(next));
  } catch {
    // ignore storage quota errors
  }
}

function syncReminderWithReviews(reviews: ReviewItem[]): ReminderState {
  const currentKeys = reviews.map(repLowReviewKey);
  const reminder = readReminderStorage();
  if (!reminder.initialized) {
    let legacySeenDate: string | null = null;
    try {
      legacySeenDate = localStorage.getItem(REP_LOW_LEGACY_SEEN_KEY);
    } catch {
      legacySeenDate = null;
    }
    reminder.knownIds = currentKeys.slice(0, REP_LOW_MAX_KNOWN_IDS);
    reminder.unreadIds = legacySeenDate
      ? reviews
          .filter((review) => String(review.commentDate || '') > legacySeenDate!)
          .map(repLowReviewKey)
      : [];
    reminder.initialized = true;
  } else {
    const known = new Set(reminder.knownIds);
    const unread = new Set(reminder.unreadIds);
    currentKeys.forEach((key) => {
      if (!known.has(key)) unread.add(key);
    });
    reminder.knownIds = [...new Set([...currentKeys, ...reminder.knownIds])].slice(
      0,
      REP_LOW_MAX_KNOWN_IDS,
    );
    const current = new Set(currentKeys);
    reminder.unreadIds = [...unread].filter((key) => current.has(key));
  }
  saveReminderStorage(reminder);
  return reminder;
}

function renderHighlightedPain(text?: string | null): React.ReactNode {
  const raw = String(text || '');
  if (!raw) return '—';
  const parts = raw.split(PAIN_HIGHLIGHT);
  return parts.map((part, idx) =>
    idx % 2 === 1 ? (
      <mark key={idx} className="wb-rep-highlight">
        {part}
      </mark>
    ) : (
      <React.Fragment key={idx}>{part}</React.Fragment>
    ),
  );
}

const StarBadge: React.FC<{ star?: number }> = ({ star }) => {
  const value = Math.max(0, Math.min(5, Math.round(num(star))));
  const color = value >= 4 ? 'success' : value === 3 ? 'warning' : 'danger';
  return (
    <HeroChip as="span" size="sm" variant="flat" color={color} className="wb-mono">
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
        <StarFilled style={{ fontSize: 11 }} />
        {value} ★
      </span>
    </HeroChip>
  );
};

const ReputationTrendChart: React.FC<{
  points: MonthlyTrendPoint[];
  width?: number;
  height?: number;
}> = ({ points, width = 760, height = 210 }) => {
  if (!points.length) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description="暂无可成图的月度评价样本"
        style={{ padding: '24px 0' }}
      />
    );
  }
  const left = 36;
  const right = 42;
  const top = 14;
  const bottom = 26;
  const plotW = width - left - right;
  const plotH = height - top - bottom;
  const maxCount = Math.max(...points.map((p) => num(p.count)), 1) * 1.15;
  const minAvg = 3.5;
  const maxAvg = 5;
  const band = plotW / points.length;
  const xCenter = (index: number) => left + band * (index + 0.5);
  const barW = Math.min(20, band * 0.54);
  const yCount = (value: number) => top + plotH - (num(value) / maxCount) * plotH;
  const yAvg = (value: number) => top + plotH - ((num(value) - minAvg) / (maxAvg - minAvg)) * plotH;
  const yNeg = (value: number) => top + plotH - (num(value) / 100) * plotH;

  const avgPath = points
    .map((p, i) => `${i ? 'L' : 'M'}${xCenter(i).toFixed(1)},${yAvg(p.avgStar).toFixed(1)}`)
    .join(' ');
  const negPath = points
    .map(
      (p, i) =>
        `${i ? 'L' : 'M'}${xCenter(i).toFixed(1)},${yNeg(p.lowRatingRate || 0).toFixed(1)}`,
    )
    .join(' ');
  const labelEvery = Math.max(1, Math.ceil(points.length / 7));

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="wb-rep-trend-svg"
      role="img"
      aria-label="月度独立评价量、样本均分与低满意率趋势"
    >
      {[0, 0.5, 1].map((t) => {
        const y = top + plotH * t;
        return (
          <g key={t}>
            <line
              x1={left}
              x2={left + plotW}
              y1={y}
              y2={y}
              stroke="rgba(0,0,0,0.06)"
              strokeDasharray="3 3"
            />
            <text
              x={left - 7}
              y={y + 3}
              textAnchor="end"
              fill="#626267"
              fontSize="10"
            >
              {intText(maxCount * (1 - t))}
            </text>
            <text
              x={left + plotW + 8}
              y={y + 3}
              textAnchor="start"
              fill="#626267"
              fontSize="10"
            >
              {(maxAvg - (maxAvg - minAvg) * t).toFixed(1)}★
            </text>
          </g>
        );
      })}
      {points.map((point, index) => {
        const y = yCount(point.count);
        const h = Math.max(2, top + plotH - y);
        return (
          <rect
            key={point.month}
            x={xCenter(index) - barW / 2}
            y={y}
            width={barW}
            height={h}
            rx={4}
            fill="rgba(0, 113, 227, 0.2)"
          >
            <title>
              {`${point.month}：${intText(point.count)} 条独立评价，均分 ${num(point.avgStar).toFixed(2)}★，≤3 星率 ${num(point.lowRatingRate || 0).toFixed(1)}%`}
            </title>
          </rect>
        );
      })}
      <path d={negPath} fill="none" stroke="#ff3b30" strokeWidth="1.8" strokeDasharray="4 4" />
      <path d={avgPath} fill="none" stroke="#ff9500" strokeWidth="2.2" />
      {points.map((point, index) => (
        <circle
          key={`${point.month}-dot`}
          cx={xCenter(index)}
          cy={yAvg(point.avgStar)}
          r={3.2}
          fill="#ff9500"
          stroke="#ffffff"
          strokeWidth="1.2"
        >
          <title>{`${point.month} 均分 ${num(point.avgStar).toFixed(2)}★`}</title>
        </circle>
      ))}
      {points.map((point, index) =>
        index % labelEvery === 0 || index === points.length - 1 ? (
          <text
            key={`${point.month}-lbl`}
            x={xCenter(index)}
            y={height - 6}
            textAnchor="middle"
            fill="#626267"
            fontSize="10"
          >
            {point.month}
          </text>
        ) : null,
      )}
    </svg>
  );
};

let pendingLoadCount = 0;
let pendingOpenLowCount = 0;
const loadListeners = new Set<() => void>();
const lowModalListeners = new Set<() => void>();

export function triggerReputationLoad(): void {
  if (loadListeners.size === 0) {
    pendingLoadCount += 1;
    return;
  }
  loadListeners.forEach((fn) => fn());
}

export function triggerReputationLowModal(): void {
  if (lowModalListeners.size === 0) {
    pendingOpenLowCount += 1;
    return;
  }
  lowModalListeners.forEach((fn) => fn());
}

export const ReputationView: React.FC = () => {
  const [data, setData] = useState<ReputationDataPayload | null>(null);
  const [products, setProducts] = useState<ProductCatalogSummary[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>('');
  const [syncingAction, setSyncingAction] = useState<'sync' | 'backfill' | null>(null);
  const [syncActionNote, setSyncActionNote] = useState<{ text: string; isError?: boolean } | null>(
    null,
  );
  const [unreadIds, setUnreadIds] = useState<string[]>(() => readReminderStorage().unreadIds);

  // Wall controls
  const [wallFilter, setWallFilter] = useState<string>('all');
  const [wallSearch, setWallSearch] = useState<string>('');
  const [wallSort, setWallSort] = useState<string>('score');

  // Negative list controls
  const [listSearch, setListSearch] = useState<string>('');
  const [starFilter, setStarFilter] = useState<string>('0');
  const [issueFilter, setIssueFilter] = useState<string>('ALL');
  const [listPage, setListPage] = useState<number>(1);
  const [listPageSize, setListPageSize] = useState<number>(20);

  // Style drawer state
  const [drawerSku, setDrawerSku] = useState<string | null>(null);
  const [drawerLoading, setDrawerLoading] = useState<boolean>(false);
  const [drawerDetail, setDrawerDetail] = useState<StyleDetailPayload | null>(null);
  const [drawerError, setDrawerError] = useState<string>('');
  const [drawerStarFilter, setDrawerStarFilter] = useState<string>('0');
  const [drawerSearch, setDrawerSearch] = useState<string>('');

  // Low-rating reminder modal/drawer
  const [lowModalOpen, setLowModalOpen] = useState<boolean>(false);
  const [lowSearch, setLowSearch] = useState<string>('');

  const mountedRef = useRef(true);

  const pushReminderBridgeSync = useCallback(
    (repData: ReputationDataPayload | null, unreadList: string[]) => {
      const reviews = repData?.lowRatingReviews || [];
      const unreadSet = new Set(unreadList);
      const unreadReviews = reviews.filter((r) => unreadSet.has(repLowReviewKey(r)));
      const criticalCount = unreadReviews.filter((r) => num(r.star) <= 2).length;
      workbenchBridge.syncReputationState({
        data: repData,
        unreadCount: unreadReviews.length,
        criticalCount,
      });
    },
    [],
  );

  const loadData = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const payload = await workbenchBridge.apiFetch<ReputationDataPayload>('/api/comments/data');
      if (!mountedRef.current) return;
      const reminder = syncReminderWithReviews(payload.lowRatingReviews || []);
      setData(payload);
      setUnreadIds(reminder.unreadIds);
      pushReminderBridgeSync(payload, reminder.unreadIds);
      setLoading(false);

      workbenchBridge
        .getProductCatalog()
        .then((list) => {
          if (mountedRef.current && Array.isArray(list)) {
            setProducts(list);
          }
        })
        .catch(() => {});
    } catch (err) {
      if (!mountedRef.current) return;
      setError(err instanceof Error ? err.message : '口碑数据读取失败');
      setLoading(false);
    }
  }, [pushReminderBridgeSync]);

  useEffect(() => {
    mountedRef.current = true;
    loadListeners.add(loadData);
    const openLow = () => {
      setLowSearch('');
      setLowModalOpen(true);
    };
    lowModalListeners.add(openLow);

    // Preload at mount so sidebar low-rating reminder badge lights up automatically.
    loadData();
    if (pendingLoadCount > 0) {
      pendingLoadCount = 0;
    }
    if (pendingOpenLowCount > 0) {
      pendingOpenLowCount = 0;
      openLow();
    }
    return () => {
      mountedRef.current = false;
      loadListeners.delete(loadData);
      lowModalListeners.delete(openLow);
    };
  }, [loadData]);

  const handleSyncAction = async (kind: 'sync' | 'backfill') => {
    if (syncingAction) return;
    setSyncingAction(kind);
    setSyncActionNote({
      text: kind === 'sync' ? '正在同步最新评价…' : '正在全历史对账…',
    });
    try {
      const url = kind === 'sync' ? '/api/comments/sync' : '/api/comments/backfill';
      const res = await workbenchBridge.apiFetch<SyncActionResult>(url, { method: 'POST' });
      const msg = `${res.status === 'SUCCESS' ? '完成' : '失败'}：拉取 ${intText(
        res.fetchedCount,
      )} 条，新增 ${intText(res.insertedCount)}，更新 ${intText(res.updatedCount)}，归款 ${intText(
        res.matchedSkuCount,
      )}，均分 ${num(res.avgStar).toFixed(2)}，耗时 ${intText(res.durationMs)}ms`;
      setSyncActionNote({ text: msg, isError: res.status !== 'SUCCESS' });
      workbenchBridge.showToast(kind === 'sync' ? '评价同步完成' : '全历史对账完成');
      await loadData();
    } catch (err) {
      const message = err instanceof Error ? err.message : '同步失败';
      setSyncActionNote({ text: `同步失败：${message}`, isError: true });
      workbenchBridge.showToast(`同步失败：${message}`);
    } finally {
      if (mountedRef.current) setSyncingAction(null);
    }
  };

  const openStyleDrawer = useCallback(async (parentSku: string) => {
    if (!parentSku) return;
    setDrawerSku(parentSku);
    setDrawerStarFilter('0');
    setDrawerSearch('');
    setDrawerDetail(null);
    setDrawerError('');
    setDrawerLoading(true);
    try {
      const detail = await workbenchBridge.apiFetch<StyleDetailPayload>(
        `/api/comments/style/${encodeURIComponent(parentSku)}`,
      );
      setDrawerDetail(detail);
    } catch (err) {
      setDrawerError(err instanceof Error ? err.message : '该款口碑读取失败');
    } finally {
      setDrawerLoading(false);
    }
  }, []);

  const markLowSeen = useCallback(
    (reviewKey?: string) => {
      const reminder = readReminderStorage();
      const before = reminder.unreadIds.length;
      reminder.unreadIds = reviewKey
        ? reminder.unreadIds.filter((k) => k !== reviewKey)
        : [];
      saveReminderStorage(reminder);
      setUnreadIds(reminder.unreadIds);
      pushReminderBridgeSync(data, reminder.unreadIds);
      const cleared = Math.max(0, before - reminder.unreadIds.length);
      if (cleared > 0) {
        workbenchBridge.showToast(
          reviewKey ? '已将该评价标记为已查看。' : `已将 ${cleared} 条评价标记为已查看。`,
        );
      }
    },
    [data, pushReminderBridgeSync],
  );

  const overview = data?.overview || {};
  const quality = data?.dataQuality || {};
  const sync = data?.sync || {};
  const lastLog = sync.lastLog || null;
  const unreadSet = useMemo(() => new Set(unreadIds), [unreadIds]);
  const lowReviews = data?.lowRatingReviews || [];
  const unreadReviews = useMemo(
    () => lowReviews.filter((r) => unreadSet.has(repLowReviewKey(r))),
    [lowReviews, unreadSet],
  );
  const criticalUnreadCount = useMemo(
    () => unreadReviews.filter((r) => num(r.star) <= 2).length,
    [unreadReviews],
  );

  const productIndex = useMemo(() => {
    const map = new Map<string, ProductCatalogSummary>();
    products.forEach((p) => {
      if (p.parentSku) map.set(String(p.parentSku).toUpperCase(), p);
    });
    return map;
  }, [products]);

  const bannerTone: SyncBannerTone = error
    ? 'danger'
    : loading
      ? 'loading'
      : lastLog?.finishedAt
        ? 'success'
        : 'warning';

  const bannerHeadline = error
    ? '评价数据读取失败'
    : loading
      ? '正在读取评价数据'
      : '评价数据已就绪';

  const bannerDetail = error
    ? error
    : syncActionNote?.text ||
      (lastLog?.finishedAt
        ? `最近任务 ${new Date(lastLog.finishedAt).toLocaleString('zh-CN')} · ${
            lastLog.status || ''
          } · 拉取 ${intText(lastLog.fetchedCount || 0)} 条，新增 ${intText(
            lastLog.insertedCount || 0,
          )} 条`
        : '尚无同步记录，请执行同步或全历史对账。');

  const kpiItems: MetricCardItem[] = useMemo(() => {
    const w30 = overview.windows?.['30'] || {};
    const lowCount = intText(overview.lowRatingCount ?? overview.negativeCount ?? 0);
    return [
      {
        key: 'platform-total',
        label: '平台累计评价',
        value: intText(reviewTotal(overview)),
        accent: true,
        loading,
        note: `${intText(overview.ratedStyleCount || 0)} / ${intText(
          overview.totalStyleCount || 0,
        )} 款有平台评价`,
      },
      {
        key: 'platform-avg',
        label: '平台平均星级',
        value: `${num(overview.platformAverageRating || 0).toFixed(2)} ★`,
        loading,
        note: '按各款平台评价量加权',
      },
      {
        key: 'sample-count',
        label: '可分析独立样本',
        value: intText(overview.sampleCount || 0),
        loading,
        note: `覆盖 ${num(overview.sampleCoverageRate || 0).toFixed(1)}% · 未归款 ${intText(
          overview.unmatchedCount || 0,
        )}`,
      },
      {
        key: 'low-rating',
        label: '样本 ≤3 星',
        value: lowCount,
        valueColor: WORKBENCH_COLORS.signalRed,
        loading,
        note: (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
            <span>
              {`${intText(overview.criticalCount || 0)} 条 1–2 星 · ${intText(
                overview.mixedCount || 0,
              )} 条 3 星 · 占样本 ${num(overview.lowRatingRate || 0).toFixed(1)}%`}
            </span>
            <HeroButton
              size="sm"
              variant="flat"
              color={unreadReviews.length > 0 ? 'danger' : 'primary'}
              className="h-6 min-w-0 px-2 text-[11px] font-semibold"
              onPress={() => {
                setLowSearch('');
                setLowModalOpen(true);
              }}
            >
              {unreadReviews.length > 0 ? `新 ${unreadReviews.length} 条待查看` : '查看提醒'}
            </HeroButton>
          </div>
        ),
      },
      {
        key: 'w30-avg',
        label: '近 30 天样本均分',
        value: `${num(w30.avgStar || 0).toFixed(2)} ★`,
        loading,
        note: w30.count
          ? `${intText(w30.count)} 条独立评价 · ${intText(w30.lowRatingCount || 0)} 条 ≤3 星`
          : '近 30 天暂无新增',
      },
    ];
  }, [overview, loading, unreadReviews.length]);

  const qualityChips = useMemo(() => {
    const delta = num(quality.sourceLocalDelta || 0);
    const items: Array<{
      label: string;
      value: string;
      hint?: string;
      color?: 'success' | 'warning';
    }> = [
      { label: '源明细', value: `${intText(quality.rawRowCount || 0)} 条` },
      { label: '独立样本', value: `${intText(quality.uniqueReviewCount || 0)} 条` },
      {
        label: '跨变体折叠',
        value: `${intText(quality.collapsedCount || 0)} 条`,
        hint: `含匿名复制 ${intText(quality.anonymousCollapsedCount || 0)} 条`,
      },
      {
        label: '归款率',
        value: `${num(quality.mappingRate || 0).toFixed(1)}%`,
        color: num(quality.mappingRate) >= 99 ? 'success' : 'warning',
      },
      {
        label: '文本覆盖',
        value: `${num(quality.analyzableCoverageRate || 0).toFixed(1)}%`,
        hint: '独立样本 / 平台累计',
      },
    ];
    if (quality.lastFullSnapshotCount != null) {
      items.push({
        label: '全历史对账',
        value: delta === 0 ? '已对齐' : `差异 ${Math.abs(delta)} 条`,
        color: delta === 0 ? 'success' : 'warning',
        hint: quality.lastFullSnapshotAt
          ? new Date(quality.lastFullSnapshotAt).toLocaleString('zh-CN')
          : '',
      });
    }
    return items;
  }, [quality]);

  // Style Risk Wall
  const styleRows = useMemo(() => {
    const raw = (data?.styles || []).map((stat) => ({
      stat,
      risk: repRiskLevel(stat),
      product: productIndex.get(String(stat.parentSku || '').toUpperCase()) || null,
    }));
    const counts = { all: raw.length, RISK: 0, WATCH: 0, HEALTHY: 0, INSUFFICIENT: 0, NONE: 0 };
    raw.forEach((row) => {
      if (row.risk in counts) {
        counts[row.risk as keyof typeof counts] += 1;
      }
    });
    let filtered = wallFilter === 'all' ? raw.slice() : raw.filter((r) => r.risk === wallFilter);
    if (wallSearch.trim()) {
      filtered = filtered.filter((r) =>
        matchesQuery(wallSearch, r.stat.parentSku, r.product?.title),
      );
    }
    filtered.sort((a, b) => {
      if (wallSort === 'avg') {
        return num(a.stat.displayAverageRating || 0) - num(b.stat.displayAverageRating || 0);
      }
      if (wallSort === 'risk') {
        return num(b.stat.lowRatingRate) - num(a.stat.lowRatingRate);
      }
      if (wallSort === 'recent') {
        return String(b.stat.latestCommentDate || '').localeCompare(
          String(a.stat.latestCommentDate || ''),
        );
      }
      if (wallSort === 'count') {
        return reviewTotal(b.stat) - reviewTotal(a.stat);
      }
      return num(b.stat.riskScore) - num(a.stat.riskScore);
    });
    return { filtered, counts };
  }, [data?.styles, productIndex, wallFilter, wallSearch, wallSort]);

  // Pain Themes Table
  const painRows = data?.issueThemes || data?.painPoints || [];
  const painColumns: TableProps<ThemePoint>['columns'] = [
    {
      title: '问题主题',
      dataIndex: 'label',
      key: 'label',
      width: 170,
      render: (value: string, record) => (
        <Space size={6}>
          <span
            className="wb-rep-pain-dot"
            style={{
              background: record.styleCount < 2 ? '#8e8e93' : WORKBENCH_COLORS.signalRed,
            }}
          />
          <strong style={{ color: '#1d1d1f', fontSize: 12 }}>{value}</strong>
        </Space>
      ),
    },
    {
      title: '归因',
      dataIndex: 'group',
      key: 'group',
      width: 110,
      render: (group: string) => (
        <Tag color={REP_GROUP_ANT_COLOR[group] || 'default'}>
          {REP_GROUP_TEXT[group] || group || '其他'}
        </Tag>
      ),
    },
    {
      title: '命中条数',
      dataIndex: 'hits',
      key: 'hits',
      width: 90,
      align: 'right',
      render: (v: number) => <span className="wb-num">{intText(v)}</span>,
    },
    {
      title: '涉及款数',
      dataIndex: 'styleCount',
      key: 'styleCount',
      width: 90,
      align: 'right',
      render: (v: number) => <span className="wb-num">{intText(v)}</span>,
    },
    {
      title: '涉及款号',
      dataIndex: 'styles',
      key: 'styles',
      width: 210,
      render: (styles?: string[]) => (
        <Space size={[4, 4]} wrap>
          {(styles || []).map((sku) => (
            <HeroButton
              key={sku}
              size="sm"
              variant="flat"
              color="primary"
              className="wb-mono h-6 min-w-0 px-2 text-[11px]"
              onPress={() => openStyleDrawer(sku)}
            >
              {sku}
            </HeroButton>
          ))}
        </Space>
      ),
    },
    {
      title: '代表性原文',
      dataIndex: 'samples',
      key: 'samples',
      render: (samples?: string[]) =>
        samples?.length ? (
          <div style={{ display: 'grid', gap: 4, fontSize: 12 }}>
            {samples.map((s, idx) => (
              <div key={idx} style={{ color: '#3a3a3c', lineHeight: 1.45 }}>
                <span style={{ color: ACCENT, fontWeight: 700, marginRight: 5 }}>›</span>
                {renderHighlightedPain(s)}
              </div>
            ))}
          </div>
        ) : (
          <span style={{ color: '#86868b' }}>—</span>
        ),
    },
    {
      title: '建议动作',
      dataIndex: 'action',
      key: 'action',
      width: 220,
      render: (action?: string) => (
        <span style={{ color: '#515154', fontSize: 12 }}>{action || '—'}</span>
      ),
    },
  ];

  // Recent Negatives Evidence Table
  const issueFilterOptions = useMemo(() => {
    const groups = [...new Set(painRows.map((t) => t.group).filter(Boolean))] as string[];
    return [
      { label: '全部问题类型', value: 'ALL' },
      ...groups.map((g) => ({ label: REP_GROUP_TEXT[g] || g, value: g })),
    ];
  }, [painRows]);

  const filteredNegatives = useMemo(() => {
    let rows = data?.recentNegatives || [];
    if (starFilter === 'critical') rows = rows.filter((r) => num(r.star) <= 2);
    else if (starFilter === '3') rows = rows.filter((r) => num(r.star) === 3);
    if (issueFilter !== 'ALL') {
      rows = rows.filter((r) => (r.issueGroups || []).includes(issueFilter));
    }
    if (listSearch.trim()) {
      rows = rows.filter((r) =>
        matchesQuery(listSearch, r.content, r.title, r.parentSku, r.buyerName, r.platformItemId),
      );
    }
    return rows;
  }, [data?.recentNegatives, starFilter, issueFilter, listSearch]);

  const negativeColumns: TableProps<ReviewItem>['columns'] = [
    {
      title: '日期',
      dataIndex: 'commentDate',
      key: 'commentDate',
      width: 112,
      render: (v?: string) => <span className="wb-mono">{v || '—'}</span>,
    },
    {
      title: '款号',
      dataIndex: 'parentSku',
      key: 'parentSku',
      width: 145,
      render: (sku?: string | null, rec?: ReviewItem) => (
        <SkuLinkTag
          sku={rec?.platformItemId || sku || '未归款'}
          parentSku={sku || undefined}
          unmatchedParentLabel="未归款"
        />
      ),
    },
    {
      title: '星级',
      dataIndex: 'star',
      key: 'star',
      width: 96,
      align: 'right',
      render: (star?: number) => <StarBadge star={star} />,
    },
    {
      title: '问题归因',
      dataIndex: 'issueGroups',
      key: 'issueGroups',
      width: 185,
      render: (groups?: string[], rec?: ReviewItem) => (
        <Space size={[4, 4]} wrap>
          {(groups || []).length ? (
            groups!.map((g) => (
              <Tag key={g} color={REP_GROUP_ANT_COLOR[g] || 'default'}>
                {REP_GROUP_TEXT[g] || g}
              </Tag>
            ))
          ) : (
            <span style={{ color: '#86868b', fontSize: 11 }}>未归类</span>
          )}
          {rec?.incentivized ? <Tag color="gold">激励测评</Tag> : null}
        </Space>
      ),
    },
    {
      title: '评价内容',
      dataIndex: 'content',
      key: 'content',
      render: (_: unknown, rec: ReviewItem) => (
        <div style={{ lineHeight: 1.5, color: '#1d1d1f' }}>
          {rec.title ? (
            <div style={{ fontWeight: 650, marginBottom: 2 }}>{renderHighlightedPain(rec.title)}</div>
          ) : null}
          <div style={{ color: '#3a3a3c' }}>{renderHighlightedPain(rec.content)}</div>
        </div>
      ),
    },
    {
      title: '买家',
      dataIndex: 'buyerName',
      key: 'buyerName',
      width: 120,
      render: (v?: string | null) => <span style={{ color: '#626267' }}>{v || '—'}</span>,
    },
  ];

  // Low-rating reminder drawer list
  const sortedLowReviews = useMemo(() => {
    const rows = lowReviews
      .slice()
      .sort((a, b) => {
        const na = unreadSet.has(repLowReviewKey(a)) ? 1 : 0;
        const nb = unreadSet.has(repLowReviewKey(b)) ? 1 : 0;
        if (na !== nb) return nb - na;
        return String(b.commentDate || '').localeCompare(String(a.commentDate || ''));
      })
      .filter((r) =>
        matchesQuery(lowSearch, r.content, r.title, r.parentSku, r.buyerName, r.platformItemId),
      );
    return rows;
  }, [lowReviews, unreadSet, lowSearch]);

  const lowColumns: TableProps<ReviewItem>['columns'] = [
    {
      title: '日期',
      dataIndex: 'commentDate',
      key: 'commentDate',
      width: 108,
      render: (v?: string) => <span className="wb-mono">{v || '—'}</span>,
    },
    {
      title: '款号',
      dataIndex: 'parentSku',
      key: 'parentSku',
      width: 120,
      render: (sku?: string | null) =>
        sku ? (
          <HeroButton
            size="sm"
            variant="light"
            color="primary"
            className="wb-mono h-6 min-w-0 px-1.5 text-[11px] font-semibold"
            onPress={() => {
              setLowModalOpen(false);
              openStyleDrawer(sku);
            }}
          >
            {sku}
          </HeroButton>
        ) : (
          <span style={{ color: '#86868b', fontSize: 11 }}>未归款</span>
        ),
    },
    {
      title: '星级',
      dataIndex: 'star',
      key: 'star',
      width: 90,
      align: 'right',
      render: (star?: number) => <StarBadge star={star} />,
    },
    {
      title: '买家',
      dataIndex: 'buyerName',
      key: 'buyerName',
      width: 110,
      render: (v?: string | null) => <span style={{ color: '#626267' }}>{v || '—'}</span>,
    },
    {
      title: '评价内容',
      dataIndex: 'content',
      key: 'content',
      render: (_: unknown, rec: ReviewItem) => (
        <div style={{ lineHeight: 1.5 }}>
          {rec.title ? (
            <div style={{ fontWeight: 650, marginBottom: 2 }}>{renderHighlightedPain(rec.title)}</div>
          ) : null}
          <div>{renderHighlightedPain(rec.content)}</div>
        </div>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 132,
      render: (_: unknown, rec: ReviewItem) => {
        const key = repLowReviewKey(rec);
        const isUnread = unreadSet.has(key);
        return isUnread ? (
          <HeroButton
            size="sm"
            variant="flat"
            color="warning"
            className="h-7 px-2.5 text-[11px] font-semibold"
            onPress={() => markLowSeen(key)}
          >
            待查看 · 标为已读
          </HeroButton>
        ) : (
          <HeroChip as="span" size="sm" variant="flat" color="default">
            已查看
          </HeroChip>
        );
      },
    },
  ];

  // Style Drilldown Drawer computed items
  const drawerProduct = drawerSku ? productIndex.get(drawerSku.toUpperCase()) || null : null;
  const drawerSummary = drawerDetail?.summary || {};
  const drawerKpis: MetricCardItem[] = useMemo(() => {
    const w30 = drawerDetail?.windows?.['30'] || {};
    return [
      {
        key: 'd-plat',
        label: '平台平均星级',
        value: `${num(drawerSummary.platformAverageRating || 0).toFixed(2)} ★`,
        note: `${intText(reviewTotal(drawerSummary))} 条平台累计`,
      },
      {
        key: 'd-sample',
        label: '独立文字样本',
        value: intText(drawerSummary.sampleCount || 0),
        note: `覆盖 ${num(drawerSummary.sampleCoverageRate || 0).toFixed(1)}% · ${
          REP_CONFIDENCE_TEXT[drawerSummary.sampleConfidence || ''] || '—'
        }`,
      },
      {
        key: 'd-critical',
        label: '严重 1–2 星',
        value: intText(drawerSummary.criticalCount || 0),
        valueColor: WORKBENCH_COLORS.signalRed,
        note: `${intText(drawerSummary.mixedCount || 0)} 条 3 星预警`,
      },
      {
        key: 'd-lowrate',
        label: '样本 ≤3 星率',
        value: `${num(drawerSummary.lowRatingRate || 0).toFixed(1)}%`,
        accent: true,
        note: `分母 ${intText(drawerSummary.sampleCount || 0)} 条独立样本`,
      },
      {
        key: 'd-w30',
        label: '近 30 天样本均分',
        value: `${num(w30.avgStar || 0).toFixed(2)} ★`,
        note: w30.count ? `${intText(w30.count)} 条独立评价` : '无新增评价',
      },
    ];
  }, [drawerSummary, drawerDetail?.windows]);

  const drawerNegatives = useMemo(() => {
    let rows = drawerDetail?.negatives || [];
    if (drawerStarFilter === 'critical') rows = rows.filter((r) => num(r.star) <= 2);
    else if (drawerStarFilter === '3') rows = rows.filter((r) => num(r.star) === 3);
    if (drawerSearch.trim()) {
      rows = rows.filter((r) =>
        matchesQuery(drawerSearch, r.content, r.title, r.buyerName, r.platformItemId),
      );
    }
    return rows;
  }, [drawerDetail?.negatives, drawerStarFilter, drawerSearch]);

  const monthlyTrend = data?.monthlyTrend || [];
  const recentMonths = monthlyTrend.slice(-12);
  const lastMonth = recentMonths[recentMonths.length - 1];

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <div className="wb-rep-root">
        <PageHeaderCard
          eyebrow="WALMART REPUTATION & REVIEW INTELLIGENCE"
          title="Walmart 评价口碑 · 数据可信度与款级风险"
          description="平台累计水位与可读评价样本分开计算；先确认数据覆盖，再判断具体产品、尺码或履约问题。"
          accentColor={ACCENT}
          extra={
            <div className="wb-rep-header-actions">
              <div className="wb-rep-header-chips">
                <HeroChip size="sm" variant="flat" color="primary" className="wb-mono font-semibold">
                  平台累计 {intText(reviewTotal(overview))} 条评价
                </HeroChip>
                {sync.latestCommentDate ? (
                  <HeroChip size="sm" variant="flat" color="default" className="wb-mono">
                    最新评价 {sync.latestCommentDate}
                  </HeroChip>
                ) : null}
              </div>
              <Space wrap size={8}>
                <HeroButton
                  size="sm"
                  color="primary"
                  variant="flat"
                  startContent={<ReloadOutlined />}
                  isLoading={syncingAction === 'sync'}
                  isDisabled={syncingAction !== null}
                  onPress={() => handleSyncAction('sync')}
                >
                  {syncingAction === 'sync' ? '正在同步' : '同步最新评价'}
                </HeroButton>
                <HeroButton
                  size="sm"
                  color="default"
                  variant="bordered"
                  startContent={<HistoryOutlined />}
                  isLoading={syncingAction === 'backfill'}
                  isDisabled={syncingAction !== null}
                  onPress={() => handleSyncAction('backfill')}
                >
                  {syncingAction === 'backfill' ? '正在回填' : '全历史对账'}
                </HeroButton>
                <HeroButton
                  size="sm"
                  color={unreadReviews.length > 0 ? 'danger' : 'default'}
                  variant={unreadReviews.length > 0 ? 'flat' : 'bordered'}
                  startContent={<BellOutlined />}
                  onPress={() => {
                    setLowSearch('');
                    setLowModalOpen(true);
                  }}
                >
                  ≤3 星提醒{unreadReviews.length > 0 ? ` (${unreadReviews.length})` : ''}
                </HeroButton>
              </Space>
            </div>
          }
        />

        <SyncStatusBanner
          tone={syncActionNote?.isError ? 'danger' : bannerTone}
          headline={bannerHeadline}
          detail={bannerDetail}
          metaLabel="评价覆盖"
          metaValue={sync.latestCommentDate || '暂无评价'}
        />

        {/* 数据质量标签条 */}
        <HeroCard shadow="sm" radius="lg" className="wb-panel mb-3.5 border border-black/[0.06] bg-white">
          <HeroCardBody className="flex flex-row flex-wrap items-center gap-2 px-4 py-3">
            <span className="mr-1 text-[11px] font-semibold text-[#626267]">数据质量核对</span>
            {qualityChips.map((chip) => (
              <HeroTooltip key={chip.label} content={chip.hint || chip.label} isDisabled={!chip.hint}>
                <HeroChip
                  size="sm"
                  variant="flat"
                  color={chip.color || 'default'}
                  className="wb-mono"
                >
                  <span style={{ opacity: 0.78, marginRight: 5 }}>{chip.label}</span>
                  <strong>{chip.value}</strong>
                </HeroChip>
              </HeroTooltip>
            ))}
          </HeroCardBody>
        </HeroCard>

        <MetricCardGrid items={kpiItems} columns={5} accentColor={ACCENT} />

        {/* 口碑趋势 + 正向口碑资产 */}
        <div className="wb-rep-insight-grid">
          <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white">
            <HeroCardHeader className="wb-panel-header">
              <div>
                <h2 className="wb-panel-title">口碑趋势</h2>
                <p className="wb-panel-subtitle">
                  {lastMonth
                    ? `${lastMonth.month}：${intText(lastMonth.count)} 条独立评价 · 样本均分 ${num(
                        lastMonth.avgStar,
                      ).toFixed(2)}★ · ≤3 星率 ${num(lastMonth.lowRatingRate || 0).toFixed(1)}%`
                    : '按可读独立评价计算，不与平台累计数混用'}
                </p>
              </div>
              <Space size={10}>
                <HeroChip size="sm" variant="dot" color="primary">
                  评价量
                </HeroChip>
                <HeroChip size="sm" variant="dot" color="warning">
                  样本均分
                </HeroChip>
                <HeroChip size="sm" variant="dot" color="danger">
                  ≤3 星率
                </HeroChip>
              </Space>
            </HeroCardHeader>
            <HeroCardBody className="p-4">
              {loading ? (
                <div style={{ padding: '44px 0', textAlign: 'center' }}>
                  <HeroSpinner size="md" color="primary" />
                </div>
              ) : (
                <ReputationTrendChart points={recentMonths} width={760} height={210} />
              )}
            </HeroCardBody>
          </HeroCard>

          <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white">
            <HeroCardHeader className="wb-panel-header">
              <div>
                <h2 className="wb-panel-title">正向口碑资产</h2>
                <p className="wb-panel-subtitle">从 ≥4 星原文提炼，可用于商品文案和广告卖点</p>
              </div>
            </HeroCardHeader>
            <HeroCardBody className="p-4">
              {(data?.positiveThemes || []).length ? (
                <div className="wb-rep-positive-list">
                  {(data?.positiveThemes || []).slice(0, 8).map((theme, idx) => (
                    <div key={theme.label} className="wb-rep-positive-item">
                      <span className="wb-rep-positive-rank">{idx + 1}</span>
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ fontWeight: 650, color: '#1d1d1f', fontSize: 12 }}>
                          {theme.label}
                        </div>
                        <div style={{ fontSize: 11, color: '#626267' }}>
                          {intText(theme.hits)} 次 · {intText(theme.styleCount)} 款
                        </div>
                      </div>
                      {theme.action ? (
                        <HeroChip size="sm" variant="flat" color="success">
                          {theme.action}
                        </HeroChip>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="需要更多 ≥4 星文字评价"
                />
              )}
            </HeroCardBody>
          </HeroCard>
        </div>

        {/* 款级风险队列 */}
        <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white">
          <HeroCardHeader className="wb-panel-header">
            <div>
              <h2 className="wb-panel-title">款级风险队列</h2>
              <p className="wb-panel-subtitle">
                {`${styleRows.counts.all} 款 · 高风险 ${styleRows.counts.RISK} · 需关注 ${styleRows.counts.WATCH} · 健康 ${styleRows.counts.HEALTHY} · 样本不足 ${styleRows.counts.INSUFFICIENT} · 暂无评价 ${styleRows.counts.NONE}`}
              </p>
            </div>
            <Space wrap size={8}>
              <Input.Search
                allowClear
                placeholder="搜索款号或商品名"
                value={wallSearch}
                onChange={(e) => setWallSearch(e.target.value)}
                style={{ width: 210 }}
              />
              <Select
                value={wallSort}
                onChange={(val) => setWallSort(val)}
                style={{ width: 175 }}
                options={[
                  { value: 'score', label: '按风险优先' },
                  { value: 'count', label: '按平台评价量' },
                  { value: 'avg', label: '按平台均分（低 → 高）' },
                  { value: 'risk', label: '按样本 ≤3 星率' },
                  { value: 'recent', label: '按最近评价' },
                ]}
              />
            </Space>
          </HeroCardHeader>
          <div className="wb-toolbar">
            <AppleSegmentedTabs
              ariaLabel="风险队列筛选"
              value={wallFilter}
              onChange={(val) => setWallFilter(val)}
              options={[
                { label: `全部 (${styleRows.counts.all})`, value: 'all' },
                { label: `高风险 (${styleRows.counts.RISK})`, value: 'RISK' },
                { label: `需关注 (${styleRows.counts.WATCH})`, value: 'WATCH' },
                { label: `健康 (${styleRows.counts.HEALTHY})`, value: 'HEALTHY' },
                { label: `样本不足 (${styleRows.counts.INSUFFICIENT})`, value: 'INSUFFICIENT' },
                { label: `暂无 (${styleRows.counts.NONE})`, value: 'NONE' },
              ]}
            />
          </div>
          <HeroCardBody className="p-4">
            {styleRows.filtered.length ? (
              <div className="wb-rep-card-grid">
                {styleRows.filtered.map(({ stat, risk, product }) => {
                  const total = reviewTotal(stat);
                  const rating =
                    stat.displayAverageRating ??
                    stat.platformAverageRating ??
                    stat.sampleAverageRating ??
                    0;
                  const confidence =
                    REP_CONFIDENCE_TEXT[String(stat.sampleConfidence || 'NONE')] || '样本未知';
                  const trend =
                    REP_TREND_TEXT[String(stat.trend || 'INSUFFICIENT')] || '趋势未知';
                  return (
                    <button
                      key={stat.parentSku}
                      type="button"
                      className={`wb-rep-style-card${risk === 'NONE' ? ' is-empty' : ''}`}
                      title={product?.title || '点击查看该款评价下钻详情'}
                      onClick={() => openStyleDrawer(stat.parentSku)}
                    >
                      <div className="wb-rep-style-top">
                        <span className="wb-mono wb-rep-style-code">{stat.parentSku}</span>
                        <HeroChip
                          as="span"
                          size="sm"
                          variant="flat"
                          color={REP_RISK_COLOR[risk] || 'default'}
                        >
                          {REP_RISK_TEXT[risk] || risk}
                        </HeroChip>
                      </div>
                      <div className="wb-rep-style-score">
                        <strong className="wb-num">
                          {risk === 'NONE' ? '—' : num(rating).toFixed(2)}
                        </strong>
                        {risk !== 'NONE' ? (
                          <>
                            <span style={{ color: '#ff9500', fontSize: 13 }}>★</span>
                            <span className="wb-rep-style-plat">平台 {intText(total)} 条</span>
                          </>
                        ) : null}
                      </div>
                      <div className="wb-rep-star-track" aria-hidden="true">
                        {risk === 'NONE' ? (
                          <span className="wb-rep-star-seg is-blank" />
                        ) : (
                          [
                            { key: '5', val: num(stat.fiveStar), cls: 'is-s5' },
                            { key: '4', val: num(stat.fourStar), cls: 'is-s4' },
                            { key: '3', val: num(stat.threeStar), cls: 'is-s3' },
                            { key: '2', val: num(stat.twoStar), cls: 'is-s2' },
                            { key: '1', val: num(stat.oneStar), cls: 'is-s1' },
                          ].map((seg) =>
                            seg.val > 0 ? (
                              <span
                                key={seg.key}
                                className={`wb-rep-star-seg ${seg.cls}`}
                                style={{ flex: seg.val }}
                                title={`${seg.key} 星 ${seg.val} 条`}
                              />
                            ) : null,
                          )
                        )}
                      </div>
                      <div className="wb-rep-style-foot">
                        <span>
                          {risk === 'NONE'
                            ? '平台累计与可读样本均为 0'
                            : `样本 ${intText(stat.sampleCount)} · ≤3星 ${num(
                                stat.lowRatingRate,
                              ).toFixed(1)}%`}
                        </span>
                        {risk !== 'NONE' ? (
                          <span>
                            {confidence} · {trend}
                          </span>
                        ) : null}
                      </div>
                    </button>
                  );
                })}
              </div>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该筛选条件下没有款" />
            )}
          </HeroCardBody>
        </HeroCard>

        {/* 问题主题与责任归因 */}
        <HeroCard shadow="sm" radius="lg" className="wb-panel wb-table-panel border border-black/[0.06] bg-white">
          <HeroCardHeader className="wb-panel-header">
            <div>
              <h2 className="wb-panel-title">问题主题与责任归因</h2>
              <p className="wb-panel-subtitle">
                仅扫描 ≤3 星样本；区分产品、尺码、预期和履约问题，跨款问题优先处理
              </p>
            </div>
            <HeroChip size="sm" variant="flat" color="default" className="wb-mono">
              {painRows.length} 类问题
            </HeroChip>
          </HeroCardHeader>
          <div className="wb-table-panel-body">
            <Table<ThemePoint>
              size="small"
              rowKey={(r) => `${r.label}-${r.group || ''}`}
              loading={loading}
              columns={painColumns}
              dataSource={painRows}
              pagination={false}
              scroll={{ x: 'max-content' }}
              locale={{ emptyText: '≤3 星文字样本未命中问题规则' }}
            />
          </div>
        </HeroCard>

        {/* 低满意评价证据 */}
        <HeroCard shadow="sm" radius="lg" className="wb-panel wb-table-panel border border-black/[0.06] bg-white">
          <HeroCardHeader className="wb-panel-header">
            <div>
              <h2 className="wb-panel-title">低满意评价证据</h2>
              <p className="wb-panel-subtitle">
                {`共 ${filteredNegatives.length} 条独立样本 · 1–2 星为严重问题，3 星为中性预警`}
              </p>
            </div>
            <Space wrap size={8}>
              <Input.Search
                allowClear
                placeholder="搜索评价内容、款号或买家名"
                value={listSearch}
                onChange={(e) => {
                  setListSearch(e.target.value);
                  setListPage(1);
                }}
                style={{ width: 220 }}
              />
              <Select
                value={issueFilter}
                onChange={(val) => {
                  setIssueFilter(val);
                  setListPage(1);
                }}
                options={issueFilterOptions}
                style={{ width: 145 }}
              />
            </Space>
          </HeroCardHeader>
          <div className="wb-toolbar">
            <AppleSegmentedTabs
              ariaLabel="星级筛选"
              value={starFilter}
              onChange={(val) => {
                setStarFilter(val);
                setListPage(1);
              }}
              options={[
                { label: '全部 ≤3 星', value: '0' },
                { label: '严重 1–2 星', value: 'critical' },
                { label: '中性预警 3 星', value: '3' },
              ]}
            />
          </div>
          <div className="wb-table-panel-body">
            <Table<ReviewItem>
              size="small"
              rowKey={(r) => repLowReviewKey(r)}
              loading={loading}
              columns={negativeColumns}
              dataSource={filteredNegatives}
              scroll={{ x: 'max-content' }}
              pagination={{
                current: listPage,
                pageSize: listPageSize,
                total: filteredNegatives.length,
                showSizeChanger: true,
                pageSizeOptions: ['20', '50', '100'],
                showTotal: (t) => `共 ${t} 条`,
                onChange: (p, ps) => {
                  setListPage(p);
                  setListPageSize(ps);
                },
              }}
              locale={{ emptyText: '没有符合条件的低满意评价' }}
            />
          </div>
        </HeroCard>

        {/* 款级下钻 Drawer */}
        <Drawer
          open={drawerSku !== null}
          onClose={() => setDrawerSku(null)}
          width="min(760px, 100%)"
          className="wb-detail-drawer"
          title={
            <div>
              <span
                className="wb-mono"
                style={{
                  display: 'block',
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: '0.12em',
                  color: ACCENT,
                  marginBottom: 4,
                }}
              >
                STYLE REPUTATION DRILLDOWN
              </span>
              <div style={{ fontSize: 16, fontWeight: 700, color: '#1d1d1f' }}>
                {drawerSku || '—'} · 款级口碑下钻
              </div>
              <div style={{ fontSize: 12, fontWeight: 400, color: '#515154', marginTop: 2 }}>
                {drawerProduct?.title || '核对款内趋势、正向卖点与具体差评证据'}
              </div>
            </div>
          }
          extra={
            drawerSku ? (
              <HeroButton
                size="sm"
                color="primary"
                variant="flat"
                onPress={() => workbenchBridge.openOperationDialog('PARENT_SKU', drawerSku)}
              >
                记入运营记录
              </HeroButton>
            ) : null
          }
        >
          {drawerLoading ? (
            <div style={{ padding: '48px 0', textAlign: 'center' }}>
              <HeroSpinner size="md" color="primary" />
              <div style={{ marginTop: 10, color: '#626267', fontSize: 12 }}>
                正在读取该款口碑...
              </div>
            </div>
          ) : drawerError ? (
            <Alert type="error" showIcon message="该款口碑读取失败" description={drawerError} />
          ) : (
            <div style={{ display: 'grid', gap: 16 }}>
              {drawerProduct ? (
                <div className="wb-rep-drawer-product">
                  {drawerProduct.imageUrl ? (
                    <img
                      src={drawerProduct.imageUrl}
                      alt={drawerProduct.parentSku}
                      className="wb-rep-drawer-thumb"
                    />
                  ) : null}
                  <div style={{ minWidth: 0, flex: 1 }}>
                    <div style={{ fontWeight: 650, fontSize: 13, color: '#1d1d1f' }}>
                      {drawerProduct.title || drawerProduct.parentSku}
                    </div>
                    <div style={{ fontSize: 11, color: '#626267', marginTop: 4 }}>
                      {[
                        drawerProduct.price != null ? `在售 $${num(drawerProduct.price).toFixed(2)}` : null,
                        drawerProduct.variantCount ? `${intText(drawerProduct.variantCount)} 个变体` : null,
                        drawerProduct.colorCount ? `${intText(drawerProduct.colorCount)} 色` : null,
                        drawerProduct.sizeCount ? `${intText(drawerProduct.sizeCount)} 码` : null,
                      ]
                        .filter(Boolean)
                        .join(' · ') || '产品库中暂无规格详情'}
                    </div>
                  </div>
                </div>
              ) : null}

              <MetricCardGrid items={drawerKpis} columns={3} accentColor={ACCENT} />

              <div>
                <h4 className="wb-detail-section-title">款内月度趋势</h4>
                <ReputationTrendChart
                  points={drawerDetail?.monthlyTrend || []}
                  width={680}
                  height={176}
                />
              </div>

              <div>
                <h4 className="wb-detail-section-title">
                  正向口碑（{(drawerDetail?.positiveThemes || []).length} 类）
                </h4>
                {(drawerDetail?.positiveThemes || []).length ? (
                  <Space size={[6, 6]} wrap>
                    {(drawerDetail?.positiveThemes || []).map((t) => (
                      <HeroChip key={t.label} size="sm" variant="flat" color="success">
                        <strong>{t.label}</strong> · {intText(t.hits)} 次
                      </HeroChip>
                    ))}
                  </Space>
                ) : (
                  <span style={{ color: '#86868b', fontSize: 12 }}>暂无稳定正向主题。</span>
                )}
              </div>

              <div>
                <h4 className="wb-detail-section-title">
                  该款低满意评价证据（{drawerNegatives.length} 条）
                </h4>
                <Space wrap size={8} style={{ marginBottom: 10 }}>
                  <Input.Search
                    allowClear
                    placeholder="搜索评价内容或买家名"
                    value={drawerSearch}
                    onChange={(e) => setDrawerSearch(e.target.value)}
                    style={{ width: 220 }}
                  />
                  <AppleSegmentedTabs
                    ariaLabel="款内差评星级筛选"
                    value={drawerStarFilter}
                    onChange={(val) => setDrawerStarFilter(val)}
                    options={[
                      { label: '全部 ≤3 星', value: '0' },
                      { label: '严重 1–2 星', value: 'critical' },
                      { label: '3 星预警', value: '3' },
                    ]}
                  />
                </Space>
                {drawerNegatives.length ? (
                  <div style={{ display: 'grid', gap: 10 }}>
                    {drawerNegatives.map((row) => (
                      <div key={repLowReviewKey(row)} className="wb-rep-drawer-neg-item">
                        <div className="wb-rep-drawer-neg-head">
                          <StarBadge star={row.star} />
                          <span className="wb-mono" style={{ color: '#626267', fontSize: 11 }}>
                            {row.commentDate}
                          </span>
                          <span style={{ color: '#626267', fontSize: 11 }}>
                            {row.buyerName || '—'}
                          </span>
                          {(row.issueGroups || []).map((g) => (
                            <Tag key={g} color={REP_GROUP_ANT_COLOR[g] || 'default'}>
                              {REP_GROUP_TEXT[g] || g}
                            </Tag>
                          ))}
                        </div>
                        <div style={{ fontSize: 12, lineHeight: 1.55, color: '#1d1d1f' }}>
                          {row.title ? (
                            <strong style={{ display: 'block', marginBottom: 2 }}>
                              {renderHighlightedPain(row.title)}
                            </strong>
                          ) : null}
                          {renderHighlightedPain(row.content)}
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的差评" />
                )}
              </div>
            </div>
          )}
        </Drawer>

        {/* ≤3 星评价提醒 Drawer */}
        <Drawer
          open={lowModalOpen}
          onClose={() => setLowModalOpen(false)}
          width="min(840px, 100%)"
          className="wb-detail-drawer"
          title={
            <div>
              <span
                className="wb-mono"
                style={{
                  display: 'block',
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: '0.12em',
                  color: WORKBENCH_COLORS.signalRed,
                  marginBottom: 4,
                }}
              >
                LOW RATING ALERT
              </span>
              <div style={{ fontSize: 16, fontWeight: 700, color: '#1d1d1f' }}>
                ≤3 星评价提醒
              </div>
              <div style={{ fontSize: 12, fontWeight: 400, color: '#515154', marginTop: 2 }}>
                {`共 ${lowReviews.length} 条 ≤3 星 · ${unreadReviews.length} 条待查看 · 待查看评价优先置顶`}
              </div>
            </div>
          }
          extra={
            <HeroButton
              size="sm"
              color="primary"
              variant="flat"
              isDisabled={unreadReviews.length === 0}
              onPress={() => markLowSeen()}
            >
              {unreadReviews.length
                ? `全部标为已查看 (${unreadReviews.length})`
                : '全部已查看'}
            </HeroButton>
          }
        >
          <div style={{ display: 'grid', gap: 14 }}>
            <Alert
              type={
                criticalUnreadCount > 0
                  ? 'error'
                  : unreadReviews.length > 0
                    ? 'warning'
                    : 'info'
              }
              showIcon
              icon={<WarningOutlined />}
              message={
                unreadReviews.length > 0
                  ? `${unreadReviews.length} 条新评价等待查看`
                  : '所有新评价均已查看'
              }
              description={
                unreadReviews.length > 0
                  ? criticalUnreadCount > 0
                    ? `其中 ${criticalUnreadCount} 条为 1–2 星，建议优先核对产品质量、尺码与履约问题。`
                    : '当前待查看项均为 3 星评价，建议结合原文判断改进优先级。'
                  : '后续同步到新的 ≤3 星评价时，这里会重新出现提醒。'
              }
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10 }}>
              <Input.Search
                allowClear
                placeholder="搜索评价内容、款号或买家名"
                value={lowSearch}
                onChange={(e) => setLowSearch(e.target.value)}
                style={{ maxWidth: 320 }}
              />
              <span className="wb-mono" style={{ fontSize: 12, color: '#626267' }}>
                {sortedLowReviews.length} / {lowReviews.length} 条
              </span>
            </div>
            <Table<ReviewItem>
              size="small"
              rowKey={(r) => repLowReviewKey(r)}
              columns={lowColumns}
              dataSource={sortedLowReviews}
              pagination={{ pageSize: 20, showSizeChanger: false }}
              scroll={{ x: 'max-content' }}
              locale={{ emptyText: '暂无符合条件的 ≤3 星评价' }}
            />
          </div>
        </Drawer>
      </div>
    </ConfigProvider>
  );
};

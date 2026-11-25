import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Button,
  ConfigProvider,
  Empty,
  Input,
  Space,
  Table,
} from 'antd';
import type { TableProps } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
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
} from '../../components';
import type { MetricCardItem } from '../../components';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import type { SkuMatrixRow, SkuMatrixSnapshot } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';

const ACCENT = WORKBENCH_COLORS.walmartBlue;

type DecisionKey = SkuMatrixRow['decision'];
type FilterDecision = 'all' | DecisionKey;

const DECISION_META: Record<
  DecisionKey,
  {
    label: string;
    chipColor: 'danger' | 'success' | 'warning' | 'primary';
    bubbleColor: string;
    rank: number;
  }
> = {
  reduce: {
    label: '建议降耗',
    chipColor: 'danger',
    bubbleColor: WORKBENCH_COLORS.signalRed,
    rank: 0,
  },
  scale: {
    label: '建议放量',
    chipColor: 'success',
    bubbleColor: WORKBENCH_COLORS.productionGreen,
    rank: 1,
  },
  optimize: {
    label: '继续优化',
    chipColor: 'warning',
    bubbleColor: WORKBENCH_COLORS.signalOrange,
    rank: 2,
  },
  collect: {
    label: '数据不足',
    chipColor: 'primary',
    bubbleColor: WORKBENCH_COLORS.walmartBlue,
    rank: 3,
  },
};

let skuMatrixLoadHandler: (() => void) | null = null;
let pendingSkuMatrixLoad = false;

export function triggerSkuMatrixLoad(): void {
  if (skuMatrixLoadHandler) {
    pendingSkuMatrixLoad = false;
    skuMatrixLoadHandler();
  } else {
    pendingSkuMatrixLoad = true;
  }
}

export const SkuMatrixView: React.FC = () => {
  const [snapshot, setSnapshot] = useState<SkuMatrixSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [filterDecision, setFilterDecision] = useState<FilterDecision>('all');
  const [searchQuery, setSearchQuery] = useState('');

  const loadingRef = useRef(false);

  const loadData = useCallback(async () => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    setLoading(true);
    try {
      const next = await workbenchBridge.getSkuMatrixSnapshot();
      setSnapshot(next);
    } catch (err) {
      workbenchBridge.showToast(err instanceof Error ? err.message : '销量矩阵加载失败');
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    skuMatrixLoadHandler = () => {
      void loadData();
    };
    if (pendingSkuMatrixLoad) {
      pendingSkuMatrixLoad = false;
      void loadData();
    }
    return () => {
      skuMatrixLoadHandler = null;
    };
  }, [loadData]);

  const rows = snapshot?.rows || [];
  const thresholds = snapshot?.thresholds || { targetRoas: 5, floorRoas: 1.5 };

  const counts = useMemo(() => {
    const map: Record<DecisionKey, number> = { scale: 0, reduce: 0, optimize: 0, collect: 0 };
    rows.forEach((row) => {
      if (map[row.decision] !== undefined) map[row.decision] += 1;
    });
    return map;
  }, [rows]);

  const metrics: MetricCardItem[] = [
    {
      key: 'scale',
      label: '建议放量 (Scale)',
      value: counts.scale.toLocaleString('zh-CN'),
      note: `ROAS ≥ ${thresholds.targetRoas}x · 稳定出单`,
      accent: true,
      valueColor: WORKBENCH_COLORS.productionGreen,
      loading: loading && !snapshot,
    },
    {
      key: 'reduce',
      label: '建议降耗 (Reduce)',
      value: counts.reduce.toLocaleString('zh-CN'),
      note: `ROAS < ${thresholds.floorRoas}x · 优先控损`,
      valueColor: WORKBENCH_COLORS.signalRed,
      loading: loading && !snapshot,
    },
    {
      key: 'optimize',
      label: '继续优化 (Optimize)',
      value: counts.optimize.toLocaleString('zh-CN'),
      note: '样本充足待调优转化或竞价',
      valueColor: WORKBENCH_COLORS.signalOrange,
      loading: loading && !snapshot,
    },
    {
      key: 'collect',
      label: '数据积累 (Collect)',
      value: counts.collect.toLocaleString('zh-CN'),
      note: `当前活跃父 SKU 共 ${rows.length} 款`,
      loading: loading && !snapshot,
    },
  ];

  const bubbleGeometry = useMemo(() => {
    if (!rows.length) return { maxRevenue: 1, maxOrders: 1, maxRoas: 10, items: [] };
    const maxRevenue = Math.max(...rows.map((r) => r.revenue), 1);
    const maxOrders = Math.max(...rows.map((r) => r.orders), 1);
    const maxRoas = Math.max(
      thresholds.targetRoas * 2,
      ...rows.map((r) => Math.min(r.roas || 0, thresholds.targetRoas * 3)),
      1,
    );
    const items = rows.map((row) => {
      const clampedRoas = Math.min(row.roas || 0, maxRoas);
      const x = Math.max(3, Math.min(97, (clampedRoas / maxRoas) * 100));
      const y = Math.max(5, Math.min(94, (row.revenue / maxRevenue) * 100));
      const size = Math.round(22 + Math.sqrt(Math.max(0, row.orders) / maxOrders) * 28);
      return { row, x, y, size };
    });
    return { maxRevenue, maxOrders, maxRoas, items };
  }, [rows, thresholds.targetRoas]);

  const sortedFilteredRows = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return [...rows]
      .filter((row) => {
        if (filterDecision !== 'all' && row.decision !== filterDecision) return false;
        if (q && !row.sku.toLowerCase().includes(q)) return false;
        return true;
      })
      .sort(
        (a, b) =>
          DECISION_META[a.decision].rank - DECISION_META[b.decision].rank || b.spend - a.spend,
      );
  }, [rows, filterDecision, searchQuery]);

  const columns: TableProps<SkuMatrixRow>['columns'] = [
    {
      title: '款号',
      dataIndex: 'sku',
      key: 'sku',
      width: 170,
      render: (sku: string) => <SkuLinkTag sku={sku} parentSku={sku} />,
    },
    {
      title: '诊断建议',
      dataIndex: 'decision',
      key: 'decision',
      width: 115,
      render: (decision: DecisionKey) => {
        const meta = DECISION_META[decision] || DECISION_META.collect;
        return (
          <HeroChip size="sm" variant="flat" color={meta.chipColor}>
            {meta.label}
          </HeroChip>
        );
      },
    },
    {
      title: '销售额',
      dataIndex: 'revenue',
      key: 'revenue',
      align: 'right',
      width: 120,
      sorter: (a, b) => a.revenue - b.revenue,
      render: (val: number) => (
        <span className="wb-num">${Number(val || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</span>
      ),
    },
    {
      title: '广告花费',
      dataIndex: 'spend',
      key: 'spend',
      align: 'right',
      width: 120,
      sorter: (a, b) => a.spend - b.spend,
      render: (val: number) => (
        <span className="wb-num">${Number(val || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</span>
      ),
    },
    {
      title: '订单量',
      dataIndex: 'orders',
      key: 'orders',
      align: 'right',
      width: 95,
      sorter: (a, b) => a.orders - b.orders,
      render: (val: number) => <span className="wb-num">{Math.round(val || 0).toLocaleString('zh-CN')}</span>,
    },
    {
      title: 'ROAS',
      dataIndex: 'roas',
      key: 'roas',
      align: 'right',
      width: 100,
      sorter: (a, b) => a.roas - b.roas,
      render: (val: number) => (
        <span className="wb-num" style={{ fontWeight: 700 }}>
          {Number(val || 0).toFixed(2)}x
        </span>
      ),
    },
    {
      title: '操作',
      key: 'action',
      align: 'right',
      width: 110,
      render: (_, row) => (
        <Button
          type="link"
          size="small"
          icon={<PlusOutlined />}
          style={{ padding: 0 }}
          onClick={() =>
            workbenchBridge.openOperationDialog('PARENT_SKU', row.sku, {
              title: `${row.sku} 广告调整`,
            })
          }
        >
          记录操作
        </Button>
      ),
    },
  ];

  const targetPct = Math.min(95, (thresholds.targetRoas / bubbleGeometry.maxRoas) * 100);

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <PageHeaderCard
        eyebrow="SKU OPPORTUNITY MATRIX"
        title="看清增长款、低效款与自然流量强势款"
        description="横轴是重新计算的 ROAS，纵轴是销售额，圆点大小反映订单量。点击较少的 SKU 会标记为数据不足。"
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

      <MetricCardGrid items={metrics} columns={4} accentColor={ACCENT} />

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="SKU 效率四象限矩阵">
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">SKU 效率四象限矩阵</h3>
            <p className="wb-panel-subtitle">
              目标 ROAS {thresholds.targetRoas}x 分界线随 Agent 规则同步 · 点击气泡查看款号详情
            </p>
          </div>
          <Space size="small" wrap>
            <HeroChip size="sm" variant="flat" color="success">建议放量</HeroChip>
            <HeroChip size="sm" variant="flat" color="danger">建议降耗</HeroChip>
            <HeroChip size="sm" variant="flat" color="warning">继续优化</HeroChip>
            <HeroChip size="sm" variant="flat" color="primary">数据不足</HeroChip>
          </Space>
        </HeroCardHeader>
        <HeroCardBody style={{ padding: 16 }}>
          {loading && !snapshot ? (
            <div style={{ padding: '64px 0', textAlign: 'center' }}>
              <HeroSpinner color="primary" size="md" />
            </div>
          ) : !rows.length ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="没有 SKU 数据，请检查当前分析周期"
            />
          ) : (
            <div
              style={{
                position: 'relative',
                height: 360,
                borderRadius: 14,
                border: '1px solid rgba(0, 0, 0, 0.06)',
                background:
                  'linear-gradient(180deg, rgba(245, 245, 247, 0.72), rgba(255, 255, 255, 0.98))',
                overflow: 'hidden',
              }}
            >
              {/* Horizontal midpoint */}
              <div
                style={{
                  position: 'absolute',
                  left: 0,
                  right: 0,
                  top: '50%',
                  borderTop: '1px dashed rgba(0, 0, 0, 0.12)',
                  pointerEvents: 'none',
                }}
              />
              {/* Vertical targetRoas boundary */}
              <div
                style={{
                  position: 'absolute',
                  top: 0,
                  bottom: 0,
                  left: `${targetPct}%`,
                  borderLeft: '1px dashed rgba(36, 138, 61, 0.45)',
                  pointerEvents: 'none',
                }}
              />
              <span
                style={{
                  position: 'absolute',
                  right: 12,
                  top: 10,
                  fontSize: 11,
                  fontWeight: 600,
                  color: WORKBENCH_COLORS.productionGreen,
                }}
              >
                高销售 · 高效率
              </span>
              <span
                style={{
                  position: 'absolute',
                  left: 12,
                  top: 10,
                  fontSize: 11,
                  fontWeight: 600,
                  color: WORKBENCH_COLORS.signalOrange,
                }}
              >
                高销售 · 待优化
              </span>
              <span
                style={{
                  position: 'absolute',
                  left: 12,
                  bottom: 10,
                  fontSize: 11,
                  fontWeight: 600,
                  color: WORKBENCH_COLORS.signalRed,
                }}
              >
                低销售 · 低效率
              </span>
              <span
                style={{
                  position: 'absolute',
                  right: 12,
                  bottom: 10,
                  fontSize: 11,
                  fontWeight: 600,
                  color: WORKBENCH_COLORS.walmartBlue,
                }}
              >
                低销售 · 有潜力
              </span>

              {bubbleGeometry.items.map(({ row, x, y, size }) => {
                const meta = DECISION_META[row.decision] || DECISION_META.collect;
                const label = row.sku.match(/\d+$/)?.[0] ?? row.sku;
                const focusInset = `${size / 2 + 4}px`;
                return (
                  <HeroTooltip
                    key={row.sku}
                    content={`${row.sku} · 销售 $${row.revenue.toFixed(2)} · 花费 $${row.spend.toFixed(
                      2,
                    )} · ${row.orders} 单 · ROAS ${row.roas.toFixed(2)}x · ${meta.label}`}
                  >
                    <HeroButton
                      aria-label={`打开 ${row.sku} 款号详情`}
                      className="focus-visible:z-10 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#0071e3]"
                      isIconOnly
                      onPress={() => workbenchBridge.openProductDashboard(row.sku)}
                      style={{
                        position: 'absolute',
                        left: `clamp(${focusInset}, ${x}%, calc(100% - ${focusInset}))`,
                        bottom: `clamp(${focusInset}, ${y}%, calc(100% - ${focusInset}))`,
                        minWidth: 0,
                        width: size,
                        height: size,
                        transform: 'translate(-50%, 50%)',
                        borderRadius: '50%',
                        border: '2px solid rgba(255, 255, 255, 0.94)',
                        background: meta.bubbleColor,
                        color: '#ffffff',
                        fontSize: 10,
                        fontWeight: 650,
                        display: 'grid',
                        placeItems: 'center',
                        cursor: 'pointer',
                        boxShadow: '0 4px 12px rgba(0, 0, 0, 0.16)',
                        padding: 0,
                      }}
                    >
                      {size >= 38 && label.length <= 6 ? label : null}
                    </HeroButton>
                  </HeroTooltip>
                );
              })}
            </div>
          )}
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              marginTop: 8,
              color: '#86868b',
              fontSize: 11,
            }}
          >
            <span>纵轴：销售额 ($0 ~ ${bubbleGeometry.maxRevenue.toFixed(0)})</span>
            <span>横轴：ROAS (0x ~ {bubbleGeometry.maxRoas.toFixed(1)}x)</span>
          </div>
        </HeroCardBody>
      </HeroCard>

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="机会排序与行动清单">
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">机会排序与行动清单</h3>
            <p className="wb-panel-subtitle">
              优先显示建议降耗与放量款，支持按诊断结论筛选并直接发起运营记录
            </p>
          </div>
          <Space size="small" wrap>
            <AppleSegmentedTabs<FilterDecision>
              ariaLabel="诊断结论筛选"
              value={filterDecision}
              options={[
                { label: '全部', value: 'all' },
                { label: '建议降耗', value: 'reduce' },
                { label: '建议放量', value: 'scale' },
                { label: '继续优化', value: 'optimize' },
                { label: '数据不足', value: 'collect' },
              ]}
              onChange={(val) => setFilterDecision(val)}
            />
            <Input.Search
              aria-label="搜索款号"
              allowClear
              placeholder="搜索款号"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onSearch={(val) => setSearchQuery(val)}
              style={{ width: 190 }}
            />
          </Space>
        </HeroCardHeader>
        <HeroCardBody style={{ padding: '0 12px 12px' }}>
          <Table<SkuMatrixRow>
            size="small"
            rowKey="sku"
            columns={columns}
            dataSource={sortedFilteredRows}
            loading={loading}
            pagination={{ pageSize: 20, showSizeChanger: false, size: 'small' }}
            scroll={{ x: 860 }}
            locale={{
              emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前筛选条件下没有 SKU" />
              ),
            }}
          />
        </HeroCardBody>
      </HeroCard>
    </ConfigProvider>
  );
};

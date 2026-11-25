import React, { useMemo, useRef, useState } from 'react';
import { ConfigProvider, Input, InputNumber } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {
  HeroButton,
  HeroCard,
  HeroCardBody,
  HeroCardHeader,
  MetricCardGrid,
  PageHeaderCard,
} from '../../components';
import type { MetricCardItem } from '../../components';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';

const ACCENT = WORKBENCH_COLORS.walmartBlue;

interface ProfitInputs {
  skuLabel: string;
  price: number | null;
  refundRate: number | null;
  deliveryFee: number | null;
  purchaseCost: number | null;
  firstLeg: number | null;
  adCost: number | null;
  exchangeRate: number | null;
}

const INITIAL_INPUTS: ProfitInputs = {
  skuLabel: '',
  price: null,
  refundRate: null,
  deliveryFee: null,
  purchaseCost: null,
  firstLeg: null,
  adCost: null,
  exchangeRate: 7,
};

interface ProfitResult {
  profit: number;
  margin: number;
  commission: number;
  storage: number;
  exchangeRate: number;
}

interface CatalogSummaryItem {
  parentSku?: string | null;
  price?: number | null;
  deliveryFee?: number | null;
  manualRefundRate?: number | null;
  historicalRefundRate?: number | null;
  searchTerms?: string[] | null;
}

interface CatalogListResponse {
  products?: CatalogSummaryItem[];
}

interface ProductDetailResponse {
  refundRate?: number | null;
  colorGroups?: Array<{
    variants?: Array<{
      sku?: string | null;
      price?: number | null;
      deliveryFee?: number | null;
    }>;
  }>;
  profitAssumption?: {
    deliveryFee?: number | null;
    purchaseCost?: number | null;
    firstLeg?: number | null;
    manualRefundRate?: number | null;
    adCost?: number | null;
  };
}

function computeProfitEstimate(inputs: ProfitInputs): ProfitResult | null {
  if (
    inputs.price === null ||
    inputs.deliveryFee === null ||
    inputs.purchaseCost === null ||
    inputs.firstLeg === null
  ) {
    return null;
  }
  const b = Number(inputs.price);
  const f = Number(inputs.deliveryFee);
  const g = Number(inputs.purchaseCost);
  const h = Number(inputs.firstLeg);
  const c = 1; // 销量固定为 1，单件测算
  const i = inputs.adCost === null ? 0 : Number(inputs.adCost);
  const d = inputs.refundRate === null ? 0 : Number(inputs.refundRate);
  const q = inputs.exchangeRate === null ? 7 : Number(inputs.exchangeRate);
  const rate = b >= 20 ? 0.15 : b < 15 ? 0.05 : 0.1;
  const commission = b * rate;
  const storage = b * 0.03;
  const unitNet = b - b * d - f - commission - storage;
  const raw = unitNet * q * c - i * q - (g + h) * c + c * d * 0.7 * (g + h);
  const profit = Math.round(raw * 100) / 100;
  const revenue = b * c * q;
  const margin = revenue === 0 ? 0 : profit / revenue;
  return { profit, margin, commission, storage, exchangeRate: q };
}

export const ProfitEstimateView: React.FC = () => {
  const [inputs, setInputs] = useState<ProfitInputs>(INITIAL_INPUTS);
  const [autofilling, setAutofilling] = useState(false);

  const catalogCacheRef = useRef<CatalogListResponse | null>(null);
  const detailCacheRef = useRef<Map<string, ProductDetailResponse>>(new Map());
  const lastFilledSkuRef = useRef('');

  const result = useMemo(() => computeProfitEstimate(inputs), [inputs]);

  const handleAutofill = async (rawValue: string, force = false) => {
    const raw = rawValue.trim();
    if (!raw) return;
    if (!force && raw === lastFilledSkuRef.current) return;
    lastFilledSkuRef.current = raw;

    const upper = raw.toUpperCase();
    setAutofilling(true);
    try {
      if (!catalogCacheRef.current) {
        try {
          catalogCacheRef.current = await workbenchBridge.apiFetch<CatalogListResponse>(
            '/api/products/catalog',
          );
        } catch {
          catalogCacheRef.current = { products: [] };
        }
      }

      const catalogList = catalogCacheRef.current?.products || [];
      let matchedSummary = catalogList.find(
        (p) => String(p.parentSku || '').toUpperCase() === upper,
      );
      if (!matchedSummary) {
        matchedSummary = catalogList.find((p) =>
          (p.searchTerms || []).some((term) => String(term || '').toUpperCase() === upper),
        );
      }
      const parentSku =
        matchedSummary?.parentSku || (upper.includes('-') ? upper.split('-')[0] : upper);
      if (!parentSku) return;

      let detail = detailCacheRef.current.get(parentSku) ?? null;
      if (!detail) {
        try {
          detail = await workbenchBridge.apiFetch<ProductDetailResponse>(
            `/api/products/${encodeURIComponent(parentSku)}`,
          );
          if (detail) detailCacheRef.current.set(parentSku, detail);
        } catch {
          detail = null;
        }
      }

      if (!detail && !matchedSummary) {
        workbenchBridge.showToast(`未找到款号或 SKU「${raw}」的参数`);
        return;
      }

      const variants = (detail?.colorGroups || []).flatMap((group) => group.variants || []);
      const variant =
        variants.find((v) => String(v.sku || '').toUpperCase() === upper) || variants[0] || null;
      const assumption = detail?.profitAssumption || {};

      const pickNum = (val: number | null | undefined, fallback: number | null): number | null => {
        if (val === null || val === undefined) return fallback;
        const n = Number(val);
        return Number.isFinite(n) ? n : fallback;
      };

      setInputs((prev) => ({
        ...prev,
        price: pickNum(variant?.price ?? matchedSummary?.price, prev.price),
        deliveryFee: pickNum(
          assumption.deliveryFee ?? variant?.deliveryFee ?? matchedSummary?.deliveryFee,
          prev.deliveryFee,
        ),
        purchaseCost: pickNum(assumption.purchaseCost, prev.purchaseCost),
        firstLeg: pickNum(assumption.firstLeg, prev.firstLeg),
        refundRate: pickNum(
          detail?.refundRate ??
            assumption.manualRefundRate ??
            matchedSummary?.manualRefundRate ??
            matchedSummary?.historicalRefundRate,
          prev.refundRate,
        ),
        adCost: pickNum(assumption.adCost, prev.adCost),
      }));
    } finally {
      setAutofilling(false);
    }
  };

  const handleReset = () => {
    lastFilledSkuRef.current = '';
    setInputs({ ...INITIAL_INPUTS });
  };

  const metrics: MetricCardItem[] = [
    {
      key: 'commission',
      label: '佣金 (USD)',
      value: result
        ? `$${result.commission.toFixed(2)} (¥${(result.commission * result.exchangeRate).toFixed(2)})`
        : '—',
      note: '按售价档位：≥20→15% / 15~20→10% / <15→5%',
    },
    {
      key: 'storage',
      label: '仓储 (3%)',
      value: result
        ? `$${result.storage.toFixed(2)} (¥${(result.storage * result.exchangeRate).toFixed(2)})`
        : '—',
      note: '售价 × 3%',
    },
    {
      key: 'profit',
      label: '测试毛利 (CNY)',
      value: result ? `¥${result.profit.toFixed(2)}` : '—',
      note: '单件实测人民币毛利',
      accent: true,
      valueColor: result
        ? result.profit > 0
          ? WORKBENCH_COLORS.productionGreen
          : result.profit < 0
            ? WORKBENCH_COLORS.signalRed
            : undefined
        : undefined,
    },
    {
      key: 'margin',
      label: '毛利率',
      value: result ? `${(result.margin * 100).toFixed(2)}%` : '—',
      note: '毛利 ÷ 折算销售收入',
      accent: true,
      valueColor: result
        ? result.margin >= 0.15
          ? WORKBENCH_COLORS.productionGreen
          : result.margin >= 0
            ? WORKBENCH_COLORS.signalOrange
            : WORKBENCH_COLORS.signalRed
        : undefined,
    },
  ];

  const updateField = <K extends keyof ProfitInputs>(key: K, value: ProfitInputs[K]) => {
    setInputs((prev) => ({ ...prev, [key]: value }));
  };

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <PageHeaderCard
        eyebrow="PROFIT CALCULATOR"
        title="毛利估算计算器（工具）"
        description="实时测算单 SKU 毛利与毛利率。佣金按售价档位（≥20→15% / 15~20→10% / <15→5%），仓储 3%，退款成本按 0.7×采购头程计。汇率默认 7。"
        accentColor={ACCENT}
        extra={
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <HeroButton variant="flat" radius="full" size="sm" onPress={handleReset}>
              重置
            </HeroButton>
          </div>
        }
      />

      <MetricCardGrid items={metrics} columns={4} accentColor={ACCENT} />

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="毛利估算参数输入">
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">测算参数输入</h3>
            <p className="wb-panel-subtitle">输入参数即时测算，结果不保存</p>
          </div>
        </HeroCardHeader>
        <HeroCardBody style={{ padding: 16 }}>
          <div className="wb-form-grid-3">
            <div className="wb-filter-field">
              <span className="wb-filter-field-label">主SKU（可选）</span>
              <Input.Search
                allowClear
                placeholder="输入款号，回车或失焦自动带出参数"
                enterButton="带出参数"
                loading={autofilling}
                value={inputs.skuLabel}
                onChange={(e) => updateField('skuLabel', e.target.value)}
                onBlur={() => void handleAutofill(inputs.skuLabel, false)}
                onSearch={(val) => {
                  lastFilledSkuRef.current = '';
                  void handleAutofill(val, true);
                }}
              />
            </div>

            <div className="wb-filter-field">
              <span className="wb-filter-field-label">售价 (USD)</span>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                step={0.01}
                placeholder="必填"
                value={inputs.price}
                onChange={(val) => updateField('price', val)}
              />
            </div>

            <div className="wb-filter-field">
              <span className="wb-filter-field-label">销量（固定 1 · 单件测算）</span>
              <InputNumber style={{ width: '100%' }} value={1} disabled />
            </div>

            <div className="wb-filter-field">
              <span className="wb-filter-field-label">退款率 (如 0.08)</span>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                step={0.01}
                placeholder="默认 0"
                value={inputs.refundRate}
                onChange={(val) => updateField('refundRate', val)}
              />
            </div>

            <div className="wb-filter-field">
              <span className="wb-filter-field-label">配送费 (USD)</span>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                step={0.01}
                placeholder="必填"
                value={inputs.deliveryFee}
                onChange={(val) => updateField('deliveryFee', val)}
              />
            </div>

            <div className="wb-filter-field">
              <span className="wb-filter-field-label">采购成本 (CNY)</span>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                step={0.01}
                placeholder="必填"
                value={inputs.purchaseCost}
                onChange={(val) => updateField('purchaseCost', val)}
              />
            </div>

            <div className="wb-filter-field">
              <span className="wb-filter-field-label">头程 (CNY)</span>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                step={0.01}
                placeholder="必填"
                value={inputs.firstLeg}
                onChange={(val) => updateField('firstLeg', val)}
              />
            </div>

            <div className="wb-filter-field">
              <span className="wb-filter-field-label">广告费 (USD)</span>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                step={0.01}
                placeholder="默认 0"
                value={inputs.adCost}
                onChange={(val) => updateField('adCost', val)}
              />
            </div>

            <div className="wb-filter-field">
              <span className="wb-filter-field-label">汇率</span>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                step={0.01}
                placeholder="默认 7"
                value={inputs.exchangeRate}
                onChange={(val) => updateField('exchangeRate', val)}
              />
            </div>
          </div>
        </HeroCardBody>
      </HeroCard>
    </ConfigProvider>
  );
};

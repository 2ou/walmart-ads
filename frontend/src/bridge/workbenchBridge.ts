export interface SkuMatrixRow {
  sku: string;
  revenue: number;
  spend: number;
  orders: number;
  clicks?: number;
  impressions?: number;
  roas: number;
  decision: 'scale' | 'reduce' | 'optimize' | 'collect';
}

export interface SkuMatrixSnapshot {
  rows: SkuMatrixRow[];
  thresholds: {
    targetRoas: number;
    floorRoas: number;
    minClicks?: number;
    minSpend?: number;
  };
  periodDays: number;
}

export interface DashboardMetric {
  key: string;
  label: string;
  value: string;
  note: string;
  change?: { text: string; cls: 'up' | 'down' | 'flat' | 'new'; arrow: string };
  tone?: 'good' | 'alert' | 'focus' | 'warning' | 'default';
  clickable?: boolean;
  disabled?: boolean;
  dataAttributes?: Record<string, string | undefined>;
  ariaLabel?: string;
  ariaHaspopup?: 'dialog';
  ariaControls?: string;
  actionLabel?: string;
}

export interface ProductCatalogSummary {
  parentSku: string;
  title?: string;
  imageUrl?: string | null;
  price?: number | null;
  variantCount?: number;
  colorCount?: number;
  sizeCount?: number;
}

export interface WorkbenchBridge {
  switchView: (view: string, options?: Record<string, unknown>) => void;
  getActiveView?: () => string;
  openProductDashboard: (parentSku: string) => void;
  openInventoryStyleDashboard: (parentSku: string) => void;
  openReturnsDrawer: (parentSku: string) => void;
  showToast: (message: string) => void;
  apiFetch: <T = unknown>(url: string, options?: RequestInit) => Promise<T>;
  getAnalysisQuery: (extra?: Record<string, string>) => string;
  getActivePeriodRange?: () => { startDate: string; endDate: string };
  getDashboardMetrics?: () => DashboardMetric[] | null;
  getSalesMetrics?: () => DashboardMetric[] | null;
  getProductMetrics?: () => DashboardMetric[] | null;
  getDiagnosisMetrics?: () => DashboardMetric[] | null;
  getManualKeywordMetrics?: () => DashboardMetric[] | null;
  getLxAdMetrics?: () => DashboardMetric[] | null;
  getLxAdQualityMetrics?: () => DashboardMetric[] | null;
  getInventoryMetrics?: () => DashboardMetric[] | null;
  getSupplyChainMetrics?: () => DashboardMetric[] | null;
  getForecastMetrics?: () => DashboardMetric[] | null;
  getDataCenterMetrics?: () => DashboardMetric[] | null;
  getReturnMetrics?: () => DashboardMetric[] | null;
  getProductCatalog?: () => Promise<ProductCatalogSummary[]>;
  syncReputationState?: (payload: {
    data: unknown;
    unreadCount: number;
    criticalCount: number;
  }) => void;
  getSkuMatrixSnapshot: () => Promise<SkuMatrixSnapshot>;
  openOperationDialog: (
    targetType?: string,
    targetKey?: string,
    prefill?: Record<string, unknown>,
  ) => void;
  resolveOperationReminder: (reminderId: number) => Promise<void>;
  refreshOperationReminders: () => Promise<void>;
}

export interface WorkbenchReactUI {
  updateDashboardMetrics: (metrics: DashboardMetric[] | null) => void;
  updateSalesMetrics: (metrics: DashboardMetric[] | null) => void;
  updateProductMetrics: (metrics: DashboardMetric[] | null) => void;
  updateDiagnosisMetrics: (metrics: DashboardMetric[] | null) => void;
  updateManualKeywordMetrics: (metrics: DashboardMetric[] | null) => void;
  updateLxAdMetrics: (metrics: DashboardMetric[] | null) => void;
  updateLxAdQualityMetrics: (metrics: DashboardMetric[] | null) => void;
  updateInventoryMetrics: (metrics: DashboardMetric[] | null) => void;
  updateSupplyChainMetrics: (metrics: DashboardMetric[] | null) => void;
  updateForecastMetrics: (metrics: DashboardMetric[] | null) => void;
  updateDataCenterMetrics: (metrics: DashboardMetric[] | null) => void;
  updateReturnMetrics: (metrics: DashboardMetric[] | null) => void;
  loadWfsView: (kind: 'settlement' | 'storage') => void;
  loadLingxingView: () => void;
  loadSkuMatrixView: () => void;
  loadOperationsView: () => void;
  loadRecentView: () => void;
  loadReputationView: () => void;
  openReputationLowModal: () => void;
  loadAgentView: () => void;
}

declare global {
  interface Window {
    workbenchBridge?: WorkbenchBridge;
    workbenchReactUI?: WorkbenchReactUI;
  }
}

interface ApiEnvelope<T> {
  success?: boolean;
  message?: string;
  data?: T;
}

async function fallbackApiFetch<T>(url: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(url, { cache: 'no-store', ...options });
  let payload: ApiEnvelope<T> | null = null;
  try {
    payload = (await response.json()) as ApiEnvelope<T>;
  } catch {
    payload = null;
  }
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.message || `请求失败：HTTP ${response.status}`);
  }
  return payload.data as T;
}

function buildFallbackQuery(extra?: Record<string, string>): string {
  const params = new URLSearchParams({ days: '7' });
  if (extra) {
    Object.entries(extra).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') params.set(k, v);
    });
  }
  return params.toString();
}

/**
 * Typed bridge for React views inside `#view-*` to communicate with legacy views and shared helpers in `index.html`.
 */
export const workbenchBridge = {
  switchView(view: string, options?: Record<string, unknown>): void {
    window.workbenchBridge?.switchView(view, options);
  },
  getActiveView(): string | undefined {
    return window.workbenchBridge?.getActiveView?.();
  },
  openProductDashboard(parentSku: string): void {
    if (!parentSku) return;
    window.workbenchBridge?.openProductDashboard(parentSku);
  },
  openInventoryStyleDashboard(parentSku: string): void {
    if (!parentSku) return;
    window.workbenchBridge?.openInventoryStyleDashboard(parentSku);
  },
  openReturnsDrawer(parentSku: string): void {
    if (!parentSku) return;
    window.workbenchBridge?.openReturnsDrawer(parentSku);
  },
  showToast(message: string): void {
    if (window.workbenchBridge?.showToast) {
      window.workbenchBridge.showToast(message);
    } else {
      console.info('[Workbench]', message);
    }
  },
  apiFetch<T = unknown>(url: string, options?: RequestInit): Promise<T> {
    if (window.workbenchBridge?.apiFetch) {
      return window.workbenchBridge.apiFetch<T>(url, options);
    }
    return fallbackApiFetch<T>(url, options);
  },
  getAnalysisQuery(extra?: Record<string, string>): string {
    if (window.workbenchBridge?.getAnalysisQuery) {
      return window.workbenchBridge.getAnalysisQuery(extra);
    }
    return buildFallbackQuery(extra);
  },
  getActivePeriodRange(): { startDate: string; endDate: string } {
    if (window.workbenchBridge?.getActivePeriodRange) {
      return window.workbenchBridge.getActivePeriodRange();
    }
    const end = new Date();
    const start = new Date(end);
    start.setDate(end.getDate() - 6);
    const toIso = (date: Date) =>
      `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    return { startDate: toIso(start), endDate: toIso(end) };
  },
  getDashboardMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getDashboardMetrics?.() ?? null;
  },
  getSalesMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getSalesMetrics?.() ?? null;
  },
  getProductMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getProductMetrics?.() ?? null;
  },
  getDiagnosisMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getDiagnosisMetrics?.() ?? null;
  },
  getManualKeywordMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getManualKeywordMetrics?.() ?? null;
  },
  getLxAdMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getLxAdMetrics?.() ?? null;
  },
  getLxAdQualityMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getLxAdQualityMetrics?.() ?? null;
  },
  getInventoryMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getInventoryMetrics?.() ?? null;
  },
  getSupplyChainMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getSupplyChainMetrics?.() ?? null;
  },
  getForecastMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getForecastMetrics?.() ?? null;
  },
  getDataCenterMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getDataCenterMetrics?.() ?? null;
  },
  getReturnMetrics(): DashboardMetric[] | null {
    return window.workbenchBridge?.getReturnMetrics?.() ?? null;
  },
  getProductCatalog(): Promise<ProductCatalogSummary[]> {
    if (window.workbenchBridge?.getProductCatalog) {
      return window.workbenchBridge.getProductCatalog();
    }
    return Promise.resolve([]);
  },
  syncReputationState(payload: {
    data: unknown;
    unreadCount: number;
    criticalCount: number;
  }): void {
    window.workbenchBridge?.syncReputationState?.(payload);
  },
  getSkuMatrixSnapshot(): Promise<SkuMatrixSnapshot> {
    if (window.workbenchBridge?.getSkuMatrixSnapshot) {
      return window.workbenchBridge.getSkuMatrixSnapshot();
    }
    return Promise.resolve({
      rows: [],
      thresholds: { targetRoas: 5, floorRoas: 1.5 },
      periodDays: 7,
    });
  },
  openOperationDialog(
    targetType = 'PARENT_SKU',
    targetKey = '',
    prefill?: Record<string, unknown>,
  ): void {
    window.workbenchBridge?.openOperationDialog?.(targetType, targetKey, prefill);
  },
  async resolveOperationReminder(reminderId: number): Promise<void> {
    if (window.workbenchBridge?.resolveOperationReminder) {
      await window.workbenchBridge.resolveOperationReminder(reminderId);
      return;
    }
    await fallbackApiFetch(`/api/operations/reminders/${reminderId}/resolve`, { method: 'POST' });
  },
  async refreshOperationReminders(): Promise<void> {
    await window.workbenchBridge?.refreshOperationReminders?.();
  },
};

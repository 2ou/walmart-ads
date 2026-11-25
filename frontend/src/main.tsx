import React from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { HeroUIProvider } from '@heroui/react';
import { MotionConfig } from 'framer-motion';
import { workbenchBridge } from './bridge/workbenchBridge';
import './workbench-ui.css';
import {
  DashboardMetrics,
  DataCenterMetrics,
  DiagnosisMetrics,
  ForecastMetrics,
  InventoryMetrics,
  LxAdMetrics,
  LxAdQualityMetrics,
  ManualKeywordMetrics,
  ProductMetrics,
  ReturnMetrics,
  SalesMetrics,
  SupplyChainMetrics,
} from './views/dashboard/DashboardMetrics';
import { WfsSettlementView } from './views/wfs/WfsSettlementView';
import { WfsStorageView } from './views/wfs/WfsStorageView';
import { triggerWfsLoad } from './views/wfs/wfsTypes';
import { LingxingDataView, triggerLingxingLoad } from './views/lingxing/LingxingDataView';
import { ProfitEstimateView } from './views/tools/ProfitEstimateView';
import { BoxConvertView } from './views/tools/BoxConvertView';
import { SkuMatrixView, triggerSkuMatrixLoad } from './views/sku/SkuMatrixView';
import { OperationsView, triggerOperationsLoad } from './views/operations/OperationsView';
import { RecentSalesView, triggerRecentLoad } from './views/recent/RecentSalesView';
import {
  ReputationView,
  triggerReputationLoad,
  triggerReputationLowModal,
} from './views/reputation/ReputationView';
import { AdvertisingAgentView, triggerAgentLoad } from './views/agent/AdvertisingAgentView';

const renderDashboardMetrics = createViewRenderer('metricGrid');
const renderSalesMetrics = createViewRenderer('salesMetricGrid');
const renderProductMetrics = createViewRenderer('productMetricGrid');
const renderDiagnosisMetrics = createViewRenderer('diagnosisMetricGrid');
const renderManualKeywordMetrics = createViewRenderer('manualKeywordMetrics');
const renderLxAdMetrics = createViewRenderer('lxAdMetrics');
const renderLxAdQualityMetrics = createViewRenderer('lxAdQualityMetrics');
const renderInventoryMetrics = createViewRenderer('inventoryMetricGrid');
const renderSupplyChainMetrics = createViewRenderer('supplyChainMetrics');
const renderForecastMetrics = createViewRenderer('forecastWorkbenchMetrics');
const renderDataCenterMetrics = createViewRenderer('dataCenterSummary');
const renderReturnMetrics = createViewRenderer('returnMetricGrid');

window.workbenchReactUI = {
  updateDashboardMetrics: (metrics) => renderDashboardMetrics(<DashboardMetrics metrics={metrics} />),
  updateSalesMetrics: (metrics) => renderSalesMetrics(<SalesMetrics metrics={metrics} />),
  updateProductMetrics: (metrics) => renderProductMetrics(<ProductMetrics metrics={metrics} />),
  updateDiagnosisMetrics: (metrics) => renderDiagnosisMetrics(<DiagnosisMetrics metrics={metrics} />),
  updateManualKeywordMetrics: (metrics) =>
    renderManualKeywordMetrics(<ManualKeywordMetrics metrics={metrics} />),
  updateLxAdMetrics: (metrics) => renderLxAdMetrics(<LxAdMetrics metrics={metrics} />),
  updateLxAdQualityMetrics: (metrics) =>
    renderLxAdQualityMetrics(<LxAdQualityMetrics metrics={metrics} />),
  updateInventoryMetrics: (metrics) => renderInventoryMetrics(<InventoryMetrics metrics={metrics} />),
  updateSupplyChainMetrics: (metrics) =>
    renderSupplyChainMetrics(<SupplyChainMetrics metrics={metrics} />),
  updateForecastMetrics: (metrics) => renderForecastMetrics(<ForecastMetrics metrics={metrics} />),
  updateDataCenterMetrics: (metrics) =>
    renderDataCenterMetrics(<DataCenterMetrics metrics={metrics} />),
  updateReturnMetrics: (metrics) => renderReturnMetrics(<ReturnMetrics metrics={metrics} />),
  loadWfsView: (kind) => {
    triggerWfsLoad(kind);
  },
  loadLingxingView: () => {
    triggerLingxingLoad();
  },
  loadSkuMatrixView: () => {
    triggerSkuMatrixLoad();
  },
  loadOperationsView: () => {
    triggerOperationsLoad();
  },
  loadRecentView: () => {
    triggerRecentLoad();
  },
  loadReputationView: () => {
    triggerReputationLoad();
  },
  openReputationLowModal: () => {
    triggerReputationLowModal();
  },
  loadAgentView: () => {
    triggerAgentLoad();
  },
};

const rootCache = new WeakMap<HTMLElement, Root>();

function createViewRenderer(elementId: string): (node: React.ReactElement) => void {
  return (node) => {
    const container = document.getElementById(elementId);
    if (!container) return;
    let root = rootCache.get(container);
    if (!root) {
      root = createRoot(container);
      rootCache.set(container, root);
    }
    root.render(
      <HeroUIProvider locale="zh-CN">
        <MotionConfig reducedMotion="user">{node}</MotionConfig>
      </HeroUIProvider>,
    );
  };
}

function mountView(elementId: string, node: React.ReactElement): void {
  createViewRenderer(elementId)(node);
}

window.workbenchReactUI.updateDashboardMetrics(workbenchBridge.getDashboardMetrics());
window.workbenchReactUI.updateSalesMetrics(workbenchBridge.getSalesMetrics());
window.workbenchReactUI.updateProductMetrics(workbenchBridge.getProductMetrics());
window.workbenchReactUI.updateDiagnosisMetrics(workbenchBridge.getDiagnosisMetrics());
window.workbenchReactUI.updateManualKeywordMetrics(workbenchBridge.getManualKeywordMetrics());
window.workbenchReactUI.updateLxAdMetrics(workbenchBridge.getLxAdMetrics());
window.workbenchReactUI.updateLxAdQualityMetrics(workbenchBridge.getLxAdQualityMetrics());
window.workbenchReactUI.updateInventoryMetrics(workbenchBridge.getInventoryMetrics());
window.workbenchReactUI.updateSupplyChainMetrics(workbenchBridge.getSupplyChainMetrics());
window.workbenchReactUI.updateForecastMetrics(workbenchBridge.getForecastMetrics());
window.workbenchReactUI.updateDataCenterMetrics(workbenchBridge.getDataCenterMetrics());
window.workbenchReactUI.updateReturnMetrics(workbenchBridge.getReturnMetrics());
mountView('wfs-settlement-root', <WfsSettlementView />);
mountView('wfs-storage-root', <WfsStorageView />);
mountView('lingxing-root', <LingxingDataView />);
mountView('profit-estimate-root', <ProfitEstimateView />);
mountView('box-convert-root', <BoxConvertView />);
mountView('sku-root', <SkuMatrixView />);
mountView('operations-root', <OperationsView />);
mountView('recent-root', <RecentSalesView />);
mountView('reputation-root', <ReputationView />);
mountView('agent-root', <AdvertisingAgentView />);

// The legacy shell may have selected a deep link before this module was ready.
switch (workbenchBridge.getActiveView()) {
  case 'wfs-settlement':
    window.workbenchReactUI.loadWfsView('settlement');
    break;
  case 'wfs-storage':
    window.workbenchReactUI.loadWfsView('storage');
    break;
  case 'lingxing':
    window.workbenchReactUI.loadLingxingView();
    break;
  case 'sku':
    window.workbenchReactUI.loadSkuMatrixView();
    break;
  case 'operations':
    window.workbenchReactUI.loadOperationsView();
    break;
  case 'recent':
    window.workbenchReactUI.loadRecentView();
    break;
  case 'reputation':
    window.workbenchReactUI.loadReputationView();
    break;
  case 'agent':
    window.workbenchReactUI.loadAgentView();
    break;
}

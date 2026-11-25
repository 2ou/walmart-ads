/**
 * Central Workbench Business Composite Component Catalog
 *
 * AI Rule (Apple HIG Hybrid Stack):
 * - Primary UI, buttons, cards, chips, tabs, dropdowns & motion: Use HeroUI primitives (`HeroButton`, `HeroChip`, `HeroCard`, `AppleSegmentedTabs`) + these Workbench domain composites.
 * - Supplementary heavy ERP data controls: Use Ant Design `Table` (fixed columns / summary rows / server sorting), `Input`, `InputNumber`, `Select`, `Descriptions`, `Drawer`.
 */

/** Standard top header card with HeroUI Card + Chip, H2 title, description, and right-aligned extra slot (e.g. Dropzone). */
export { PageHeaderCard } from './PageHeaderCard';
export type { PageHeaderCardProps } from './PageHeaderCard';

/** 4-column responsive KPI summary cards backed by HeroUI Card + Skeleton. */
export { MetricCardGrid } from './MetricCardGrid';
export type { MetricCardGridProps, MetricCardItem } from './MetricCardGrid';

/** Unified HeroUI filter/table cards + Ant Design Table with server-side pagination (0-indexed) and sorting. */
export { WorkbenchFilterTable } from './WorkbenchFilterTable';
export type { WorkbenchFilterTableProps } from './WorkbenchFilterTable';

/** File upload dropzone, import feedback banner, and collapsible import batch audit table with delete confirmation. */
export {
  FileUploadDropzone,
  ImportFeedbackAlert,
  ImportBatchPanel,
} from './FileImportSuite';
export type {
  FileUploadDropzoneProps,
  ImportFeedbackAlertProps,
  ImportBatchPanelProps,
  ImportBatchRecord,
  ImportErrorRow,
  ImportFeedbackState,
} from './FileImportSuite';

/** SKU + clickable parent SKU cross-view HeroUI Dropdown jumper and structured Detail Drawer. */
export { SkuLinkTag, RecordDetailDrawer } from './SkuAndDetailSuite';
export type {
  SkuLinkTagProps,
  RecordDetailDrawerProps,
  DetailDrawerSection,
} from './SkuAndDetailSuite';

/** Horizontal sync/snapshot status banner backed by HeroUI Card + Chip. */
export { SyncStatusBanner } from './SyncStatusBanner';
export type { SyncStatusBannerProps, SyncBannerTone } from './SyncStatusBanner';

/** macOS / iOS-style Segmented Control backed by HeroUI Tabs with Framer Motion pill cursor. */
export { AppleSegmentedTabs } from './AppleSegmentedTabs';
export type { AppleSegmentedTabsProps, AppleSegmentedOption } from './AppleSegmentedTabs';

/** Re-exported HeroUI Apple-style UI primitives for consistent usage across Workbench views. */
export {
  Button as HeroButton,
  Chip as HeroChip,
  Card as HeroCard,
  CardHeader as HeroCardHeader,
  CardBody as HeroCardBody,
  Spinner as HeroSpinner,
  Tooltip as HeroTooltip,
} from '@heroui/react';

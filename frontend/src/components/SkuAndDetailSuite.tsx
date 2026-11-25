import React from 'react';
import {
  Button as HeroButton,
  Dropdown,
  DropdownItem,
  DropdownMenu,
  DropdownTrigger,
  Spinner,
} from '@heroui/react';
import { Descriptions, Drawer, Empty } from 'antd';
import {
  AppstoreOutlined,
  BarChartOutlined,
  DownOutlined,
  RollbackOutlined,
} from '@ant-design/icons';
import { workbenchBridge } from '../bridge/workbenchBridge';

export interface SkuLinkTagProps {
  sku?: string | null;
  parentSku?: string | null;
  matched?: boolean;
  unmatchedSkuLabel?: string;
  unmatchedParentLabel?: string;
}

/**
 * Renders SKU on line 1 and clickable parent SKU on line 2 with cross-view HeroUI Dropdown navigation actions.
 */
export const SkuLinkTag: React.FC<SkuLinkTagProps> = ({
  sku,
  parentSku,
  matched: _matched,
  unmatchedSkuLabel = '未匹配 SKU',
  unmatchedParentLabel = '未匹配款号',
}) => {
  const cleanParent = parentSku ? String(parentSku).trim() : '';
  const hasJumpableParent = Boolean(
    cleanParent && cleanParent !== unmatchedParentLabel && cleanParent !== '—',
  );

  const handleAction = (key: React.Key) => {
    if (key === 'product') workbenchBridge.openProductDashboard(cleanParent);
    else if (key === 'inventory') workbenchBridge.openInventoryStyleDashboard(cleanParent);
    else if (key === 'returns') workbenchBridge.openReturnsDrawer(cleanParent);
  };

  return (
    <div>
      <span className="wb-cell-primary wb-mono" title={sku || undefined}>
        {sku || unmatchedSkuLabel}
      </span>
      {hasJumpableParent ? (
        <Dropdown>
          <DropdownTrigger>
            <HeroButton
              type="button"
              size="sm"
              variant="light"
              disableRipple
              className="wb-sku-link-btn wb-mono h-auto min-w-0 overflow-visible"
              aria-label={`查看 ${cleanParent} 相关看板`}
              title={`点击跳转到 ${cleanParent} 相关看板`}
            >
              <span>{cleanParent}</span>
              <DownOutlined aria-hidden="true" style={{ fontSize: 9 }} />
            </HeroButton>
          </DropdownTrigger>
          <DropdownMenu aria-label="款号跳转菜单" variant="flat" onAction={handleAction}>
            <DropdownItem key="product" startContent={<AppstoreOutlined />}>
              查看产品库详情
            </DropdownItem>
            <DropdownItem key="inventory" startContent={<BarChartOutlined />}>
              查看库存款式看板
            </DropdownItem>
            <DropdownItem key="returns" startContent={<RollbackOutlined />}>
              查看退款分析
            </DropdownItem>
          </DropdownMenu>
        </Dropdown>
      ) : (
        <span className="wb-cell-secondary">{cleanParent || unmatchedParentLabel}</span>
      )}
    </div>
  );
};

export interface DetailDrawerSection {
  title: string;
  data: Record<string, unknown>;
}

export interface RecordDetailDrawerProps {
  open: boolean;
  eyebrow?: string;
  title: string;
  subtitle?: string;
  sections: DetailDrawerSection[];
  loading?: boolean;
  onClose: () => void;
}

function formatDetailValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

/**
 * Right-hand detail drawer displaying grouped key/value Descriptions sections (structured fields, audit info, raw fields).
 */
export const RecordDetailDrawer: React.FC<RecordDetailDrawerProps> = ({
  open,
  eyebrow,
  title,
  subtitle,
  sections,
  loading = false,
  onClose,
}) => {
  return (
    <Drawer
      open={open}
      onClose={onClose}
      width="min(720px, 100%)"
      className="wb-detail-drawer"
      destroyOnClose={false}
      title={
        <div>
          {eyebrow ? (
            <span
              className="wb-mono"
              style={{
                display: 'block',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.12em',
                color: '#0071e3',
                marginBottom: 4,
              }}
            >
              {eyebrow}
            </span>
          ) : null}
          <div style={{ fontSize: 16, fontWeight: 700, color: '#1d1d1f' }}>{title}</div>
          {subtitle ? (
            <div style={{ fontSize: 12, fontWeight: 400, color: '#515154', marginTop: 2 }}>
              {subtitle}
            </div>
          ) : null}
        </div>
      }
    >
      {loading ? (
        <div style={{ padding: '48px 0', textAlign: 'center' }}>
          <Spinner color="primary" size="md" />
          <div style={{ marginTop: 10, color: '#86868b', fontSize: 12 }}>正在加载明细...</div>
        </div>
      ) : (
        sections.map((section) => {
          const entries = Object.entries(section.data || {});
          return (
            <div key={section.title} style={{ marginBottom: 18 }}>
              <h4 className="wb-detail-section-title">{section.title}</h4>
              {entries.length > 0 ? (
                <Descriptions
                  bordered
                  size="small"
                  column={{ xs: 1, sm: 2 }}
                  items={entries.map(([key, value]) => ({
                    key,
                    label: <span style={{ fontSize: 11, color: '#515154' }}>{key}</span>,
                    children: (
                      <span className="wb-mono" style={{ fontSize: 11, color: '#1d1d1f' }}>
                        {formatDetailValue(value)}
                      </span>
                    ),
                  }))}
                />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="该记录未保存额外字段"
                />
              )}
            </div>
          );
        })
      )}
    </Drawer>
  );
};

import React from 'react';
import { Card, CardBody, Skeleton } from '@heroui/react';

export interface MetricCardItem {
  key: string;
  label: string;
  value: React.ReactNode;
  note?: React.ReactNode;
  /** Draws a highlighted subtle top accent indicator using the module accent color. */
  accent?: boolean;
  valueColor?: string;
  loading?: boolean;
  clickable?: boolean;
  disabled?: boolean;
  dataAttributes?: Record<string, string | undefined>;
  ariaLabel?: string;
  ariaHaspopup?: 'dialog';
  ariaControls?: string;
  actionLabel?: React.ReactNode;
}

export interface MetricCardGridProps {
  items: MetricCardItem[];
  /** Number of equal columns on desktop (defaults to 4). */
  columns?: number;
  /** Accent hex color applied when item.accent is true (defaults to Apple Blue #0071e3). */
  accentColor?: string;
}

/**
 * Responsive KPI metric card row backed by HeroUI Card + Skeleton with Apple HIG visual rhythm.
 */
export const MetricCardGrid: React.FC<MetricCardGridProps> = ({
  items,
  columns = 4,
  accentColor = '#0071e3',
}) => {
  const gridStyle = {
    gridTemplateColumns: `repeat(${Math.max(1, columns)}, minmax(0, 1fr))`,
    '--wb-accent': accentColor,
  } as React.CSSProperties;

  return (
    <div className="wb-metric-grid" style={gridStyle}>
      {items.map((item) => {
        const titleText =
          typeof item.value === 'string' || typeof item.value === 'number'
            ? String(item.value)
            : undefined;
        const interactiveProps = item.clickable
          ? {
              role: 'button' as const,
              tabIndex: item.disabled ? -1 : 0,
              'aria-disabled': item.disabled || undefined,
              'aria-label': item.ariaLabel,
              'aria-haspopup': item.ariaHaspopup,
              'aria-controls': item.ariaControls,
              ...(item.dataAttributes || {}),
            }
          : item.dataAttributes || {};

        return (
          <Card
            key={item.key}
            shadow="sm"
            radius="lg"
            className={`wb-metric-card border border-black/[0.06] bg-white${
              item.accent ? ' is-accent' : ''
            }${item.clickable ? ' is-clickable' : ''}${item.disabled ? ' is-disabled' : ''}`}
            {...(interactiveProps as Record<string, unknown>)}
          >
            <CardBody className="p-4">
              <span className="wb-metric-label">{item.label}</span>
              {item.loading ? (
                <div style={{ marginTop: 10 }}>
                  <Skeleton className="h-6 w-28 rounded-lg" />
                </div>
              ) : (
                <strong
                  className="wb-metric-value"
                  title={titleText}
                  style={item.valueColor ? { color: item.valueColor } : undefined}
                >
                  {item.value}
                </strong>
              )}
              {item.note ? <small className="wb-metric-note">{item.note}</small> : null}
              {item.actionLabel ? (
                <span className="wb-metric-action">{item.actionLabel}</span>
              ) : null}
            </CardBody>
          </Card>
        );
      })}
    </div>
  );
};

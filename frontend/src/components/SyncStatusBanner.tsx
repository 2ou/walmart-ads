import React from 'react';
import { Card, CardBody, Chip } from '@heroui/react';

export type SyncBannerTone = 'loading' | 'success' | 'warning' | 'danger';

export interface SyncStatusBannerProps {
  /** Semantic status tone controlling indicator dot and border/tint styling. */
  tone: SyncBannerTone;
  /** Primary concise status headline. */
  headline: React.ReactNode;
  /** Secondary detail summary (counts, timestamps, fallback notes). */
  detail?: React.ReactNode;
  /** Right-aligned small metadata caption label (e.g. "数据来源"). */
  metaLabel?: string;
  /** Right-aligned metadata value (e.g. "领星 MCP"). */
  metaValue?: React.ReactNode;
  /** Optional extra action node rendered on the far right. */
  extra?: React.ReactNode;
}

const TONE_CHIP_COLOR: Record<SyncBannerTone, 'primary' | 'success' | 'warning' | 'danger'> = {
  loading: 'primary',
  success: 'success',
  warning: 'warning',
  danger: 'danger',
};

/**
 * High-contrast horizontal status banner backed by HeroUI Card + Chip.
 */
export const SyncStatusBanner: React.FC<SyncStatusBannerProps> = ({
  tone,
  headline,
  detail,
  metaLabel,
  metaValue,
  extra,
}) => {
  return (
    <Card
      shadow="none"
      radius="lg"
      className={`wb-sync-banner is-${tone} border`}
      role="status"
      aria-live="polite"
    >
      <CardBody className="wb-sync-banner-body">
        <span className="wb-sync-dot" aria-hidden="true" />
        <div className="wb-sync-copy">
          <strong className="wb-sync-headline">{headline}</strong>
          {detail ? <span className="wb-sync-detail">{detail}</span> : null}
        </div>
        {(metaLabel || metaValue !== undefined) ? (
          <div className="wb-sync-meta">
            {metaLabel ? <span>{metaLabel}</span> : null}
            {metaValue !== undefined ? (
              <Chip
                size="sm"
                variant="flat"
                color={TONE_CHIP_COLOR[tone]}
                className="font-mono text-[11px] font-semibold"
              >
                {metaValue}
              </Chip>
            ) : null}
          </div>
        ) : null}
        {extra ? <div className="wb-sync-extra">{extra}</div> : null}
      </CardBody>
    </Card>
  );
};

import React from 'react';
import { Card, CardBody, Chip } from '@heroui/react';

export interface PageHeaderCardProps {
  /** Monospace uppercase eyebrow label displayed above the title (e.g. "WFS FINANCE"). */
  eyebrow: string;
  /** Main section title. */
  title: string;
  /** Concise single-sentence description of the view scope or data rules. */
  description: string;
  /** Accent hex color for the eyebrow and child controls (defaults to Apple Blue #0071e3). */
  accentColor?: string;
  /** Right-aligned action area (such as FileUploadDropzone or action buttons). */
  extra?: React.ReactNode;
}

/**
 * Standard top header card for a migrated Workbench module backed by HeroUI Card + Chip.
 */
export const PageHeaderCard: React.FC<PageHeaderCardProps> = ({
  eyebrow,
  title,
  description,
  accentColor = '#0071e3',
  extra,
}) => {
  const style = { '--wb-accent': accentColor } as React.CSSProperties;
  return (
    <Card
      shadow="sm"
      radius="lg"
      className="wb-page-header-card border border-black/[0.06] bg-white/90 backdrop-blur-md"
      style={style}
    >
      <CardBody className="wb-page-header p-5">
        <div className="wb-page-header-copy">
          <Chip
            size="sm"
            variant="flat"
            className="wb-page-eyebrow-chip mb-2 h-6 px-2.5 font-mono text-[10px] font-bold tracking-[0.12em] uppercase"
            style={{
              color: accentColor,
              backgroundColor: `color-mix(in srgb, ${accentColor} 10%, transparent)`,
            }}
          >
            {eyebrow}
          </Chip>
          <h2 className="wb-page-title">{title}</h2>
          <p className="wb-page-desc">{description}</p>
        </div>
        {extra ? <div className="wb-page-extra">{extra}</div> : null}
      </CardBody>
    </Card>
  );
};

import React from 'react';
import { Tab, Tabs } from '@heroui/react';

export interface AppleSegmentedOption<T extends string> {
  label: React.ReactNode;
  value: T;
}

export interface AppleSegmentedTabsProps<T extends string> {
  value: T;
  options: Array<AppleSegmentedOption<T>>;
  onChange: (value: T) => void;
  ariaLabel?: string;
}

/**
 * macOS / iOS-style Segmented Control backed by HeroUI Tabs with sliding pill cursor.
 */
export function AppleSegmentedTabs<T extends string>({
  value,
  options,
  onChange,
  ariaLabel = '视图切换',
}: AppleSegmentedTabsProps<T>): React.ReactElement {
  return (
    <Tabs
      aria-label={ariaLabel}
      selectedKey={value}
      onSelectionChange={(key) => onChange(String(key) as T)}
      radius="full"
      size="sm"
      variant="solid"
      classNames={{
        tabList:
          'gap-1 bg-[#e5e5ea]/80 p-1 rounded-full border border-black/[0.04]',
        cursor:
          'bg-white shadow-[0_2px_6px_rgba(0,0,0,0.09)] rounded-full',
        tab: 'h-7 px-3 text-[12px] font-semibold text-[#515154]',
        tabContent: 'group-data-[selected=true]:text-[#1d1d1f]',
      }}
    >
      {options.map((opt) => (
        <Tab key={opt.value} title={opt.label} />
      ))}
    </Tabs>
  );
}

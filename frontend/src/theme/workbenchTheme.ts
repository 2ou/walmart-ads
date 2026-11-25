import type { ThemeConfig } from 'antd';

export const WORKBENCH_COLORS = {
  midnightGrid: '#f5f5f7',
  navyPanel: '#ffffff',
  navyRaised: '#f5f5f7',
  frostInk: '#1d1d1f',
  steelCopy: '#515154',
  slateDim: '#626267',
  walmartBlue: '#0071e3',
  productionGreen: '#248a3d',
  signalRed: '#ff3b30',
  signalOrange: '#ff9500',
  lineSoft: 'rgba(0, 0, 0, 0.06)',
  lineStrong: 'rgba(0, 0, 0, 0.12)',
} as const;

export const WORKBENCH_FONTS = {
  sans: '-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "PingFang SC", "Helvetica Neue", system-ui, sans-serif',
  mono: '"SF Mono", "SFMono-Regular", "Fira Code", "Cascadia Mono", Menlo, Consolas, monospace',
} as const;

/**
 * Returns Ant Design 5 ThemeConfig aligned with Apple HIG / HeroUI light visual tokens.
 * Note: Do NOT import `antd/dist/reset.css` globally to avoid breaking legacy views in `index.html`.
 */
export function getWorkbenchTheme(accentColor: string = WORKBENCH_COLORS.walmartBlue): ThemeConfig {
  return {
    token: {
      colorPrimary: accentColor,
      colorSuccess: WORKBENCH_COLORS.productionGreen,
      colorWarning: WORKBENCH_COLORS.signalOrange,
      colorError: WORKBENCH_COLORS.signalRed,
      colorInfo: accentColor,
      colorTextBase: WORKBENCH_COLORS.frostInk,
      colorTextSecondary: WORKBENCH_COLORS.steelCopy,
      colorTextTertiary: WORKBENCH_COLORS.slateDim,
      colorBgContainer: WORKBENCH_COLORS.navyPanel,
      colorBgLayout: WORKBENCH_COLORS.midnightGrid,
      colorBorder: WORKBENCH_COLORS.lineStrong,
      colorBorderSecondary: WORKBENCH_COLORS.lineSoft,
      borderRadius: 10,
      borderRadiusLG: 16,
      fontSize: 12,
      fontFamily: WORKBENCH_FONTS.sans,
      fontFamilyCode: WORKBENCH_FONTS.mono,
      boxShadowSecondary: '0 4px 20px rgba(0, 0, 0, 0.04), 0 1px 3px rgba(0, 0, 0, 0.02)',
    },
    components: {
      Table: {
        headerBg: '#f5f5f7',
        headerColor: WORKBENCH_COLORS.slateDim,
        headerSplitColor: 'transparent',
        rowHoverBg: 'rgba(0, 113, 227, 0.04)',
        borderColor: 'rgba(0, 0, 0, 0.05)',
        cellPaddingBlockSM: 10,
        cellPaddingInlineSM: 12,
      },
      Card: {
        borderRadiusLG: 16,
        paddingLG: 18,
      },
      Collapse: {
        headerBg: '#ffffff',
        contentBg: '#ffffff',
        borderRadiusLG: 16,
      },
      Button: {
        borderRadius: 10,
      },
      Input: {
        borderRadius: 10,
      },
      InputNumber: {
        borderRadius: 10,
      },
      Select: {
        borderRadius: 10,
      },
    },
  };
}

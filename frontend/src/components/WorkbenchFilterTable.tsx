import React, { useEffect, useId, useRef } from 'react';
import { Button as HeroButton, Card, CardBody, CardHeader } from '@heroui/react';
import { Alert, Empty, Select, Space, Table } from 'antd';
import type { TableProps } from 'antd';
import type { TableRef } from 'antd/es/table';
import type { SorterResult } from 'antd/es/table/interface';

export interface WorkbenchFilterTableProps<T extends object> {
  filterTitle: string;
  filterSubtitle?: React.ReactNode;
  /** Filter form fields (rendered inside CSS grid). */
  filterContent: React.ReactNode;
  onSearchSubmit: () => void;
  onReset: () => void;
  tableTitle: string;
  tableSubtitle?: React.ReactNode;
  /** Optional extra controls rendered on the right side of the table header. */
  headerExtra?: React.ReactNode;
  rowKey: string | ((record: T) => string);
  columns: TableProps<T>['columns'];
  dataSource: T[];
  loading?: boolean;
  error?: string;
  /** 0-indexed page number matching Spring Boot API conventions. */
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (nextPageZeroIndexed: number, nextPageSize: number) => void;
  onSortChange?: (field: string, direction: 'ASC' | 'DESC') => void;
  emptyText?: string;
  scrollX?: number | string;
}

const PAGE_SIZE_OPTIONS = [
  { value: 20, label: '20 条/页' },
  { value: 50, label: '50 条/页' },
  { value: 100, label: '100 条/页' },
];

/**
 * Unified Filter Panel + High-Density Data Table + Controlled Server-Side Pagination & Sorting.
 */
export function WorkbenchFilterTable<T extends object>({
  filterTitle,
  filterSubtitle,
  filterContent,
  onSearchSubmit,
  onReset,
  tableTitle,
  tableSubtitle,
  headerExtra,
  rowKey,
  columns,
  dataSource,
  loading = false,
  error,
  page,
  pageSize,
  total,
  onPageChange,
  onSortChange,
  emptyText = '暂无数据',
  scrollX = 1140,
}: WorkbenchFilterTableProps<T>): React.ReactElement {
  const filterTitleId = useId();
  const tableTitleId = useId();
  const tableRef = useRef<TableRef>(null);

  useEffect(() => {
    // AntD owns the scrolling element so it can keep fixed columns in sync.
    const viewport = tableRef.current?.nativeElement.querySelector<HTMLElement>('.ant-table-content');
    if (!viewport) return;
    viewport.tabIndex = 0;
    viewport.setAttribute('role', 'region');
    viewport.setAttribute('aria-labelledby', tableTitleId);
  }, [error, tableTitleId]);

  const handleFormSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSearchSubmit();
  };

  const handleTableChange: TableProps<T>['onChange'] = (_pagination, _filters, sorter, extra) => {
    if (extra.action !== 'sort' || !onSortChange) {
      return;
    }
    const result = (Array.isArray(sorter) ? sorter[0] : sorter) as SorterResult<T> | undefined;
    if (!result) return;
    const rawField = result.columnKey ?? result.field;
    const field = Array.isArray(rawField) ? String(rawField[0] ?? '') : String(rawField ?? '');
    if (!field) return;

    if (result.order === 'ascend') {
      onSortChange(field, 'ASC');
    } else if (result.order === 'descend') {
      onSortChange(field, 'DESC');
    } else {
      // Controlled server sorting toggles cleanly without dropping into an unsorted state
      const previousOrder = result.column?.sortOrder;
      onSortChange(field, previousOrder === 'ascend' ? 'DESC' : 'ASC');
    }
  };

  return (
    <>
      <Card shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label={filterTitle}>
        <CardHeader className="wb-panel-header">
          <div>
            <h3 id={filterTitleId} className="wb-panel-title">{filterTitle}</h3>
            {filterSubtitle ? <p className="wb-panel-subtitle">{filterSubtitle}</p> : null}
          </div>
          <HeroButton
            type="button"
            size="sm"
            variant="light"
            color="primary"
            radius="full"
            onPress={onReset}
          >
            重置筛选
          </HeroButton>
        </CardHeader>
        <CardBody className="p-0">
          <form className="wb-filter-body" aria-labelledby={filterTitleId} onSubmit={handleFormSubmit}>
            <div className="wb-filter-grid">
              {filterContent}
              <div className="wb-filter-field">
                <HeroButton
                  size="sm"
                  color="primary"
                  radius="md"
                  type="submit"
                  className="font-semibold"
                >
                  查询
                </HeroButton>
              </div>
            </div>
          </form>
        </CardBody>
      </Card>

      <Card shadow="sm" radius="lg" className="wb-panel wb-table-panel border border-black/[0.06] bg-white">
        <CardHeader className="wb-panel-header">
          <div>
            <h3 id={tableTitleId} className="wb-panel-title">{tableTitle}</h3>
            <p className="wb-panel-subtitle" role="status">
              {loading ? '正在读取数据…' : tableSubtitle}
            </p>
          </div>
          <Space size="small" wrap>
            {headerExtra}
            <Select
              aria-label="每页显示条数"
              size="small"
              value={pageSize}
              options={PAGE_SIZE_OPTIONS}
              style={{ width: 102 }}
              onChange={(nextSize) => onPageChange(0, nextSize)}
            />
          </Space>
        </CardHeader>
        <CardBody className="wb-table-panel-body" aria-busy={loading}>
          {error ? (
            <Alert type="error" showIcon message="读取失败" description={error} />
          ) : (
            <Table<T>
              ref={tableRef}
              size="small"
              rowKey={rowKey}
              columns={columns}
              dataSource={dataSource}
              loading={loading}
              sortDirections={['descend', 'ascend']}
              scroll={{ x: scrollX }}
              onChange={handleTableChange}
              locale={{
                emptyText: (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={emptyText}
                  />
                ),
              }}
              pagination={{
                current: page + 1,
                pageSize,
                total,
                size: 'small',
                showSizeChanger: false,
                showQuickJumper: total > pageSize * 5,
                showTotal: (count, range) =>
                  count > 0
                    ? `第 ${range[0]}-${range[1]} 条，共 ${count.toLocaleString('zh-CN')} 条`
                    : '共 0 条',
                onChange: (nextOneIndexed, nextSize) => {
                  const resolvedSize = nextSize || pageSize;
                  const nextZeroIndexed =
                    resolvedSize !== pageSize ? 0 : Math.max(0, nextOneIndexed - 1);
                  onPageChange(nextZeroIndexed, resolvedSize);
                },
              }}
            />
          )}
        </CardBody>
      </Card>
    </>
  );
}

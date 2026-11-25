import React, { useId, useRef, useState } from 'react';
import { Button as HeroButton, Card, Chip } from '@heroui/react';
import { Alert, Button, Collapse, Popconfirm, Table } from 'antd';
import type { TableProps } from 'antd';
import { CloudUploadOutlined, DeleteOutlined, LoadingOutlined } from '@ant-design/icons';

export interface ImportErrorRow {
  rowNumber?: number | null;
  message?: string;
}

export interface ImportFeedbackState {
  isError: boolean;
  alreadyImported?: boolean;
  title?: string;
  message: string;
  errors?: ImportErrorRow[];
}

export interface ImportBatchRecord {
  id: number | string;
  fileName: string;
  fileHash?: string;
  fileSize?: number;
  sourceStore?: string | null;
  periodStart?: string | null;
  periodEnd?: string | null;
  status: string;
  importedRows?: number;
  duplicateRows?: number;
  failedRows?: number;
  errorMessage?: string | null;
  errors?: ImportErrorRow[];
  createdBy?: string | null;
  importedAt?: string | null;
}

export interface ImportFeedbackAlertProps {
  feedback: ImportFeedbackState | null | undefined;
  onClose?: () => void;
}

export const ImportFeedbackAlert: React.FC<ImportFeedbackAlertProps> = ({ feedback, onClose }) => {
  if (!feedback) return null;
  const errors = feedback.errors || [];
  const heading =
    feedback.title ||
    (feedback.isError
      ? '导入未完成'
      : feedback.alreadyImported
        ? '文件已导入'
        : '导入完成');

  return (
    <div className="wb-import-feedback">
      <Alert
        type={feedback.isError ? 'error' : 'success'}
        showIcon
        closable={Boolean(onClose)}
        onClose={onClose}
        message={<strong>{heading}</strong>}
        description={
          <div>
            <div>{feedback.message || '处理完成'}</div>
            {errors.length > 0 ? (
              <ul className="wb-import-errors">
                {errors.slice(0, 20).map((err, index) => (
                  <li key={`${err.rowNumber ?? 'row'}-${index}`}>
                    第 {err.rowNumber ?? '?'} 行：{err.message || '未知错误'}
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        }
      />
    </div>
  );
};

export interface FileUploadDropzoneProps {
  title: string;
  hint: string;
  /** Comma-separated file extensions / MIME types (defaults to .csv,.xlsx). */
  accept?: string;
  accentColor?: string;
  uploading?: boolean;
  /** Optional inline feedback rendered right below the dropzone. */
  feedback?: ImportFeedbackState | null;
  onUploadFile: (file: File) => Promise<void> | void;
  onClearFeedback?: () => void;
  onValidationError?: (message: string) => void;
}

function acceptsFile(fileName: string, accept: string): boolean {
  const lower = fileName.toLowerCase();
  const extensions = accept
    .split(',')
    .map((part) => part.trim().toLowerCase())
    .filter((part) => part.startsWith('.'));
  if (extensions.length === 0) return true;
  return extensions.some((ext) => lower.endsWith(ext));
}

/**
 * Interactive single-file drag-and-drop uploader for CSV/XLSX reports.
 */
export const FileUploadDropzone: React.FC<FileUploadDropzoneProps> = ({
  title,
  hint,
  accept = '.csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  accentColor = '#1668d6',
  uploading = false,
  feedback,
  onUploadFile,
  onClearFeedback,
  onValidationError,
}) => {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const titleId = useId();
  const hintId = useId();
  const dragDepthRef = useRef(0);
  const [dragging, setDragging] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  const reportError = (msg: string) => {
    if (onValidationError) {
      onValidationError(msg);
    } else {
      setLocalError(msg);
    }
  };

  const processFile = (file: File | undefined) => {
    setLocalError(null);
    if (!file || !acceptsFile(file.name, accept)) {
      reportError(`请选择支持的文件格式（${accept.split(',').filter((p) => p.trim().startsWith('.')).join(' / ')}）。`);
      return;
    }
    void onUploadFile(file);
  };

  const isFileDrag = (event: React.DragEvent) =>
    Array.from(event.dataTransfer?.types || []).includes('Files') ||
    Number(event.dataTransfer?.files?.length || 0) > 0;

  const style = { '--wb-accent': accentColor } as React.CSSProperties;

  return (
    <div style={style}>
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        style={{ display: 'none' }}
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = '';
          if (file) processFile(file);
        }}
      />
      <HeroButton
        type="button"
        variant="light"
        disableRipple
        className={`wb-dropzone h-auto min-w-0${dragging ? ' is-dragging' : ''}`}
        isDisabled={uploading}
        aria-labelledby={titleId}
        aria-describedby={hintId}
        aria-busy={uploading}
        onPress={() => inputRef.current?.click()}
        onDragEnter={(event) => {
          if (!isFileDrag(event)) return;
          event.preventDefault();
          if (uploading) return;
          dragDepthRef.current += 1;
          setDragging(true);
        }}
        onDragOver={(event) => {
          if (!isFileDrag(event)) return;
          event.preventDefault();
          if (uploading) return;
          event.dataTransfer.dropEffect = 'copy';
          setDragging(true);
        }}
        onDragLeave={(event) => {
          if (!isFileDrag(event)) return;
          event.preventDefault();
          dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
          if (dragDepthRef.current === 0) setDragging(false);
        }}
        onDrop={(event) => {
          if (!isFileDrag(event)) return;
          event.preventDefault();
          event.stopPropagation();
          dragDepthRef.current = 0;
          setDragging(false);
          if (uploading) return;
          const files = Array.from(event.dataTransfer?.files || []);
          if (files.length !== 1) {
            reportError('一次只能上传一个文件，请重新拖入单个支持的文件。');
            return;
          }
          processFile(files[0]);
        }}
      >
        <span className="wb-dropzone-icon" aria-hidden="true">
          {uploading ? <LoadingOutlined /> : <CloudUploadOutlined />}
        </span>
        <span className="wb-dropzone-copy">
          <strong id={titleId}>{uploading ? '正在解析并去重…' : title}</strong>
          <small id={hintId}>{hint}</small>
        </span>
        <Chip
          as="span"
          size="sm"
          variant="flat"
          color="primary"
          className="font-semibold"
          style={{
            color: accentColor,
            backgroundColor: `color-mix(in srgb, ${accentColor} 12%, transparent)`,
          }}
        >
          {uploading ? '处理中' : '选择文件'}
        </Chip>
      </HeroButton>

      {localError ? (
        <div style={{ marginTop: 8 }}>
          <Alert
            type="error"
            showIcon
            closable
            message={localError}
            onClose={() => setLocalError(null)}
          />
        </div>
      ) : null}

      {feedback ? (
        <div style={{ marginTop: 10 }}>
          <ImportFeedbackAlert feedback={feedback} onClose={onClearFeedback} />
        </div>
      ) : null}
    </div>
  );
};

export interface ImportBatchPanelProps {
  batches: ImportBatchRecord[];
  loading?: boolean;
  subtitle?: string;
  onInspectBatchErrors: (batch: ImportBatchRecord) => void;
  onDeleteBatch: (batch: ImportBatchRecord) => Promise<void> | void;
}

const BATCH_STATUS_META: Record<
  string,
  { label: string; color: 'success' | 'warning' | 'danger' | 'default' }
> = {
  SUCCESS: { label: '成功', color: 'success' },
  PARTIAL: { label: '部分成功', color: 'warning' },
  FAILED: { label: '失败', color: 'danger' },
  DELETED: { label: '已删除', color: 'default' },
};

function formatDateTimeShort(value?: string | null): string {
  if (!value) return '—';
  const normalized = String(value).replace('T', ' ');
  return normalized.length > 19 ? normalized.slice(0, 19) : normalized;
}

/**
 * Collapsible panel displaying file import batch audit history, SHA hashes, row counts, and deletion confirmation.
 */
export const ImportBatchPanel: React.FC<ImportBatchPanelProps> = ({
  batches,
  loading = false,
  subtitle = '查看文件哈希、重复行和错误明细',
  onInspectBatchErrors,
  onDeleteBatch,
}) => {
  const [deletingId, setDeletingId] = useState<string | number | null>(null);

  const columns: TableProps<ImportBatchRecord>['columns'] = [
    {
      title: '文件 / 周期',
      dataIndex: 'fileName',
      key: 'fileName',
      width: 320,
      render: (_, batch) => {
        const period =
          batch.periodStart && batch.periodEnd
            ? `${batch.periodStart} 至 ${batch.periodEnd}`
            : '未识别周期';
        return (
          <div>
            <span className="wb-cell-primary" title={batch.fileName}>
              {batch.fileName}
            </span>
            <span className="wb-cell-secondary">
              {period} · {batch.sourceStore || '未标注店铺'}
            </span>
            {batch.fileHash ? (
              <code
                className="wb-mono"
                title={batch.fileHash}
                style={{ display: 'block', marginTop: 2, fontSize: 10, color: '#667994' }}
              >
                SHA {batch.fileHash.slice(0, 14)}…
              </code>
            ) : null}
          </div>
        );
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 105,
      render: (status: string) => {
        const meta = BATCH_STATUS_META[status] || { label: status, color: 'default' as const };
        return (
          <Chip size="sm" variant="flat" color={meta.color}>
            {meta.label}
          </Chip>
        );
      },
    },
    {
      title: '新增',
      dataIndex: 'importedRows',
      key: 'importedRows',
      align: 'right',
      width: 85,
      render: (val?: number) => (
        <span className="wb-num">{Number(val || 0).toLocaleString('zh-CN')}</span>
      ),
    },
    {
      title: '重复',
      dataIndex: 'duplicateRows',
      key: 'duplicateRows',
      align: 'right',
      width: 85,
      render: (val?: number) => (
        <span className="wb-num">{Number(val || 0).toLocaleString('zh-CN')}</span>
      ),
    },
    {
      title: '失败',
      dataIndex: 'failedRows',
      key: 'failedRows',
      align: 'right',
      width: 85,
      render: (val: number | undefined, batch) => {
        const count = Number(val || 0);
        if (!count) return <span className="wb-num">0</span>;
        return (
          <Button
            type="link"
            danger
            size="small"
            className="wb-num"
            style={{ padding: 0 }}
            onClick={() => onInspectBatchErrors(batch)}
          >
            {count.toLocaleString('zh-CN')}
          </Button>
        );
      },
    },
    {
      title: '导入时间',
      dataIndex: 'importedAt',
      key: 'importedAt',
      width: 170,
      render: (_, batch) => (
        <div>
          <span className="wb-cell-primary wb-mono" style={{ fontSize: 11, fontWeight: 500 }}>
            {formatDateTimeShort(batch.importedAt)}
          </span>
          <span className="wb-cell-secondary">{batch.createdBy || '—'}</span>
        </div>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      align: 'right',
      render: (_, batch) =>
        batch.status === 'DELETED' ? (
          <span style={{ color: '#667994' }}>—</span>
        ) : (
          <Popconfirm
            title="删除这次导入？"
            description={`将删除「${batch.fileName}」的批次关联；仍被其他批次引用的明细会保留。`}
            okText="确认删除"
            cancelText="取消"
            okButtonProps={{ danger: true, loading: deletingId === batch.id }}
            onConfirm={async () => {
              setDeletingId(batch.id);
              try {
                await onDeleteBatch(batch);
              } finally {
                setDeletingId(null);
              }
            }}
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
              loading={deletingId === batch.id}
            >
              删除
            </Button>
          </Popconfirm>
        ),
    },
  ];

  return (
    <Card
      shadow="sm"
      radius="lg"
      className="wb-panel border border-black/[0.06] bg-white"
      style={{ overflow: 'visible' }}
    >
      <Collapse
        bordered={false}
        items={[
          {
            key: 'batches',
            label: (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                <strong style={{ fontSize: 13, color: '#1d1d1f' }}>导入记录</strong>
                <span style={{ fontSize: 11, color: '#86868b' }}>{subtitle}</span>
              </div>
            ),
            extra: (
              <Chip size="sm" variant="flat" color="default" className="font-mono text-[11px]">
                {batches.length} 个批次
              </Chip>
            ),
            children: (
              <Table<ImportBatchRecord>
                size="small"
                rowKey="id"
                columns={columns}
                dataSource={batches}
                loading={loading}
                pagination={false}
                scroll={{ x: 820 }}
                locale={{ emptyText: '还没有导入批次' }}
              />
            ),
          },
        ]}
      />
    </Card>
  );
};

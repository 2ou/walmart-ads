import { useCallback, useEffect, useRef, useState } from 'react';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import type { ImportBatchRecord } from '../../components';
import type { WfsPageResult } from './wfsTypes';

export function useWfsReportData<Query>(
  initialQuery: Query,
  report: 'settlements' | 'storage',
  buildSearchParams: (query: Query) => URLSearchParams,
) {
  const [query, setQuery] = useState(initialQuery);
  const [data, setData] = useState<WfsPageResult | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [batches, setBatches] = useState<ImportBatchRecord[]>([]);
  const [batchesLoading, setBatchesLoading] = useState(false);
  const queryRef = useRef(initialQuery);
  const pageRequest = useRef<AbortController>();
  const batchRequest = useRef<AbortController>();

  const loadBatches = useCallback(async () => {
    batchRequest.current?.abort();
    const request = new AbortController();
    batchRequest.current = request;
    const type = report === 'settlements' ? 'SETTLEMENT' : 'STORAGE';
    setBatchesLoading(true);
    try {
      const result = await workbenchBridge.apiFetch<ImportBatchRecord[]>(`/api/wfs/imports?type=${type}&limit=30`, {
        signal: request.signal,
      });
      if (!request.signal.aborted) setBatches(result);
    } catch (failure) {
      if (!request.signal.aborted) {
        workbenchBridge.showToast(failure instanceof Error ? failure.message : 'WFS 导入批次读取失败');
      }
    } finally {
      if (!request.signal.aborted) setBatchesLoading(false);
    }
  }, [report]);

  const loadData = useCallback(async (nextQuery = queryRef.current, includeBatches = true) => {
    queryRef.current = nextQuery;
    setQuery(nextQuery);
    pageRequest.current?.abort();
    const request = new AbortController();
    pageRequest.current = request;
    setLoading(true);
    setError('');
    const batchLoad = includeBatches ? loadBatches() : undefined;
    try {
      const qs = buildSearchParams(nextQuery);
      const result = await workbenchBridge.apiFetch<WfsPageResult>(`/api/wfs/${report}?${qs}`, {
        signal: request.signal,
      });
      if (!request.signal.aborted) setData(result);
    } catch (failure) {
      if (!request.signal.aborted) {
        setData(null);
        setError(failure instanceof Error ? failure.message : 'WFS 报表数据读取失败');
      }
    } finally {
      if (!request.signal.aborted) setLoading(false);
    }
    await batchLoad;
  }, [buildSearchParams, loadBatches, report]);

  useEffect(() => () => {
    pageRequest.current?.abort();
    batchRequest.current?.abort();
  }, []);

  return { query, queryRef, data, error, loading, batches, batchesLoading, loadData };
}

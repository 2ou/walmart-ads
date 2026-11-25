import React, { useRef, useState } from 'react';
import { Alert, ConfigProvider, Descriptions, Radio, Space } from 'antd';
import { CopyOutlined, DownloadOutlined, PlayCircleOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import {
  FileUploadDropzone,
  HeroButton,
  HeroCard,
  HeroCardBody,
  HeroCardHeader,
  MetricCardGrid,
  PageHeaderCard,
} from '../../components';
import type { MetricCardItem } from '../../components';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';

const ACCENT = WORKBENCH_COLORS.walmartBlue;

interface XlsxWorkbook {
  SheetNames: string[];
  Sheets: Record<string, Record<string, unknown>>;
}

interface XlsxApi {
  read: (data: Uint8Array, opts: { type: 'array' }) => XlsxWorkbook;
  writeFile: (wb: XlsxWorkbook, filename: string) => void;
  utils: {
    sheet_to_json: (ws: Record<string, unknown>, opts: { header: 1 }) => unknown[][];
    aoa_to_sheet: (data: unknown[][]) => Record<string, unknown>;
    book_new: () => XlsxWorkbook;
    book_append_sheet: (wb: XlsxWorkbook, ws: Record<string, unknown>, name: string) => void;
  };
}

declare global {
  interface Window {
    XLSX?: XlsxApi;
  }
}

let xlsxLibraryPromise: Promise<XlsxApi> | null = null;

function ensureXlsxLibrary(): Promise<XlsxApi> {
  if (window.XLSX) return Promise.resolve(window.XLSX);
  if (!xlsxLibraryPromise) {
    xlsxLibraryPromise = new Promise<XlsxApi>((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/js/xlsx.full.min.js';
      script.onload = () =>
        window.XLSX ? resolve(window.XLSX) : reject(new Error('Excel 组件初始化失败'));
      script.onerror = () => reject(new Error('Excel 组件加载失败'));
      document.head.appendChild(script);
    }).catch((err) => {
      xlsxLibraryPromise = null;
      throw err;
    });
  }
  return xlsxLibraryPromise;
}

function createCellPos(n: number): string {
  const ordA = 'A'.charCodeAt(0);
  const ordZ = 'Z'.charCodeAt(0);
  const len = ordZ - ordA + 1;
  let s = '';
  let x = n;
  while (x >= 0) {
    s = String.fromCharCode((x % len) + ordA) + s;
    x = Math.floor(x / len) - 1;
  }
  return s;
}

function outputFileBase(selectedFileName: string): string {
  let xlsName = '转换结果-' + selectedFileName.replace(/\.[^.]+$/, '');
  if (xlsName.length > 28) {
    xlsName = xlsName.substr(0, 14) + '..' + xlsName.substr(-14, 14);
  }
  return xlsName;
}

function fallbackCopy(text: string): void {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try {
    document.execCommand('copy');
    workbenchBridge.showToast('复制成功！');
  } catch {
    workbenchBridge.showToast('复制失败，请手动选择。');
  } finally {
    document.body.removeChild(ta);
  }
}

interface ConvertOutput {
  workbook: XlsxWorkbook;
  boxCount: number;
  sumWeight: number;
  sumWeightCeil: number;
  sumNum: number;
  spuNumStr: string;
  copyBox: unknown[][];
  copyEu: unknown[][];
  copyUs: unknown[][];
}

export const BoxConvertView: React.FC = () => {
  const xlsxRef = useRef<XlsxApi | null>(null);
  const [selectedFileName, setSelectedFileName] = useState('');
  const [workbook, setWorkbook] = useState<XlsxWorkbook | null>(null);
  const [selectedSheet, setSelectedSheet] = useState<string>('');
  const [parsing, setParsing] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [output, setOutput] = useState<ConvertOutput | null>(null);

  const handleUploadFile = async (file: File) => {
    setErrorMsg(null);
    setParsing(true);
    try {
      const buffer = await file.arrayBuffer();
      const XLSX = await ensureXlsxLibrary();
      xlsxRef.current = XLSX;
      const wb = XLSX.read(new Uint8Array(buffer), { type: 'array' });
      setSelectedFileName(file.name);
      setWorkbook(wb);
      setSelectedSheet(wb.SheetNames[0] || '');
      setOutput(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : '未知错误';
      setErrorMsg(`Excel 组件或文件读取失败：${msg}`);
    } finally {
      setParsing(false);
    }
  };

  const handleConvert = () => {
    setErrorMsg(null);
    const XLSX = xlsxRef.current || window.XLSX;
    if (!workbook || !XLSX) {
      setErrorMsg('请先上传装箱 Excel 文件');
      return;
    }
    if (!selectedSheet) {
      setErrorMsg('请选择工作表');
      return;
    }

    const ws = workbook.Sheets[selectedSheet];
    const data = XLSX.utils.sheet_to_json(ws, { header: 1 });
    const header = data[0] || [];

    const boxStrCol = header.findIndex(
      (it) => Boolean(it) && String(it).startsWith('箱号') && String(it).includes('扫码'),
    );
    const skuCol = header.findIndex(
      (it) => Boolean(it) && String(it).startsWith('SKU') && String(it).includes('抓取'),
    );
    if (boxStrCol < 0 || skuCol < 0) {
      setErrorMsg('未找到「箱号（扫码）」或「SKU（抓取）」列，请确认工作表正确。');
      return;
    }

    const boxNoArr: unknown[][] = [
      ['箱号（扫码）', '箱子序号（上传亚马逊的最终序号）', '品名', '重量(KG)', '高度(cm)', '每箱数量'],
    ];
    let boxNoArrNo = 1;

    for (let i = 1; i < data.length; i++) {
      const row = data[i] || [];
      if (row[boxStrCol] && String(row[boxStrCol]).trim()) {
        if (boxNoArr.findIndex((it) => it[0] == row[boxStrCol]) !== -1) {
          setErrorMsg(`箱号重复，请修正后重试！报错箱号：${String(row[boxStrCol])}`);
          return;
        }
        if (!row[skuCol]) {
          setErrorMsg(`箱号所在行缺少SKU；请输入完整信息后重试！报错箱号：${String(row[boxStrCol])}`);
          return;
        }
        if (!row[skuCol + 3]) {
          setErrorMsg(`箱号所在行缺少重量信息；请输入完整信息后重试！报错箱号：${String(row[boxStrCol])}`);
          return;
        }
        if (!row[skuCol + 4]) {
          setErrorMsg(`箱号所在行缺少箱子规格信息；请输入完整信息后重试！报错箱号：${String(row[boxStrCol])}`);
          return;
        }
        boxNoArr.push([row[boxStrCol], `B${boxNoArrNo}`, '', row[skuCol + 3], row[skuCol + 4]]);
        boxNoArrNo++;
      }
    }

    const boxNum = boxNoArr.length - 1;
    if (boxNum === 0) {
      setErrorMsg('未识别到任何装箱数据行（箱号列需有值）。');
      return;
    }

    const resultsSheet: unknown[][] = [
      ['SKU', 'FNSKU', '数量(计划上加减条码后的数量)', '验证', '右侧求和'],
    ];
    const thirdSheet: unknown[][] = [['父SKU']];
    const sumSheet: unknown[][] = [
      ['箱数', boxNoArrNo - 1],
      ['重量'],
      ['每箱向上取整(1kg)重量和'],
      ['发货数量'],
      ['品名数量'],
    ];

    for (let i = 0; i < boxNum; i++) {
      resultsSheet[0].push(`箱${i + 1}`);
      thirdSheet[0].push(`箱${i + 1}`);
    }

    const skuLists: string[] = [];
    const pSkuLists: string[] = [];
    for (let i = 1; i < data.length; i++) {
      const row = data[i] || [];
      const skuVal = row[skuCol] ? String(row[skuCol]) : '';
      if (skuVal) {
        if (skuLists.indexOf(skuVal) === -1) {
          skuLists.push(skuVal);
          const pSku = skuVal.slice(0, skuVal.indexOf('-') + 1);
          if (pSkuLists.indexOf(pSku) === -1) pSkuLists.push(pSku);
        }
      }
    }
    skuLists.sort();
    pSkuLists.sort();

    const spuNum: Array<[string, number?]> = [];
    for (let n = 0; n < pSkuLists.length; n++) {
      thirdSheet.push([pSkuLists[n]]);
      spuNum.push([pSkuLists[n]]);
    }

    const firstRow = data[1] || [];
    const height0 = Number(data[1] ? firstRow[skuCol + 4] : 50);
    const boxWidthNum = height0 == 60 ? 35 : 40;
    const boxLenNum = height0 == 60 ? 40 : 50;
    const firstW = data[1] ? Number(firstRow[skuCol + 3] || 0) : 0;

    const boxWeight: unknown[] = ['', '', '', '', '重量(KG)：', data[1] ? firstRow[skuCol + 3] : ''];
    const boxWidth: unknown[] = ['', '', '', '', '宽度(cm)：', boxWidthNum];
    const boxLen: unknown[] = ['', '', '', '', '长度(cm)：', boxLenNum];
    const boxHeigh: unknown[] = ['', '', '', '', '高度(cm)：', height0];
    const boxWeightLb: unknown[] = ['', '', '美国：', '', '重量(磅)：', parseFloat((firstW * 2.2046226).toFixed(2))];
    const boxWidthIn: unknown[] = ['', '', '', '', '宽度(in)：', parseFloat((boxWidthNum * 0.393700787).toFixed(2))];
    const boxLenIn: unknown[] = ['', '', '', '', '长度(in)：', parseFloat((boxLenNum * 0.393700787).toFixed(2))];
    const boxHeighIn: unknown[] = ['', '', '', '', '高度(in)：', parseFloat((height0 * 0.393700787).toFixed(2))];

    let sumWeight = firstW;
    let sumNum = 0;
    let boxNo = 1;

    for (let j = 1; j < data.length; j++) {
      const row = data[j] || [];
      const skuVal = row[skuCol] ? String(row[skuCol]) : '';
      if (row.length >= skuCol + 1 && skuVal && skuVal !== '#N/A') {
        const qtyVal = Number(row[skuCol + 2] || 0);
        sumNum += qtyVal;
        if (j > 1 && row[boxStrCol] && String(row[boxStrCol]).trim()) {
          const h = Number(row[skuCol + 4]);
          const w = h == 60 ? 35 : 40;
          const l = h == 60 ? 40 : 50;
          const w3 = Number(row[skuCol + 3] || 0);
          boxWeight.push(w3);
          sumWeight += w3;
          boxWidth.push(w);
          boxLen.push(l);
          boxHeigh.push(h);
          boxWeightLb.push(parseFloat((w3 * 2.2046226).toFixed(2)));
          boxWidthIn.push(parseFloat((w * 0.393700787).toFixed(2)));
          boxLenIn.push(parseFloat((l * 0.393700787).toFixed(2)));
          boxHeighIn.push(parseFloat((h * 0.393700787).toFixed(2)));
          boxNo += 1;
        }
        const resultsIdx = skuLists.indexOf(skuVal);
        const pIdx = pSkuLists.findIndex((p) => skuVal.startsWith(p));
        if (pIdx >= 0) {
          if (!thirdSheet[pIdx + 1][boxNo]) thirdSheet[pIdx + 1][boxNo] = 0;
          thirdSheet[pIdx + 1][boxNo] = Number(thirdSheet[pIdx + 1][boxNo] || 0) + qtyVal;
          if (spuNum[pIdx].length < 2) spuNum[pIdx].push(0);
          spuNum[pIdx][1] = Number(spuNum[pIdx][1] || 0) + qtyVal;
        }
        if (resultsSheet[resultsIdx + 1] && resultsSheet[resultsIdx + 1][0]) {
          if (resultsSheet[resultsIdx + 1][boxNo + 4]) {
            resultsSheet[resultsIdx + 1][boxNo + 4] =
              Number(resultsSheet[resultsIdx + 1][boxNo + 4] || 0) + qtyVal;
          } else {
            resultsSheet[resultsIdx + 1][boxNo + 4] = qtyVal;
          }
        } else {
          resultsSheet[resultsIdx + 1] = [skuVal, row[skuCol + 1]];
          resultsSheet[resultsIdx + 1][boxNo + 4] = qtyVal;
        }
      }
    }

    const spuNumStr = spuNum.map((a) => a.join('')).join('\n');
    const sumWeightCeil = boxWeight
      .slice(5)
      .map((w) => Number(w || 0))
      .reduce((acc, w) => acc + Math.ceil(w), 0);
    sumSheet[1].push(sumWeight);
    sumSheet[2].push(sumWeightCeil);
    sumSheet[3].push(sumNum);
    sumSheet[4].push(spuNumStr);

    resultsSheet.push([]);
    resultsSheet.push([]);
    resultsSheet.push(['', '', '', '', '每箱数量：']);
    resultsSheet.push([]);
    resultsSheet.push(['', '', '', '', '平均毛重：']);
    resultsSheet.push([]);
    resultsSheet.push(['箱子重量按1kg计算', '', '', '', '平均净重：']);
    resultsSheet.push([]);
    resultsSheet.push(boxWeight);
    resultsSheet.push(boxWidth);
    resultsSheet.push(boxLen);
    resultsSheet.push(boxHeigh);
    resultsSheet.push([[]]);
    resultsSheet.push([[]]);
    resultsSheet.push(boxWeightLb);
    resultsSheet.push(boxWidthIn);
    resultsSheet.push(boxLenIn);
    resultsSheet.push(boxHeighIn);
    resultsSheet.push([[]]);
    resultsSheet.push(boxWeight);
    resultsSheet.push(boxLen);
    resultsSheet.push(boxWidth);
    resultsSheet.push(boxHeigh);

    const wsResult = XLSX.utils.aoa_to_sheet(resultsSheet);
    const wsBox = XLSX.utils.aoa_to_sheet(boxNoArr);
    const wsThird = XLSX.utils.aoa_to_sheet(thirdSheet);
    const wsSum = XLSX.utils.aoa_to_sheet(sumSheet);

    for (let i = 0; i < skuLists.length; i++) {
      wsResult[`D${i + 2}`] = { f: `C${i + 2}=E${i + 2}` };
      wsResult[`E${i + 2}`] = { f: `SUM(F${i + 2}:ZZ${i + 2})` };
    }
    for (let i = 0; i < boxNoArrNo; i++) {
      wsResult[createCellPos(i + 5) + (skuLists.length + 4)] = {
        f: `SUM(${createCellPos(i + 5)}2:${createCellPos(i + 5)}${skuLists.length + 2})`,
      };
      wsResult[createCellPos(i + 5) + (skuLists.length + 6)] = {
        f: `ROUND(${createCellPos(i + 5)}${skuLists.length + 10}/${createCellPos(i + 5)}${skuLists.length + 4},3)`,
      };
      wsResult[createCellPos(i + 5) + (skuLists.length + 8)] = {
        f: `ROUND((${createCellPos(i + 5)}${skuLists.length + 10}-1)/${createCellPos(i + 5)}${skuLists.length + 4},3)`,
      };
      if (i > 0) {
        wsBox[`F${i + 1}`] = {
          f: `SUM(按父SKU汇总数量!${createCellPos(i)}:${createCellPos(i)})`,
        };
      }
    }

    wsResult['!cols'] = [{ wch: 45 }, { wch: 11 }, { wch: 6 }, { wch: 9 }, { wch: 9 }];
    wsThird['!cols'] = [{ wch: 20 }];
    wsBox['!cols'] = [{ wch: 25 }, { wch: 30 }];
    wsResult['!freeze'] = { xSplit: 1, ySplit: 1, state: 'frozen' };
    wsBox['!freeze'] = { ySplit: 1, state: 'frozen' };
    wsThird['!freeze'] = { xSplit: 1, ySplit: 1, state: 'frozen' };

    const outWorkbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(outWorkbook, wsResult, '转换结果');
    XLSX.utils.book_append_sheet(outWorkbook, wsBox, '箱子编号对应关系');
    XLSX.utils.book_append_sheet(outWorkbook, wsThird, '按父SKU汇总数量');
    XLSX.utils.book_append_sheet(outWorkbook, wsSum, '汇总');

    const copyBox = resultsSheet.slice(1, 1 + skuLists.length).map((r) => (r || []).slice(5));
    const copyEu = [boxWeight, boxWidth, boxLen, boxHeigh].map((r) => (r || []).slice(5));
    const copyUs = [boxWeightLb, boxWidthIn, boxLenIn, boxHeighIn].map((r) => (r || []).slice(5));

    setOutput({
      workbook: outWorkbook,
      boxCount: boxNoArrNo - 1,
      sumWeight,
      sumWeightCeil,
      sumNum,
      spuNumStr,
      copyBox,
      copyEu,
      copyUs,
    });
    workbenchBridge.showToast('转换完成，可下载结果或复制信息。');
  };

  const handleDownload = () => {
    const XLSX = xlsxRef.current || window.XLSX;
    if (!output?.workbook || !XLSX) return;
    XLSX.writeFile(output.workbook, `${outputFileBase(selectedFileName)}.xlsx`);
  };

  const handleCopy = (kind: 'box' | 'eu' | 'us') => {
    if (!output) return;
    const arr = kind === 'box' ? output.copyBox : kind === 'eu' ? output.copyEu : output.copyUs;
    const text = arr.map((r) => (r || []).join('\t')).join('\n');
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard
        .writeText(text)
        .then(() => workbenchBridge.showToast('复制成功！'))
        .catch(() => fallbackCopy(text));
    } else {
      fallbackCopy(text);
    }
  };

  const resultMetrics: MetricCardItem[] = output
    ? [
        {
          key: 'boxCount',
          label: '总箱数',
          value: output.boxCount.toLocaleString('zh-CN'),
          note: '按扫码箱号去重统计',
          accent: true,
        },
        {
          key: 'sumWeight',
          label: '总重量 (KG)',
          value: Number.isFinite(output.sumWeight) ? output.sumWeight.toFixed(2) : String(output.sumWeight),
          note: '原始箱重之和',
        },
        {
          key: 'sumWeightCeil',
          label: '每箱向上取整(1kg)重量和',
          value: `${output.sumWeightCeil.toLocaleString('zh-CN')} KG`,
          note: 'Math.ceil 逐箱计费重之和',
        },
        {
          key: 'sumNum',
          label: '发货总件数',
          value: output.sumNum.toLocaleString('zh-CN'),
          note: '全箱 SKU 数量汇总',
        },
      ]
    : [];

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <PageHeaderCard
        eyebrow="WAREHOUSE PIPELINE TOOL"
        title="流水线装箱转换（工具）"
        description="读取仓库给到的流水线装箱 Excel，选择工作表后一键转换，导出含「转换结果 / 箱子编号对应关系 / 按父SKU汇总数量 / 汇总」四个工作表的结果文件，并支持复制装箱与体积重量信息。列定位规则：箱号（扫码）、SKU（抓取），数量=SKU列+2、重量=+3、箱子规格=+4；箱子规格为 60 时按 40×35×60，否则 50×40×60。"
        accentColor={ACCENT}
      />

      {errorMsg ? (
        <div style={{ marginBottom: 14 }}>
          <Alert type="error" showIcon closable message={errorMsg} onClose={() => setErrorMsg(null)} />
        </div>
      ) : null}

      <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="第一步 · 上传装箱 Excel">
        <HeroCardHeader className="wb-panel-header">
          <div>
            <h3 className="wb-panel-title">第一步 · 上传装箱 Excel</h3>
            <p className="wb-panel-subtitle">
              {selectedFileName ? `当前已选择：${selectedFileName}` : '支持拖拽或点击选择文件'}
            </p>
          </div>
        </HeroCardHeader>
        <HeroCardBody style={{ padding: 16 }}>
          <FileUploadDropzone
            title="把装箱 Excel 文件拖拽到这里"
            hint="支持 .xlsx / .xls / .csv · 本地浏览器解析不落盘"
            accept=".xlsx,.xls,.csv"
            accentColor={ACCENT}
            uploading={parsing}
            onUploadFile={handleUploadFile}
            onValidationError={(msg) => setErrorMsg(msg)}
          />
        </HeroCardBody>
      </HeroCard>

      {workbook ? (
        <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="第二步 · 选择工作表">
          <HeroCardHeader className="wb-panel-header">
            <div>
              <h3 className="wb-panel-title">第二步 · 选择工作表</h3>
              <p className="wb-panel-subtitle">选择包含装箱数据的工作表，然后开始转换</p>
            </div>
            <HeroButton
              color="primary"
              radius="full"
              size="sm"
              startContent={<PlayCircleOutlined />}
              onPress={handleConvert}
              className="font-semibold"
            >
              开始转换
            </HeroButton>
          </HeroCardHeader>
          <HeroCardBody style={{ padding: 16 }}>
            <Radio.Group
              value={selectedSheet}
              onChange={(e) => setSelectedSheet(e.target.value)}
            >
              <Space wrap size="middle">
                {workbook.SheetNames.map((name) => (
                  <Radio key={name} value={name}>
                    {name}
                  </Radio>
                ))}
              </Space>
            </Radio.Group>
          </HeroCardBody>
        </HeroCard>
      ) : null}

      {output ? (
        <>
          <MetricCardGrid items={resultMetrics} columns={4} accentColor={ACCENT} />

          <HeroCard shadow="sm" radius="lg" className="wb-panel border border-black/[0.06] bg-white" aria-label="第三步 · 转换结果">
            <HeroCardHeader className="wb-panel-header">
              <div>
                <h3 className="wb-panel-title">第三步 · 转换结果</h3>
                <p className="wb-panel-subtitle">可下载结果文件或复制信息</p>
              </div>
              <HeroButton
                color="primary"
                radius="full"
                size="sm"
                startContent={<DownloadOutlined />}
                onPress={handleDownload}
                className="font-semibold"
              >
                下载转换结果 (.xlsx)
              </HeroButton>
            </HeroCardHeader>
            <HeroCardBody style={{ padding: 16, display: 'grid', gap: 16 }}>
              <Descriptions
                bordered
                size="small"
                column={1}
                items={[
                  {
                    key: 'spuSummary',
                    label: '品名数量 (按父SKU汇总)',
                    children: (
                      <pre
                        className="wb-mono"
                        style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}
                      >
                        {output.spuNumStr || '—'}
                      </pre>
                    ),
                  },
                ]}
              />

              <Space wrap size="small">
                <HeroButton
                  variant="flat"
                  radius="md"
                  size="sm"
                  startContent={<CopyOutlined />}
                  onPress={() => handleCopy('box')}
                >
                  一键复制装箱信息
                </HeroButton>
                <HeroButton
                  variant="flat"
                  radius="md"
                  size="sm"
                  startContent={<CopyOutlined />}
                  onPress={() => handleCopy('eu')}
                >
                  一键复制体积重量信息——欧洲
                </HeroButton>
                <HeroButton
                  variant="flat"
                  radius="md"
                  size="sm"
                  startContent={<CopyOutlined />}
                  onPress={() => handleCopy('us')}
                >
                  一键复制体积重量信息——美国
                </HeroButton>
              </Space>
            </HeroCardBody>
          </HeroCard>
        </>
      ) : null}
    </ConfigProvider>
  );
};

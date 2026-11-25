import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Collapse,
  ConfigProvider,
  Empty,
  Input,
  Select,
  Space,
  Tag,
} from 'antd';
import {
  LockOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SendOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import {
  AppleSegmentedTabs,
  HeroButton,
  HeroCard,
  HeroCardBody,
  HeroChip,
  HeroSpinner,
  SkuLinkTag,
} from '../../components';
import { workbenchBridge } from '../../bridge/workbenchBridge';
import { WORKBENCH_COLORS, getWorkbenchTheme } from '../../theme/workbenchTheme';

const ACCENT = WORKBENCH_COLORS.walmartBlue;

interface AiStatusPayload {
  configured?: boolean;
  enabled?: boolean;
  model?: string;
}

interface AgentModelItem {
  id: string;
  name: string;
  description?: string;
  recommended?: boolean;
}

interface AgentModelsPayload {
  models?: AgentModelItem[];
  defaultModel?: string;
}

interface AgentSkillItem {
  code: string;
  name: string;
  version?: number;
}

interface ExecutionStep {
  stepId?: string;
  kind?: string;
  status?: string;
  title?: string;
  summary?: string;
  durationMs?: number | null;
  details?: Record<string, unknown>;
  tool?: string;
  resultChars?: number;
}

interface StructuredFinding {
  severity?: string;
  title?: string;
  evidence?: string;
  recommendation?: string;
}

interface StructuredAction {
  priority?: number;
  target?: string;
  action?: string;
  reason?: string;
}

interface StructuredResponse {
  summary?: string;
  skill?: string;
  dataAsOf?: string;
  confidence?: string;
  findings?: StructuredFinding[];
  actions?: StructuredAction[];
  limitations?: string[];
}

interface AgentRun {
  id?: number;
  model?: string;
  status?: string;
  question?: string;
  activatedSkill?: string;
  skillVersion?: number;
  totalTokens?: number;
  createdAt?: string;
  completedAt?: string;
  error?: string;
  scope?: {
    startDate?: string;
    endDate?: string;
    parentSku?: string | null;
    adType?: string;
  };
  toolTrace?: ExecutionStep[];
  response?: StructuredResponse;
}

interface AgentMessageItem {
  role: 'USER' | 'ASSISTANT';
  content?: string;
  status?: string;
  error?: string;
  createdAt?: string;
  run?: AgentRun;
  payload?: StructuredResponse;
}

interface ConversationSummary {
  id: number;
  title?: string;
  lastMessagePreview?: string;
  messageCount?: number;
  lastMessageAt?: string;
  createdAt?: string;
  model?: string;
  requestedSkill?: string;
  scope?: {
    startDate?: string;
    endDate?: string;
    parentSku?: string | null;
    adType?: string;
  };
}

interface ConversationDetail {
  conversation: ConversationSummary;
  messages: AgentMessageItem[];
}

const QUICK_PROMPTS = [
  {
    tag: '数据检查',
    title: '检查领星数据是否新鲜',
    prompt: '检查最近一次领星广告同步是否完整，列出各报表最新日期。',
  },
  {
    tag: '整体诊断',
    title: '找出风险与放量机会',
    prompt: '分析当前范围的广告整体表现，指出主要风险和可放量机会。',
  },
  {
    tag: '款号复盘',
    title: '比较当前与历史广告',
    prompt: '分析这个款号当前广告与历史广告的差异，并给出下一步建议。',
  },
  {
    tag: '关键词',
    title: '定位浪费与高转化词',
    prompt: '分析手动广告关键词，找出浪费花费和高转化机会。',
  },
];

const TOOL_LABELS: Record<string, string> = {
  query_account_ad_overview: '查询广告经营总览',
  query_campaign_diagnosis: '查询款号广告诊断',
  query_manual_keyword_performance: '查询手动广告关键词',
  query_historical_ads: '查询历史广告表现',
  query_data_freshness: '检查领星数据新鲜度',
  activate_skill: '选择分析 Skill',
};

const CONFIDENCE_MAP: Record<string, string> = {
  high: '高',
  medium: '中',
  low: '低',
};

const SEVERITY_ANT_COLOR: Record<string, string> = {
  high: 'red',
  critical: 'red',
  warning: 'orange',
  medium: 'orange',
  positive: 'green',
  success: 'green',
  info: 'blue',
};

function formatTimeShort(value?: string): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function durationLabel(ms?: number | null): string {
  if (ms === null || ms === undefined || Number.isNaN(Number(ms))) return '历史记录未采集';
  const val = Math.max(0, Number(ms));
  if (val < 1000) return `${Math.round(val)} ms`;
  return `${(val / 1000).toFixed(val >= 10000 ? 1 : 2)} 秒`;
}

function normalizeSteps(run?: AgentRun): ExecutionStep[] {
  const traces = Array.isArray(run?.toolTrace) ? run!.toolTrace! : [];
  if (traces.some((step) => step?.stepId)) return traces;
  const steps: ExecutionStep[] = [];
  if (run?.scope) {
    steps.push({
      stepId: 'legacy-scope',
      kind: 'SCOPE',
      status: 'SUCCESS',
      title: '锁定分析范围',
      summary: '历史运行已保留分析范围',
      durationMs: null,
      details: { scope: run.scope },
    });
  }
  traces.forEach((trace, idx) => {
    steps.push({
      stepId: `legacy-tool-${idx}`,
      kind: 'TOOL',
      status: trace.status || 'SUCCESS',
      title: (trace.tool && TOOL_LABELS[trace.tool]) || trace.tool || '数据库工具',
      summary: '历史记录仅保留工具调用摘要',
      durationMs: null,
      details: { scope: run?.scope, resultChars: trace.resultChars },
    });
  });
  if (run && !traces.length && run.status) {
    const failed = run.status === 'FAILED';
    steps.push({
      stepId: 'legacy-result',
      kind: failed ? 'ERROR' : 'RESULT',
      status: failed ? 'FAILED' : 'SUCCESS',
      title: failed ? '执行失败' : '生成分析结论',
      summary: failed ? run.error || '分析失败' : '分析结论和运行审计已保存',
      durationMs: null,
    });
  }
  return steps;
}

const loadListeners = new Set<() => void>();
let pendingLoad = false;

export function triggerAgentLoad(): void {
  if (loadListeners.size === 0) {
    pendingLoad = true;
    return;
  }
  loadListeners.forEach((fn) => fn());
}

export const AdvertisingAgentView: React.FC = () => {
  const [aiStatus, setAiStatus] = useState<AiStatusPayload | null>(null);
  const [models, setModels] = useState<AgentModelItem[]>([]);
  const [defaultModel, setDefaultModel] = useState<string>('');
  const [skills, setSkills] = useState<AgentSkillItem[]>([]);
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [activeConvId, setActiveConvId] = useState<number | null>(null);
  const [detail, setDetail] = useState<ConversationDetail | null>(null);

  // Scope fields
  const initialRange = useMemo(() => workbenchBridge.getActivePeriodRange(), []);
  const [selectedModel, setSelectedModel] = useState<string>('');
  const [skillCode, setSkillCode] = useState<string>('AUTO');
  const [parentSku, setParentSku] = useState<string>('');
  const [adType, setAdType] = useState<string>('ALL');
  const [startDate, setStartDate] = useState<string>(initialRange.startDate);
  const [endDate, setEndDate] = useState<string>(initialRange.endDate);

  // Composer & stream state
  const [question, setQuestion] = useState<string>('');
  const [sending, setSending] = useState<boolean>(false);
  const [liveSteps, setLiveSteps] = useState<ExecutionStep[]>([]);
  const [liveModel, setLiveModel] = useState<string>('');
  const [bootstrapError, setBootstrapError] = useState<string>('');

  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const modelSelectRef = useRef<HTMLDivElement | null>(null);

  const scrollToBottom = useCallback(() => {
    requestAnimationFrame(() => {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    });
  }, []);

  const applyConversationScope = useCallback(
    (conv?: ConversationSummary | null, modelList: AgentModelItem[] = models, defModel: string = defaultModel) => {
      const range = workbenchBridge.getActivePeriodRange();
      if (!conv) {
        setSelectedModel(defModel || modelList[0]?.id || '');
        setSkillCode('AUTO');
        setParentSku('');
        setAdType('ALL');
        setStartDate(range.startDate);
        setEndDate(range.endDate);
        return;
      }
      const nextModel =
        conv.model && modelList.some((m) => m.id === conv.model)
          ? conv.model
          : defModel || modelList[0]?.id || '';
      setSelectedModel(nextModel);
      setSkillCode(conv.requestedSkill || 'AUTO');
      setParentSku(conv.scope?.parentSku || '');
      setAdType(conv.scope?.adType || 'ALL');
      setStartDate(conv.scope?.startDate || range.startDate);
      setEndDate(conv.scope?.endDate || range.endDate);
    },
    [models, defaultModel],
  );

  const openConversation = useCallback(
    async (convId: number, modelList?: AgentModelItem[], defModel?: string) => {
      if (sending) return;
      try {
        const res = await workbenchBridge.apiFetch<ConversationDetail>(
          `/api/ai/agent/conversations/${convId}`,
        );
        setActiveConvId(res.conversation.id);
        setDetail(res);
        applyConversationScope(res.conversation, modelList, defModel);
        scrollToBottom();
      } catch (err) {
        workbenchBridge.showToast(
          `读取对话失败：${err instanceof Error ? err.message : '未知错误'}`,
        );
      }
    },
    [sending, applyConversationScope, scrollToBottom],
  );

  const loadWorkspace = useCallback(async () => {
    setBootstrapError('');
    try {
      const [statusData, modelsData, skillsData, convData] = await Promise.all([
        workbenchBridge.apiFetch<AiStatusPayload>('/api/ai/status').catch(() => null),
        workbenchBridge.apiFetch<AgentModelsPayload>('/api/ai/agent/models'),
        workbenchBridge.apiFetch<AgentSkillItem[]>('/api/ai/agent/skills'),
        workbenchBridge.apiFetch<ConversationSummary[]>('/api/ai/agent/conversations?limit=30'),
      ]);
      if (statusData) setAiStatus(statusData);
      const nextModels = modelsData?.models || [];
      const nextDefault = modelsData?.defaultModel || nextModels[0]?.id || '';
      setModels(nextModels);
      setDefaultModel(nextDefault);
      setSelectedModel((prev) =>
        nextModels.some((m) => m.id === prev) ? prev : nextDefault,
      );
      setSkills(skillsData || []);
      const convList = convData || [];
      setConversations(convList);
      if (activeConvId && convList.some((c) => c.id === activeConvId)) {
        await openConversation(activeConvId, nextModels, nextDefault);
      } else if (convList.length > 0 && !detail) {
        await openConversation(convList[0].id, nextModels, nextDefault);
      }
    } catch (err) {
      setBootstrapError(err instanceof Error ? err.message : '初始化 Agent 工作台失败');
    }
  }, [activeConvId, detail, openConversation]);

  useEffect(() => {
    loadListeners.add(loadWorkspace);
    if (pendingLoad) {
      pendingLoad = false;
      loadWorkspace();
    }
    return () => {
      loadListeners.delete(loadWorkspace);
    };
  }, [loadWorkspace]);

  const startNewConversation = () => {
    if (sending) return;
    setActiveConvId(null);
    setDetail(null);
    applyConversationScope(null);
    setQuestion('');
  };

  const modelLabel = (modelId?: string) => {
    const found = models.find((m) => m.id === modelId);
    return found?.name || modelId || '文本模型';
  };

  const formatAgentError = (raw?: string, modelId?: string) => {
    const msg = String(raw || '').trim();
    const label = modelLabel(modelId);
    if (/HTTP 5\d\d|server exception/i.test(msg)) {
      return `${label} 服务暂时不可用，可在下方切换其他模型后重试。`;
    }
    if (/timeout|timed out/i.test(msg)) {
      return `${label} 响应超时，可切换模型或缩小日期范围后重试。`;
    }
    return msg || '分析失败，请稍后重试。';
  };

  const sendMessage = async (overrideQuestion?: string) => {
    const text = (overrideQuestion ?? question).trim();
    if (!text || sending) return;
    if (!startDate || !endDate || startDate > endDate) {
      workbenchBridge.showToast('请选择有效的开始和结束日期');
      return;
    }

    setSending(true);
    setLiveSteps([]);
    setLiveModel(selectedModel);
    try {
      let convId = activeConvId;
      if (!convId) {
        const created = await workbenchBridge.apiFetch<ConversationDetail>(
          '/api/ai/agent/conversations',
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              skillCode,
              model: selectedModel,
              startDate,
              endDate,
              parentSku: parentSku.trim() || null,
              adType,
            }),
          },
        );
        convId = created.conversation.id;
        setActiveConvId(convId);
        setDetail(created);
      }

      // Append optimistic user message
      setDetail((prev) => ({
        conversation: prev?.conversation || { id: convId! },
        messages: [
          ...(prev?.messages || []),
          {
            role: 'USER',
            content: text,
            status: 'SUCCESS',
            createdAt: new Date().toISOString(),
          },
        ],
      }));
      if (!overrideQuestion) setQuestion('');
      scrollToBottom();

      const response = await fetch(`/api/ai/agent/conversations/${convId}/messages/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          skillCode,
          model: selectedModel,
          question: text,
          startDate,
          endDate,
          parentSku: parentSku.trim() || null,
          adType,
        }),
      });
      if (!response.ok) {
        let errMsg = `HTTP ${response.status}`;
        try {
          const payload = (await response.json()) as { message?: string };
          errMsg = payload.message || errMsg;
        } catch {
          // keep http status
        }
        throw new Error(errMsg);
      }
      if (!response.body?.getReader) {
        throw new Error('当前浏览器不支持实时执行流');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let streamDetail: ConversationDetail | null = null;
      let streamError: string | null = null;

      const consumeLine = (line: string) => {
        if (!line.trim()) return;
        const ev = JSON.parse(line) as {
          type?: string;
          progress?: ExecutionStep;
          detail?: ConversationDetail;
          message?: string;
        };
        if (ev.type === 'progress' && ev.progress) {
          const prog = ev.progress;
          setLiveSteps((prev) => {
            const idx = prev.findIndex((s) => s.stepId === prog.stepId);
            if (idx >= 0) {
              const next = prev.slice();
              next[idx] = prog;
              return next;
            }
            return [...prev, prog];
          });
          scrollToBottom();
        }
        if (ev.type === 'complete' && ev.detail) {
          streamDetail = ev.detail;
        }
        if (ev.type === 'error') {
          if (ev.detail) streamDetail = ev.detail;
          streamError = ev.message || 'Agent 分析失败';
        }
      };

      while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        lines.forEach(consumeLine);
        if (done) break;
      }
      if (buffer.trim()) consumeLine(buffer);

      if (streamDetail) {
        setDetail(streamDetail);
      }
      if (streamError) throw new Error(streamError);

      const refreshedList = await workbenchBridge.apiFetch<ConversationSummary[]>(
        '/api/ai/agent/conversations?limit=30',
      );
      setConversations(refreshedList || []);
      workbenchBridge.showToast('Agent 已回复，执行过程与工具审计已保存。');
      scrollToBottom();
    } catch (err) {
      if (activeConvId) {
        openConversation(activeConvId).catch(() => {});
      }
      workbenchBridge.showToast(
        formatAgentError(err instanceof Error ? err.message : '请求失败', selectedModel),
      );
    } finally {
      setSending(false);
      setLiveSteps([]);
    }
  };

  const modelReady = Boolean(aiStatus?.configured) && models.some((m) => m.id === selectedModel);
  const canSend = modelReady && skills.length > 0 && question.trim().length > 0 && !sending;
  const activeConversation = conversations.find((c) => c.id === activeConvId) || detail?.conversation;
  const skillLabel =
    skillCode === 'AUTO'
      ? '自动 Skill'
      : skills.find((s) => s.code === skillCode)?.name || skillCode;

  const messages = detail?.messages || [];

  return (
    <ConfigProvider locale={zhCN} theme={getWorkbenchTheme(ACCENT)}>
      <div className="wb-agent-shell">
        {/* Left Conversation Sidebar */}
        <HeroCard shadow="sm" radius="lg" className="wb-agent-sidebar border border-black/[0.06] bg-white">
          <HeroCardBody className="flex h-full flex-col gap-3 p-3.5">
            <div className="wb-agent-brand">
              <span className="wb-agent-brand-mark" aria-hidden="true">
                <RobotOutlined />
              </span>
              <div>
                <h2 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: '#1d1d1f' }}>
                  广告分析 Agent
                </h2>
                <p style={{ margin: '2px 0 0', fontSize: 11, color: '#626267' }}>
                  只读分析 · 数据库直查
                </p>
              </div>
            </div>

            <HeroButton
              color="primary"
              variant="flat"
              size="sm"
              startContent={<PlusOutlined />}
              className="w-full font-semibold"
              isDisabled={sending}
              onPress={startNewConversation}
            >
              新建对话
            </HeroButton>

            <div className="flex items-center justify-between px-1">
              <HeroChip
                size="sm"
                variant="dot"
                color={aiStatus?.configured ? 'success' : 'warning'}
              >
                {aiStatus?.configured
                  ? `${skills.length} Skills · ${models.length} Models`
                  : '分析模型未就绪'}
              </HeroChip>
              <span className="wb-mono text-[11px] text-[#626267]">
                {conversations.length} 个会话
              </span>
            </div>

            <div className="wb-agent-conv-list">
              {conversations.length ? (
                conversations.map((conv) => {
                  const isActive = conv.id === activeConvId;
                  return (
                    <button
                      key={conv.id}
                      type="button"
                      className={`wb-agent-conv-item${isActive ? ' is-active' : ''}`}
                      onClick={() => openConversation(conv.id)}
                    >
                      <div className="wb-agent-conv-title">{conv.title || '新对话'}</div>
                      <div className="wb-agent-conv-preview">
                        {conv.lastMessagePreview || '等待第一条消息'}
                      </div>
                      <div className="wb-agent-conv-meta">
                        <span>{conv.messageCount || 0} 条</span>
                        <span>{formatTimeShort(conv.lastMessageAt || conv.createdAt)}</span>
                      </div>
                    </button>
                  );
                })
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="还没有历史对话"
                  style={{ margin: '24px 0' }}
                />
              )}
            </div>

            <div className="wb-agent-sidebar-foot">
              <LockOutlined style={{ fontSize: 12 }} />
              <span>仅生成分析建议，不会修改沃尔玛广告</span>
            </div>
          </HeroCardBody>
        </HeroCard>

        {/* Main Chat Workspace */}
        <HeroCard shadow="sm" radius="lg" className="wb-agent-main border border-black/[0.06] bg-white">
          <HeroCardBody className="flex h-full flex-col p-0">
            {/* Top Bar */}
            <header className="wb-agent-main-header">
              <div style={{ minWidth: 0 }}>
                <span className="wb-mono text-[10px] font-bold tracking-[0.12em] text-[#0071e3] uppercase">
                  CURRENT CONVERSATION
                </span>
                <h3 style={{ margin: '2px 0 0', fontSize: 16, fontWeight: 700, color: '#1d1d1f' }}>
                  {activeConversation?.title || '新对话'}
                </h3>
              </div>
              <Space wrap size={6}>
                <HeroChip size="sm" variant="flat" color="success">
                  本地 MySQL
                </HeroChip>
                <HeroChip size="sm" variant="flat" color="primary" className="wb-mono">
                  {modelLabel(selectedModel)}
                </HeroChip>
                <HeroButton
                  size="sm"
                  variant="light"
                  isIconOnly
                  aria-label="刷新 Agent 状态"
                  onPress={loadWorkspace}
                >
                  <ReloadOutlined />
                </HeroButton>
              </Space>
            </header>

            {bootstrapError ? (
              <div style={{ padding: '10px 18px 0' }}>
                <Alert type="error" showIcon message={bootstrapError} />
              </div>
            ) : null}

            {/* Collapsible Scope Bar */}
            <div className="wb-agent-scope-bar">
              <Collapse
                ghost
                size="small"
                items={[
                  {
                    key: 'scope',
                    label: (
                      <div className="flex flex-wrap items-center gap-2 text-[12px]">
                        <span className="font-semibold text-[#1d1d1f]">分析范围</span>
                        <HeroChip size="sm" variant="flat" color="default" className="wb-mono">
                          {`${skillLabel} · ${parentSku.trim().toUpperCase() || '全部款号'} · ${
                            adType === 'ALL' ? '全部广告' : `${adType} 广告`
                          } · ${startDate} 至 ${endDate}`}
                        </HeroChip>
                      </div>
                    ),
                    children: (
                      <div className="wb-agent-scope-grid">
                        <label className="wb-filter-field">
                          <span className="wb-filter-field-label">分析 Skill</span>
                          <Select
                            size="small"
                            value={skillCode}
                            onChange={(val) => setSkillCode(val)}
                            options={[
                              { value: 'AUTO', label: '自动选择' },
                              ...skills.map((s) => ({
                                value: s.code,
                                label: `${s.name} · v${s.version || 1}`,
                              })),
                            ]}
                          />
                        </label>
                        <label className="wb-filter-field">
                          <span className="wb-filter-field-label">限定款号</span>
                          <Input
                            size="small"
                            placeholder="全部款号（可选）"
                            value={parentSku}
                            onChange={(e) => setParentSku(e.target.value)}
                          />
                        </label>
                        <div className="wb-filter-field">
                          <span className="wb-filter-field-label">广告类型</span>
                          <AppleSegmentedTabs
                            ariaLabel="广告类型筛选"
                            value={adType}
                            onChange={(val) => setAdType(val)}
                            options={[
                              { label: '全部', value: 'ALL' },
                              { label: 'SP', value: 'SP' },
                              { label: 'SB', value: 'SB' },
                              { label: 'SV', value: 'SV' },
                            ]}
                          />
                        </div>
                        <label className="wb-filter-field">
                          <span className="wb-filter-field-label">开始日期</span>
                          <Input
                            size="small"
                            type="date"
                            value={startDate}
                            onChange={(e) => setStartDate(e.target.value)}
                          />
                        </label>
                        <label className="wb-filter-field">
                          <span className="wb-filter-field-label">结束日期</span>
                          <Input
                            size="small"
                            type="date"
                            value={endDate}
                            onChange={(e) => setEndDate(e.target.value)}
                          />
                        </label>
                      </div>
                    ),
                  },
                ]}
              />
            </div>

            {/* Messages Thread */}
            <div className="wb-agent-messages" role="log" aria-live="polite">
              {messages.length === 0 && !sending ? (
                <div className="wb-agent-welcome">
                  <div className="wb-agent-welcome-icon">
                    <ThunderboltOutlined />
                  </div>
                  <h3 style={{ margin: '10px 0 4px', fontSize: 18, fontWeight: 700, color: '#1d1d1f' }}>
                    今天想分析什么？
                  </h3>
                  <p style={{ margin: 0, fontSize: 12, color: '#626267', maxWidth: 520 }}>
                    从已同步的领星数据中查询广告活动、款号、关键词与历史表现，结论会附带数据证据和工具记录。
                  </p>
                  <div className="wb-agent-prompt-grid">
                    {QUICK_PROMPTS.map((item) => (
                      <button
                        key={item.title}
                        type="button"
                        className="wb-agent-prompt-card"
                        onClick={() => setQuestion(item.prompt)}
                      >
                        <HeroChip as="span" size="sm" variant="flat" color="primary">
                          {item.tag}
                        </HeroChip>
                        <strong>{item.title}</strong>
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <>
                  {messages.map((msg, index) => {
                    if (msg.role === 'USER') {
                      return (
                        <div key={`user-${index}`} className="wb-agent-msg is-user">
                          <div className="wb-agent-bubble-user">
                            <div style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</div>
                            {msg.createdAt ? (
                              <time className="wb-agent-msg-time">
                                {formatTimeShort(msg.createdAt)}
                              </time>
                            ) : null}
                          </div>
                        </div>
                      );
                    }

                    const steps = normalizeSteps(msg.run);
                    const analysis = msg.run?.response || msg.payload;
                    const failed = msg.status === 'FAILED';
                    const actions = [...(analysis?.actions || [])].sort(
                      (a, b) => (a.priority ?? 99) - (b.priority ?? 99),
                    );

                    return (
                      <div key={`assistant-${index}`} className="wb-agent-msg is-assistant">
                        <div className="wb-agent-bubble-assistant">
                          <div className="wb-agent-msg-role">
                            <RobotOutlined style={{ color: ACCENT }} />
                            <span>广告分析 Agent</span>
                            {msg.createdAt ? (
                              <time className="wb-agent-msg-time">
                                {formatTimeShort(msg.createdAt)}
                              </time>
                            ) : null}
                          </div>

                          {steps.length > 0 ? (
                            <Collapse
                              size="small"
                              defaultActiveKey={failed ? ['trace'] : []}
                              style={{ marginBottom: 10 }}
                              items={[
                                {
                                  key: 'trace',
                                  label: (
                                    <Space size={8}>
                                      <span style={{ fontWeight: 600, fontSize: 12 }}>
                                        执行过程（{steps.length} 步）
                                      </span>
                                      <Tag color={failed ? 'red' : 'green'}>
                                        {failed ? '失败' : '已完成'}
                                      </Tag>
                                    </Space>
                                  ),
                                  children: (
                                    <div style={{ display: 'grid', gap: 8 }}>
                                      {steps.map((s, si) => (
                                        <div key={s.stepId || si} className="wb-agent-step-row">
                                          <Tag
                                            color={
                                              s.status === 'FAILED'
                                                ? 'red'
                                                : s.status === 'RUNNING'
                                                  ? 'blue'
                                                  : 'green'
                                            }
                                          >
                                            {s.status === 'FAILED' ? '×' : '✓'}
                                          </Tag>
                                          <div style={{ minWidth: 0, flex: 1 }}>
                                            <div className="flex justify-between gap-2 text-[12px]">
                                              <strong>{s.title || '执行步骤'}</strong>
                                              <span className="wb-mono text-[#626267]">
                                                {durationLabel(s.durationMs)}
                                              </span>
                                            </div>
                                            {s.summary ? (
                                              <div style={{ fontSize: 11, color: '#515154' }}>
                                                {s.summary}
                                              </div>
                                            ) : null}
                                          </div>
                                        </div>
                                      ))}
                                    </div>
                                  ),
                                },
                              ]}
                            />
                          ) : null}

                          {failed ? (
                            <Alert
                              type="error"
                              showIcon
                              message={formatAgentError(
                                msg.content || msg.error,
                                msg.run?.model || detail?.conversation?.model,
                              )}
                              action={
                                msg.run?.question ? (
                                  <Space size={6}>
                                    <HeroButton
                                      size="sm"
                                      color="danger"
                                      variant="flat"
                                      onPress={() => sendMessage(msg.run!.question)}
                                    >
                                      重新发送
                                    </HeroButton>
                                    <HeroButton
                                      size="sm"
                                      color="default"
                                      variant="bordered"
                                      onPress={() => {
                                        setQuestion(msg.run!.question || '');
                                        modelSelectRef.current?.scrollIntoView({ behavior: 'smooth' });
                                      }}
                                    >
                                      切换模型重试
                                    </HeroButton>
                                  </Space>
                                ) : null
                              }
                            />
                          ) : analysis ? (
                            <div className="wb-agent-report">
                              <div className="wb-agent-report-summary">
                                {analysis.summary || 'Agent 分析完成。'}
                              </div>
                              <Space wrap size={[6, 6]} style={{ marginBottom: 10 }}>
                                <HeroChip size="sm" variant="flat" color="primary">
                                  Skill {analysis.skill || msg.run?.activatedSkill || '—'}
                                </HeroChip>
                                {analysis.dataAsOf ? (
                                  <HeroChip size="sm" variant="flat" color="default" className="wb-mono">
                                    数据截至 {analysis.dataAsOf}
                                  </HeroChip>
                                ) : null}
                                {analysis.confidence ? (
                                  <HeroChip size="sm" variant="flat" color="success">
                                    可信度{' '}
                                    {CONFIDENCE_MAP[analysis.confidence] || analysis.confidence}
                                  </HeroChip>
                                ) : null}
                                {msg.run?.totalTokens ? (
                                  <HeroChip size="sm" variant="flat" color="default" className="wb-mono">
                                    Token {msg.run.totalTokens}
                                  </HeroChip>
                                ) : null}
                              </Space>

                              {(analysis.findings || []).length > 0 ? (
                                <div className="wb-agent-section">
                                  <div className="wb-agent-section-label">核心发现</div>
                                  <div style={{ display: 'grid', gap: 8 }}>
                                    {(analysis.findings || []).map((f, fi) => (
                                      <div key={fi} className="wb-agent-finding-card">
                                        <Tag
                                          color={
                                            SEVERITY_ANT_COLOR[
                                              String(f.severity || 'info').toLowerCase()
                                            ] || 'blue'
                                          }
                                        >
                                          {f.severity || 'info'}
                                        </Tag>
                                        <div style={{ minWidth: 0, flex: 1 }}>
                                          <strong style={{ fontSize: 12, color: '#1d1d1f' }}>
                                            {f.title || '发现'}
                                          </strong>
                                          {f.evidence ? (
                                            <p style={{ margin: '3px 0 0', fontSize: 12, color: '#515154' }}>
                                              {f.evidence}
                                            </p>
                                          ) : null}
                                          {f.recommendation ? (
                                            <p style={{ margin: '3px 0 0', fontSize: 12, color: ACCENT }}>
                                              建议：{f.recommendation}
                                            </p>
                                          ) : null}
                                        </div>
                                      </div>
                                    ))}
                                  </div>
                                </div>
                              ) : null}

                              {actions.length > 0 ? (
                                <div className="wb-agent-section">
                                  <div className="wb-agent-section-label">建议动作</div>
                                  <div style={{ display: 'grid', gap: 8 }}>
                                    {actions.map((act, ai) => {
                                      const looksLikeSku = Boolean(
                                        act.target && /^[A-Z0-9_-]{4,24}$/i.test(act.target.trim()),
                                      );
                                      return (
                                        <div key={ai} className="wb-agent-action-card">
                                          <Tag color="blue" className="wb-mono">
                                            P{act.priority ?? ai + 1}
                                          </Tag>
                                          <div style={{ minWidth: 110 }}>
                                            {looksLikeSku ? (
                                              <SkuLinkTag
                                                sku={act.target}
                                                parentSku={act.target}
                                              />
                                            ) : (
                                              <strong style={{ fontSize: 12 }}>
                                                {act.target || '整体'}
                                              </strong>
                                            )}
                                          </div>
                                          <div style={{ minWidth: 0, flex: 1 }}>
                                            <div style={{ fontWeight: 650, fontSize: 12, color: '#1d1d1f' }}>
                                              {act.action}
                                            </div>
                                            {act.reason ? (
                                              <div style={{ fontSize: 11, color: '#626267', marginTop: 2 }}>
                                                {act.reason}
                                              </div>
                                            ) : null}
                                          </div>
                                        </div>
                                      );
                                    })}
                                  </div>
                                </div>
                              ) : null}

                              {(analysis.limitations || []).length > 0 ? (
                                <div className="wb-agent-limitations">
                                  <strong>分析限制：</strong>
                                  <ul>
                                    {(analysis.limitations || []).map((lim, li) => (
                                      <li key={li}>{lim}</li>
                                    ))}
                                  </ul>
                                </div>
                              ) : null}
                            </div>
                          ) : (
                            <span style={{ color: '#626267', fontSize: 12 }}>
                              分析已完成，但没有可展示的结构化结果。
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })}

                  {sending ? (
                    <div className="wb-agent-msg is-assistant">
                      <div className="wb-agent-bubble-assistant">
                        <div className="wb-agent-msg-role">
                          <HeroSpinner size="sm" color="primary" />
                          <span>正在查询数据库并生成分析（{modelLabel(liveModel)}）...</span>
                        </div>
                        {liveSteps.length > 0 ? (
                          <div style={{ display: 'grid', gap: 6, marginTop: 8 }}>
                            {liveSteps.map((s, idx) => (
                              <div key={s.stepId || idx} className="wb-agent-step-row">
                                <Tag color={s.status === 'RUNNING' ? 'blue' : 'green'}>
                                  {s.status === 'RUNNING' ? '…' : '✓'}
                                </Tag>
                                <span style={{ fontSize: 12 }}>
                                  <strong>{s.title || '执行步骤'}</strong>
                                  {s.summary ? ` — ${s.summary}` : ''}
                                </span>
                              </div>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    </div>
                  ) : null}
                </>
              )}
              <div ref={messagesEndRef} />
            </div>

            {/* Bottom Composer */}
            <div className="wb-agent-composer" ref={modelSelectRef}>
              <Input.TextArea
                rows={2}
                maxLength={2000}
                value={question}
                disabled={sending}
                placeholder="询问广告表现、款号、活动或关键词…（Enter 发送，Shift + Enter 换行）"
                onChange={(e) => setQuestion(e.target.value)}
                onPressEnter={(e) => {
                  if (!e.shiftKey) {
                    e.preventDefault();
                    if (canSend) sendMessage();
                  }
                }}
                style={{ borderRadius: 12, fontSize: 13 }}
              />
              <div className="wb-agent-composer-bar">
                <Space size={8}>
                  <span style={{ fontSize: 11, color: '#626267' }}>文本模型</span>
                  <Select
                    size="small"
                    value={selectedModel || undefined}
                    onChange={(val) => setSelectedModel(val)}
                    style={{ minWidth: 210 }}
                    placeholder="读取模型…"
                    options={models.map((m) => ({
                      value: m.id,
                      label: `${m.name}${m.recommended ? ' · 推荐' : ''}`,
                    }))}
                  />
                </Space>
                <HeroButton
                  color="primary"
                  size="sm"
                  endContent={<SendOutlined />}
                  isLoading={sending}
                  isDisabled={!canSend}
                  onPress={() => sendMessage()}
                >
                  {sending ? '分析中' : '发送'}
                </HeroButton>
              </div>
              <div className="wb-agent-composer-foot">
                <span>Enter 发送 · Shift + Enter 换行</span>
                <span>数据来自本地已同步数据库，不实时请求领星 MCP</span>
              </div>
            </div>
          </HeroCardBody>
        </HeroCard>
      </div>
    </ConfigProvider>
  );
};

import { App as AntdApp, Button, Checkbox, Divider, Drawer, Empty, Form, Input, InputNumber, Select, Space, Switch } from 'antd';
import { Activity, ArrowRight, Copy, Eye, Flame, Play, Power, RotateCw, Square, Trash2 } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, unwrap } from '../api';
import { TerminalView, type TerminalViewHandle } from '../components/terminal/TerminalView';
import { useTerminalSession } from '../components/terminal/useTerminalSession';
import type { AccessTarget, CommandSource, PageResult, RiskLevel } from '../types';
import { TargetStatusTag } from '../ui/StatusTag';

type RiskCheckResult = {
  riskLevel: RiskLevel;
  matchedRule: string;
  message: string;
  executable: boolean;
};

type TraceFormValues = {
  classPattern: string;
  methodPattern: string;
  costThresholdMs?: number;
  customCondition?: string;
  times: number;
  maxMatch: number;
  timeoutSeconds: number;
  regex?: boolean;
  includeJdkMethod?: boolean;
  classLoaderHash?: string;
  excludeClassPattern?: string;
};

type WatchFormValues = {
  classPattern: string;
  methodPattern: string;
  expression: string;
  condition?: string;
  expandDepth: number;
  times: number;
  maxMatch: number;
  timeoutSeconds: number;
  before?: boolean;
  success?: boolean;
  exception?: boolean;
  finish?: boolean;
  regex?: boolean;
  verbose?: boolean;
  classLoaderHash?: string;
  excludeClassPattern?: string;
};

type ProfilerFormValues = {
  event: 'wall' | 'cpu' | 'alloc' | 'lock';
  durationSeconds: number;
  format: 'md' | 'flat' | 'tree' | 'traces' | 'collapsed';
  topN: number;
};

type ProfilerRun = {
  startedAt: number;
  durationSeconds: number;
  stopCommand: string;
  startCommand: string;
  event: ProfilerFormValues['event'];
  autoStopDue?: boolean;
};

const TRACE_INITIAL_VALUES: TraceFormValues = {
  classPattern: '',
  methodPattern: '',
  costThresholdMs: 100,
  times: 1,
  maxMatch: 10,
  timeoutSeconds: 300,
  regex: false,
  includeJdkMethod: false
};

const PROFILER_INITIAL_VALUES: ProfilerFormValues = {
  event: 'wall',
  durationSeconds: 600,
  format: 'md',
  topN: 10
};

const WATCH_INITIAL_VALUES: WatchFormValues = {
  classPattern: '',
  methodPattern: '',
  expression: '{params, target, returnObj}',
  expandDepth: 2,
  times: 1,
  maxMatch: 50,
  timeoutSeconds: 300,
  before: false,
  success: false,
  exception: false,
  finish: true,
  regex: false,
  verbose: false
};

export default function ConsolePage() {
  const { targetId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message, modal } = AntdApp.useApp();
  const id = targetId ? Number(targetId) : undefined;
  const hasSelectedTarget = Number.isFinite(id);
  const [command, setCommand] = useState('dashboard -n 1');
  const [traceOpen, setTraceOpen] = useState(false);
  const [watchOpen, setWatchOpen] = useState(false);
  const [profilerOpen, setProfilerOpen] = useState(false);
  const [profilerRun, setProfilerRun] = useState<ProfilerRun>();
  const [, setProfilerTick] = useState(0);
  const terminalRef = useRef<TerminalViewHandle>(null);
  const profilerTimerRef = useRef<number>();
  const runningRef = useRef(false);
  const terminalSession = useTerminalSession({
    enabled: hasSelectedTarget,
    targetId: id,
    terminalRef
  });
  const target = useQuery({
    queryKey: ['access-target', id],
    queryFn: () => unwrap<AccessTarget>(api.get(`/api/access-targets/${id}`)),
    enabled: hasSelectedTarget,
    retry: false
  });
  const detach = useMutation({
    mutationFn: () => unwrap<AccessTarget>(api.post(`/api/access-targets/${id}/detach`)),
    onSuccess: async () => {
      terminalSession.close();
      terminalRef.current?.clear();
      message.success('已断开当前控制台');
      await queryClient.invalidateQueries({ queryKey: ['access-target', id] });
      await queryClient.invalidateQueries({ queryKey: ['access-targets'] });
      await queryClient.invalidateQueries({ queryKey: ['access-stats'] });
      navigate('/console');
    },
    onError: (error) => {
      message.error(error instanceof Error ? error.message : '断开失败');
    }
  });

  const quickCommands = useMemo(() => [
    { label: 'dashboard', command: 'dashboard -n 1' },
    { label: 'thread', command: 'thread' },
    { label: 'jvm', command: 'jvm' },
    { label: 'memory', command: 'memory' },
    { label: 'version', command: 'version' }
  ], []);

  useEffect(() => {
    runningRef.current = terminalSession.running;
  }, [terminalSession.running]);

  useEffect(() => () => {
    if (profilerTimerRef.current !== undefined) {
      window.clearTimeout(profilerTimerRef.current);
    }
  }, []);

  useEffect(() => {
    if (id === undefined) {
      setProfilerRun(undefined);
      return;
    }
    setProfilerRun(readProfilerRun(id));
  }, [id]);

  useEffect(() => {
    if (id === undefined) {
      return;
    }
    if (profilerRun) {
      localStorage.setItem(profilerStorageKey(id), JSON.stringify(profilerRun));
    } else {
      localStorage.removeItem(profilerStorageKey(id));
    }
  }, [id, profilerRun]);

  useEffect(() => {
    if (!profilerOpen) {
      return;
    }
    const interval = window.setInterval(() => setProfilerTick((value) => value + 1), 1000);
    return () => window.clearInterval(interval);
  }, [profilerOpen]);

  const execute = async (nextCommand = command, source: CommandSource = 'MANUAL', timeoutSeconds?: number) => {
    if (!hasSelectedTarget || id === undefined) {
      message.warning('请先选择要进入的控制台');
      return false;
    }
    if (!terminalSession.ready) {
      message.warning('控制台连接尚未就绪');
      return false;
    }
    const normalizedCommand = nextCommand.trim();
    if (!normalizedCommand) {
      message.warning('请输入要执行的 Arthas 命令');
      return false;
    }
    try {
      const risk = await unwrap<RiskCheckResult>(api.post('/api/commands/risk-check', {
        targetId: id,
        command: normalizedCommand
      }));
      if (risk.riskLevel === 'DENY' || !risk.executable) {
        message.error(risk.message);
        return false;
      }
      if (risk.riskLevel === 'CONFIRM') {
        const confirmed = await confirmRiskCommand(risk, normalizedCommand, modal);
        if (!confirmed) {
          return false;
        }
      }
      const started = terminalSession.execute({
        command: normalizedCommand,
        riskConfirmed: risk.riskLevel === 'CONFIRM',
        source,
        timeoutSeconds
      });
      if (started) {
        setCommand(normalizedCommand);
      }
      return started;
    } catch (error) {
      message.error(error instanceof Error ? error.message : '命令风险检查失败');
      return false;
    }
  };

  const stop = () => {
    terminalSession.stop();
  };

  const startProfiler = async (values: ProfilerFormValues) => {
    const startCommand = buildProfilerStartCommand(values);
    const stopCommand = buildProfilerStopCommand(values);
    if (await execute(startCommand, 'MANUAL', 60)) {
      setProfilerRun({
        startedAt: Date.now(),
        durationSeconds: values.durationSeconds,
        stopCommand,
        startCommand,
        event: values.event
      });
      message.success(`Profiler 已开始采样，${values.durationSeconds} 秒后自动输出报告`);
    }
  };

  const stopProfiler = async (stopCommand: string) => {
    if (profilerTimerRef.current !== undefined) {
      window.clearTimeout(profilerTimerRef.current);
      profilerTimerRef.current = undefined;
    }
    setProfilerRun(undefined);
    await execute(stopCommand, 'MANUAL', 120);
  };

  const forgetProfilerRun = () => {
    if (profilerTimerRef.current !== undefined) {
      window.clearTimeout(profilerTimerRef.current);
      profilerTimerRef.current = undefined;
    }
    setProfilerRun(undefined);
  };

  useEffect(() => {
    if (profilerTimerRef.current !== undefined) {
      window.clearTimeout(profilerTimerRef.current);
      profilerTimerRef.current = undefined;
    }
    if (!profilerRun || profilerRun.autoStopDue) {
      return;
    }
    const dueAt = profilerRun.startedAt + profilerRun.durationSeconds * 1000;
    const delay = Math.max(0, dueAt - Date.now());
    profilerTimerRef.current = window.setTimeout(() => {
      profilerTimerRef.current = undefined;
      if (runningRef.current) {
        setProfilerRun((current) => current ? { ...current, autoStopDue: true } : current);
        message.warning('Profiler 采样已到时，但当前有命令正在执行；命令结束后会自动停止并输出报告');
        return;
      }
      void stopProfiler(profilerRun.stopCommand);
    }, delay);
  }, [profilerRun]);

  useEffect(() => {
    if (profilerRun?.autoStopDue && !terminalSession.running) {
      void stopProfiler(profilerRun.stopCommand);
    }
  }, [profilerRun, terminalSession.running]);

  if (!hasSelectedTarget) {
    return <ConsoleTargetPicker />;
  }

  return (
    <>
      <div className="page-header console-page-header">
        <div>
          <h1>{target.data ? target.data.name : '控制台'}</h1>
          <p>
            当前控制台：
            {target.data
              ? `${target.data.host}${target.data.containerName ? ` / ${target.data.containerName}` : ''}`
              : '正在加载目标信息'}
          </p>
        </div>
        <div className="console-header-actions">
          <Button
            danger
            icon={<Power size={16} />}
            loading={detach.isPending}
            disabled={!target.data || target.data.arthasStatus !== 'ATTACHED'}
            onClick={() => detach.mutate()}
          >
            断开
          </Button>
          <Link to="/console"><Button>切换控制台</Button></Link>
        </div>
      </div>
      {target.data && (
        <div className="summary-bar">
          <Summary label="接入名称" value={target.data.name} />
          <Summary label="主机" value={target.data.host} />
          <Summary label="目标类型" value={target.data.targetType === 'DOCKER_CONTAINER' ? 'Docker 容器' : '物理机 Java'} />
          <Summary label="目标名称" value={target.data.containerName || target.data.processName || '-'} />
          <Summary label="PID" value={target.data.processId || '-'} />
          <div className="summary-item"><span>状态</span><TargetStatusTag status={target.data.arthasStatus} /></div>
        </div>
      )}
      <div className="panel" style={{ padding: 20 }}>
        <Space className="console-quick-actions">
          {quickCommands.map((item) => <Button key={item.label} onClick={() => execute(item.command, 'QUICK')}>{item.label}</Button>)}
          <Button icon={<Activity size={16} />} onClick={() => setTraceOpen(true)}>Trace</Button>
          <Button icon={<Eye size={16} />} onClick={() => setWatchOpen(true)}>Watch</Button>
          <Button icon={<Flame size={16} />} onClick={() => setProfilerOpen(true)}>Profiler</Button>
        </Space>
        <TerminalView ref={terminalRef} />
      </div>
      <div className="command-bar">
        <Input value={command} onChange={(event) => setCommand(event.target.value)} placeholder="输入 Arthas 命令，例如 dashboard -n 1 / thread / jvm" onPressEnter={() => execute()} />
        <Button type="primary" icon={<Play size={16} />} onClick={() => execute()} disabled={terminalSession.running}>执行</Button>
        <Button icon={<Square size={16} />} onClick={stop} disabled={!terminalSession.running}>停止</Button>
        <Button icon={<Trash2 size={16} />} onClick={() => terminalRef.current?.clear()}>清空</Button>
        <Button icon={<Copy size={16} />} onClick={() => navigator.clipboard.writeText(terminalRef.current?.copyText() ?? '')}>复制</Button>
        <Link to="/commands">查看命令历史</Link>
      </div>
      <TraceCommandDrawer
        open={traceOpen}
        running={terminalSession.running}
        onClose={() => setTraceOpen(false)}
        onSubmit={async ({ command: traceCommand, timeoutSeconds }) => {
          if (await execute(traceCommand, 'MANUAL', timeoutSeconds)) {
            setTraceOpen(false);
          }
        }}
      />
      <WatchCommandDrawer
        open={watchOpen}
        running={terminalSession.running}
        onClose={() => setWatchOpen(false)}
        onSubmit={async ({ command: watchCommand, timeoutSeconds }) => {
          if (await execute(watchCommand, 'MANUAL', timeoutSeconds)) {
            setWatchOpen(false);
          }
        }}
      />
      <ProfilerCommandDrawer
        open={profilerOpen}
        running={terminalSession.running}
        profilerRun={profilerRun}
        onClose={() => setProfilerOpen(false)}
        onStart={startProfiler}
        onStop={stopProfiler}
        onForget={forgetProfilerRun}
        onRunCommand={(nextCommand, timeoutSeconds) => execute(nextCommand, 'MANUAL', timeoutSeconds)}
      />
    </>
  );
}

function ConsoleTargetPicker() {
  const targets = useQuery({
    queryKey: ['access-targets', 'console-picker'],
    queryFn: () => unwrap<PageResult<AccessTarget>>(api.get('/api/access-targets?page=1&pageSize=50'))
  });
  const attachedTargets = (targets.data?.items ?? []).filter((item) => item.arthasStatus === 'ATTACHED');

  return (
    <>
      <div className="page-header">
        <h1>选择控制台</h1>
        <p>先确认要进入的接入目标，再打开对应 Arthas 控制台</p>
      </div>
      <div className="panel console-picker">
        {targets.isLoading && <div className="empty-state">正在加载已接入目标...</div>}
        {!targets.isLoading && attachedTargets.length === 0 && (
          <Empty description="暂无已接入的控制台">
            <Link to="/access"><Button type="primary">去接入管理</Button></Link>
          </Empty>
        )}
        {attachedTargets.map((item) => (
          <div className="console-target-row" key={item.id}>
            <div className="console-target-main">
              <strong>{item.name}</strong>
              <span>{item.host}{item.containerName ? ` / ${item.containerName}` : ''}{item.processId ? ` / PID ${item.processId}` : ''}</span>
            </div>
            <TargetStatusTag status={item.arthasStatus} />
            <Link to={`/console/${item.id}`}>
              <Button type="primary" icon={<ArrowRight size={16} />}>进入控制台</Button>
            </Link>
          </div>
        ))}
      </div>
    </>
  );
}

function Summary({ label, value }: { label: string; value: ReactNode }) {
  return <div className="summary-item"><span>{label}</span><strong>{value}</strong></div>;
}

function TraceCommandDrawer({
  onClose,
  onSubmit,
  open,
  running
}: {
  onClose: () => void;
  onSubmit: (request: { command: string; timeoutSeconds: number }) => void | Promise<void>;
  open: boolean;
  running: boolean;
}) {
  const [form] = Form.useForm<TraceFormValues>();
  const values = Form.useWatch([], form) ?? TRACE_INITIAL_VALUES;
  const commandPreview = buildTraceCommand(values);

  return (
    <Drawer
      destroyOnClose
      open={open}
      title="Trace 调用链"
      width={560}
      onClose={onClose}
      footer={(
        <div className="trace-drawer-footer">
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" icon={<Play size={16} />} disabled={running} onClick={() => form.submit()}>
            开始 Trace
          </Button>
        </div>
      )}
    >
      <Form
        form={form}
        initialValues={TRACE_INITIAL_VALUES}
        layout="vertical"
        onFinish={(formValues) => onSubmit({
          command: buildTraceCommand(formValues),
          timeoutSeconds: formValues.timeoutSeconds
        })}
      >
        <Form.Item
          label="类名表达式"
          name="classPattern"
          rules={[
            { required: true, message: '请输入类名表达式' },
            { pattern: /^\S+$/, message: '类名表达式不能包含空格' }
          ]}
        >
          <Input placeholder="com.example.OrderService 或 *OrderService" />
        </Form.Item>
        <Form.Item
          label="方法表达式"
          name="methodPattern"
          rules={[
            { required: true, message: '请输入方法表达式' },
            { pattern: /^\S+$/, message: '方法表达式不能包含空格' }
          ]}
        >
          <Input placeholder="createOrder 或 *" />
        </Form.Item>

        <div className="trace-form-grid">
          <Form.Item label="慢调用阈值 ms" name="costThresholdMs">
            <InputNumber min={0} max={600000} step={10} placeholder="100" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="捕获次数"
            name="times"
            rules={[{ required: true, message: '请输入捕获次数' }]}
          >
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="最大匹配类数"
            name="maxMatch"
            rules={[{ required: true, message: '请输入最大匹配类数' }]}
          >
            <InputNumber min={1} max={200} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="采样窗口 秒"
            name="timeoutSeconds"
            tooltip="在这个时间内等待目标方法调用；达到捕获次数会提前结束，也可以手动停止。"
            rules={[{ required: true, message: '请输入采样窗口' }]}
          >
            <InputNumber min={10} max={3600} step={30} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="正则匹配" name="regex" valuePropName="checked">
            <Switch />
          </Form.Item>
        </div>

        <Form.Item label="自定义条件表达式" name="customCondition">
          <Input.TextArea rows={2} placeholder="params[0] != null 或 #cost > 200；填写后会覆盖慢调用阈值" />
        </Form.Item>

        <Divider />

        <Form.Item name="includeJdkMethod" valuePropName="checked">
          <Checkbox>包含 JDK 方法调用</Checkbox>
        </Form.Item>
        <Form.Item label="ClassLoader Hash" name="classLoaderHash">
          <Input placeholder="可选，例如 3d4eac69" />
        </Form.Item>
        <Form.Item label="排除类表达式" name="excludeClassPattern">
          <Input placeholder="可选，例如 com.example.Filter" />
        </Form.Item>

        <div className="trace-command-preview">
          <span>命令预览</span>
          <code>{commandPreview}</code>
        </div>
      </Form>
    </Drawer>
  );
}

function WatchCommandDrawer({
  onClose,
  onSubmit,
  open,
  running
}: {
  onClose: () => void;
  onSubmit: (request: { command: string; timeoutSeconds: number }) => void | Promise<void>;
  open: boolean;
  running: boolean;
}) {
  const [form] = Form.useForm<WatchFormValues>();
  const values = Form.useWatch([], form) ?? WATCH_INITIAL_VALUES;
  const commandPreview = buildWatchCommand(values);

  return (
    <Drawer
      destroyOnClose
      open={open}
      title="Watch 方法数据"
      width={620}
      onClose={onClose}
      footer={(
        <div className="trace-drawer-footer">
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" icon={<Eye size={16} />} disabled={running} onClick={() => form.submit()}>
            开始 Watch
          </Button>
        </div>
      )}
    >
      <Form
        form={form}
        initialValues={WATCH_INITIAL_VALUES}
        layout="vertical"
        onFinish={(formValues) => onSubmit({
          command: buildWatchCommand(formValues),
          timeoutSeconds: formValues.timeoutSeconds
        })}
      >
        <Form.Item
          label="类名表达式"
          name="classPattern"
          rules={[
            { required: true, message: '请输入类名表达式' },
            { pattern: /^\S+$/, message: '类名表达式不能包含空格' }
          ]}
        >
          <Input placeholder="com.example.OrderService 或 *OrderService" />
        </Form.Item>
        <Form.Item
          label="方法表达式"
          name="methodPattern"
          rules={[
            { required: true, message: '请输入方法表达式' },
            { pattern: /^\S+$/, message: '方法表达式不能包含空格' }
          ]}
        >
          <Input placeholder="createOrder 或 *" />
        </Form.Item>

        <Form.Item
          label="观察表达式"
          name="expression"
          tooltip="Arthas watch 的 OGNL 表达式，用来指定输出入参、返回值、异常对象或目标对象。"
          rules={[{ required: true, message: '请输入观察表达式' }]}
        >
          <Input.TextArea rows={2} placeholder="{params, target, returnObj} 或 {params, returnObj, throwExp}" />
        </Form.Item>
        <Form.Item
          label="条件表达式"
          name="condition"
          tooltip="可选。只输出满足条件的调用，例如 #cost > 100 或 params[0] != null。"
        >
          <Input.TextArea rows={2} placeholder="#cost > 100 或 params[0] != null" />
        </Form.Item>

        <div className="watch-location-grid">
          <Form.Item name="before" valuePropName="checked">
            <Checkbox>调用前 -b</Checkbox>
          </Form.Item>
          <Form.Item name="success" valuePropName="checked">
            <Checkbox>正常返回 -s</Checkbox>
          </Form.Item>
          <Form.Item name="exception" valuePropName="checked">
            <Checkbox>异常抛出 -e</Checkbox>
          </Form.Item>
          <Form.Item name="finish" valuePropName="checked">
            <Checkbox>结束时 -f</Checkbox>
          </Form.Item>
        </div>

        <div className="trace-form-grid">
          <Form.Item
            label="展开深度"
            name="expandDepth"
            tooltip="对应 -x，Arthas 最大支持 4。"
            rules={[{ required: true, message: '请输入展开深度' }]}
          >
            <InputNumber min={1} max={4} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="捕获次数"
            name="times"
            tooltip="对应 -n，达到次数后自动结束。"
            rules={[{ required: true, message: '请输入捕获次数' }]}
          >
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="最大匹配类数"
            name="maxMatch"
            tooltip="对应 -m，限制增强的类数量。"
            rules={[{ required: true, message: '请输入最大匹配类数' }]}
          >
            <InputNumber min={1} max={200} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="等待窗口 秒"
            name="timeoutSeconds"
            tooltip="Fordring 等待 watch 输出的最长时间；达到捕获次数会提前结束，也可以手动停止。"
            rules={[{ required: true, message: '请输入等待窗口' }]}
          >
            <InputNumber min={10} max={3600} step={30} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="正则匹配" name="regex" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item label="Verbose" name="verbose" valuePropName="checked">
            <Switch />
          </Form.Item>
        </div>

        <Divider />

        <Form.Item label="ClassLoader Hash" name="classLoaderHash">
          <Input placeholder="可选，例如 3d4eac69" />
        </Form.Item>
        <Form.Item label="排除类表达式" name="excludeClassPattern">
          <Input placeholder="可选，例如 com.example.Filter" />
        </Form.Item>

        <div className="trace-command-preview">
          <span>命令预览</span>
          <code>{commandPreview}</code>
        </div>
      </Form>
    </Drawer>
  );
}

function ProfilerCommandDrawer({
  onForget,
  onClose,
  onRunCommand,
  onStart,
  onStop,
  open,
  profilerRun,
  running
}: {
  onForget: () => void;
  onClose: () => void;
  onRunCommand: (command: string, timeoutSeconds?: number) => void | Promise<boolean>;
  onStart: (values: ProfilerFormValues) => void | Promise<void>;
  onStop: (stopCommand: string) => void | Promise<void>;
  open: boolean;
  profilerRun?: ProfilerRun;
  running: boolean;
}) {
  const [form] = Form.useForm<ProfilerFormValues>();
  const values = Form.useWatch([], form) ?? PROFILER_INITIAL_VALUES;
  const startCommand = buildProfilerStartCommand(values);
  const stopCommand = profilerRun?.stopCommand ?? buildProfilerStopCommand(values);
  const remainingSeconds = profilerRun
    ? Math.max(0, Math.ceil((profilerRun.startedAt + profilerRun.durationSeconds * 1000 - Date.now()) / 1000))
    : undefined;
  const elapsedSeconds = profilerRun
    ? Math.max(0, Math.floor((Date.now() - profilerRun.startedAt) / 1000))
    : undefined;
  const statusText = profilerRun?.autoStopDue
    ? '采样已到时，等待当前命令结束后自动输出报告。'
    : `已采样 ${elapsedSeconds} 秒，预计 ${remainingSeconds} 秒后自动输出报告。`;

  return (
    <Drawer
      destroyOnClose
      open={open}
      title="Profiler 采样"
      width={560}
      onClose={onClose}
      footer={(
        <div className="trace-drawer-footer">
          <Button onClick={onClose}>关闭</Button>
          {profilerRun && (
            <Button onClick={onForget}>
              仅清除本地状态
            </Button>
          )}
          <Button
            icon={<Square size={16} />}
            disabled={running}
            onClick={() => onStop(stopCommand)}
          >
            {profilerRun ? '停止并输出' : '停止已有采样并输出'}
          </Button>
          <Button
            type="primary"
            icon={<Play size={16} />}
            disabled={running || !!profilerRun}
            onClick={() => form.submit()}
          >
            开始采样
          </Button>
        </div>
      )}
    >
      <Form
        form={form}
        initialValues={PROFILER_INITIAL_VALUES}
        layout="vertical"
        onFinish={(formValues) => onStart(formValues)}
      >
        {profilerRun && (
          <div className="profiler-running-state">
            <strong>{profilerRun.event} 采样中</strong>
            <span>{statusText}</span>
            <code>{profilerRun.startCommand}</code>
          </div>
        )}

        <div className="profiler-session-actions">
          <Button
            icon={<RotateCw size={16} />}
            disabled={running}
            onClick={() => onRunCommand('profiler status', 30)}
          >
            查看状态
          </Button>
          <Button
            disabled={running}
            onClick={() => onRunCommand('profiler getSamples', 30)}
          >
            查看采样数
          </Button>
          <Button
            disabled={running}
            onClick={() => onRunCommand('profiler list', 30)}
          >
            支持事件
          </Button>
        </div>

        <div className="trace-form-grid">
          <Form.Item
            label="采样事件"
            name="event"
            tooltip="wall 更适合定位慢请求、IO、锁等待和 RPC 等等待型耗时；cpu 更适合定位 CPU 热点。"
            rules={[{ required: true, message: '请选择采样事件' }]}
          >
            <Select
              options={[
                { value: 'wall', label: 'wall：慢调用/等待耗时' },
                { value: 'cpu', label: 'cpu：CPU 热点' },
                { value: 'alloc', label: 'alloc：对象分配' },
                { value: 'lock', label: 'lock：锁竞争' }
              ]}
            />
          </Form.Item>
          <Form.Item
            label="采样时长 秒"
            name="durationSeconds"
            tooltip="Fordring 会按这个时长计时，到点后自动执行 profiler stop，让报告直接输出到控制台。"
            rules={[{ required: true, message: '请输入采样时长' }]}
          >
            <InputNumber min={10} max={86400} step={30} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            label="输出格式"
            name="format"
            tooltip="md=TopN 更适合直接在控制台查看热点；flat 是纯文本方法列表。"
            rules={[{ required: true, message: '请选择输出格式' }]}
          >
            <Select
              options={[
                { value: 'md', label: 'md：Top 热点报告' },
                { value: 'flat', label: 'flat：方法耗时列表' },
                { value: 'tree', label: 'tree：调用树' },
                { value: 'traces', label: 'traces：调用轨迹' },
                { value: 'collapsed', label: 'collapsed：折叠栈' }
              ]}
            />
          </Form.Item>
          <Form.Item
            label="Top N"
            name="topN"
            tooltip="仅 md 格式使用，例如 md=10 表示输出前 10 个热点。"
            rules={[{ required: true, message: '请输入 Top N' }]}
          >
            <InputNumber min={1} max={100} style={{ width: '100%' }} disabled={values.format !== 'md'} />
          </Form.Item>
        </div>

        <div className="trace-command-preview profiler-command-preview">
          <span>开始命令</span>
          <code>{startCommand}</code>
          <span>停止输出命令</span>
          <code>{stopCommand}</code>
        </div>
      </Form>
    </Drawer>
  );
}

function buildTraceCommand(values: Partial<TraceFormValues>) {
  const classPattern = values.classPattern?.trim() ?? '';
  const methodPattern = values.methodPattern?.trim() ?? '';
  const args = ['trace'];

  if (values.regex) {
    args.push('-E');
  }
  if (values.includeJdkMethod) {
    args.push('--skipJDKMethod', 'false');
  }
  if (classPattern) {
    args.push(classPattern);
  }
  if (methodPattern) {
    args.push(methodPattern);
  }

  const condition = traceCondition(values);
  if (condition) {
    args.push(quoteArthasArgument(condition));
  }
  if (values.classLoaderHash?.trim()) {
    args.push('-c', values.classLoaderHash.trim());
  }
  if (values.times) {
    args.push('-n', String(values.times));
  }
  if (values.maxMatch) {
    args.push('-m', String(values.maxMatch));
  }
  if (values.excludeClassPattern?.trim()) {
    args.push('--exclude-class-pattern', values.excludeClassPattern.trim());
  }

  return args.join(' ');
}

function buildWatchCommand(values: Partial<WatchFormValues>) {
  const classPattern = values.classPattern?.trim() ?? '';
  const methodPattern = values.methodPattern?.trim() ?? '';
  const expression = values.expression?.trim() || WATCH_INITIAL_VALUES.expression;
  const condition = values.condition?.trim();
  const args = ['watch'];

  if (values.regex) {
    args.push('-E');
  }
  if (values.verbose) {
    args.push('-v');
  }
  if (values.before) {
    args.push('-b');
  }
  if (values.success) {
    args.push('-s');
  }
  if (values.exception) {
    args.push('-e');
  }
  if (values.finish || (!values.before && !values.success && !values.exception)) {
    args.push('-f');
  }
  if (classPattern) {
    args.push(classPattern);
  }
  if (methodPattern) {
    args.push(methodPattern);
  }
  args.push(quoteArthasArgument(expression));
  if (condition) {
    args.push(quoteArthasArgument(condition));
  }
  args.push('-x', String(values.expandDepth ?? WATCH_INITIAL_VALUES.expandDepth));
  args.push('-n', String(values.times ?? WATCH_INITIAL_VALUES.times));
  args.push('-m', String(values.maxMatch ?? WATCH_INITIAL_VALUES.maxMatch));
  if (values.classLoaderHash?.trim()) {
    args.push('-c', values.classLoaderHash.trim());
  }
  if (values.excludeClassPattern?.trim()) {
    args.push('--exclude-class-pattern', values.excludeClassPattern.trim());
  }

  return args.join(' ');
}

function buildProfilerStartCommand(values: Partial<ProfilerFormValues>) {
  const event = values.event ?? PROFILER_INITIAL_VALUES.event;
  return `profiler start --event ${event}`;
}

function buildProfilerStopCommand(values: Partial<ProfilerFormValues>) {
  const format = values.format ?? PROFILER_INITIAL_VALUES.format;
  if (format === 'md') {
    const topN = values.topN ?? PROFILER_INITIAL_VALUES.topN;
    return `profiler stop --format md=${topN}`;
  }
  return `profiler stop --format ${format}`;
}

function profilerStorageKey(targetId: number) {
  return `fordring.profilerRun.${targetId}`;
}

function readProfilerRun(targetId: number): ProfilerRun | undefined {
  try {
    const raw = localStorage.getItem(profilerStorageKey(targetId));
    if (!raw) {
      return undefined;
    }
    const value = JSON.parse(raw) as Partial<ProfilerRun>;
    if (!value.startedAt || !value.durationSeconds || !value.stopCommand || !value.startCommand || !value.event) {
      return undefined;
    }
    return {
      startedAt: value.startedAt,
      durationSeconds: value.durationSeconds,
      stopCommand: value.stopCommand,
      startCommand: value.startCommand,
      event: value.event,
      autoStopDue: value.autoStopDue
    };
  } catch {
    return undefined;
  }
}

function traceCondition(values: Partial<TraceFormValues>) {
  const customCondition = values.customCondition?.trim();
  if (customCondition) {
    return customCondition;
  }
  if (values.costThresholdMs && values.costThresholdMs > 0) {
    return `#cost > ${values.costThresholdMs}`;
  }
  return '';
}

function quoteArthasArgument(value: string) {
  if (!value.includes("'")) {
    return `'${value}'`;
  }
  if (!value.includes('"')) {
    return `"${value}"`;
  }
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function confirmRiskCommand(risk: RiskCheckResult, command: string, modal: ReturnType<typeof AntdApp.useApp>['modal']) {
  return new Promise<boolean>((resolve) => {
    modal.confirm({
      title: '确认执行高风险命令',
      content: (
        <div className="risk-confirm-content">
          <p>{risk.message}</p>
          <code>{command}</code>
        </div>
      ),
      okText: '确认执行',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onCancel: () => resolve(false),
      onOk: () => resolve(true)
    });
  });
}

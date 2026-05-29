import { App as AntdApp, Button, Checkbox, Divider, Drawer, Empty, Form, Input, InputNumber, Space, Switch } from 'antd';
import { Activity, ArrowRight, Copy, Play, Power, Square, Trash2 } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
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

export default function ConsolePage() {
  const { targetId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message, modal } = AntdApp.useApp();
  const id = targetId ? Number(targetId) : undefined;
  const hasSelectedTarget = Number.isFinite(id);
  const [command, setCommand] = useState('dashboard -n 1');
  const [traceOpen, setTraceOpen] = useState(false);
  const terminalRef = useRef<TerminalViewHandle>(null);
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

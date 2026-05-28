import { Button, Empty, Input, message, Space } from 'antd';
import { ArrowRight, Copy, Play, Power, Square, Trash2 } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, unwrap } from '../api';
import { TerminalView, type TerminalViewHandle } from '../components/terminal/TerminalView';
import { useTerminalSession } from '../components/terminal/useTerminalSession';
import type { AccessTarget, PageResult } from '../types';
import { TargetStatusTag } from '../ui/StatusTag';

export default function ConsolePage() {
  const { targetId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const id = targetId ? Number(targetId) : undefined;
  const hasSelectedTarget = Number.isFinite(id);
  const [command, setCommand] = useState('dashboard -n 1');
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
    { label: 'memory', command: 'memory' }
  ], []);

  const execute = (nextCommand = command) => {
    if (!hasSelectedTarget || id === undefined) {
      message.warning('请先选择要进入的控制台');
      return;
    }
    if (!terminalSession.ready) {
      message.warning('控制台连接尚未就绪');
      return;
    }
    setCommand(nextCommand);
    terminalSession.execute({ command: nextCommand });
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
          <h1>{target.data ? `${target.data.environment || '未标环境'} / ${target.data.name}` : '控制台'}</h1>
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
          <Summary label="环境" value={target.data.environment || '-'} />
          <Summary label="主机" value={target.data.host} />
          <Summary label="目标类型" value={target.data.targetType === 'DOCKER_CONTAINER' ? 'Docker 容器' : '物理机 Java'} />
          <Summary label="目标名称" value={target.data.containerName || target.data.processName || '-'} />
          <Summary label="PID" value={target.data.processId || '-'} />
          <div className="summary-item"><span>状态</span><TargetStatusTag status={target.data.arthasStatus} /></div>
        </div>
      )}
      <div className="panel" style={{ padding: 20 }}>
        <Space style={{ marginBottom: 20 }}>
          {quickCommands.map((item) => <Button key={item.label} onClick={() => execute(item.command)}>{item.label}</Button>)}
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
        <p>先确认要进入的接入环境，再打开对应 Arthas 控制台</p>
      </div>
      <div className="panel console-picker">
        {targets.isLoading && <div className="empty-state">正在加载已接入环境...</div>}
        {!targets.isLoading && attachedTargets.length === 0 && (
          <Empty description="暂无已接入的控制台">
            <Link to="/access"><Button type="primary">去接入管理</Button></Link>
          </Empty>
        )}
        {attachedTargets.map((item) => (
          <div className="console-target-row" key={item.id}>
            <div className="console-target-main">
              <strong>{item.environment || '未标环境'} / {item.name}</strong>
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

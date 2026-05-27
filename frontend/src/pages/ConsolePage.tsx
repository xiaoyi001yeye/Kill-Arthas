import { Button, Input, message, Space } from 'antd';
import { Copy, Play, Square, Trash2 } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api, unwrap, wsBaseUrl } from '../api';
import type { AccessTarget } from '../types';
import { TargetStatusTag } from '../ui/StatusTag';

export default function ConsolePage() {
  const { targetId = '1' } = useParams();
  const id = Number(targetId);
  const [command, setCommand] = useState('dashboard');
  const [output, setOutput] = useState('');
  const [running, setRunning] = useState(false);
  const [executionId, setExecutionId] = useState<number>();
  const wsRef = useRef<WebSocket>();
  const target = useQuery({ queryKey: ['access-target', id], queryFn: () => unwrap<AccessTarget>(api.get(`/api/access-targets/${id}`)), retry: false });

  useEffect(() => {
    const ws = new WebSocket(`${wsBaseUrl}/ws/console?targetId=${id}`);
    wsRef.current = ws;
    ws.onmessage = (event) => {
      const data = JSON.parse(event.data);
      if (data.type === 'COMMAND_STARTED') {
        setExecutionId(data.executionId);
        setRunning(true);
      }
      if (data.type === 'COMMAND_OUTPUT') {
        setOutput((prev) => prev + data.chunk);
      }
      if (data.type === 'COMMAND_FINISHED' || data.type === 'COMMAND_FAILED' || data.type === 'COMMAND_STOPPED') {
        setRunning(false);
      }
    };
    ws.onerror = () => message.error('控制台连接失败');
    return () => ws.close();
  }, [id]);

  const quickCommands = useMemo(() => ['dashboard', 'thread', 'jvm', 'memory'], []);

  const execute = (nextCommand = command) => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
      message.warning('控制台连接尚未就绪');
      return;
    }
    setCommand(nextCommand);
    setOutput((prev) => prev + `\n[arthas@${target.data?.processId ?? id}]$ ${nextCommand}\n`);
    wsRef.current.send(JSON.stringify({
      type: 'EXECUTE_COMMAND',
      requestId: crypto.randomUUID(),
      targetId: id,
      command: nextCommand,
      timeoutSeconds: 30,
      source: 'MANUAL',
      riskConfirmed: true
    }));
  };

  const stop = () => {
    if (!executionId || !wsRef.current) return;
    wsRef.current.send(JSON.stringify({
      type: 'STOP_COMMAND',
      requestId: crypto.randomUUID(),
      executionId,
      targetId: id
    }));
  };

  return (
    <>
      <div className="page-header">
        <h1>控制台</h1>
        <p>对已接入目标执行 Arthas 命令</p>
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
          {quickCommands.map((item) => <Button key={item} onClick={() => execute(item)}>{item}</Button>)}
        </Space>
        <div className="terminal">{output || <span className="prompt">[arthas]$ 等待命令执行...</span>}</div>
      </div>
      <div className="command-bar">
        <Input value={command} onChange={(event) => setCommand(event.target.value)} placeholder="输入 Arthas 命令，例如 dashboard / thread / jvm" onPressEnter={() => execute()} />
        <Button type="primary" icon={<Play size={16} />} onClick={() => execute()} disabled={running}>执行</Button>
        <Button icon={<Square size={16} />} onClick={stop} disabled={!running}>停止</Button>
        <Button icon={<Trash2 size={16} />} onClick={() => setOutput('')}>清空</Button>
        <Button icon={<Copy size={16} />} onClick={() => navigator.clipboard.writeText(output)}>复制</Button>
        <Link to="/commands">查看命令历史</Link>
      </div>
    </>
  );
}

function Summary({ label, value }: { label: string; value: ReactNode }) {
  return <div className="summary-item"><span>{label}</span><strong>{value}</strong></div>;
}

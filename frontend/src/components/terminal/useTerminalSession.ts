import { RefObject, useCallback, useEffect, useRef, useState } from 'react';
import { wsBaseUrl } from '../../api';
import type { TerminalViewHandle } from './TerminalView';

type UseTerminalSessionOptions = {
  enabled: boolean;
  targetId?: number;
  terminalRef: RefObject<TerminalViewHandle>;
};

type ExecuteOptions = {
  command: string;
  timeoutSeconds?: number;
};

export function useTerminalSession({ enabled, targetId, terminalRef }: UseTerminalSessionOptions) {
  const wsRef = useRef<WebSocket>();
  const [ready, setReady] = useState(false);
  const [running, setRunning] = useState(false);
  const [executionId, setExecutionId] = useState<number>();

  useEffect(() => {
    if (!enabled || targetId === undefined) return;

    setReady(false);
    const ws = new WebSocket(`${wsBaseUrl}/ws/console?targetId=${targetId}`);
    wsRef.current = ws;

    ws.onopen = () => setReady(true);
    ws.onclose = () => setReady(false);
    ws.onerror = () => {
      setReady(false);
      terminalRef.current?.writeln('[fordring] 控制台连接失败');
    };
    ws.onmessage = (event) => {
      const data = JSON.parse(event.data);
      if (data.type === 'COMMAND_STARTED') {
        setExecutionId(data.executionId);
        setRunning(true);
        terminalRef.current?.writeln(`[fordring] 已提交真实 Arthas 命令，executionId=${data.executionId}`);
      }
      if (data.type === 'COMMAND_OUTPUT') {
        terminalRef.current?.write(data.chunk);
      }
      if (data.type === 'COMMAND_FAILED') {
        terminalRef.current?.writeln(`[fordring] 命令失败：${data.message || '未知错误'}`);
        setRunning(false);
      }
      if (data.type === 'COMMAND_STOPPED') {
        terminalRef.current?.writeln('[fordring] 命令已停止');
        setRunning(false);
      }
      if (data.type === 'COMMAND_FINISHED') {
        terminalRef.current?.writeln(`[fordring] 命令完成，耗时 ${data.durationMs ?? '-'}ms`);
        setRunning(false);
      }
    };

    return () => {
      ws.close();
      if (wsRef.current === ws) {
        wsRef.current = undefined;
      }
    };
  }, [enabled, targetId, terminalRef]);

  const execute = useCallback(({ command, timeoutSeconds = 30 }: ExecuteOptions) => {
    if (targetId === undefined || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
      return false;
    }
    wsRef.current.send(JSON.stringify({
      type: 'EXECUTE_COMMAND',
      requestId: crypto.randomUUID(),
      targetId,
      command,
      timeoutSeconds,
      source: 'MANUAL',
      riskConfirmed: true
    }));
    return true;
  }, [targetId]);

  const stop = useCallback(() => {
    if (targetId === undefined || !executionId || !wsRef.current) {
      return;
    }
    wsRef.current.send(JSON.stringify({
      type: 'STOP_COMMAND',
      requestId: crypto.randomUUID(),
      executionId,
      targetId
    }));
  }, [executionId, targetId]);

  const close = useCallback(() => {
    wsRef.current?.close();
    wsRef.current = undefined;
    setReady(false);
    setRunning(false);
    setExecutionId(undefined);
  }, []);

  return {
    close,
    execute,
    executionId,
    ready,
    running,
    stop
  };
}

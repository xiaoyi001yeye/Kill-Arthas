import { Terminal as XTerm } from '@xterm/xterm';
import '@xterm/xterm/css/xterm.css';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

const MAX_COPY_BUFFER = 1_048_576;
const MIN_COLS = 20;
const MAX_COLS = 240;
const MIN_ROWS = 12;
const MAX_ROWS = 80;

export type TerminalViewHandle = {
  write: (content: string) => void;
  writeln: (content: string) => void;
  clear: () => void;
  copyText: () => string;
};

type TerminalViewProps = {
  initialMessage?: string;
};

export const TerminalView = forwardRef<TerminalViewHandle, TerminalViewProps>(function TerminalView(
  { initialMessage = '[arthas]$ 等待命令执行...' },
  ref
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const terminalRef = useRef<XTerm>();
  const copyBufferRef = useRef('');
  const pendingWritesRef = useRef<string[]>([]);
  const lastSizeRef = useRef({ cols: 0, rows: 0 });

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    let resizeFrame: number | undefined;

    const terminal = new XTerm({
      convertEol: true,
      cursorBlink: false,
      disableStdin: true,
      fontFamily: '"SFMono-Regular", Consolas, "Liberation Mono", monospace',
      fontSize: 14,
      lineHeight: 1.45,
      scrollback: 5000,
      theme: {
        background: '#020b12',
        foreground: '#d8dee9',
        cursor: '#16d784',
        selectionBackground: '#29435c'
      }
    });

    const scheduleResize = () => {
      if (resizeFrame !== undefined) {
        cancelAnimationFrame(resizeFrame);
      }
      resizeFrame = requestAnimationFrame(() => {
        resizeFrame = undefined;
        resizeTerminal(container, terminal, lastSizeRef.current);
      });
    };

    terminal.open(container);
    terminalRef.current = terminal;
    scheduleResize();
    writeToTerminal(terminal, copyBufferRef, initialMessage + '\n');
    pendingWritesRef.current.forEach((item) => writeToTerminal(terminal, copyBufferRef, item));
    pendingWritesRef.current = [];

    const resizeObserver = new ResizeObserver(scheduleResize);
    resizeObserver.observe(container);

    return () => {
      if (resizeFrame !== undefined) {
        cancelAnimationFrame(resizeFrame);
      }
      resizeObserver.disconnect();
      terminal.dispose();
      terminalRef.current = undefined;
    };
  }, [initialMessage]);

  useImperativeHandle(ref, () => ({
    write(content) {
      const terminal = terminalRef.current;
      if (!terminal) {
        pendingWritesRef.current.push(content);
        return;
      }
      writeToTerminal(terminal, copyBufferRef, content);
    },
    writeln(content) {
      const value = content.endsWith('\n') ? content : content + '\n';
      const terminal = terminalRef.current;
      if (!terminal) {
        pendingWritesRef.current.push(value);
        return;
      }
      writeToTerminal(terminal, copyBufferRef, value);
    },
    clear() {
      copyBufferRef.current = '';
      pendingWritesRef.current = [];
      terminalRef.current?.clear();
    },
    copyText() {
      return copyBufferRef.current;
    }
  }), []);

  return <div ref={containerRef} className="terminal terminal-xterm" />;
});

function writeToTerminal(terminal: XTerm, copyBufferRef: React.MutableRefObject<string>, content: string) {
  const normalized = content.replace(/\r?\n/g, '\r\n');
  terminal.write(normalized);
  copyBufferRef.current = trimBuffer(copyBufferRef.current + stripAnsi(content));
}

function stripAnsi(value: string) {
  return value.replace(/\x1B\[[0-?]*[ -/]*[@-~]/g, '');
}

function trimBuffer(value: string) {
  if (value.length <= MAX_COPY_BUFFER) {
    return value;
  }
  return value.slice(value.length - MAX_COPY_BUFFER);
}

function resizeTerminal(container: HTMLDivElement, terminal: XTerm, lastSize: { cols: number; rows: number }) {
  const width = Math.min(container.clientWidth, container.parentElement?.clientWidth ?? container.clientWidth);
  const height = container.clientHeight;
  if (width <= 0 || height <= 0) return;

  const cols = clamp(Math.floor((width - 24) / 8.4), MIN_COLS, MAX_COLS);
  const rows = clamp(Math.floor((height - 24) / 20), MIN_ROWS, MAX_ROWS);
  if (cols === lastSize.cols && rows === lastSize.rows) {
    return;
  }
  lastSize.cols = cols;
  lastSize.rows = rows;
  terminal.resize(cols, rows);
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

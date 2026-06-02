import { Button, DatePicker, Drawer, Form, Input, message, Modal, Select, Space, Table, Upload as AntUpload } from 'antd';
import { Download, RefreshCw, Search, Upload as UploadIcon } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type Key, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import dayjs from 'dayjs';
import { api, unwrap } from '../api';
import type { AccessTarget, CommandExecution, CommandHistoryItem, PageResult } from '../types';
import { CommandStatusTag } from '../ui/StatusTag';

const TRACE_HOT_NODE_PERCENT = 80;
const standalone = import.meta.env.VITE_FORDRING_MODE === 'standalone';

export default function CommandsPage() {
  const [selected, setSelected] = useState<CommandHistoryItem>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<Key[]>([]);
  const [keyword, setKeyword] = useState('');
  const [origin, setOrigin] = useState<'ALL' | 'LOCAL' | 'IMPORTED'>('ALL');
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [importOpen, setImportOpen] = useState(false);
  const [importFile, setImportFile] = useState<File>();
  const [importPreview, setImportPreview] = useState<ImportPreview>();
  const [saveForm] = Form.useForm();
  const queryClient = useQueryClient();
  const histories = useQuery({
    queryKey: ['command-history', keyword, origin, status, page, pageSize],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize), origin });
      if (keyword.trim()) {
        params.set('keyword', keyword.trim());
      }
      if (status !== 'ALL') {
        params.set('status', status);
      }
      return unwrap<PageResult<CommandHistoryItem>>(api.get(`/api/command-history/items?${params.toString()}`));
    }
  });
  const targets = useQuery({
    queryKey: ['access-targets', 'command-history-targets'],
    queryFn: () => unwrap<PageResult<AccessTarget>>(api.get('/api/access-targets?page=1&pageSize=100'))
  });
  const targetNameById = new Map((targets.data?.items ?? []).map((target) => [target.id, target.name]));
  const selectedDetail = useQuery({
    queryKey: ['command-history-detail', selected?.historyId],
    enabled: !!selected,
    queryFn: () => unwrap<CommandHistoryItem>(api.get(`/api/command-history/items/${encodeURIComponent(selected!.historyId)}`)),
    refetchInterval: (query) => query.state.data?.status === 'RUNNING' ? 1000 : false
  });
  const activeSelected = selectedDetail.data ?? selected;
  const output = useQuery({
    queryKey: ['command-history-output', activeSelected?.historyId],
    enabled: !!activeSelected,
    queryFn: () => unwrap<{ chunks: { content: string }[]; outputTruncated: boolean }>(api.get(`/api/command-history/items/${encodeURIComponent(activeSelected!.historyId)}/output?fromSequence=1&limit=100`)),
    refetchInterval: activeSelected?.status === 'RUNNING' ? 1000 : false
  });
  const rerun = useMutation({
    mutationFn: (row: CommandHistoryItem) => unwrap<CommandExecution>(api.post(`/api/commands/executions/${localExecutionId(row)}/rerun`, {
      targetId: row.targetId,
      command: row.command,
      timeoutSeconds: 30,
      riskConfirmed: true,
      keepHistory: true
    })),
    onSuccess: async (execution) => {
      setSelected({
        historyId: `local:${execution.id}`,
        origin: 'LOCAL',
        command: execution.command,
        targetId: execution.targetId,
        targetSnapshot: execution.targetSnapshot,
        status: execution.status,
        durationMs: execution.durationMs,
        source: execution.source,
        operatorName: execution.operatorName,
        executedAt: execution.executedAt,
        outputSizeBytes: execution.outputSizeBytes,
        outputTruncated: execution.outputTruncated,
        errorMessage: execution.errorMessage,
        riskLevel: execution.riskLevel,
        riskConfirmed: execution.riskConfirmed
      });
      message.success('已提交再次执行');
      await queryClient.invalidateQueries({ queryKey: ['command-history'] });
      await queryClient.invalidateQueries({ queryKey: ['command-history-detail', `local:${execution.id}`] });
      await queryClient.invalidateQueries({ queryKey: ['command-history-output', `local:${execution.id}`] });
    },
    onError: (error) => {
      message.error(error instanceof Error ? error.message : '再次执行失败');
    }
  });
  const save = useMutation({
    mutationFn: (values: any) => unwrap(api.post('/api/saved-commands', values)),
    onSuccess: () => {
      message.success('命令已保存');
      Modal.destroyAll();
    }
  });
  const exportHistory = useMutation({
    mutationFn: async () => api.post('/api/command-history/exports', {
      historyIds: selectedRowKeys.map(String)
    }, { responseType: 'blob' }),
    onSuccess: (response) => {
      const blob = new Blob([response.data], { type: 'application/zip' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filenameFromDisposition(response.headers['content-disposition']) ?? 'fordring-command-history.zip';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      message.success('导出完成');
    },
    onError: (error) => {
      message.error(error instanceof Error ? error.message : '导出失败');
    }
  });
  const previewImport = useMutation({
    mutationFn: async (file: File) => {
      const formData = new FormData();
      formData.append('file', file);
      return unwrap<ImportPreview>(api.post('/api/command-history/imports/preview', formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      }));
    },
    onSuccess: (preview) => {
      setImportPreview(preview);
      message.success('导入包校验通过');
    },
    onError: (error) => {
      message.error(error instanceof Error ? error.message : '导入预览失败');
    }
  });
  const confirmImport = useMutation({
    mutationFn: async (fileToken: string) => unwrap<ImportResult>(api.post('/api/command-history/imports', { fileToken })),
    onSuccess: async (result) => {
      message.success(`导入完成：新增 ${result.importedCount} 条，跳过重复 ${result.skippedDuplicateCount} 条`);
      setImportOpen(false);
      setImportFile(undefined);
      setImportPreview(undefined);
      setOrigin('IMPORTED');
      await queryClient.invalidateQueries({ queryKey: ['command-history'] });
    },
    onError: (error) => {
      message.error(error instanceof Error ? error.message : '导入失败');
    }
  });

  const openSave = (row: CommandHistoryItem) => {
    saveForm.setFieldsValue({ name: row.command, command: row.command, visibleInConsole: true });
    Modal.confirm({
      title: '保存命令',
      icon: null,
      width: 520,
      okText: '保存',
      cancelText: '取消',
      content: (
        <Form form={saveForm} layout="vertical" style={{ marginTop: 20 }}>
          <Form.Item name="name" label="命令名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="command" label="命令" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="visibleInConsole" label="在控制台快捷显示">
            <Select options={[{ value: true, label: '是' }, { value: false, label: '否' }]} />
          </Form.Item>
        </Form>
      ),
      onOk: async () => save.mutateAsync(await saveForm.validateFields())
    });
  };

  return (
    <>
      <div className="page-header">
        <h1>命令历史</h1>
        <p>查看和管理已执行的 Arthas 命令记录</p>
      </div>
      <div className="panel" style={{ marginTop: 34 }}>
        <div className="toolbar">
          <Input prefix={<Search size={16} />} placeholder="搜索命令" value={keyword} onChange={(event) => {
            setKeyword(event.target.value);
            setPage(1);
          }} style={{ width: 280 }} />
          <Select value={origin} onChange={(value) => {
            setOrigin(value);
            setPage(1);
          }} style={{ width: 160 }} options={[
            { value: 'ALL', label: '全部来源' },
            { value: 'LOCAL', label: '本地执行' },
            { value: 'IMPORTED', label: '导入记录' }
          ]} />
          <Select value={status} onChange={(value) => {
            setStatus(value);
            setPage(1);
          }} style={{ width: 180 }} options={[
            { value: 'ALL', label: '全部状态' },
            { value: 'SUCCESS', label: '成功' },
            { value: 'FAILED', label: '失败' },
            { value: 'TIMEOUT', label: '超时' },
            { value: 'STOPPED', label: '已停止' },
            { value: 'CANCELLED', label: '已取消' },
            { value: 'RUNNING', label: '运行中' }
          ]} />
          <DatePicker.RangePicker />
          <div className="spacer" />
          <Button icon={<Download size={16} />} disabled={selectedRowKeys.length === 0} loading={exportHistory.isPending} onClick={() => exportHistory.mutate()}>
            导出
          </Button>
          {!standalone && <Button icon={<UploadIcon size={16} />} onClick={() => setImportOpen(true)}>导入</Button>}
          <Button icon={<RefreshCw size={16} />} onClick={() => {
            setKeyword('');
            setOrigin('ALL');
            setStatus('ALL');
            setPage(1);
            setSelectedRowKeys([]);
          }}>重置</Button>
        </div>
        <Table
          rowKey="historyId"
          loading={histories.isLoading}
          rowSelection={{
            selectedRowKeys,
            onChange: setSelectedRowKeys
          }}
          dataSource={histories.data?.items ?? []}
          pagination={{
            current: page,
            pageSize,
            total: histories.data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (nextPage, nextPageSize) => {
              setPage(nextPage);
              setPageSize(nextPageSize);
            }
          }}
          columns={[
            {
              title: '命令',
              dataIndex: 'command',
              width: 360,
              render: (value) => <span style={{ display: 'inline-block', maxWidth: 360, whiteSpace: 'normal', wordBreak: 'break-all' }}>{value}</span>,
              sorter: (left, right) => left.command.localeCompare(right.command)
            },
            {
              title: '目标',
              render: (_, row) => targetName(row, targetNameById),
              sorter: (left, right) => targetName(left, targetNameById).localeCompare(targetName(right, targetNameById))
            },
            {
              title: '来源环境',
              render: (_, row) => row.originalSourceEnvironmentName ?? (row.origin === 'LOCAL' ? '本地环境' : '-'),
              sorter: (left, right) => (left.originalSourceEnvironmentName ?? '').localeCompare(right.originalSourceEnvironmentName ?? '')
            },
            {
              title: '执行时间',
              dataIndex: 'executedAt',
              defaultSortOrder: 'descend',
              render: (value) => formatDateTime(value),
              sorter: (left, right) => timestamp(left.executedAt) - timestamp(right.executedAt)
            },
            {
              title: '状态',
              render: (_, row) => <CommandStatusTag status={row.status} />,
              sorter: (left, right) => left.status.localeCompare(right.status)
            },
            {
              title: '耗时',
              render: (_, row) => formatDuration(row.durationMs),
              sorter: (left, right) => (left.durationMs ?? -1) - (right.durationMs ?? -1)
            },
            {
              title: '来源',
              dataIndex: 'source',
              sorter: (left, right) => left.source.localeCompare(right.source)
            },
            {
              title: '操作',
              render: (_, row) => (
                <Space>
                  <Button type="link" onClick={() => setSelected(row)}>详情</Button>
                  {row.origin === 'LOCAL' && (
                    <Button type="link" loading={rerun.isPending && rerun.variables?.historyId === row.historyId} onClick={() => rerun.mutate(row)}>再次执行</Button>
                  )}
                  <Button type="link" onClick={() => openSave(row)}>保存</Button>
                  {row.origin === 'LOCAL' && row.targetId && <Link to={`/console/${row.targetId}`}>控制台</Link>}
                </Space>
              )
            }
          ]}
        />
      </div>
      <Drawer
        title="命令详情"
        open={!!selected}
        onClose={() => setSelected(undefined)}
        width="min(1180px, calc(100vw - 64px))"
      >
        {activeSelected && (
          <>
            <p><strong>命令：</strong>{activeSelected.command}</p>
            <p><strong>记录来源：</strong>{activeSelected.origin === 'LOCAL' ? '本地执行' : '导入记录'}</p>
            <p><strong>来源环境：</strong>{activeSelected.originalSourceEnvironmentName ?? '-'}</p>
            <p><strong>执行时间：</strong>{formatDateTime(activeSelected.executedAt)}</p>
            <p><strong>状态：</strong><CommandStatusTag status={activeSelected.status} /></p>
            <p><strong>耗时：</strong>{formatDuration(activeSelected.durationMs)}</p>
            <h3>执行结果</h3>
            <div className="terminal" style={{ minHeight: 320, overflowX: 'auto', whiteSpace: 'pre' }}>
              {formatCommandOutput(activeSelected.command, output.data?.chunks.map((chunk) => chunk.content).join('') || '暂无输出')}
            </div>
          </>
        )}
      </Drawer>
      <Modal
        title="导入命令历史"
        open={importOpen}
        onCancel={() => {
          setImportOpen(false);
          setImportFile(undefined);
          setImportPreview(undefined);
        }}
        okText={importPreview ? '确认导入' : '校验导入包'}
        confirmLoading={previewImport.isPending || confirmImport.isPending}
        onOk={async () => {
          if (!importPreview) {
            if (!importFile) {
              message.warning('请选择 zip 文件');
              return;
            }
            await previewImport.mutateAsync(importFile);
            return;
          }
          await confirmImport.mutateAsync(importPreview.fileToken);
        }}
      >
        <AntUpload
          accept=".zip,application/zip"
          maxCount={1}
          beforeUpload={(file) => {
            setImportFile(file);
            setImportPreview(undefined);
            return false;
          }}
          onRemove={() => {
            setImportFile(undefined);
            setImportPreview(undefined);
          }}
        >
          <Button icon={<UploadIcon size={16} />}>选择 zip 文件</Button>
        </AntUpload>
        {importPreview && (
          <div style={{ marginTop: 20, lineHeight: 1.9 }}>
            <div><strong>导出环境：</strong>{importPreview.exportingEnvironmentName}</div>
            <div><strong>导出时间：</strong>{formatDateTime(importPreview.exportedAt)}</div>
            <div><strong>导出人：</strong>{importPreview.exportedBy}</div>
            <div><strong>记录数：</strong>{importPreview.recordCount}</div>
            <div><strong>输出大小：</strong>{formatBytes(importPreview.outputSizeBytes)}</div>
            <div><strong>重复记录：</strong>{importPreview.duplicateCount}</div>
            {importPreview.warnings.map((warning) => (
              <div key={warning} style={{ color: '#ad6800' }}>{warning}</div>
            ))}
          </div>
        )}
      </Modal>
    </>
  );
}

interface ImportPreview {
  fileToken: string;
  schemaVersion: string;
  exportingEnvironmentName: string;
  exportingEnvironmentId: string;
  exportedAt: string;
  exportedBy: string;
  recordCount: number;
  outputSizeBytes: number;
  duplicateCount: number;
  invalidCount: number;
  warnings: string[];
}

interface ImportResult {
  importBatchId: number;
  importedCount: number;
  skippedDuplicateCount: number;
  failedCount: number;
}

function formatDateTime(value?: string) {
  if (!value) {
    return '-';
  }
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
}

function formatDuration(value?: number) {
  if (value === undefined || value === null) {
    return '-';
  }
  if (value < 1000) {
    return `${value}ms`;
  }
  return `${(value / 1000).toFixed(2)}s`;
}

function timestamp(value?: string) {
  if (!value) {
    return 0;
  }
  const date = dayjs(value);
  return date.isValid() ? date.valueOf() : 0;
}

function targetName(row: CommandHistoryItem, targetNameById: Map<number, string>) {
  if (row.targetId) {
    return targetNameById.get(row.targetId) ?? `目标 #${row.targetId}`;
  }
  const snapshot = parseSnapshot(row.targetSnapshot);
  return snapshot?.name ?? snapshot?.host ?? '导入目标';
}

function parseSnapshot(value?: string): any {
  if (!value) {
    return undefined;
  }
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function localExecutionId(row: CommandHistoryItem) {
  if (!row.historyId.startsWith('local:')) {
    throw new Error('导入记录不能再次执行');
  }
  return row.historyId.slice('local:'.length);
}

function filenameFromDisposition(value?: string) {
  if (!value) {
    return undefined;
  }
  const utf8Match = value.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match) {
    return decodeURIComponent(utf8Match[1]);
  }
  const match = value.match(/filename="?([^"]+)"?/i);
  return match?.[1];
}

function formatBytes(value: number) {
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function formatCommandOutput(command: string, content: string): ReactNode {
  const plainContent = stripAnsi(content);
  if (!command.trim().startsWith('trace ')) {
    return renderAnsiText(content);
  }
  return renderTraceText(formatTraceOutput(plainContent));
}

function formatTraceOutput(content: string) {
  const lines = content.split(/\r?\n/);
  const output: string[] = [];
  let jsonBuffer: string[] = [];
  let depth = 0;

  const flushJson = () => {
    if (jsonBuffer.length === 0) {
      return;
    }
    const block = jsonBuffer.join('\n');
    output.push(formatTraceJsonBlock(block));
    jsonBuffer = [];
    depth = 0;
  };

  for (const line of lines) {
    const trimmed = line.trim();
    if (jsonBuffer.length === 0 && trimmed.startsWith('{')) {
      jsonBuffer.push(line);
      depth += braceDelta(line);
      if (depth <= 0) {
        flushJson();
      }
      continue;
    }
    if (jsonBuffer.length > 0) {
      jsonBuffer.push(line);
      depth += braceDelta(line);
      if (depth <= 0) {
        flushJson();
      }
      continue;
    }
    output.push(line);
  }
  flushJson();
  return output.join('\n');
}

function formatTraceJsonBlock(block: string) {
  try {
    const value = JSON.parse(block);
    if (value?.type === 'enhancer') {
      return formatTraceEnhancer(value);
    }
    if (value?.type === 'trace') {
      return formatTraceTree(value);
    }
    return JSON.stringify(value, null, 2);
  } catch {
    return block;
  }
}

function formatTraceEnhancer(value: any) {
  const effect = value.effect ?? {};
  return [
    '[arthas] trace listener attached',
    `jobId=${dash(value.jobId)}`,
    `listenerId=${dash(effect.listenerId)}`,
    `classes=${dash(effect.classCount)}`,
    `methods=${dash(effect.methodCount)}`
  ].join(', ');
}

function formatTraceTree(value: any) {
  const root = value.root;
  if (!root) {
    return JSON.stringify(value, null, 2);
  }
  const rows = ['`---' + traceThreadText(root)];
  if (isSyntheticTraceRoot(root)) {
    appendTopLevelTraceChildren(rows, root);
  } else {
    appendTraceNode(rows, root, '    ', true, traceNodeTotalCostNanos(root), false);
  }
  return rows.join('\n');
}

function isSyntheticTraceRoot(node: any) {
  return dash(node?.className) === '-' && dash(node?.methodName) === '-';
}

function appendTopLevelTraceChildren(rows: string[], root: any) {
  const children = Array.isArray(root.children) ? root.children : [];
  children.forEach((child: any, index: number) => {
    appendTraceNode(rows, child, '    ', index === children.length - 1, traceNodeTotalCostNanos(child), false);
  });
}

function appendTraceNode(rows: string[], node: any, prefix: string, last: boolean, rootCostNanos: number, includePercent: boolean) {
  rows.push(`${prefix}${last ? '`---' : '+---'}${traceCostBlock(node, rootCostNanos, includePercent)} ${traceNodeText(node)}`);
  const children = Array.isArray(node.children) ? node.children : [];
  const nextPrefix = prefix + (last ? '    ' : '|   ');
  children.forEach((child: any, index: number) => {
    appendTraceNode(rows, child, nextPrefix, index === children.length - 1, rootCostNanos, true);
  });
}

function traceThreadText(root: any) {
  return `ts=${dash(firstPresent(root, ['ts', 'timestamp', 'timeStamp']))}`
    + `;thread_name=${dash(firstPresent(root, ['threadName', 'thread_name', 'name']))}`
    + `;id=${dash(firstPresent(root, ['threadId', 'thread_id', 'id']))}`
    + `;is_daemon=${dash(firstPresent(root, ['isDaemon', 'is_daemon', 'daemon']))}`
    + `;priority=${dash(root.priority)}`
    + `;TCCL=${traceClassLoaderText(root)}`;
}

function traceClassLoaderText(root: any) {
  const direct = firstPresent(root, ['TCCL', 'tccl', 'contextClassLoader', 'classLoader', 'classLoaderName']);
  if (direct !== undefined && direct !== null && direct !== '') {
    return typeof direct === 'object' ? JSON.stringify(direct) : String(direct);
  }
  const hash = firstPresent(root, ['classLoaderHash', 'classloaderHash']);
  if (hash === undefined || hash === null || hash === '') {
    return '-';
  }
  const klass = firstPresent(root, ['classLoaderClass', 'classloaderClass']);
  return klass === undefined || klass === null || klass === '' ? String(hash) : `${klass}@${hash}`;
}

function traceNodeText(node: any) {
  const line = node.lineNumber !== undefined && node.lineNumber !== -1 ? ` #${node.lineNumber}` : '';
  return `${dash(node.className)}:${dash(node.methodName)}()${line}`;
}

function traceCostBlock(node: any, rootCostNanos: number, includePercent: boolean) {
  const costNanos = traceNodeTotalCostNanos(node);
  const cost = traceCostDisplay(node);
  if (!includePercent || rootCostNanos <= 0 || costNanos < 0) {
    return `[${cost}]`;
  }
  return `[${(costNanos / rootCostNanos * 100).toFixed(2)}% ${cost}]`;
}

function traceCostDisplay(node: any) {
  const count = traceCount(node);
  if (count !== '-' && count !== '1' && hasAnyNumber(node, ['minCost', 'maxCost', 'totalCost', 'total'])) {
    return `min=${traceCost(firstPresent(node, ['minCost']))}`
      + `,max=${traceCost(firstPresent(node, ['maxCost']))}`
      + `,total=${traceCost(firstPresent(node, ['totalCost', 'total']))}`
      + `,count=${count}`;
  }
  return traceCost(firstPresent(node, ['cost', 'totalCost', 'total']));
}

function traceCount(node: any) {
  return dash(firstPresent(node, ['count', 'times']));
}

function hasAnyNumber(node: any, fields: string[]) {
  return fields.some((field) => typeof node?.[field] === 'number');
}

function traceNodeTotalCostNanos(node: any) {
  const value = firstPresent(node, ['totalCost', 'total', 'cost']);
  return typeof value === 'number' ? value : -1;
}

function traceCost(value: unknown) {
  return typeof value === 'number' ? `${(value / 1_000_000).toFixed(6)}ms` : dash(value);
}

function firstPresent(value: any, fields: string[]) {
  return fields.map((field) => value?.[field]).find((item) => item !== undefined && item !== null);
}

function stripAnsi(value: string) {
  return value.replace(/\x1B\[[0-?]*[ -/]*[@-~]/g, '');
}

function renderTraceText(value: string): ReactNode {
  const lines = value.split('\n');
  return lines.map((line, index) => (
    <span key={index}>
      {renderTraceLine(line, index)}
      {index < lines.length - 1 ? '\n' : ''}
    </span>
  ));
}

function renderTraceLine(line: string, lineIndex: number): ReactNode {
  const match = line.match(/\[(\d+(?:\.\d+)?)% [^\]]+\]/);
  if (!match || Number(match[1]) < TRACE_HOT_NODE_PERCENT || match.index === undefined) {
    return line;
  }
  const start = match.index;
  const end = start + match[0].length;
  return [
    line.slice(0, start),
    <span className="terminal-hot" key={`hot-${lineIndex}`}>{match[0]}</span>,
    line.slice(end)
  ];
}

function renderAnsiText(value: string): ReactNode {
  const nodes: ReactNode[] = [];
  const ansiPattern = /\x1B\[([0-9;]*)m/g;
  let lastIndex = 0;
  let hot = false;
  let key = 0;
  let match: RegExpExecArray | null;

  while ((match = ansiPattern.exec(value)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(wrapTerminalText(value.slice(lastIndex, match.index), hot, key++));
    }
    const codes = match[1].split(';').filter(Boolean);
    if (codes.length === 0 || codes.includes('0') || codes.includes('39')) {
      hot = false;
    }
    if (codes.includes('31') || codes.includes('91')) {
      hot = true;
    }
    lastIndex = ansiPattern.lastIndex;
  }

  if (lastIndex < value.length) {
    nodes.push(wrapTerminalText(value.slice(lastIndex), hot, key++));
  }

  return nodes.length > 0 ? nodes : value;
}

function wrapTerminalText(value: string, hot: boolean, key: number): ReactNode {
  return hot ? <span className="terminal-hot" key={key}>{value}</span> : value;
}

function dash(value: unknown) {
  return value === undefined || value === null || value === '' ? '-' : String(value);
}

function braceDelta(value: string) {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (const char of value) {
    if (escaped) {
      escaped = false;
      continue;
    }
    if (char === '\\') {
      escaped = true;
      continue;
    }
    if (char === '"') {
      inString = !inString;
      continue;
    }
    if (!inString && char === '{') {
      depth += 1;
    }
    if (!inString && char === '}') {
      depth -= 1;
    }
  }
  return depth;
}

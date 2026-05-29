import { Button, DatePicker, Drawer, Form, Input, message, Modal, Select, Space, Table } from 'antd';
import { RefreshCw, Search } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import dayjs from 'dayjs';
import { api, unwrap } from '../api';
import type { AccessTarget, CommandExecution, PageResult } from '../types';
import { CommandStatusTag } from '../ui/StatusTag';

export default function CommandsPage() {
  const [selected, setSelected] = useState<CommandExecution>();
  const [saveForm] = Form.useForm();
  const queryClient = useQueryClient();
  const executions = useQuery({
    queryKey: ['command-executions'],
    queryFn: () => unwrap<PageResult<CommandExecution>>(api.get('/api/commands/executions?page=1&pageSize=10'))
  });
  const targets = useQuery({
    queryKey: ['access-targets', 'command-history-targets'],
    queryFn: () => unwrap<PageResult<AccessTarget>>(api.get('/api/access-targets?page=1&pageSize=100'))
  });
  const targetNameById = new Map((targets.data?.items ?? []).map((target) => [target.id, target.name]));
  const selectedDetail = useQuery({
    queryKey: ['command-execution', selected?.id],
    enabled: !!selected,
    queryFn: () => unwrap<CommandExecution>(api.get(`/api/commands/executions/${selected!.id}`)),
    refetchInterval: (query) => query.state.data?.status === 'RUNNING' ? 1000 : false
  });
  const activeSelected = selectedDetail.data ?? selected;
  const output = useQuery({
    queryKey: ['command-output', activeSelected?.id],
    enabled: !!activeSelected,
    queryFn: () => unwrap<{ chunks: { content: string }[]; outputTruncated: boolean }>(api.get(`/api/commands/executions/${activeSelected!.id}/output?fromSequence=1&limit=100`)),
    refetchInterval: activeSelected?.status === 'RUNNING' ? 1000 : false
  });
  const rerun = useMutation({
    mutationFn: (row: CommandExecution) => unwrap<CommandExecution>(api.post(`/api/commands/executions/${row.id}/rerun`, {
      targetId: row.targetId,
      command: row.command,
      timeoutSeconds: 30,
      riskConfirmed: true,
      keepHistory: true
    })),
    onSuccess: async (execution) => {
      setSelected(execution);
      message.success('已提交再次执行');
      await queryClient.invalidateQueries({ queryKey: ['command-executions'] });
      await queryClient.invalidateQueries({ queryKey: ['command-execution', execution.id] });
      await queryClient.invalidateQueries({ queryKey: ['command-output', execution.id] });
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

  const openSave = (row: CommandExecution) => {
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
          <Input prefix={<Search size={16} />} placeholder="搜索命令" style={{ width: 280 }} />
          <Select defaultValue="全部目标" style={{ width: 220 }} options={[{ value: '全部目标', label: '全部目标' }]} />
          <Select defaultValue="全部状态" style={{ width: 180 }} options={[{ value: '全部状态', label: '全部状态' }]} />
          <DatePicker.RangePicker />
          <div className="spacer" />
          <Button icon={<RefreshCw size={16} />}>重置</Button>
        </div>
        <Table
          rowKey="id"
          loading={executions.isLoading}
          dataSource={executions.data?.items ?? []}
          pagination={{ pageSize: 10, total: executions.data?.total ?? 0 }}
          columns={[
            {
              title: '命令',
              dataIndex: 'command',
              sorter: (left, right) => left.command.localeCompare(right.command)
            },
            {
              title: '目标',
              render: (_, row) => targetName(row, targetNameById),
              sorter: (left, right) => targetName(left, targetNameById).localeCompare(targetName(right, targetNameById))
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
                  <Button type="link" loading={rerun.isPending && rerun.variables?.id === row.id} onClick={() => rerun.mutate(row)}>再次执行</Button>
                  <Button type="link" onClick={() => openSave(row)}>保存</Button>
                  <Link to={`/console/${row.targetId}`}>控制台</Link>
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
    </>
  );
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

function targetName(row: CommandExecution, targetNameById: Map<number, string>) {
  return targetNameById.get(row.targetId) ?? `目标 #${row.targetId}`;
}

function formatCommandOutput(command: string, content: string) {
  const plainContent = stripAnsi(content);
  if (!command.trim().startsWith('trace ')) {
    return plainContent;
  }
  return formatTraceOutput(plainContent);
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
  appendTraceNode(rows, root, '    ', true, traceNodeTotalCostNanos(root), false);
  return rows.join('\n');
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

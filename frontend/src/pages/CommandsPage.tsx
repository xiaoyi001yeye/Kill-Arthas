import { Button, DatePicker, Drawer, Form, Input, message, Modal, Select, Space, Table } from 'antd';
import { RefreshCw, Search } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, unwrap } from '../api';
import type { CommandExecution, PageResult } from '../types';
import { CommandStatusTag } from '../ui/StatusTag';

export default function CommandsPage() {
  const [selected, setSelected] = useState<CommandExecution>();
  const [saveForm] = Form.useForm();
  const queryClient = useQueryClient();
  const executions = useQuery({
    queryKey: ['command-executions'],
    queryFn: () => unwrap<PageResult<CommandExecution>>(api.get('/api/commands/executions?page=1&pageSize=10'))
  });
  const output = useQuery({
    queryKey: ['command-output', selected?.id],
    enabled: !!selected,
    queryFn: () => unwrap<{ chunks: { content: string }[]; outputTruncated: boolean }>(api.get(`/api/commands/executions/${selected!.id}/output?fromSequence=1&limit=100`))
  });
  const rerun = useMutation({
    mutationFn: (row: CommandExecution) => unwrap(api.post(`/api/commands/executions/${row.id}/rerun`, {
      targetId: row.targetId,
      command: row.command,
      timeoutSeconds: 30,
      riskConfirmed: true,
      keepHistory: true
    })),
    onSuccess: async () => {
      message.success('命令已再次执行');
      await queryClient.invalidateQueries({ queryKey: ['command-executions'] });
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
            { title: '命令', dataIndex: 'command' },
            { title: '目标', render: (_, row) => `目标 #${row.targetId}` },
            { title: '执行时间', dataIndex: 'executedAt' },
            { title: '状态', render: (_, row) => <CommandStatusTag status={row.status} /> },
            { title: '耗时', render: (_, row) => row.durationMs ? `${row.durationMs}ms` : '-' },
            { title: '来源', dataIndex: 'source' },
            {
              title: '操作',
              render: (_, row) => (
                <Space>
                  <Button type="link" onClick={() => setSelected(row)}>详情</Button>
                  <Button type="link" onClick={() => rerun.mutate(row)}>再次执行</Button>
                  <Button type="link" onClick={() => openSave(row)}>保存</Button>
                  <Link to={`/console/${row.targetId}`}>控制台</Link>
                </Space>
              )
            }
          ]}
        />
      </div>
      <Drawer title="命令详情" open={!!selected} onClose={() => setSelected(undefined)} width={620}>
        {selected && (
          <>
            <p><strong>命令：</strong>{selected.command}</p>
            <p><strong>执行时间：</strong>{selected.executedAt}</p>
            <p><strong>状态：</strong><CommandStatusTag status={selected.status} /></p>
            <p><strong>耗时：</strong>{selected.durationMs ?? '-'}ms</p>
            <h3>执行结果</h3>
            <div className="terminal" style={{ minHeight: 320 }}>{output.data?.chunks.map((chunk) => chunk.content).join('') || '暂无输出'}</div>
          </>
        )}
      </Drawer>
    </>
  );
}

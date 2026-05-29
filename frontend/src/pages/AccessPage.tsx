import { Alert, Button, Collapse, Form, Input, InputNumber, message, Modal, Radio, Select, Space, Table } from 'antd';
import { CheckCircle, Clock3, Copy, Cuboid, Download, Edit3, PackageCheck, Plus, Search, Trash2 } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useState, type ReactNode } from 'react';
import { api, unwrap } from '../api';
import type { AccessTarget, PageResult } from '../types';
import { TargetStatusTag } from '../ui/StatusTag';

type Stats = { totalCount: number; attachedCount: number; pendingCount: number; failedCount: number };
type JavaProcessDiscoveryResult = { processes: { processId: number; processName: string }[] };
type ArthasInstallationResult = { installed: boolean; message: string; version: string; installationPath?: string; traceId: string; outputPreview: string };
type ArthasInstallPrompt = { target: AccessTarget; message: string; failureMessage?: string };
type ArthasPromptAction = 'install' | 'install-and-attach';

export default function AccessPage() {
  const [form] = Form.useForm();
  const [modal, contextHolder] = Modal.useModal();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingTarget, setEditingTarget] = useState<AccessTarget>();
  const [arthasInstallPrompt, setArthasInstallPrompt] = useState<ArthasInstallPrompt>();
  const [arthasPromptAction, setArthasPromptAction] = useState<ArthasPromptAction>();
  const queryClient = useQueryClient();
  const closeEditor = () => {
    setEditorOpen(false);
    setEditingTarget(undefined);
  };
  const refreshAccessTargets = async () => {
    await queryClient.invalidateQueries({ queryKey: ['access-targets'] });
    await queryClient.invalidateQueries({ queryKey: ['access-stats'] });
  };
  const stats = useQuery({ queryKey: ['access-stats'], queryFn: () => unwrap<Stats>(api.get('/api/access-targets/stats')) });
  const targets = useQuery({
    queryKey: ['access-targets'],
    queryFn: () => unwrap<PageResult<AccessTarget>>(api.get('/api/access-targets?page=1&pageSize=10'))
  });
  const create = useMutation({
    mutationFn: (values: any) => unwrap<AccessTarget>(api.post('/api/access-targets', values)),
    onSuccess: async () => {
      message.success('接入目标已创建');
      await queryClient.invalidateQueries({ queryKey: ['access-targets'] });
      await queryClient.invalidateQueries({ queryKey: ['access-stats'] });
      closeEditor();
    }
  });
  const update = useMutation({
    mutationFn: ({ id, values }: { id: number; values: any }) => unwrap<AccessTarget>(api.put(`/api/access-targets/${id}`, values)),
    onSuccess: async () => {
      message.success('接入目标已更新');
      await queryClient.invalidateQueries({ queryKey: ['access-targets'] });
      await queryClient.invalidateQueries({ queryKey: ['access-stats'] });
      closeEditor();
    }
  });
  const attach = useMutation<AccessTarget, Error, AccessTarget>({
    mutationFn: (target) => unwrap<AccessTarget>(api.post(`/api/access-targets/${target.id}/attach`, {
      telnetPort: 3658,
      httpPort: 8563,
      forceRestart: target.arthasStatus === 'DISCONNECTED' || target.arthasStatus === 'ATTACH_FAILED'
    })),
    onSuccess: async () => {
      message.success('Arthas 已接入');
      await refreshAccessTargets();
    },
    onError: async (error, target) => {
      await refreshAccessTargets();
      const errorMessage = getErrorMessage(error, '接入 Arthas 失败');
      if (isMissingArthasBoot(errorMessage)) {
        setArthasInstallPrompt({ target, message: errorMessage });
        return;
      }
      message.error(errorMessage);
    }
  });
  const detach = useMutation({
    mutationFn: (id: number) => unwrap<AccessTarget>(api.post(`/api/access-targets/${id}/detach`)),
    onSuccess: async () => {
      message.success('已断开');
      await queryClient.invalidateQueries({ queryKey: ['access-targets'] });
    }
  });
  const deleteTarget = useMutation({
    mutationFn: (id: number) => unwrap<void>(api.delete(`/api/access-targets/${id}`)),
    onSuccess: async () => {
      message.success('接入目标已删除');
      await queryClient.invalidateQueries({ queryKey: ['access-targets'] });
      await queryClient.invalidateQueries({ queryKey: ['access-stats'] });
    },
    onError: (error) => {
      message.error(error instanceof Error ? error.message : '删除失败');
    }
  });
  const checkArthasInstallation = useMutation({
    mutationFn: (id: number) => unwrap<ArthasInstallationResult>(api.post(`/api/access-targets/${id}/arthas/check-installation`)),
    onSuccess: (result) => {
      if (result.installed) {
        message.success(formatArthasInstallationMessage(result));
      } else {
        message.warning(result.message);
      }
    },
    onError: (error) => {
      message.error(error instanceof Error ? error.message : '检查 Arthas 失败');
    }
  });
  const installArthas = useMutation({
    mutationFn: (id: number) => unwrap<ArthasInstallationResult>(api.post(`/api/access-targets/${id}/arthas/install`)),
    onSuccess: async (result) => {
      message.success(formatArthasInstallationMessage(result));
      await refreshAccessTargets();
    },
    onError: (error) => {
      message.error(error instanceof Error ? error.message : '安装 Arthas 失败');
    }
  });

  const openEditor = (target?: AccessTarget) => {
    form.resetFields();
    setEditingTarget(target);
    form.setFieldsValue(target ? {
      name: target.name,
      host: target.host,
      sshPort: target.sshPort ?? 22,
      authType: target.authType,
      username: target.username,
      targetType: target.targetType,
      containerName: target.containerName,
      processId: target.processId,
      telnetPort: target.telnetPort,
      httpPort: target.httpPort
    } : {
      name: 'order-service',
      host: '10.0.0.1',
      sshPort: 22,
      authType: 'PASSWORD',
      username: 'admin',
      targetType: 'DOCKER_CONTAINER',
      containerName: 'order-service',
      processId: 12345,
      telnetPort: 3658,
      httpPort: 8563,
      credential: { name: 'dev-password', secret: 'fordring_dev' }
    });
    setEditorOpen(true);
  };

  const submitEditor = async () => {
    const values = await form.validateFields();
    if (editingTarget) {
      await update.mutateAsync({ id: editingTarget.id, values });
    } else {
      await create.mutateAsync(values);
    }
  };

  const confirmDelete = (target: AccessTarget) => {
    if (target.arthasStatus === 'ATTACHED') {
      message.warning('请先断开 Arthas 后再删除接入目标');
      return;
    }
    modal.confirm({
      title: '删除接入目标',
      content: `确定删除「${target.name}」吗？命令历史和审计记录会保留。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => deleteTarget.mutateAsync(target.id)
    });
  };

  const confirmInstallArthas = (target: AccessTarget) => {
    modal.confirm({
      title: '安装 Arthas',
      content: `将在「${target.name}」所在主机 ${target.host}:${target.sshPort ?? 22} 下载并安装 Arthas，确定继续吗？`,
      okText: '安装',
      cancelText: '取消',
      onOk: () => installArthas.mutateAsync(target.id)
    });
  };

  const runPromptInstall = async (attachAfterInstall: boolean) => {
    if (!arthasInstallPrompt) {
      return;
    }
    const target = arthasInstallPrompt.target;
    setArthasPromptAction(attachAfterInstall ? 'install-and-attach' : 'install');
    try {
      await unwrap<ArthasInstallationResult>(api.post(`/api/access-targets/${target.id}/arthas/install`));
      if (attachAfterInstall) {
        await unwrap<AccessTarget>(api.post(`/api/access-targets/${target.id}/attach`, { telnetPort: 3658, httpPort: 8563 }));
        message.success('Arthas 已安装并接入');
      } else {
        message.success('Arthas 安装完成');
      }
      setArthasInstallPrompt(undefined);
      await refreshAccessTargets();
    } catch (error) {
      const errorMessage = getErrorMessage(error, attachAfterInstall ? '安装并接入失败' : '安装 Arthas 失败');
      setArthasInstallPrompt({ target, message: arthasInstallPrompt.message, failureMessage: errorMessage });
      await refreshAccessTargets();
    } finally {
      setArthasPromptAction(undefined);
    }
  };

  const copyPromptDiagnostics = async () => {
    if (!arthasInstallPrompt) {
      return;
    }
    const target = arthasInstallPrompt.target;
    await navigator.clipboard.writeText([
      `targetId=${target.id}`,
      `name=${target.name}`,
      `host=${target.host}:${target.sshPort ?? 22}`,
      target.containerName ? `container=${target.containerName}` : undefined,
      target.processId ? `pid=${target.processId}` : undefined,
      `message=${arthasInstallPrompt.failureMessage ?? arthasInstallPrompt.message}`
    ].filter(Boolean).join('\n'));
    message.success('已复制排障信息');
  };

  return (
    <>
      {contextHolder}
      <Modal
        title="目标未安装 Arthas"
        open={!!arthasInstallPrompt}
        width={600}
        destroyOnClose
        onCancel={() => setArthasInstallPrompt(undefined)}
        footer={[
          arthasInstallPrompt?.failureMessage && (
            <Button key="copy" icon={<Copy size={16} />} onClick={copyPromptDiagnostics}>
              复制排障信息
            </Button>
          ),
          <Button key="cancel" disabled={!!arthasPromptAction} onClick={() => setArthasInstallPrompt(undefined)}>
            取消
          </Button>,
          <Button
            key="install"
            loading={arthasPromptAction === 'install'}
            disabled={arthasPromptAction === 'install-and-attach'}
            onClick={() => runPromptInstall(false)}
          >
            仅安装
          </Button>,
          <Button
            key="install-and-attach"
            type="primary"
            loading={arthasPromptAction === 'install-and-attach'}
            disabled={arthasPromptAction === 'install'}
            onClick={() => runPromptInstall(true)}
          >
            安装并接入
          </Button>
        ]}
      >
        {arthasInstallPrompt && (
          <>
            <Alert
              type={arthasInstallPrompt.failureMessage ? 'error' : 'warning'}
              showIcon
              message={arthasInstallPrompt.failureMessage ? '操作失败' : '当前目标缺少 arthas-boot.jar'}
              description={arthasInstallPrompt.failureMessage ?? '当前目标缺少 ~/.arthas/arthas-boot.jar，因此无法接入 Arthas。可以先安装 Arthas，安装完成后继续接入。'}
            />
            <div className="install-prompt-detail">
              <span>目标</span><strong>{arthasInstallPrompt.target.name}</strong>
              <span>主机</span><strong>{arthasInstallPrompt.target.host}:{arthasInstallPrompt.target.sshPort ?? 22}</strong>
              <span>容器</span><strong>{arthasInstallPrompt.target.containerName || '-'}</strong>
              <span>PID</span><strong>{arthasInstallPrompt.target.processId || '-'}</strong>
              <span>原因</span><strong>{arthasInstallPrompt.message}</strong>
            </div>
          </>
        )}
      </Modal>
      <Modal
        title={editingTarget ? '编辑接入' : '新增接入'}
        open={editorOpen}
        width={640}
        okText={editingTarget ? '保存修改' : '保存并检测'}
        cancelText="取消"
        confirmLoading={create.isPending || update.isPending}
        forceRender
        onCancel={closeEditor}
        onOk={submitEditor}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 20 }}>
          <Form.Item name="name" label="接入名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <div className="connection-grid">
            <Form.Item name="host" label="主机地址" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item
              name="sshPort"
              label="SSH 端口"
              rules={[{ required: true, message: '请填写 SSH 端口' }]}
              extra="默认 22，用于登录目标主机发现容器和 Java 进程。"
            >
              <InputNumber style={{ width: '100%' }} min={1} max={65535} />
            </Form.Item>
          </div>
          <Form.Item name="authType" label="认证方式" rules={[{ required: true }]}>
            <Select options={[{ value: 'PASSWORD', label: '账号密码' }, { value: 'SSH_KEY', label: 'SSH Key' }]} />
          </Form.Item>
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name={['credential', 'secret']}
            label={editingTarget ? '新凭据（不填则保持原凭据）' : '凭据'}
            rules={editingTarget ? [] : [{ required: true }]}
          >
            <Input.Password placeholder={editingTarget ? '保持原凭据' : undefined} />
          </Form.Item>
          <Form.Item name="targetType" label="目标类型" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio.Button value="PHYSICAL_JAVA">物理机 Java</Radio.Button>
              <Radio.Button value="DOCKER_CONTAINER">Docker 容器</Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="containerName" label="容器名称">
            <Input />
          </Form.Item>
          <Form.Item label="Java 进程 PID" required>
            <Space.Compact style={{ width: '100%' }}>
              <Form.Item
                name="processId"
                noStyle
                rules={[{ required: true, message: '请填写 Java 进程 PID，或点击获取 PID 自动发现' }]}
              >
                <InputNumber style={{ width: '100%' }} min={1} />
              </Form.Item>
              <Button
                onClick={async () => {
                  const values = form.getFieldsValue();
                  if (!values.host || !values.sshPort || !values.authType || !values.username) {
                    message.warning('请先填写主机、SSH 端口、认证方式和用户名');
                    return;
                  }
                  if (!editingTarget?.credentialId && !values.credential?.secret) {
                    message.warning('请先填写 SSH 凭据');
                    return;
                  }
                  if (values.targetType === 'DOCKER_CONTAINER' && !values.containerName) {
                    message.warning('Docker 容器目标请先填写容器名称');
                    return;
                  }
                  try {
                    const result = await unwrap<JavaProcessDiscoveryResult>(api.post('/api/discovery/java-processes', {
                      host: values.host,
                      sshPort: values.sshPort,
                      authType: values.authType,
                      username: values.username,
                      credentialId: editingTarget?.credentialId,
                      credential: values.credential?.secret ? { secret: values.credential.secret } : undefined,
                      targetType: values.targetType,
                      containerName: values.containerName
                    }));
                    const process = result.processes[0];
                    if (!process) {
                      message.warning('未发现 Java 进程，可手动填写 PID');
                      return;
                    }
                    form.setFieldsValue({ processId: process.processId });
                    message.success(`已获取 PID ${process.processId}`);
                  } catch (error) {
                    message.error(error instanceof Error ? error.message : '获取 PID 失败');
                  }
                }}
              >
                获取 PID
              </Button>
            </Space.Compact>
          </Form.Item>
          <Collapse
            className="form-advanced"
            size="small"
            ghost
            items={[{
              key: 'arthas-ports',
              label: 'Arthas 通道端口（默认即可）',
              forceRender: true,
              children: (
                <>
                  <Alert
                    type="info"
                    showIcon
                    message="这不是业务服务端口。Fordring 接入目标 JVM 后，会通过这些 Arthas 通道执行诊断命令。只有目标机器端口冲突或平台有固定规范时才需要修改。"
                  />
                  <div className="port-grid">
                    <Form.Item
                      name="telnetPort"
                      label="Telnet 命令通道"
                      extra="默认 3658，用于向 Arthas 发送 dashboard、thread、jvm 等命令。"
                    >
                      <InputNumber style={{ width: '100%' }} min={1} max={65535} />
                    </Form.Item>
                    <Form.Item
                      name="httpPort"
                      label="HTTP API 通道"
                      extra="默认 8563，用于 Arthas HTTP API 和 Web Console 访问。"
                    >
                      <InputNumber style={{ width: '100%' }} min={1} max={65535} />
                    </Form.Item>
                  </div>
                </>
              )
            }]}
          />
        </Form>
      </Modal>
      <div className="page-header">
        <h1>接入管理</h1>
        <p>统一管理需要接入 Arthas 的主机与 Docker 容器</p>
      </div>
      <div className="stat-grid">
        <Metric icon={<Cuboid />} label="总接入数" value={stats.data?.totalCount ?? 0} />
        <Metric icon={<CheckCircle />} label="已接入" value={stats.data?.attachedCount ?? 0} />
        <Metric icon={<Clock3 />} label="待处理" value={stats.data?.pendingCount ?? 0} />
      </div>
      <div className="panel">
        <div className="toolbar">
          <Input prefix={<Search size={16} />} placeholder="搜索名称、主机或目标" style={{ width: 320 }} />
          <Radio.Group defaultValue="ALL">
            <Radio.Button value="ALL">全部</Radio.Button>
            <Radio.Button value="PHYSICAL_JAVA">物理机</Radio.Button>
            <Radio.Button value="DOCKER_CONTAINER">Docker 容器</Radio.Button>
          </Radio.Group>
          <div className="spacer" />
          <Button type="primary" icon={<Plus size={16} />} onClick={() => openEditor()}>新增接入</Button>
        </div>
        <Table
          rowKey="id"
          loading={targets.isLoading}
          dataSource={targets.data?.items ?? []}
          pagination={{ pageSize: 10, total: targets.data?.total ?? 0 }}
          columns={[
            { title: '名称', dataIndex: 'name' },
            { title: '主机', dataIndex: 'host' },
            { title: '目标类型', render: (_, row) => row.targetType === 'DOCKER_CONTAINER' ? 'Docker 容器' : '物理机 Java' },
            { title: '目标', render: (_, row) => row.processId ? `PID ${row.processId}` : '-' },
            {
              title: 'Arthas状态',
              render: (_, row) => (
                <div className="target-status-cell">
                  <TargetStatusTag status={row.arthasStatus} />
                  {row.latestFailureReason && (
                    <span className="target-status-reason" title={row.latestFailureReason}>
                      {row.latestFailureReason}
                    </span>
                  )}
                </div>
              )
            },
            { title: '最近操作时间', dataIndex: 'latestOperationTime' },
            {
              title: '操作',
              render: (_, row) => (
                <>
                  <Button type="link" icon={<Edit3 size={14} />} onClick={() => openEditor(row)}>编辑</Button>
                  <Button
                    type="link"
                    icon={<PackageCheck size={14} />}
                    loading={checkArthasInstallation.isPending}
                    onClick={() => checkArthasInstallation.mutate(row.id)}
                  >
                    检查 Arthas
                  </Button>
                  <Button
                    type="link"
                    icon={<Download size={14} />}
                    loading={installArthas.isPending}
                    onClick={() => confirmInstallArthas(row)}
                  >
                    安装 Arthas
                  </Button>
                  {row.arthasStatus === 'ATTACHED'
                    ? <><Link to={`/console/${row.id}`}>进入 {row.name} 控制台</Link><Button type="link" danger onClick={() => detach.mutate(row.id)}>断开</Button></>
                    : <Button type="link" loading={attach.isPending} onClick={() => attach.mutate(row)}>{row.arthasStatus === 'DISCONNECTED' || row.arthasStatus === 'ATTACH_FAILED' ? '重新接入 Arthas' : '接入 Arthas'}</Button>}
                  <Button type="link" danger icon={<Trash2 size={14} />} loading={deleteTarget.isPending} onClick={() => confirmDelete(row)}>删除</Button>
                </>
              )
            }
          ]}
        />
      </div>
    </>
  );
}

function Metric({ icon, label, value }: { icon: ReactNode; label: string; value: number }) {
  return <div className="stat-card"><div className="stat-icon">{icon}</div><div><span>{label}</span><strong>{value}</strong></div></div>;
}

function getErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function isMissingArthasBoot(message: string) {
  return message.includes('arthas-boot.jar')
    || message.includes('请先安装 Arthas')
    || message.includes('ARTHAS_BOOT_MISSING');
}

function formatArthasInstallationMessage(result: ArthasInstallationResult) {
  const path = result.installationPath && result.installationPath !== '-' ? result.installationPath : '未知';
  const version = result.version && result.version !== '-' && result.version !== 'unknown' ? result.version : '未识别';
  return `${result.message}，安装路径：${path}，版本号：${version}`;
}

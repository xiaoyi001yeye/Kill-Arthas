import { Tag } from 'antd';
import type { ArthasStatus, CommandStatus } from '../types';

const targetMap: Record<ArthasStatus, { color: string; text: string }> = {
  NOT_ATTACHED: { color: 'default', text: '未接入' },
  ATTACHED: { color: 'success', text: '已接入' },
  CHECK_FAILED: { color: 'warning', text: '检查失败' },
  ATTACH_FAILED: { color: 'error', text: '接入失败' },
  DISCONNECTED: { color: 'default', text: '已断开' }
};

const commandMap: Record<CommandStatus, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '等待中' },
  RUNNING: { color: 'processing', text: '运行中' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
  TIMEOUT: { color: 'warning', text: '超时' },
  STOPPING: { color: 'warning', text: '停止中' },
  STOPPED: { color: 'default', text: '已停止' },
  CANCELLED: { color: 'default', text: '已取消' }
};

export function TargetStatusTag({ status }: { status: ArthasStatus }) {
  const item = targetMap[status];
  return <Tag color={item.color}>{item.text}</Tag>;
}

export function CommandStatusTag({ status }: { status: CommandStatus }) {
  const item = commandMap[status];
  return <Tag color={item.color}>{item.text}</Tag>;
}


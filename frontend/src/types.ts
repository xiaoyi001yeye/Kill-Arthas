export type ArthasStatus = 'NOT_ATTACHED' | 'ATTACHED' | 'CHECK_FAILED' | 'ATTACH_FAILED' | 'DISCONNECTED';
export type TargetType = 'PHYSICAL_JAVA' | 'DOCKER_CONTAINER';
export type AuthType = 'PASSWORD' | 'SSH_KEY' | 'TOKEN';
export type CommandStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'STOPPING' | 'STOPPED' | 'CANCELLED';
export type CommandSource = 'QUICK' | 'MANUAL' | 'RERUN';
export type RiskLevel = 'ALLOW' | 'CONFIRM' | 'DENY';

export interface PageResult<T> {
  items: T[];
  page: number;
  pageSize: number;
  total: number;
}

export interface AccessTarget {
  id: number;
  name: string;
  environment?: string;
  host: string;
  sshPort?: number;
  authType: AuthType;
  username: string;
  credentialId?: number;
  targetType: TargetType;
  containerName?: string;
  processId?: number;
  processName?: string;
  arthasStatus: ArthasStatus;
  telnetPort?: number;
  httpPort?: number;
  latestOperationTime?: string;
  latestFailureReason?: string;
  createdByName?: string;
  createdAt?: string;
}

export interface CommandExecution {
  id: number;
  command: string;
  targetId: number;
  targetSnapshot: string;
  status: CommandStatus;
  durationMs?: number;
  source: CommandSource;
  operatorName: string;
  executedAt: string;
  outputSizeBytes: number;
  outputTruncated: boolean;
  errorMessage?: string;
  riskLevel: RiskLevel;
  riskConfirmed: boolean;
}

export interface CommandHistoryItem {
  historyId: string;
  origin: 'LOCAL' | 'IMPORTED';
  command: string;
  targetId?: number;
  targetSnapshot: string;
  originalSourceEnvironmentId?: string;
  originalSourceEnvironmentName?: string;
  directSourceEnvironmentId?: string;
  directSourceEnvironmentName?: string;
  status: CommandStatus;
  durationMs?: number;
  source: CommandSource;
  operatorName: string;
  executedAt: string;
  outputSizeBytes: number;
  outputTruncated: boolean;
  errorMessage?: string;
  riskLevel: RiskLevel;
  riskConfirmed: boolean;
  importBatchId?: number;
  importedAt?: string;
  provenanceChain?: string;
}

export interface SavedCommand {
  id: number;
  name: string;
  command: string;
  description?: string;
  visibleInConsole: boolean;
}

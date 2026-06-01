# 命令历史跨环境导出导入设计

## 1. 背景与目标

Fordring 当前已经支持命令执行、命令历史查看、输出分片保存和再次执行。新增需求是：存在两个独立部署的 Fordring 环境，称为 A 环境和 B 环境；用户希望在 A 环境选择部分历史命令，导出为文件，再在 B 环境导入并查看。

本设计目标：

- 支持用户在命令历史列表中多选记录并导出。
- 支持将导出文件从 A 环境离线传递到 B 环境并导入。
- B 环境导入后可以查看命令元信息、原目标快照、来源链路和执行输出。
- 导入记录与 B 环境本地真实执行记录区分清楚。
- 导入记录也可以再次导出到其他环境，但必须保留原始来源和流转链路。
- 导出文件不包含凭据、SSH Key、密码、Arthas 认证信息等敏感连接材料。
- 导入记录允许保存为常用命令，但默认不允许直接再次执行。

非目标：

- 不在本期实现 A/B 环境之间的自动网络同步。
- 不在本期实现导入后自动创建接入目标。
- 不在本期实现导入记录到 B 环境目标的映射执行。
- 不在本期实现导出包签名、加密或脱敏。
- 不改变现有命令执行链路和控制台 WebSocket 协议。

## 2. 已确认决策

| 决策项 | 结论 |
| --- | --- |
| 导入存储 | 导入记录独立存储，不直接写入 `command_execution`。 |
| 导入事务 | 整批原子导入，不允许部分成功；重复记录自动跳过，不算失败。 |
| 历史列表 | 本地记录和导入记录作为统一时间线展示，由后端统一分页、排序和过滤。 |
| 导出格式 | 使用标准 `.zip` 文件，不自定义扩展名。 |
| 输出格式 | zip 内每条命令一个完整 `.txt` 输出文件；导入时再按 B 环境配置重新切分入库。 |
| 输出策略 | MVP 固定包含输出，不提供用户选项。 |
| 导出记录范围 | 只允许导出终态记录：`SUCCESS`、`FAILED`、`TIMEOUT`、`STOPPED`、`CANCELLED`。 |
| 导出对象 | 本地记录和导入记录都允许导出，且同一个导出包可以混合两类记录。 |
| 目标快照 | 导出包使用结构化 `targetSnapshot` 对象。 |
| 重复导入 | MVP 不提供“导入为副本”；完全重复自动跳过。 |
| 来源链 | 导入记录必须持久化原始来源和 `provenanceChain`。 |
| Checksum | `recordChecksum` 不包含 `provenanceChain`；包级 checksum 负责 zip 内文件完整性。 |
| 实例身份 | 稳定的 `fordring.instance.id` 是导出导入强前提。 |
| 签名 | MVP 不做安全签名，只做 SHA-256 完整性校验；导入文件来源默认可信。 |
| 常用命令 | 导入记录允许保存为常用命令，但不保存原目标和输出。 |
| 批次删除 | 删除接口 P1；数据库 FK 和级联删除能力 P0 先设计好。 |

## 3. 核心设计

### 3.1 导入记录独立存储

导入到 B 环境的记录并不是 B 环境真实执行出来的命令。如果直接写入 `command_execution`，会带来三个问题：

- `target_id` 在 B 环境可能不存在，强行复用本地目标 ID 会污染执行语义。
- 再次执行、审计、统计容易把导入记录误当成本地执行记录。
- 后续导入失败、回滚、批次管理、重复导入去重和来源链路都不清晰。

因此新增导入归档表：

- `command_history_import_batch`：一次导入任务的批次信息。
- `imported_command_history`：导入后的历史命令快照。
- `imported_command_output_chunk`：导入后的输出分片。

命令历史页面通过统一查询接口同时展示本地执行记录和导入记录。导入记录在界面上显示“导入”标识，默认只允许查看、复制命令、保存为常用命令和再次导出，不允许直接再次执行。

### 3.2 统一时间线查询

命令历史列表语义是“当前可见的历史命令时间线”，不是“本地历史列表 + 导入历史列表”两个列表的前端拼接。

后端必须把本地记录和导入记录投影成统一结构，再统一排序和分页：

```sql
SELECT * FROM (
  SELECT
    'local:' || id AS history_id,
    'LOCAL' AS origin,
    command,
    target_id,
    target_snapshot,
    NULL AS import_batch_id,
    NULL AS imported_at,
    executed_at,
    status,
    duration_ms,
    source,
    operator_name
  FROM command_execution
  UNION ALL
  SELECT
    'imported:' || id AS history_id,
    'IMPORTED' AS origin,
    command,
    local_target_id AS target_id,
    target_snapshot,
    import_batch_id,
    imported_at,
    executed_at,
    status,
    duration_ms,
    source,
    operator_name
  FROM imported_command_history
) history
ORDER BY executed_at DESC, history_id DESC
LIMIT :limit OFFSET :offset;
```

实现可以使用原生 SQL、数据库视图或 repository 自定义查询。关键约束是：不能先分别查两张表的一页数据再在前端或服务端内存合并，否则分页、总数和排序会错。

### 3.3 来源链路

由于导入记录也允许再次导出，必须区分三类来源信息：

- `originalSourceEnvironment`：这条命令最早在哪个 Fordring 环境真实执行。
- `exportingEnvironment`：当前导出包由哪个 Fordring 环境生成。
- `provenanceChain`：这条记录经历过哪些执行、导出和导入事件。

示例：

```json
[
  {
    "type": "EXECUTED",
    "environmentId": "fordring-a-01",
    "environmentName": "A环境",
    "executionId": 18,
    "at": "2026-06-01T09:45:12Z"
  },
  {
    "type": "EXPORTED",
    "environmentId": "fordring-a-01",
    "environmentName": "A环境",
    "exportId": "exp-a-001",
    "at": "2026-06-01T10:30:00Z"
  },
  {
    "type": "IMPORTED",
    "environmentId": "fordring-b-01",
    "environmentName": "B环境",
    "importBatchId": 7,
    "at": "2026-06-01T11:00:00Z"
  }
]
```

本地真实执行记录首次导出时，后端生成初始链路：`EXECUTED` + `EXPORTED`。导入记录再次导出时，沿用已有链路并追加新的 `EXPORTED` 事件。

## 4. 用户流程

### 4.1 A 环境导出

1. 用户进入「命令历史」页面。
2. 用户筛选命令历史。
3. 用户勾选一条或多条历史记录；可以同时包含本地记录和导入记录。
4. 用户点击「导出」。
5. 系统打开导出确认弹窗，展示：
   - 已选择记录数。
   - 不可导出的非终态记录数。
   - 预计导出文件大小。
   - 敏感信息提示：导出包固定包含命令输出，输出可能包含业务数据。
6. 若选中记录中存在 `PENDING`、`RUNNING`、`STOPPING`，系统拒绝导出，并提示用户等待命令结束。
7. 用户确认后，浏览器下载 zip 文件。

导出文件名建议：

```text
fordring-command-history-{sourceEnvironmentName}-{yyyyMMddHHmmss}.zip
```

### 4.2 B 环境导入

1. 用户进入 B 环境「命令历史」页面。
2. 用户点击「导入」。
3. 系统打开导入弹窗，用户上传 zip 文件。
4. 系统执行预校验并展示导入预览：
   - 文件来源环境。
   - 本次导出环境。
   - 导出时间。
   - 导出操作者。
   - 记录数。
   - 输出总大小。
   - 重复记录数。
   - 不兼容记录数。
5. 用户确认导入。
6. 系统重新校验临时文件并执行整批原子导入。
7. 导入完成后，系统刷新命令历史列表，自动筛选为 `origin=IMPORTED` 和本次 `importBatchId`。

### 4.3 B 环境查看

导入后的记录在命令历史列表中展示：

- 命令。
- 原始来源环境。
- 直接来源环境。
- 原目标快照。
- 原执行时间。
- 状态。
- 耗时。
- 来源，显示为 `IMPORTED` 或“导入”。
- 导入批次。
- 操作：详情、复制命令、保存、导出。

详情抽屉展示：

- 命令内容。
- 原始来源环境。
- 直接来源环境。
- 来源链路。
- 原执行人。
- 原目标快照。
- 原执行时间。
- 导入时间和导入人。
- 执行输出。
- 输出是否截断。

导入记录默认不展示「再次执行」按钮。后续若实现目标映射，可在用户显式映射到 B 环境目标后再允许再次执行。

## 5. 导出包格式

### 5.1 Zip 结构

MVP 使用标准 zip 文件，扩展名为 `.zip`。导入接口必须能解析这个 zip 文件。

```text
manifest.json
records.ndjson
outputs/{recordFileId}.txt
checksums.sha256
```

必填规则：

- 必须包含 `manifest.json`、`records.ndjson`、`checksums.sha256`。
- 每条记录必须声明一个输出文件。
- 输出为空时也必须生成空 `.txt` 文件。
- zip entry 不允许绝对路径、`../` 或其他可触发 Zip Slip 的路径。
- 解压前检查压缩包大小，解压过程中检查累计解压大小和 entry 数量，防止压缩包炸弹。

### 5.2 manifest.json

```json
{
  "schemaVersion": "1.0",
  "fileType": "FORDRING_COMMAND_HISTORY_EXPORT",
  "exportId": "018f6b52-7c23-7c41-a5c0-0a2f6e9f1e20",
  "exportedAt": "2026-06-01T10:30:00Z",
  "exportedBy": "admin",
  "exportingEnvironment": {
    "instanceId": "fordring-b-01",
    "name": "B环境",
    "baseUrl": "https://fordring-b.example.com"
  },
  "options": {
    "outputIncluded": true,
    "outputEncoding": "utf-8"
  },
  "recordCount": 2,
  "outputFileCount": 2
}
```

说明：

- `fileType` 固定用于防止误上传其他 zip。
- `outputIncluded` MVP 固定为 `true`，不提供用户选项。
- `exportingEnvironment.instanceId` 必须存在。导出环境未配置稳定 `fordring.instance.id` 时禁止导出。

### 5.3 records.ndjson

`records.ndjson` 每行是一条 JSON 记录，便于流式生成和读取。

本地记录首次导出示例：

```json
{"recordOrigin":"LOCAL","originalSourceEnvironment":{"instanceId":"fordring-a-01","name":"A环境"},"originExecutionId":18,"command":"dashboard -n 1","targetSnapshot":{"sourceTargetId":7,"name":"order-service","environment":"prod","host":"10.0.1.12","sshPort":22,"targetType":"DOCKER_CONTAINER","containerName":"order","processId":97,"processName":"order-service.jar","telnetPort":3658,"httpPort":8563},"status":"SUCCESS","durationMs":5421,"source":"MANUAL","operatorName":"admin","executedAt":"2026-06-01T09:45:12Z","output":{"path":"outputs/fordring-a-01-18.txt","sizeBytes":12048,"sha256":"sha256:..."},"outputTruncated":false,"errorMessage":null,"riskLevel":"ALLOW","riskConfirmed":false,"recordChecksum":"sha256:...","provenanceChain":[{"type":"EXECUTED","environmentId":"fordring-a-01","environmentName":"A环境","executionId":18,"at":"2026-06-01T09:45:12Z"},{"type":"EXPORTED","environmentId":"fordring-a-01","environmentName":"A环境","exportId":"018f6b52-7c23-7c41-a5c0-0a2f6e9f1e20","at":"2026-06-01T10:30:00Z"}]}
```

导入记录再次导出示例：

```json
{"recordOrigin":"IMPORTED","originalSourceEnvironment":{"instanceId":"fordring-a-01","name":"A环境"},"originExecutionId":18,"command":"dashboard -n 1","targetSnapshot":{"sourceTargetId":7,"name":"order-service","environment":"prod","host":"10.0.1.12","sshPort":22,"targetType":"DOCKER_CONTAINER","containerName":"order","processId":97,"processName":"order-service.jar","telnetPort":3658,"httpPort":8563},"status":"SUCCESS","durationMs":5421,"source":"MANUAL","operatorName":"admin","executedAt":"2026-06-01T09:45:12Z","output":{"path":"outputs/fordring-a-01-18.txt","sizeBytes":12048,"sha256":"sha256:..."},"outputTruncated":false,"errorMessage":null,"riskLevel":"ALLOW","riskConfirmed":false,"recordChecksum":"sha256:...","provenanceChain":[{"type":"EXECUTED","environmentId":"fordring-a-01","environmentName":"A环境","executionId":18,"at":"2026-06-01T09:45:12Z"},{"type":"EXPORTED","environmentId":"fordring-a-01","environmentName":"A环境","exportId":"exp-a-001","at":"2026-06-01T10:30:00Z"},{"type":"IMPORTED","environmentId":"fordring-b-01","environmentName":"B环境","importBatchId":7,"at":"2026-06-01T11:00:00Z"},{"type":"EXPORTED","environmentId":"fordring-b-01","environmentName":"B环境","exportId":"018f6b52-7c23-7c41-a5c0-0a2f6e9f1e20","at":"2026-06-01T12:00:00Z"}]}
```

### 5.4 outputs/*.txt

每条记录一个完整输出文件。导出时按数据库分片顺序写入同一个 `.txt` 文件。导入时再按 B 环境的 `fordring.command.outputChunkBytes` 重新切分保存到 `imported_command_output_chunk`。

输出文件命名建议：

```text
outputs/{originalSourceEnvironmentId}-{originExecutionId}.txt
```

如果同一个导出包内存在相同原始身份但不同 checksum 的记录，可以追加短 checksum：

```text
outputs/{originalSourceEnvironmentId}-{originExecutionId}-{checksumPrefix}.txt
```

### 5.5 checksums.sha256

`checksums.sha256` 保存 zip 内逻辑文件的 SHA-256：

```text
sha256:<manifest-sha>  manifest.json
sha256:<records-sha>   records.ndjson
sha256:<output-sha>    outputs/fordring-a-01-18.txt
```

导入时必须校验：

- `checksums.sha256` 中列出的文件都存在。
- 每个文件的 SHA-256 和清单一致。
- 每条 record 中的 `output.sha256` 与对应输出文件一致。
- 每条 record 中的 `output.sizeBytes` 与对应输出文件一致。

### 5.6 recordChecksum 规则

`recordChecksum` 表示命令历史事实本身，不表示流转过程，因此不包含 `provenanceChain`。

参与 `recordChecksum` 的字段：

- `originalSourceEnvironment.instanceId`
- `originExecutionId`
- `command`
- `targetSnapshot`
- `status`
- `durationMs`
- `source`
- `operatorName`
- `executedAt`
- `output.path`
- `output.sizeBytes`
- `output.sha256`
- `outputTruncated`
- `errorMessage`
- `riskLevel`
- `riskConfirmed`

计算规则：

- 使用 canonical JSON，字段名按字典序排序。
- 排除 `recordChecksum` 和 `provenanceChain` 字段。
- 字符串使用 UTF-8。
- 算法为 SHA-256，保存格式为 `sha256:{hex}`。

包级 checksum 覆盖 zip 内文件内容，因此会覆盖 provenance 文件内容完整性；但 provenance 变化不会改变 `recordChecksum`。

## 6. 环境实例身份

跨环境去重和来源链路依赖稳定实例 ID。必须增加后端配置：

```yaml
fordring:
  instance:
    id: fordring-a-01
    name: A环境
    base-url: https://fordring-a.example.com
```

规则：

- `fordring.instance.id` 是导出导入强前提。
- 如果未显式配置，首次启动生成 UUID 后必须持久化到数据库或配置表。
- 无法获取稳定 `instance.id` 时禁止导出。
- 导入包缺少 `originalSourceEnvironment.instanceId` 时拒绝导入。
- 去重键固定为：

```text
originalSourceEnvironmentId + originExecutionId + recordChecksum
```

## 7. 后端设计

### 7.1 新增模块

新增包：

```text
backend/src/main/java/com/fordring/commandhistory/
  CommandHistoryExportController.java
  CommandHistoryImportController.java
  CommandHistoryQueryController.java
  CommandHistoryExportService.java
  CommandHistoryImportService.java
  CommandHistoryQueryService.java
  CommandHistoryArchiveFile.java
  ImportedCommandHistory.java
  ImportedCommandOutputChunk.java
  CommandHistoryImportBatch.java
```

职责：

| 模块 | 职责 |
| --- | --- |
| ExportController | 接收导出请求，返回 zip 下载文件。 |
| ImportController | 上传 zip、预览导入、确认导入。 |
| QueryController | 提供统一命令历史查询、详情和输出查询。 |
| ExportService | 根据选择的 `historyId` 组装 zip 导出包。 |
| ImportService | 校验 zip、去重、整批写入导入批次和导入记录。 |
| QueryService | 合并本地执行记录与导入记录，供前端统一展示。 |

### 7.2 API 设计

#### 导出命令历史

```text
POST /api/command-history/exports
Content-Type: application/json
```

请求：

```json
{
  "historyIds": ["local:18", "imported:31"]
}
```

响应：

```text
Content-Disposition: attachment; filename="fordring-command-history-A环境-20260601103000.zip"
Content-Type: application/zip
```

异常：

- `400`：未选择记录、记录不存在、包含非终态记录、缺少稳定实例 ID。
- `403`：当前操作者无权导出。
- `413`：导出文件超过服务端限制。

#### 导入预览

```text
POST /api/command-history/imports/preview
Content-Type: multipart/form-data
```

响应：

```json
{
  "fileToken": "tmp-018f6b52",
  "schemaVersion": "1.0",
  "exportingEnvironmentName": "B环境",
  "exportingEnvironmentId": "fordring-b-01",
  "exportedAt": "2026-06-01T10:30:00Z",
  "exportedBy": "admin",
  "recordCount": 3,
  "outputSizeBytes": 20480,
  "duplicateCount": 1,
  "invalidCount": 0,
  "warnings": [
    "导出包包含命令输出，请确认来源可信并符合数据流转规范"
  ]
}
```

`fileToken` 对应服务端临时文件，默认有效期 30 分钟。确认导入时必须重新读取临时文件并重新校验，不能只信任预览结果。

#### 确认导入

```text
POST /api/command-history/imports
Content-Type: application/json
```

请求：

```json
{
  "fileToken": "tmp-018f6b52"
}
```

响应：

```json
{
  "importBatchId": 7,
  "importedCount": 2,
  "skippedDuplicateCount": 1,
  "failedCount": 0
}
```

导入语义：

- 整批原子导入，不允许部分成功。
- 完全重复记录自动跳过，不算失败。
- 结构非法、checksum 不匹配、输出文件缺失、枚举值不合法等情况导致整批失败。
- MVP 不提供 `IMPORT_COPY`。

#### 统一查询命令历史

```text
GET /api/command-history/items?keyword=&targetId=&status=&origin=ALL&sourceEnvironmentId=&importBatchId=&page=1&pageSize=10
```

参数：

| 参数 | 说明 |
| --- | --- |
| `origin` | `ALL`、`LOCAL`、`IMPORTED`。 |
| `sourceEnvironmentId` | 原始来源环境筛选。 |
| `importBatchId` | 导入批次筛选。 |
| `targetId` | 本地目标筛选；导入记录仅在映射 `local_target_id` 后参与。 |

响应：

```json
{
  "items": [
    {
      "historyId": "local:18",
      "origin": "LOCAL",
      "command": "dashboard -n 1",
      "targetId": 7,
      "targetSnapshot": "...",
      "originalSourceEnvironmentName": "B环境",
      "directSourceEnvironmentName": "B环境",
      "status": "SUCCESS",
      "durationMs": 5421,
      "source": "MANUAL",
      "operatorName": "admin",
      "executedAt": "2026-06-01T09:45:12Z",
      "importBatchId": null,
      "importedAt": null
    },
    {
      "historyId": "imported:31",
      "origin": "IMPORTED",
      "command": "thread",
      "targetId": null,
      "targetSnapshot": "...",
      "originalSourceEnvironmentName": "A环境",
      "directSourceEnvironmentName": "B环境",
      "status": "SUCCESS",
      "durationMs": 900,
      "source": "MANUAL",
      "operatorName": "admin",
      "executedAt": "2026-05-31T13:20:00Z",
      "importBatchId": 7,
      "importedAt": "2026-06-01T11:00:00Z"
    }
  ],
  "page": 1,
  "pageSize": 10,
  "total": 2
}
```

#### 查看详情与输出

```text
GET /api/command-history/items/{historyId}
GET /api/command-history/items/{historyId}/output?fromSequence=1&limit=50
```

`historyId` 使用带前缀的稳定 ID：

- `local:{commandExecutionId}`
- `imported:{importedCommandHistoryId}`

前端据此决定是否展示「再次执行」。

#### 删除导入批次

删除导入批次为 P1 能力，但数据结构 P0 先支持。

```text
DELETE /api/command-history/import-batches/{id}
```

规则：

- 只删除导入记录，不影响本地真实执行历史。
- 删除批次时级联删除导入历史和输出分片。
- 审计动作：`COMMAND_HISTORY_IMPORT_BATCH_DELETE`。

### 7.3 数据库表设计

#### command_history_import_batch

```sql
CREATE TABLE command_history_import_batch (
  id BIGSERIAL PRIMARY KEY,
  file_name VARCHAR(255) NOT NULL,
  schema_version VARCHAR(32) NOT NULL,
  export_id VARCHAR(64) NOT NULL,
  exporting_environment_id VARCHAR(128) NOT NULL,
  exporting_environment_name VARCHAR(128) NOT NULL,
  exporting_base_url VARCHAR(512),
  exported_at TIMESTAMPTZ NOT NULL,
  exported_by VARCHAR(128) NOT NULL,
  record_count INTEGER NOT NULL,
  imported_count INTEGER NOT NULL DEFAULT 0,
  skipped_duplicate_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  package_checksum VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  failure_reason TEXT,
  imported_by VARCHAR(128) NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_command_history_import_batch_imported_at
  ON command_history_import_batch (imported_at DESC);
```

#### imported_command_history

```sql
CREATE TABLE imported_command_history (
  id BIGSERIAL PRIMARY KEY,
  import_batch_id BIGINT NOT NULL REFERENCES command_history_import_batch(id) ON DELETE CASCADE,
  original_source_environment_id VARCHAR(128) NOT NULL,
  original_source_environment_name VARCHAR(128) NOT NULL,
  direct_source_environment_id VARCHAR(128) NOT NULL,
  direct_source_environment_name VARCHAR(128) NOT NULL,
  origin_execution_id BIGINT NOT NULL,
  command TEXT NOT NULL,
  target_snapshot TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  duration_ms BIGINT,
  source VARCHAR(32) NOT NULL,
  operator_name VARCHAR(128) NOT NULL,
  executed_at TIMESTAMPTZ NOT NULL,
  output_size_bytes BIGINT NOT NULL DEFAULT 0,
  output_truncated BOOLEAN NOT NULL DEFAULT FALSE,
  output_sha256 VARCHAR(128) NOT NULL,
  error_message TEXT,
  risk_level VARCHAR(32) NOT NULL DEFAULT 'ALLOW',
  risk_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  record_checksum VARCHAR(128) NOT NULL,
  duplicate_key VARCHAR(512) NOT NULL,
  provenance_chain TEXT NOT NULL,
  local_target_id BIGINT,
  imported_by VARCHAR(128) NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_imported_command_history_duplicate_key
  ON imported_command_history (duplicate_key);

CREATE INDEX idx_imported_command_history_executed_at
  ON imported_command_history (executed_at DESC);

CREATE INDEX idx_imported_command_history_import_batch
  ON imported_command_history (import_batch_id);

CREATE INDEX idx_imported_command_history_original_source
  ON imported_command_history (original_source_environment_id);
```

`duplicate_key` 生成规则：

```text
{originalSourceEnvironmentId}:{originExecutionId}:{recordChecksum}
```

完全重复记录自动跳过。同一 `originExecutionId` 但 `recordChecksum` 不同，表示原始事实发生变化或输出补齐，允许作为不同版本导入，并在详情中提示“同源记录存在多个版本”。

#### imported_command_output_chunk

```sql
CREATE TABLE imported_command_output_chunk (
  id BIGSERIAL PRIMARY KEY,
  imported_history_id BIGINT NOT NULL REFERENCES imported_command_history(id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  content TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (imported_history_id, sequence)
);

CREATE INDEX idx_imported_command_output_chunk_history
  ON imported_command_output_chunk (imported_history_id, sequence);
```

## 8. 校验、去重与限制

### 8.1 导出校验

- `historyIds` 不能为空。
- 单次最多导出 500 条，默认可配置。
- 导出总大小默认不超过 50 MB，默认可配置。
- 只允许导出终态记录：`SUCCESS`、`FAILED`、`TIMEOUT`、`STOPPED`、`CANCELLED`。
- 禁止导出 `PENDING`、`RUNNING`、`STOPPING`。
- 只能导出当前操作者可见的历史记录。当前单用户模式下默认全部可见，后续权限系统接入后按权限过滤。
- 缺少稳定 `fordring.instance.id` 时禁止导出。

配置项：

```yaml
fordring:
  command-history:
    export-max-records: 500
    export-max-bytes: 52428800
    import-max-bytes: 52428800
    import-max-uncompressed-bytes: 104857600
    import-max-entry-count: 5000
    import-preview-ttl-minutes: 30
```

### 8.2 导入预览校验

- 校验文件是否为可解析 zip。
- 校验 `manifest.json`、`records.ndjson`、`checksums.sha256` 是否存在。
- 校验 `fileType`。
- 校验 `schemaVersion` 是否兼容。
- 校验 zip entry 路径，防止 Zip Slip。
- 校验压缩包大小、解压后累计大小和 entry 数量。
- 校验 `checksums.sha256`。
- 校验每条记录的 `recordChecksum`。
- 校验每条记录声明的输出文件存在。
- 校验每条记录的 `output.sizeBytes` 和 `output.sha256`。
- 校验枚举值是否合法。
- 校验必填字段是否存在。
- 校验 `originalSourceEnvironment.instanceId` 是否存在。

### 8.3 确认导入校验

确认导入时必须重新读取 `fileToken` 指向的临时文件并重新执行完整校验。不能仅依赖预览阶段缓存的解析结果。

临时文件规则：

- `fileToken` 默认有效期 30 分钟。
- 服务重启后 token 可以失效。
- token 只绑定当前操作者。
- 过期临时文件由后台清理任务删除。
- 确认导入成功或失败后，临时文件都应删除。

### 8.4 导入事务

MVP 采用整批原子导入：

- 任一记录结构非法、checksum 不匹配、输出缺失或写库失败，则整批回滚。
- 完全重复记录自动跳过，不算失败。
- 预览阶段发现不兼容记录时，确认导入按钮不可用。
- `failedCount` 仅用于返回批次级失败结果；正常成功响应中应为 0。

### 8.5 重复记录处理

默认以 `originalSourceEnvironmentId + originExecutionId + recordChecksum` 判断重复。

- 完全相同记录重复导入：自动跳过。
- 同一 `originExecutionId` 但 checksum 不同：允许导入为不同版本。
- MVP 不提供“导入为副本”选项。

## 9. 前端设计

### 9.1 命令历史列表

在 `CommandsPage` 基础上增加：

- 表格行选择。
- 顶部「导出」按钮，有选中项时可用。
- 顶部「导入」按钮。
- 来源筛选：全部、本地执行、导入记录。
- 原始来源环境筛选。
- 导入批次筛选。

列表中新增“来源环境/来源”列：

- 本地记录：显示当前环境名称，标识为“本地”。
- 导入记录：显示原始来源环境，标识为“导入”。

导入完成后，前端自动切换为：

```text
origin=IMPORTED
importBatchId={本次导入批次}
```

### 9.2 导出弹窗

展示项：

- 已选择记录数。
- 不可导出的非终态记录数。
- 预计导出大小。
- 固定包含输出的敏感信息提示。

MVP 不提供“是否包含输出”选项。确认后调用 `/api/command-history/exports` 并触发浏览器下载。

### 9.3 导入弹窗

导入弹窗分三步：

1. 上传 zip 文件。
2. 预览导入结果。
3. 确认导入并展示结果。

预览区域展示：

- 本次导出环境。
- 原始来源环境数量。
- 导出时间。
- 记录数。
- 重复数。
- 输出大小。
- 告警信息。

### 9.4 详情抽屉

本地记录保持现有能力。导入记录详情补充：

- 顶部显示“导入记录”标识。
- 展示原始来源环境、直接来源环境和导入批次。
- 展示来源链路。
- 隐藏「再次执行」。
- 保留「复制命令」「保存为常用命令」「导出」。

保存为常用命令时：

- 只保存命令文本和用户填写的名称/描述/快捷显示设置。
- 不保存原目标快照。
- 不保存输出。
- 不自动映射到 B 环境目标。

## 10. 安全与审计

### 10.1 敏感信息处理

导出包不包含：

- 凭据表内容。
- SSH 密码。
- SSH Key。
- Arthas Basic Auth 用户名和密码。
- `credential_id` 指向的任何密文。

导出包包含：

- 命令文本。
- 目标快照中的主机、端口、容器、PID、进程名。
- 命令输出。

命令输出可能包含业务数据、Token 或异常堆栈中的敏感信息，因此导出前必须展示确认提示。

### 10.2 Checksum 与信任边界

MVP 不做安全签名，只做 SHA-256 完整性校验。

需要明确：

- checksum 可以发现文件损坏或内容与清单不一致。
- checksum 不能证明导出包来自可信 A 环境。
- 导入文件来源必须可信。
- 后续如需跨组织或非可信渠道流转，应增加签名、公钥信任和密钥轮换设计。

### 10.3 审计日志

新增审计动作：

| 动作 | 资源 | 说明 |
| --- | --- | --- |
| `COMMAND_HISTORY_EXPORT` | `COMMAND_HISTORY_EXPORT` | 导出历史命令。 |
| `COMMAND_HISTORY_IMPORT_PREVIEW` | `COMMAND_HISTORY_IMPORT` | 上传并预览导入包。 |
| `COMMAND_HISTORY_IMPORT` | `COMMAND_HISTORY_IMPORT` | 确认导入。 |
| `COMMAND_HISTORY_IMPORT_BATCH_DELETE` | `COMMAND_HISTORY_IMPORT` | 删除导入批次。 |

审计记录应包含：

- 操作者。
- 记录数量。
- 包 checksum。
- 导出环境。
- 原始来源环境列表。
- 成功/失败结果。
- 失败原因。

## 11. 与现有代码的关系

现有结构中：

- `CommandExecution` 保存本地真实执行记录。
- `CommandOutputChunk` 保存本地输出分片。
- `CommandController` 提供当前命令历史列表、详情、输出和再次执行。
- `CommandsPage` 当前直接使用 `/api/commands/executions`。

本需求建议新增统一命令历史 API，而不是直接替换原接口：

- 保留 `/api/commands/executions` 供执行域使用。
- 新增 `/api/command-history/items` 供命令历史页面使用。
- 前端命令历史页迁移到新接口后，可以同时展示本地和导入记录。
- 再次执行仍然只调用 `/api/commands/executions/{id}/rerun`，仅对 `origin=LOCAL` 的记录开放。
- 导出使用新的 `historyIds`，支持 `local:*` 和 `imported:*`。

## 12. 实施步骤

### 12.1 后端

1. 增加环境实例配置 `fordring.instance.*` 和持久化兜底。
2. 增加导入归档相关 Flyway migration，包含 FK 和级联删除。
3. 实现 zip 导出包 DTO、NDJSON 写入、输出文件写入和 checksum 计算。
4. 实现 `/api/command-history/exports`。
5. 实现导入预览临时文件机制和清理机制。
6. 实现 zip 解析、Zip Slip 防护、checksum 校验和原子导入。
7. 实现 `/api/command-history/imports/preview` 和 `/api/command-history/imports`。
8. 实现统一查询 `/api/command-history/items`、详情和输出接口。
9. 增加来源链生成、持久化和再次导出追加逻辑。
10. 增加审计日志记录。
11. 增加单元测试和集成测试。

### 12.2 前端

1. `CommandsPage` 表格增加多选。
2. 增加导出弹窗和 zip 下载逻辑。
3. 增加导入弹窗、上传、预览和确认导入流程。
4. 列表请求迁移到 `/api/command-history/items`。
5. 详情抽屉支持 `LOCAL` 与 `IMPORTED` 两种记录。
6. 导入记录隐藏再次执行入口。
7. 增加来源环境、来源类型、导入批次筛选。
8. 导入完成后自动定位到本次导入批次。

### 12.3 测试

后端测试：

- 导出本地记录，zip 包包含 manifest、records、outputs 和 checksums。
- 导出导入记录，来源链追加本次导出事件。
- 一个 zip 包混合本地记录和导入记录。
- 不允许导出 `RUNNING`、`PENDING`、`STOPPING` 记录。
- 导出包不包含凭据字段。
- 导入预览能识别导出环境、记录数、输出大小和重复数。
- 重复导入自动跳过。
- checksum 错误时拒绝导入。
- zip entry 包含 `../` 时拒绝导入。
- 输出文件缺失时拒绝导入。
- 任一记录非法时整批导入回滚。
- 导入记录能通过统一历史接口查询和查看输出。
- 删除导入批次时级联删除导入记录和输出分片。

前端测试：

- 未选择记录时导出按钮禁用。
- 选择记录后可以下载 zip 文件。
- 选中非终态记录时展示不可导出提示。
- 上传非法 zip 时展示错误。
- 上传合法 zip 后展示预览。
- 导入完成后列表自动展示本次导入批次。
- 导入记录详情不展示再次执行。
- 导入记录可以保存为常用命令。

## 13. 后续演进

- 支持导出包密码加密。
- 支持导出时输出脱敏。
- 支持导出包签名和信任公钥配置。
- 支持导入记录映射到 B 环境目标。
- 支持映射后再次执行。
- 支持定时同步或通过对象存储传递导出包。
- 支持按导入批次删除导入记录的前端入口。

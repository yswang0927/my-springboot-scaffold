# my-springboot-scaffold
我的springboot脚手架

## 1. 错误处理策略

在分散式系统中，网路的不确定性、第三方服务的不可预测、跨服务间的资源竞争，都让错误更频繁、更难排查； 在编排架构下，以分类并结构化的方式处理错误，让开发者专注于业务决策。

1.1 错误的分类（Error Taxonomy）
- Transient Errors（暂时性错误）：网路抖动、暂时的超时（Timeout）、短暂服务不可用。 
- Permanent Errors（永久性错误）：输入不合法、业务规则不符（例如卡号错误）。 
- Business Failures（业务失败）：流程的合理结果，如库存不足、风险控管拒绝；不是Bug。 
- System Failures（系统崩溃）：Worker crash、节点故障、基础设施层故障。

1.2 常见错误类型与对应策略：

| 类型               | 常见情境                | 是否适合重试 | 建议策略             |
|------------------|---------------------|--------|------------------|
| Transient        | 短暂网路错误、第三方5xx、逾时    | 是      | 可考虑自动重试          |
| Permanent        | 参数验证失败、资源不存在、卡号无效   | 否      | 快速失败并回报呼叫端       |
| Business Failure | 库存不足、额度不足、黑名单       | 视情况    | 分支流程、人工介入或补偿     |
| System Failure   | Worker crash、部署重新启动 | 需要手动   | 交由平台事件重播与任务续跑复原  |

## 2. 不要让流程无限等：Timeout Policy 的设计思维

在分散式流程中，网路延迟、第三方不稳与长任务不可避免，Timeout 与Retry 的规则设定是推进及修复流程的一大重点。

### 2.1 Timeout 与Retry 的本质介绍
- Timeout：保护机制（防止卡死、释放资源）。
- Retry：回复机制（再试一次，直到完成）。
- 两者的互动：
   - Timeout 发生→ 任务被中断→ Retry 规则生效再试一次。
   - 没有Timeout，执行就可能卡住。
   - 没有Retry，Timeout 就只会让流程失败。

### 2.2 Timeout 的类型

|类型   |作用   |典型场景   |设定建议   |
|---|---|---|---|
|Start-to-Close|限制单次Activity 执行时间|呼叫外部API、单步DB 操作|依P95~P99 Latency ×2~3 倍
|Schedule-to-Start|限制排队等待时间|高峰期工作拥塞|高于平均排队时间的2~3 倍
|Schedule-to-Close|等待+执行的整体上限|SLA 需要端到端保障|覆盖整体最坏情况但避免过长
|Heartbeat|长任务活性与进度回报|批次处理、档案搬移、汇入|5–30 秒；与checkpoint 一起用

### 2.3 Timeout 的设定思维

- 原则：
  - 以实测P95/P99 为基础估算，Start-to-Close 建议取P99×2（留安全缓冲）。
  - 短任务用短Timeout（几秒到十数秒），避免卡死执行绪。
  - 长任务必备Heartbeat（5–30 秒回报一次），缩短回复成本。
  - 能短就短：优先采「较短Timeout + Heartbeat + checkpoint」，少用超长Timeout。

- 快速检查：
  - Start-to-Close 是否覆盖单次执行的合理上限？
  - Schedule-to-Start 是否涵盖尖峰期排队等待时间？
  - Schedule-to-Close 是否满足端到端步骤上限（等待+执行）？
  - HeartbeatTimeout 是否至少涵盖一个安全的进度回报窗口？

- 常见错误：
  - Timeout 设太长→ 执行绪被占用过久、吞吐下降。
  - Timeout 设太短→ 轻易引发重试风暴。
  - 缺少Heartbeat → 长任务失败只能整批重跑，回复代价高。

## 3. 错误难免，重试要有策略：Retry Policy 的设计思维

### 3.1 Retry Policy 的类型

|类型   |作用   |典型场景   |设定建议   |
|---|---|---|---|
|maxAttempts|限制最大重试次数，控制成本与风险|短任务（API 呼叫）、高成本任务（付费/重IO）|短任务3–5、高成本2–3、批次/低优先5–7；与initialInterval/backoff/maximumInterval 一起估算总重试时间，需≤ 单次/整体Timeout
|initialInterval|第一次重试等待时间|上游抖动、瞬时错误|以上游P95 为基准：短连线/快查询100–300ms、人机互动200–500ms、第三方较慢1–2s；与maxAttempts/backoff/maximumInterval 合并估算总重试时间≤ 单次Timeout；越即时initialInterval 越短、maxAttempts 越少
|backoffCoefficient|每次重试逐步延长间隔|上游部分故障、做压力缓释|2.0（指数退避）；过大易拖长总时间
|maximumInterval|单次等待的上限|避免重试等待过长|建议20–60s（短任务5–15s、第三方较慢20–60s）；须≤ 单次Timeout；与backoffCoefficient 一起限制总重试时间，避免同时唤醒尖峰
|nonRetryableErrorTypes|标记不可重试的错误型别|验证失败、资源不存在、权限问题、业务失败|例如InvalidInput/InvalidState/PermissionDenied/DuplicateRequest

### 3.2 Retry 的设计思维
- 原则：
  - 明确标记nonRetryable（永久性/业务失败），缩短失败回馈时间。
  - 退避要有上限（maximumInterval）。
  - 幂等性必备，避免重复执行副作用。

- 快速检查：
  - maximumInterval 是否≤ Start-to-Close？
  - 重试等待总和是否≤ 步骤总上限（例如Schedule-to-Close）？ 
  - nonRetryable 型别是否完整（例如InvalidInput/InvalidState 等）？

- 常见错误：
  - 退避无上限或过长，导致等待时间不可控。
  - 忽略幂等键，重试造成重复扣款/重复写入。

### 3.3 Timeout 与Retry 策略的演进（版本升级与动态调整）
- 单次执行开始→ Timeout（或暂时性错误）→ 重试等待→ 下一次执行→ 成功或达到最大尝试→ 失败。
- Timeout 与Retry 不是一次设定就永远固定，而是应该随着流量、失败模式、下游SLA 不断调整。
- Timeout 太短→ Retry 太频繁→ 系统压力放大。
- Timeout 太长→ Worker 执行绪浪费→ 系统吞吐下降。
- 关键：Timeout 与Retry 必须一起调校。
- 经验法则：让「单次Timeout ≈ 外部作业P99 ×2」，「最大退避间隔≤ 单次Timeout」，避免同时大量超时与重试。

#### 3.3.1 上线初期：保守策略
- Timeout：设短一点，避免Worker 执行绪被卡死。
- Retry：次数设少一点，避免一开始就重压下游。
- 目标：收集真实运行数据，先确保系统不会自爆。

#### 3.3.2 运行中：根据数据调整
- 收集近7–30 天的P95/P99 latency。
  - 调整Timeout：
  - Start-to-Close ≈ P99 × 2。
  - Heartbeat interval ≈ 一个稳定子批的处理时间，HeartbeatTimeout ≈ 2–3 倍。
- 调整Retry：
  - initialInterval ≈ P95（或其0.5–1 倍）。
  - maximumInterval ≤ Start-to-Close。
  - maxAttempts 根据SLA 微调，确保总重试时间≤ 单步Timeout。

#### 3.3.3 进阶：动态策略
- 使用动态设定或feature flag，将Timeout / RetryOptions 从程式码抽离：
- 例如在高峰期，临时把maxAttempts 从5 改成2，避免雪崩。
- 或者将maximumInterval 调大，让下游有恢复空间。
- 不需要重启Worker，就能即时生效。

#### 3.3.4 策略演进原则
- 一开始保守，避免过度重试。
- 随着数据累积，用P95/P99 作为调整依据。
- 持续观测，必要时动态调整。
- Timeout 与Retry 必须联动调校，不能单独看。

#### 3.3.5 常见反模式
- 无限重试→ 下游雪崩。
- 不分transient / permanent error → 白费力气。
- Timeout 与Retry 没协调→ retry storm。
- 心跳没设→ 大任务重跑。

#### 3.3.6 补充：P95/P99
- 定义：P95 表示95% 的请求在此时间内完成（P99=99%）。用多数请求的真实Latency 来定参数，×2 留安全缓冲。
- 取样：近7–30 天的实际Latency，分环境/地区/端点各自量测。
- 视觉化：用直方图观察Latency，比分别盯平均值更有意义。
- 常见误解：P95/P99 是Latency 分位数，不是成功率；P99 对样本量敏感，需足够时间窗（7–30 天）。

应用：
1. 取数：取得目标端点的P95、P99。
2. 设单次Timeout：短任务Start-to-Close ≈ P99 × 2。
3. 设initialInterval：≈ P95（或其0.5–1 倍）。
4. 设maximumInterval 并检核：maximumInterval ≤ Start-to-Close；计算重试等待的几何和（总重试时间）需≤ Start-to-Close。
5. 长任务Heartbeat：以「一次稳定子批的处理时间」为回报间隔；HeartbeatTimeout ≈ 间隔× 2–3。
6. 上线后回看：流量高峰或改版后重看P95/P99，再微调Timeout/Retry。

例子：
- 步骤1（量测）：P95=420ms、P99=900ms。
- 步骤2（先设Timeout/初始间隔）：Start-to-Close≈1.8s（900ms×2）、initialInterval≈0.42s（≈P95）。
- 步骤3（估算重试等待；maxAttempts=5、backoff=2.0、maximumInterval=1.8s）：
0.42s → 0.84s → 1.68s → 1.8s（共4 次），合计≈4.74s。
- 检核：重试等待总和（4.74s）是否≤ 步骤总上限（例如Schedule-to-Close）？若超过，调整：减少maxAttempts、缩短maximumInterval，或拉长步骤总上限。

## 4. 流量高峰不失控：并发与限速的设计指南

在分散式系统中，错误处理常常是我们第一个想到的事（Timeout、Retry、幂等性）。但实际上，系统挂掉的原因，更多时候是「流量过大」把自己或下游撑爆。
要同时顾好上游请求进来时Worker 能存活，以及下游服务不被一波流量打挂，Worker需要提供两个重要的保护机制：Concurrency Control（并发控制） 与Rate Control（速率控制）。

### 4.1 Concurrency Control 与Rate Control 的本质
- Concurrency（并发）：资源保护机制（限制同时处理中的任务数，避免CPU/记忆体/DB 连线被吃光）。
- Rate（速率）：流量整形与配额保护（限制单位时间任务数，平滑尖峰、对齐下游QPS/配额）。
- 两者的互动：
  - 缺少并发限制→ 瞬间爆量易把Worker/DB 压垮。
  - 并发太大→ CPU/记忆体/DB 连线耗尽、延迟飙升。
  - 并发太小→ScheduleToStart排队上升、吞吐不足。
  - 缺少速率限制→ 全域QPS 易超标打挂下游。
  - 速率太低→ 任务堆积、尾延迟上升。
  - 速率太高→ 下游产生429/503、配额超标。

#### 4.1.1  Concurrency Control：控制同时并发数
Concurrency 指的是Worker 同时可以执行多少个任务。

举例来说：
- 如果Worker 一次开100 个thread 同时打资料库，DB 连线池可能瞬间被用光，其他服务也跟着挂。
- 合理做法是先限制并发，例如「最多10 个Activity 同时跑」，确保机器资源跟连线池不会瞬间爆掉。

#### 4.1.2 Rate Control：控制每秒执行速率
Rate 指的是Worker 每秒最多可以处理多少次任务。

举例来说：
- 设定「每秒最多50 次」，尖峰时Worker 会以固定节奏往下游送，不会在一秒钟内冲出200 次请求把资料库打挂。
- 这对于后端DB 或外部API 服务特别重要，因为很多系统都有QPS 上限，没控速就会直接超标。

### 4.2 Concurrency + Rate Control 的设计思维
- 只设定Concurrency → 能保护自己，不会瞬间把Worker 或DB 压爆。
- 只设定Rate → 能保护下游，但如果Worker 单次并发开太大，还是可能吃满执行绪。
- 两者都设→ 最稳健，但也不要一开始就锁太死。先从保守数值起跑，透过监控（metrics / dashboard）观察后，再慢慢调整。
- 须与Timeout/Retry 协同：过短Timeout、过密Retry 配上过大的并发/速率，容易形成重试风暴。
- 参数提示：
  - ScheduleToStart高且CPU 宽松→ 提升并发或扩Worker；
  - 429/503 上升→ 下修单机或全域速率、放大退避；
  - CPU/GC 飙高→ 降并发、将部分Local Activity 外移、或扩容；
  - 尾延迟高但下游正常→ 提升并发或扩Worker，并检查速率是否过低。
  - 协同校准：MaxWorkerActivitiesPerSecond × Worker 数 ≤ MaxTaskQueueActivitiesPerSecond ≤ 下游限制。

#### 4.2.1 常见陷阱
- 只开并发、不限速
  - Worker 撑得住，但尖峰期会直接把下游或资料库打爆。
  - 典型结果：DB 出现Connection refused、CPU 飙100%。
  - 只限速、不限并发
  - 流量总量是受控的，但瞬间爆量还是可能吃光执行绪。
  - 典型结果：Worker 卡死、延迟爆炸。
- 数值一开始就乱设
  - 设太小→ 系统看起来慢吞吞，还没发挥硬体效能。
  - 设太大→ 撑不住就雪崩。
  - 最佳做法：观测→ 微调→ 再观测。












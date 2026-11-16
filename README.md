# Research Hub

使用 AI 辅助开发，面向研究生和课题组的文献阅读与组会协作全栈项目。后端采用 Java 17、Spring Boot 3.5、Spring MVC、JDBC、Flyway、H2、PDFBox 和 Micrometer，前端采用 HTML、CSS 和原生 JavaScript，由 Spring Boot 同时提供页面与 API 服务。

核心流程是导入资料，记录阅读进展，生成带来源摘录的组会草稿，保存文字纪要，提取待办提议，人工确认后跟踪执行。

## 启动

需要 JDK 17 或更高版本。项目附带 Maven Wrapper，首次构建需要联网下载依赖。

在项目目录的 PowerShell 中执行以下命令。

```powershell
.\mvnw.cmd spring-boot:run
```

默认监听 `http://localhost:8081`。接口调试页为 `http://localhost:8081/docs`，健康检查为 `http://localhost:8081/actuator/health`。数据库保存在 `data/research-hub.mv.db`，重启后资料和任务继续保留。所有接口时间记录采用 UTC，组会日期和资料整理周期使用请求中给出的日期。

启动后在浏览器打开 `http://localhost:8081`，注册账号并创建课题组即可使用。默认 `local` 模式使用原文摘录生成草稿，按纪要中明确标记的待办提取提议。页面顶部会显示实际运行模式。

浏览器操作流程如下。

1. 在工作概览创建课题组，在成员弹窗添加已注册的同学。
2. 在文献与笔记中上传文件或粘贴文本，等待解析完成后查看原文、保存阅读记录。用于组会的资料与笔记都需要设为课题组共享。
3. 在组会空间创建组会并选择整理周期，生成草稿，展开引用核对原文。
4. 保存文字纪要并提取待办，核对事项、负责人和日期后勾选确认。
5. 在行动清单更新进度，在处理记录中查看后台任务的执行步骤、失败原因及重试操作。
6. 在使用评估中填写真实使用的对比耗时和审核结果，查看汇总与原始记录。

页面适配桌面与手机宽度。登录令牌保存在当前标签页的 sessionStorage 中，刷新后可继续使用，关闭标签页后需重新登录。笔记与纪要按版本保存，遇到并发修改会提示错误并保留输入，可手动重新读取。

另开一个 PowerShell 终端执行演示。

```powershell
.\examples\demo.ps1
```

演示会创建独立账号和课题组，导入示例材料，保存阅读记录，生成组会草稿，提取两项待办，并确认其中一项。输出账号和资源 ID，可在 `/docs` 登录后查看。示例材料在 `examples/reading-material.md`，属于用于验证流程的演示文本。

也可以打包后运行。

```powershell
.\mvnw.cmd package
java -jar target/research-hub.jar
```

## 账号与课题组

先通过 `POST /api/auth/register` 注册，或通过 `POST /api/auth/login` 登录。请求示例如下。

```json
{
  "email": "student@example.com",
  "displayName": "小李",
  "password": "change-this-password"
}
```

返回的 `token` 用于 `Authorization: Bearer <token>`。Swagger 页面右上角的 Authorize 填写 token 本身。令牌有效期为七天，登出后立即失效。密码使用 BCrypt，数据库只保存访问令牌的 SHA-256。

`POST /api/workspaces` 创建课题组，创建者成为 OWNER。OWNER 可以通过 `POST /api/workspaces/{workspaceId}/members` 添加已注册成员，请求体为 `{"email":"member@example.com"}`。成员可以导入资料、记录阅读进展和创建自己的组会。组会创建者和 OWNER 可以编辑该组会、发起生成和确认待办。

## 第一版接口

各接口的完整请求字段和响应结构见 `/docs`。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/auth/register`、`/api/auth/login` | 注册、登录 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/auth/logout` | 注销当前令牌 |
| GET | `/api/system` | 当前生成模式和上传能力，不含模型密钥 |
| GET / POST | `/api/workspaces` | 课题组列表、创建 |
| GET / POST | `/api/workspaces/{w}/members` | 查看成员、OWNER 添加成员 |
| POST | `/api/workspaces/{w}/documents` | multipart 文件导入 |
| POST | `/api/workspaces/{w}/documents/text` | JSON 文本导入 |
| GET | `/api/workspaces/{w}/documents` | 当前用户可见资料，最近 100 条 |
| GET | `/api/workspaces/{w}/documents/{d}` | 资料信息与解析状态 |
| GET | `/api/workspaces/{w}/documents/{d}/chunks` | 按页查看原文片段 |
| GET | `/api/workspaces/{w}/search?q=...` | 权限过滤后的关键词检索 |
| GET | `/api/workspaces/{w}/documents/{d}/notes` | 可见阅读记录 |
| PUT | `/api/workspaces/{w}/documents/{d}/notes/mine` | 新增或更新自己的阅读记录 |
| GET / POST | `/api/workspaces/{w}/meetings` | 组会列表、创建 |
| GET | `/api/workspaces/{w}/meetings/{m}` | 草稿、纪要与待办提议 |
| POST | `/api/workspaces/{w}/meetings/{m}/draft-jobs` | 后台生成组会草稿 |
| PUT | `/api/workspaces/{w}/meetings/{m}/minutes` | 按版本保存文字纪要 |
| POST | `/api/workspaces/{w}/meetings/{m}/action-jobs` | 后台提取待办提议 |
| POST | `/api/workspaces/{w}/meetings/{m}/confirm-actions` | 人工确认并创建待办 |
| GET | `/api/workspaces/{w}/tasks?status=TODO` | 查看课题组待办 |
| PATCH | `/api/workspaces/{w}/tasks/{t}` | 更新待办状态 |
| GET | `/api/workspaces/{w}/jobs` | 自己发起的最近 100 个任务 |
| GET | `/api/jobs/{j}`、`/api/jobs/{j}/events` | 任务状态、执行步骤 |
| POST | `/api/jobs/{j}/cancel`、`/api/jobs/{j}/retry` | 取消、手动重试 |
| POST | `/api/workspaces/{w}/measurements` | 记录人工对比实验 |
| GET | `/api/workspaces/{w}/measurements` | 按场景汇总效率与质量 |
| GET | `/api/workspaces/{w}/measurements/records` | 最近 200 条原始测量记录 |

### 资料与阅读记录

上传文件时使用 multipart 字段 `file`、`title`、`visibility`。支持最大 8MB、150 页的文本型 PDF，以及 UTF-8 编码的 Markdown、TXT。PDFBox 提取正文时保留页号。扫描版 PDF 在没有可提取文本时返回明确失败原因，需要先进行 OCR。

文本导入示例如下。

```json
{
  "title": "本周阅读材料",
  "content": "这里是待整理的正文。",
  "visibility": "GROUP"
}
```

`visibility` 使用 `PRIVATE` 或 `GROUP`，导入默认值为 PRIVATE。PRIVATE 资料只有上传者可见，GROUP 资料对课题组成员可见。同一用户、工作区、可见范围内按原始文件内容哈希去重。第一版的可见范围在导入时确定，需要公开个人资料时可重新按 GROUP 导入。

导入返回 HTTP 202 和 `job.id`，轮询 `/api/jobs/{jobId}`，直到 SUCCEEDED 或 FAILED。相同内容重复导入返回原资料及原任务。失败或取消的导入可以通过原任务的 retry 接口重新执行。

阅读记录示例如下。

```json
{
  "readingStatus": "READING",
  "visibility": "GROUP",
  "content": "阅读了方法部分，数据集划分仍需核对。",
  "version": 0
}
```

阅读状态支持 READING、DONE、BLOCKED。首次保存的 version 为 0，后续携带接口返回的版本。旧版本保存返回 409。私有资料的记录使用 PRIVATE。共享资料上的个人笔记也可以保留 PRIVATE。

### 组会与引用

创建组会时填写 `title`、`meetingDate`、`periodStart`、`periodEnd`。整理周期包含起止两天，按 UTC 统计，最多 94 天。

调用 draft-jobs 和 action-jobs 时必须提供 `Idempotency-Key` 请求头。相同 Key 和相同请求返回同一任务，同一 Key 用于另一份内容返回 409。一次组会同类型的生成任务按顺序执行。

组会工作流读取周期内的共享阅读记录、共享论文和未完成待办，生成草稿后校验引用。第一版为固定步骤的研究工作流，任务事件可以查看每一步的执行情况。

每条模型要点必须包含来源 ID 和逐字引用。服务端检查来源来自当前上下文，quote 必须真实存在于对应来源中。保存的草稿包含来源快照、PDF 页号、正文片段序号、输入哈希和模型返回的 Token 用量。组会属于共享结果，其上下文只使用 GROUP 资料和 GROUP 笔记。

输入范围有明确上限。最多准备 60 条来源、35000 字符；单篇论文取前 4 个正文片段。草稿的 coverage 字段会标明范围，适合用阅读笔记补充论文后部的重要信息。引用文本存在只能验证引用可定位，观点是否由原文支持仍需人工审核。

### 纪要与待办

保存纪要的示例如下。

```json
{
  "content": "讨论了实验准备。\nTODO: 复现基线实验\n待办：核对数据集划分",
  "version": 0
}
```

local 模式识别以 `TODO:`、`TODO：`、`待办:`、`待办：`、`- [ ]` 开头的行。模型模式从自然语言纪要提取明确行动项。提议只包含标题和原文证据，负责人和日期由确认请求显式填写。

确认示例如下。

```json
{
  "proposalVersion": 1,
  "items": [
    {
      "index": 0,
      "title": "复现基线实验",
      "assigneeId": "填写课题组成员 userId",
      "dueDate": "2026-11-20"
    }
  ]
}
```

assigneeId、dueDate 可以省略。相同提议按相同内容重复确认返回原待办。相同提议按不同内容确认返回 409。修改纪要会使旧提议失效，已创建的任务继续保留。生成期间纪要发生变化时，旧结果无法发布。

待办状态为 TODO、IN_PROGRESS、DONE、CANCELLED，更新携带当前 version。DONE 或 CANCELLED 可以重新打开为 TODO。负责人、任务创建者和 OWNER 可以修改状态。

## 接入真实模型

模型调用使用 Chat Completions 兼容接口和 JSON 输出模式。可以连接已有的 llm-gateway，也可以直接连接支持该协议的模型服务。

在启动前设置环境变量。

```powershell
$env:RESEARCH_LLM_MODE='openai'
$env:RESEARCH_LLM_BASE_URL='http://localhost:8080/v1'
$env:RESEARCH_LLM_API_KEY='填写你发放的网关 Key'
$env:RESEARCH_LLM_MODEL='填写已配置且支持 JSON 输出的模型别名'
.\mvnw.cmd spring-boot:run
```

base URL 应包含 `/v1`，程序在后面追加 `/chat/completions`。连接原网关时请选择支持 JSON 输出的真实模型，网关的 mock-chat 回复格式适用于聊天演示。

第一版模型输出参数使用 `temperature=0`、`response_format={"type":"json_object"}` 和 `max_tokens=2500`，模型服务需要支持这些参数。环境变量 `.env.example` 供参考，Spring Boot 不会自动读取该文件。

响应有完整读取期限和 2MB 上限，HTTP 408、429、5xx、连接失败和可重试的输出校验失败进入重试。HTTP 错误响应正文不会写入公开任务错误信息。模型内容当作待验证数据，失败结果无法发布。

## 持久任务与并发

任务和业务输入保存在同一个数据库事务中。默认两个后台执行线程，状态为 QUEUED、RUNNING、SUCCEEDED、FAILED、CANCELLED。

工作线程使用条件更新抢占任务，获得唯一租约令牌。每五秒续租，进程意外退出后，租约到期的任务可重新执行。业务结果和成功状态在一个事务中提交，发布时检查租约令牌和有效期，旧线程无法覆盖新执行结果。

可重试失败最多自动执行三次，重试等待时间按当前尝试次数增加。最后一次执行崩溃时，过期任务会收敛为 FAILED。取消后立即禁止结果发布，执行线程在下一次心跳发现取消后收到中断信号。外部模型服务是否停止生成取决于服务端对连接中断的处理，重试也可能产生重复的外部调用费用。

H2 文件数据库用于第一版本地、单应用实例运行。数据库队列在重启后恢复。多实例部署、共享数据库和消息中间件需要单独完成部署与兼容性验证。

## 效率记录

POST measurements 的示例结构如下，填写实际测量值。

```json
{
  "scenario": "MEETING_PREPARATION",
  "baselineMs": 100000,
  "assistedMs": 40000,
  "reviewMs": 30000,
  "qualityAccepted": true,
  "notes": "示例结构，请替换为真实记录并注明材料、参与者和执行条件"
}
```

上述数字用于说明接口格式。初始数据库的效率记录为空，演示脚本也不会填充效率结果。

scenario 支持 MEETING_PREPARATION、SOURCE_LOOKUP、ACTION_EXTRACTION。汇总返回样本数、参与人数、原流程耗时中位数、包含审核的辅助流程耗时中位数、逐条配对降耗比例的中位数，以及质量通过率。质量未通过样本也参与统计。人工记录用于小规模试用分析，不能直接视为生产效果或因果结论。

## 测试与监控

```powershell
.\mvnw.cmd test
```

测试覆盖账号与令牌、工作区和私有资料隔离、PDF 实际解析、重复导入、阅读笔记版本、共享组会引用、提议确认、待办状态、效率公式、并发抢占、过期租约、旧线程隔离、事务回滚、重试退避和模型协议。模型协议测试使用本地 HTTP 假服务，模型内容校验测试使用可控响应。

Prometheus 指标位于 `/actuator/prometheus`，请求需要登录令牌。包含 HTTP 请求指标、`research_jobs_active` 和按 kind/outcome 标记的 `research_jobs_duration_seconds`。任务执行步骤通过 job events 接口查看。

## 第一版范围

这一版交付浏览器工作台、后端 API、交互式接口文档和演示脚本。页面包含账号与课题组、资料检索与阅读记录、组会草稿与纪要、待办确认与跟踪、后台任务和使用评估。搜索采用英文词及中文双字词匹配排序，最多排序 5000 个匹配片段并返回前 20 条。PDF 处理以可提取文本的文档为主。

向量检索、扫描件 OCR、网页抓取、日历或邮件通知以及自主规划式 Agent 属于后续迭代方向。可先使用真实阅读和组会记录验证当前流程，再根据失败案例确定下一步。

## AI 辅助开发

项目使用 AI 辅助编写页面、接入接口和排查问题。前端入口位于 `src/main/resources/static`，Java 服务负责权限、版本校验、任务调度与结果发布。简历中可以按实际参与情况描述需求拆分、代码审查、联调和问题修复，并保留可复现的失败案例和验证记录。

## 文件用途说明

每个已提交文件均列出中文用途，点击路径可查看内容。

### 配置、启动与演示

| 文件 | 用途 |
|---|---|
| [.env.example](.env.example) | 服务端口、数据库和模型调用的环境变量示例 |
| [.gitignore](.gitignore) | 排除数据库、构建产物、密钥配置和个人面试资料 |
| [.mvn/wrapper/maven-wrapper.properties](.mvn/wrapper/maven-wrapper.properties) | 指定 Maven Wrapper 的 Maven 版本与下载地址 |
| [README.md](README.md) | 项目介绍、启动步骤、接口说明和文件用途索引 |
| [examples/demo.ps1](examples/demo.ps1) | 演示注册、资料导入、组会生成和待办确认 |
| [examples/reading-material.md](examples/reading-material.md) | 演示脚本使用的阅读材料 |
| [mvnw](mvnw) | Linux 与 macOS 等环境的 Maven Wrapper 启动脚本 |
| [mvnw.cmd](mvnw.cmd) | Windows 环境的 Maven Wrapper 启动脚本 |
| [pom.xml](pom.xml) | 定义 Java 版本、依赖和 Maven 构建插件 |

### Java 应用代码

| 文件 | 用途 |
|---|---|
| [HubProperties.java](src/main/java/com/researchhub/HubProperties.java) | 定义并校验后台任务与模型调用配置 |
| [ResearchHubApplication.java](src/main/java/com/researchhub/ResearchHubApplication.java) | Spring Boot 应用启动入口 |
| [agent/LlmClient.java](src/main/java/com/researchhub/agent/LlmClient.java) | 调用兼容模型接口，处理响应、超时与读取上限 |
| [agent/ResearchAgent.java](src/main/java/com/researchhub/agent/ResearchAgent.java) | 选取共享上下文，整理草稿与行动项并核验引用 |
| [auth/AuthController.java](src/main/java/com/researchhub/auth/AuthController.java) | 提供注册、登录、当前用户与退出接口 |
| [auth/AuthInterceptor.java](src/main/java/com/researchhub/auth/AuthInterceptor.java) | 从请求令牌识别用户并拦截未登录访问 |
| [auth/AuthService.java](src/main/java/com/researchhub/auth/AuthService.java) | 处理密码校验、令牌发放、过期验证和注销 |
| [common/ApiErrorHandler.java](src/main/java/com/researchhub/common/ApiErrorHandler.java) | 统一处理业务错误、参数错误和上传异常 |
| [common/ApiException.java](src/main/java/com/researchhub/common/ApiException.java) | 定义业务错误类型和 HTTP 状态 |
| [common/Values.java](src/main/java/com/researchhub/common/Values.java) | 提供标识生成、UTC 时间、哈希和 JSON 处理 |
| [config/SystemController.java](src/main/java/com/researchhub/config/SystemController.java) | 向已登录用户返回生成模式和上传能力 |
| [config/WebConfig.java](src/main/java/com/researchhub/config/WebConfig.java) | 注册登录拦截器，配置接口文档与令牌认证方式 |
| [document/DocumentController.java](src/main/java/com/researchhub/document/DocumentController.java) | 提供文件或文本导入、资料详情与正文片段接口 |
| [document/DocumentParser.java](src/main/java/com/researchhub/document/DocumentParser.java) | 解析 PDF 或文本，切分正文并保留页号 |
| [document/DocumentService.java](src/main/java/com/researchhub/document/DocumentService.java) | 保存资料、检查重复内容并创建解析任务 |
| [document/ReadingController.java](src/main/java/com/researchhub/document/ReadingController.java) | 查询与保存阅读笔记，检查版本和可见范围 |
| [document/SearchController.java](src/main/java/com/researchhub/document/SearchController.java) | 按权限过滤资料并执行关键词匹配排序 |
| [evaluation/EvaluationController.java](src/main/java/com/researchhub/evaluation/EvaluationController.java) | 保存效率对比记录并汇总耗时、样本与质量结果 |
| [jobs/JobController.java](src/main/java/com/researchhub/jobs/JobController.java) | 提供任务状态、事件、取消和手动重试接口 |
| [jobs/JobStore.java](src/main/java/com/researchhub/jobs/JobStore.java) | 持久化任务，处理领取、租约、重试和结果提交 |
| [jobs/JobWorker.java](src/main/java/com/researchhub/jobs/JobWorker.java) | 调度工作线程执行解析与生成，续租并记录指标 |
| [meeting/MeetingController.java](src/main/java/com/researchhub/meeting/MeetingController.java) | 提供组会、纪要、草稿生成和行动项提取接口 |
| [meeting/MeetingService.java](src/main/java/com/researchhub/meeting/MeetingService.java) | 管理组会版本、编辑权限和生成任务排队 |
| [meeting/TaskController.java](src/main/java/com/researchhub/meeting/TaskController.java) | 确认行动项、检查重复确认并更新待办状态 |
| [workspace/AccessService.java](src/main/java/com/researchhub/workspace/AccessService.java) | 检查成员身份、资料可见范围和组会访问权限 |
| [workspace/WorkspaceController.java](src/main/java/com/researchhub/workspace/WorkspaceController.java) | 提供课题组创建、列表与成员管理接口 |

### 页面与数据库资源

| 文件 | 用途 |
|---|---|
| [src/main/resources/application.yml](src/main/resources/application.yml) | 配置端口、数据库、上传限制、监控和后台任务 |
| [src/main/resources/db/migration/V1__initial.sql](src/main/resources/db/migration/V1__initial.sql) | 创建账号、资料、组会、任务和评估数据表及约束 |
| [src/main/resources/static/app.js](src/main/resources/static/app.js) | 实现登录、资料、组会、待办和评估等页面交互 |
| [src/main/resources/static/index.html](src/main/resources/static/index.html) | 浏览器入口，加载页面样式与交互脚本 |
| [src/main/resources/static/styles.css](src/main/resources/static/styles.css) | 定义页面样式、组件布局与手机宽度适配 |

### 自动化测试

| 文件 | 用途 |
|---|---|
| [JobStoreTest.java](src/test/java/com/researchhub/JobStoreTest.java) | 验证任务去重、并发领取、租约、取消、重试和事务回滚 |
| [LlmClientTest.java](src/test/java/com/researchhub/LlmClientTest.java) | 使用本地假服务验证模型协议、响应、读取和错误处理 |
| [ResearchAgentValidationTest.java](src/test/java/com/researchhub/ResearchAgentValidationTest.java) | 验证生成内容的来源、逐字引用、提议版本和用量记录 |
| [ResearchHubIntegrationTest.java](src/test/java/com/researchhub/ResearchHubIntegrationTest.java) | 验证认证、权限、资料解析、版本、待办和页面入口 |

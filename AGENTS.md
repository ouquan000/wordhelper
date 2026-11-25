# wordhelper 工程宪章

## 基线与入口

- 主工程是 app/src_repo/ 下的 Android Kotlin / Compose 应用。
- Room 当前 schema 版本为 27，历史升级路径属于兼容性契约。
- FSRS 使用既有记忆状态与复习记录调度学习。
- content/seed/ 保存主词库；发布端另有已发布版本。
- tools/cardback-editor/ 提供编辑、发布、同步与 HTTP/TLS 入口。
- tools/rewrite-service/ 提供重写、队列、反馈和模型适配。
- 模块边界与胖文件优先级见 docs/ARCHITECTURE.md。
- 按任务检查当前 HEAD、分支与工作树，历史报告只作线索。

## 数据兼容

- AppDatabase.kt 集中注册 Room v27 及历史迁移，禁止拆分。
- 禁止 destructive migration、清库重建或静默降级。
- 严禁删改任何历史 Migration，包括注册顺序和旧版本测试夹具。
- 新 schema 必须显式递增版本并追加迁移。
- 迁移测试覆盖从实际支持的旧版本升级后数据仍可读。
- 未经明确授权，严禁修改 tb_progress、tb_word_memory、tb_memory_lock 或 FSRS 行为。
- 将学习进度、记忆状态与卡片内容分开处理。
- 使用临时库验证回退、重试与并发写入。
- 禁止将真机库及 WAL/SHM 上传交付台。

## 签名与敏感资产

- prod/dev 双包沿用 wordhelper.jks。
- 证书 SHA-256 固定为 963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c。
- 验签使用 apksigner，对两个 APK 分别记录证书摘要。
- 保留 _toolchain/signing/ 和 app/src_repo/app/keystore.properties。
- 禁止打印配置值、口令、API Key、Bearer Token 或私钥。
- 禁止将签名材料写入 Git、补丁、报告、测试输出或云盘。
- 提交前检查新增文件，也检查完整补丁中的删除行。
- 禁止提交 baseline/、APK、运行态 data/、临时数据库与日志。

## 服务隔离

- 严禁触碰运行中的 CardBack 8765/8443/8767 和 WB 8788。
- 禁止为了测试重启、切换 TLS、抢占端口或重建线上状态。
- 对外服务经 CardBack 同源挂载，依赖路径见架构图。
- 测试使用临时数据目录、独立端口和假供应商。
- 禁止测试加载主仓队列、反馈库、词卡锁或活动会话。
- 严禁清理编辑器 published/、sync.json*、TLS 与身份文件。
- 后端 data/ 的 locks.json 和反馈 JSONL 始终原位保护。
- 校验服务存活优先读取监听状态，避免触发模型或队列任务。

## 编码与界面

- 450 行为硬棘轮：禁止新增超标文件、既有超标文件增长或抬高 scripts/line_limit_baseline.txt 上限。当前历史基线为 24 项。
- 对 Git 跟踪的 .kt/.py 按物理行计数。
- 超过 450 行的文件禁止追加新功能，先按职责拆模块。
- 先写出拆分边界、调用入口和要保留的行为。
- 复杂手势抽成纯 Kotlin 状态机，覆盖移动、取消与提交。
- 同步回退与状态流转抽成纯 Kotlin/Python 状态机配单元测试。
- 测试覆盖失败、重试、旧数据和并发，保证重放结果一致。
- Compose 组件从状态渲染，持久化操作交给相应层。
- 卡片 UI 配色统一读取 LocalHoloCardPalette。
- 严禁在卡片中混用 MaterialTheme.colorScheme 造成暗色串色。
- 布局与阈值标明 dp 或 px，在输入边界按当前密度转换。
- 严禁 dp/px 混算；手势验收覆盖不同密度及暗色系统。

## 单工作树与只读协作

- 直接在主仓从已核对的基线创建隔离分支。全程只用一棵工作树，严禁 git worktree add，也禁止在外部会话目录遗留临时脚本。
- 合并、推送、清理分支按本批授权串行执行。删除前核查已修改、暂存、未跟踪及忽略文件，备份唯一成果并校验。删除分支前确认提交已合入。
- 操作重解析点时禁止递归进入目标。先非递归卸除链接本体，再核对目标完好。保留全部 release tag，禁止强推或改写发布历史。
- 仅在任务明确授权时使用宿主机 WB 网关 `127.0.0.1:8788` 做只读测绘。只允许低价模型，优先 cn:deepseek-v4.1-flash 或 cn:glm-5.3，严禁 kimi、高价模型和未声明的备用路由。
- 若托管工具硬编码其他端口（如 10082），禁止探测或转接，回退到本地只读源码审计。任务要求零调用时，全程采用本地审计。
- 分派时限定输入、权限和输出格式，仅提供必要材料。主控复核结论，从本次可复查的输出取测试数字。

## 重构无损验证

- Kotlin 在相同工具链和 flavor 下分别编译基线与候选版本，用 `javap -public -s` 比较原入口的公开及 internal 成员、JVM 名称和描述符，要求签名零漂移。
- 对同一生产范围提取字符串集合，分别计算基线减候选、候选减基线，两向差集均为空。对拆分模块列出依赖边，验证严格 DAG 无环。
- Python 兼容入口用 `_api = sys.modules[__name__]` 持有模块引用，调用时动态读取可替换依赖。用原入口 monkeypatch 后回放拆出的路径，确认替换生效，并用 AST 提取内部导入边检查无环。

## 测试与门禁

- 在仓库根运行 pwsh -File scripts/preflight.ps1 -Quick 做卫生检查。
- 完整验收将 OpenSSL CLI 加入本次进程 PATH，确认 openssl version 可用并设置下述 GRADLE_USER_HOME，再运行 pwsh -NoProfile -File scripts/preflight.ps1。
- 记录门禁退出码、超标明细和两种 flavor 的实际测试数。
- 离线构建必须设置 GRADLE_USER_HOME=<仓库根>/_toolchain/gradle-home。
- 使用既有 mf AVD、Android 34 Google APIs x86_64 镜像及 SDK 的 platform-tools/adb，禁止重新下载。
- 避让其他项目的构建目录、真机及运行中的 cloudflared 隧道。模拟器测试使用隔离数据。
- Android 命令从 app/src_repo/ 运行，Gradle 使用 JDK 17。
- 离线运行 gradlew.bat :app:testProdDebugUnitTest :app:testDevDebugUnitTest --offline。
- 检查两组 TEST-*.xml 的 tests、failures、errors、skipped。
- 双 Release 使用 gradlew.bat :app:assembleProdRelease :app:assembleDevRelease --offline。
- 各包执行 apksigner verify --verbose --print-certs <apk>。
- 检查 applicationId、versionCode、versionName、字节数和 SHA-256。
- 后端在隔离副本运行 python -m unittest discover -s tools/rewrite-service/tests。
- 编辑器测试从隔离副本的 tools/cardback-editor/ 运行。
- JVM 单测、后端单测、真机及截图分别记录实测范围。
- 未通过门禁时保留失败证据，修复前暂停发布。

## 向哈基米交卷

- 根目录 G 为 Google Drive 的 Agent-Inbox/GPT-Handoff/work_anki_enligish/。
- 每批交付写入 G/WAE-YYYYMMDD-NNN/，先检查编号冲突。
- README.md 说明范围、基线提交、结果及待拍板项。
- manifest.json 列出文件相对路径、长度、摘要及版本。
- 01-交付物/ 保存本轮 APK 与完整 .patch。
- 文档治理批次按任务清单放入文档、脚本及完整补丁。
- 02-清单/ 保存 apk-manifest.json 和双包 apksigner 验签输出。
- 03-证据/ 保存单测、真机测试报告与暗色系统实拍。
- 按实际任务注明未运行的测试和未生成的包。
- 完整补丁应可由标明的基线重放，不得包含敏感原文。
- 写完后回读文件并核对 manifest 内的摘要。
- 云盘根只保留最近两个版本包与活跃设计文档。
- 新版本交付时将更早版本包整体迁入 99-归档/。
- 严禁任何 Agent 读取 99-归档/，包括递归枚举及内容搜索。
- 归档按已知根目录项定向移动，遇同名冲突停止覆盖。
- v1.5.4 设计宪章与白卡四阶母版永久保护。

## 收工复核

- 停止本任务临时 APK 下载服务，先核对 PID、命令行与端口。
- 清理本任务创建的临时入站规则，保留其他策略。
- 在本任务使用的模拟器上将三个系统动画倍率恢复为 1。
- 恢复后关闭该模拟器，保留真机与其他任务的设备。
- 不结束共享服务、其他任务的构建进程或网关。
- git worktree list 核对仅有主仓工作树。确认 Gradle Daemon 为 0；如有其他任务的进程，保留现场并报告。
- 核对分支清单。待终审的提交保留在隔离分支。
- git status --porcelain 确认工作区及暂存区干净。
- 核对本地 main 与 origin/main。仅在任务允许联网时核对远端。
- 报告实际测试结果、占用口径、交付目录与未决项。

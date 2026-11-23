# wordhelper 架构导览

| 路径 | 模块职责 |
|---|---|
| app/src_repo/ | Gradle 根；app/src/main 实现 Kotlin/Compose 客户端，test 与 androidTest 分别用于 JVM、设备测试 |
| content/seed/、content/words/ | 种子卡片与词库资源 |
| tools/cardback-editor/ | HTTP/TLS、编辑器静态资源、同步与发布 |
| tools/rewrite-service/ | 重写协议、队列、持久化、反馈和供应商适配 |
| tools/word-audit/ | 离线词卡扫描与复核 |
| scripts/ | 门禁、模拟器验收与词性检查 |
| docs/ | 架构、兼容约定、测试夹具及设计和运维资料 |
| _toolchain/、venv-anki | 本机工具、签名及外部环境联接；保持路径稳定 |

## 请求与数据流

```mermaid
flowchart LR
  Android["Android · Compose"] --> DB["Room 27 · FSRS"]
  Android --> Editor["cardback-editor/server.py"]
  Editor --> Sync["同步状态与 published"]
  Editor --> Service["rewrite-service/service.py"]
  Service --> Store["Store / data"]
  Service --> Worker["worker.py"]
  Worker --> Seed["content/seed/cet6_cards.json"]
  Worker --> Published["published/cards/cet6_cards.json"]
  Worker --> Providers["模型适配 / WB"]
```

CardBack 监听 8765/8443，负责鉴权、同步、发布与同源转发。server.py 的 _rewrite_module 查找 BASE_DIR/rewrite-service 和 BASE_DIR/../rewrite-service。源码部署需要两个 tools 子目录并列；打包部署依赖可执行文件旁的模块目录。
启动时 _start_rewrite 调用 startup()；/rewrite* 与 /models 由 respond(method, path, body) 分派。探测能力与启动队列应分别验证。
service.py 初始化 Store 与工作线程。REWRITE_DATA_DIR 可指定隔离目录；生产队列、锁和反馈默认位于 rewrite-service/data/。
worker.py 以仓库根下的 content/seed/cet6_cards.json 与 tools/cardback-editor/published/cards/cet6_cards.json 补全词条。迁移模块须验证两处相对路径。
providers.py 适配模型，WB 经 8788。测试使用假供应商。AppDatabase.kt 注册 Room 历史迁移；UI 或同步重构须保留学习进度与记忆状态的兼容性。

## 拆分优先级

按 2026-11-23 体检的物理行数排序分批。当前明细由门禁输出。

| 顺序 | 文件 | 行数 | 首个边界与验证 |
|---|---|---:|---|
| 1 | LearningScreen.kt | 2610 | 提取纯手势状态机，覆盖移动、取消与提交 |
| 2 | LearningViewModel.kt | 2426 | 分离学习会话转换，回放评分、跳过与恢复 |
| 3 | cardback-editor/server.py | 2154 | 分离同源重写路由，保留鉴权、错误码及健康检查 |
| 4 | RewriteRepository.kt | 2066 | 隔离提交、取消与轮询，验证已有任务恢复 |
| 5 | pipeline.py | 1575 | 拆分生成与结构校验，以固定输入回放 |
| 6 | SyncRepository.kt | 1451 | 用状态机覆盖网络回退、冲突与重试 |
| 7 | WordRepository.kt | 1375 | 分离词库查询与学习写入，保留事务边界 |
| 8 | store.py | 1281 | 区分队列与持久化，验证崩溃恢复及旧数据兼容 |

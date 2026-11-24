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

## CardBack 内部模块

server.py 保留启动入口、GET/POST 路由、兼容符号与运行态。它将同一份上下文传给处理器和辅助函数，各模块均可独立导入。

| 模块 | 职责 |
|---|---|
| cardback_security.py | 口令与身份、可信来源、限流预算与入站检查 |
| cardback_config.py | 配置校验、版本历史、发布与回滚 |
| cardback_api.py | 编辑器、词卡包、同步、健康与身份接口 |
| cardback_http.py | HTTP 请求体与响应、静态文件和缺页提示 |
| cardback_rewrite.py | 重写模块查找与缓存、能力探测、同源转发和启动 |
| cardback_network.py | 网卡探测、TLS 监听生命周期、UDP 发现 |
| cardback_startup.py | 命令行解析、启动顺序与退出清理 |

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
| 3 | cardback-editor/server.py | 436 | 已完成瘦身，入口保留路由与兼容状态；模块边界测试覆盖同源转发、健康与导入 |
| 4 | RewriteRepository.kt | 2066 | 隔离提交、取消与轮询，验证已有任务恢复 |
| 5 | pipeline.py | 1575 | 拆分生成与结构校验，以固定输入回放 |
| 6 | SyncRepository.kt | 1451 | 用状态机覆盖网络回退、冲突与重试 |
| 7 | WordRepository.kt | 1375 | 分离词库查询与学习写入，保留事务边界 |
| 8 | store.py | 1281 | 区分队列与持久化，验证崩溃恢复及旧数据兼容 |

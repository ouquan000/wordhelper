# 不爱背单词 · 六级图文记忆版

这是一款把「怎么记」放进卡片背面的 Android 背词应用。翻面可以读释义、双语例句、记忆思路和常用搭配，再按掌握程度评分。项目基于 [GuGuGuGun/english__app](https://github.com/GuGuGuGun/english__app) 改造，保留[上游 Apache-2.0 许可](app/src_repo/LICENSE)。

**最新正式版为 [v1.5.3v5](https://github.com/ouquan000/wordhelper/tree/v1.5.3v5)**，versionCode 34，Room v27。下文的模块和测试数对应发布后的主线重构。

## 从一本词书开始

- **认词与拼写**。从内置六级词书开始，也可以在词库中导入 JSON。FSRS 根据作答记录安排复习。同一个词在不同书里分别记录进度，卡背优先显示本书记忆，缺少的字段回退到全局记忆。
- **双端同步**。手机先在本地保存学习记录，连接恢复后与电脑端同步。配置局域网和公网地址后，客户端按可用性自动回退。「我的」页面可查看待发送记录或手动同步。
- **AI 助记重写**。卡背上的重写操作会把请求加入队列，由电脑生成新的记忆思路。可以反馈或锁定已有记法，也可以开启闲时洗词，让后台挑选适合改写的词。
- **可视化卡背**。在电脑的 CardBack 编辑器拖动段落、设置显隐、颜色和字号，预览后发布。手机刷新卡背结构即可应用，编辑器保留历史版本供回退。

词库导入支持记忆思路和常用搭配，例如：

```json
[{"word":"abide","meaning":"v. 遵守","approach":["把 abide by 和“遵守约定”一起记。"],"collocations":["abide by the rules — 遵守规则"]}]
```

## 仓库导航

| 入口 | 从这里找什么 |
|---|---|
| `app/src_repo/` | Android 客户端。学习页与会话由 `Learning*` 管理，`Word*` 负责词书、评分与按书进度，`Sync*` 负责学习记录同步，`Rewrite*` 负责重写请求、回执与卡背操作。 |
| `AppDrawer.kt`、`HoloCard*`、`Profile*` | Android 内的导航抽屉、卡面材质与倾角，以及「我的」页统计、备份和同步设置。 |
| `content/seed/`、`content/words/` | 内置词包与导入用词条。 |
| `tools/cardback-editor/` | `server.py` 为入口。安全校验、配置发布、HTTP 接口、网络监听和重写转发分模块维护。 |
| `tools/rewrite-service/` | `service` 处理请求，`store` 持久化队列与反馈，`worker` 调度闲时任务，`pipeline` 编排生成、润色与评分。 |
| `tools/word-audit/` | 离线词卡扫描与复核。 |
| `scripts/`、`docs/` | 开发门禁、架构导览、兼容约定与设计记录。 |

## 本地开发与离线验证

在仓库根使用 PowerShell 7、JDK 17 和已准备好的本地依赖缓存。先将 OpenSSL CLI 加入当前进程的 PATH，再执行：

```powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '_toolchain/gradle-home'
pwsh -NoProfile -File scripts/preflight.ps1
```

完整门禁覆盖 Prod、Dev 各 416 项 JVM 单测和 598 项后端测试。450 行棘轮按 Git 跟踪的 Kotlin、Python 文件计数，11 轮重构将历史超标从 39 项降至 24 项。千行以上只余 Room 迁移文件 `AppDatabase.kt` 与离线词卡扫描器 `scan.py`。

查找实现入口见[架构导览](docs/ARCHITECTURE.md)。修改前请阅读[工程宪章](AGENTS.md)，其中列有迁移、签名、隔离测试与提交规则。

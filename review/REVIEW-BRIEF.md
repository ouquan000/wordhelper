# 不爱背单词 · 代码评审简报

> 给外部 AI 评审用。快照已脱敏：签名口令、同步口令、本地数据、两个大词包均不入库。

## 一、项目是什么

「不爱背单词」是单人自用的 Android 背单词 App（`com.kaoyan.wordhelper`），主打认词卡 + 拼写 + 间隔重复，另有两个电脑端 Python 工具：

- `tools/rewrite-service`：卡背内容生成服务（调模型重写释义与记忆思路）。
- `tools/cardback-editor`：卡背编辑器 + 局域网学习进度同步（8765）。

## 二、技术栈

App：Kotlin + Jetpack Compose（Material3）、Room、DataStore、Retrofit/OkHttp、Coroutines+Flow、Jetpack Security。工具：Python 3 标准库。

## 三、目录结构与关键文件

- `app/src_repo/`：Android 工程根（无 gradlew，用系统 gradle）。
- `.../ui/`：Compose 界面。`screen/` 有 `LearningScreen.kt`（大文件）、`SpellingScreen.kt`、`BookManageScreen.kt`、`ProfileScreen.kt` 等；`theme/` 与 `component/` 管样式与动画；`viewmodel/` 管状态。
- `.../data/`：数据层。`database/`、`dao/`、`entity/`、`repository/`（`RewriteRepository.kt`、`SyncRepository.kt`、`CardBackRepository.kt`）、`network/`、`model/`。
- `tools/`、`content/seed/`、`review/`（本包）。

## 四、如何构建

进 `app/src_repo/`，建 `local.properties` 指向本机 SDK，设 `JAVA_HOME`（JDK17）后：

```powershell
gradle --no-daemon :app:assembleDebug
```

产物 `app/build/outputs/apk/debug/app-debug.apk`；签名配置引用本地 `_toolchain`（不入库）。

## 五、希望重点回答

1. 前端界面与动效怎么改得更好看、更顺手：结合 `review/prototypes/` 三套方向（A 纸页 / B 弹跳 / C 夜读），关注翻卡、评分反馈、拼写键盘与动效一致性。
2. Compose 页面结构与状态管理有哪些架构问题：大文件拆分、ViewModel 与 Screen 职责、状态提升与导航。
3. 最该先修的技术债/风险，按优先级排序并给理由（安全、性能、可维护性均可）。

## 六、已知问题清单（未验证处已标注）

- P2-01：拼写页键盘顶栏修复已实现，未独立验收。
- P2-02 / P2-03：状态存疑，未验证。
- 设置页「卡背结构地址」输入框可能点不进（未验证）。
- 卡背内联「跳过」路径疑似低效/死代码，待确认。

> 未验证结论勿当事实采信；评审时如发现相反证据，以代码为准。

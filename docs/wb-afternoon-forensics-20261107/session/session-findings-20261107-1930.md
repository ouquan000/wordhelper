# DSH 原始会话取证：2026-11-07 下午 WB 退出

调查时间：2026-11-07 19:21–19:30，北京时间。只读源记录，仅在本目录新建取证文件。未操作浏览器、停止进程、发模型请求、修改正式源码或配置。

## 已证实的结论与边界

1. 在限定的 5 个近期工作区、10 个候选 v4 会话中，13:35:00–13:50:00 有真实记录的会话为 3 个，共 894 条事件、135 条 tool/call。未找到这个窗口针对 WB 的 Stop-Process、taskkill、kill、重启、8788/8790/38084/44332 命令，也未找到 codex_cancel；仅一条 job_kill，发生于 13:36:44，目标已确认为 Python 卡片改写服务，早于父级核实的 WB 13:45:37 最后成功请求。
2. 原始会话显示，13:46:45.224 起 8788 已 ECONNREFUSED。它收紧了“守护 13:47:06 才发现 missing”的观测上界，不能单凭它确定 PID 的精确终止时刻。
3. 3 个会话在 13:46:31–39 出现 session/end-seed，当前安装实现证实这是恢复既有历史时追加的标记，不是 shutdown 标记。Anki 会话存在悬空工具调用的合成中断补全，支持该会话经历恢复。
4. 补全的 tool/result、step/end、turn/end 时间戳沿用最后真实事件时间。因此 seq5074–5076 的 13:45:37.728 是回填时间，不能当作服务或工具真正终止时刻。
5. 父级提供另一路独立证据：desktop.log:17824 在 13:46:05.799 记录 Stopping Harness service，desktop.frontdesk.log:3122 同时 busyAction='restart'，13:46:07.777 启动新 Harness PID10088。会话证据与这个重启顺序一致，但本子调查未独立读取这些宿主日志；宿主重启是否及如何带走 WB 的进程树由父级核证。
6. 这 3 个会话中未取得 38084、44332 隶属特定 DSH job 的启动/父 PID 证明。不能凭会话恢复和时间接近独立断定 WB 被何种树清理机制杀死，也不能判定是谁触发宿主 restart。

## 来源与覆盖

实际 DSH_HOME=C:/Users/ouquan/.dsh。首先只列出 sessions 工作区和会话目录元数据，再读取下列 5 工作区内 session.v4.jsonl.zstd 的 mtime 不早于 13:35 的候选文件；以每条记录的数值 time 过滤窗口，未用文件时间代替记录时间。

- --D-deepseek~0020harness-work_anki_enligish--
- --D-deepseek_v4.1flash1017-thebigfish--
- --D-deepseek_v4.1flash1017-something_light--
- --C-Users-ouquan-.dsh-bili-duty--
- --C-Users-ouquan-.dsh-ungrouped--

10 个候选中，另 7 个在目标窗口没有事件；包括父会话 session-f13c5e18-6562-4d25-91b8-95dbd17d69bc。某些候选是本次取证新建的 19:21 子会话，时间筛选后为 0 条。没有全盘扫描，没有扩展到其他旧工作区或其他产品的会话/终端历史。

| 简称 | 原始文件 | 全文件帧/记录数 | 窗口事件/tool-call |
|---|---|---:|---:|
| A | C:/Users/ouquan/.dsh/sessions/--D-deepseek~0020harness-work_anki_enligish--/session-3c12ce57-cc01-46db-86e7-1d422c5364b6/session.v4.jsonl.zstd | 3607 / 6466 | 270 / 36 |
| B | C:/Users/ouquan/.dsh/sessions/--D-deepseek_v4.1flash1017-thebigfish--/session-7845a261-6c19-4b23-a708-58ed1596636e/session.v4.jsonl.zstd | 1221 / 2269 | 243 / 37 |
| C | C:/Users/ouquan/.dsh/sessions/--D-deepseek_v4.1flash1017-something_light--/session-b65bd54b-8c90-4617-8f67-13ea509716f5/session.v4.jsonl.zstd | 308 / 599 | 381 / 62 |

完整源文件 SHA256（19:23 首次快照）：

- A，4705042 bytes：2881685a3935bde4427512129eac45ea54391939799a043e42f7746b21e275c9
- B，2310809 bytes：6f483339ad6580ab7e7b2548b83443f20dc0b27f8a9b6079fa0021aaa5fdfc15
- C，648191 bytes：a7f324bace31e4de036a8081a2f7b90e09d1f3e6924192dadb89afeedd3b9a58

读取方法：对压缩 buf 从 offset=0 开始，反复 zstdDecompressSync(buf.subarray(offset),{info:true})，用 engine.bytesWritten 累加 offset，拼接每帧 UTF-8 行并 JSON.parse；不是只解第一帧 header。帧全部推进至源文件长度。完整会话仅在内存解析，输出只投影工具和必要证据，不复制系统/模型提示全文。

## 原始锚点

| 北京时间 | 源 | seq | 事件与结果 |
|---|---|---:|---|
| 13:45:37.704 | A | 5071 | assistant/message；与父级 WB 最后 200 的时间一致。只取事件外壳，没有保留推理/提示全文。 |
| 13:45:37.714 | A | 5072 | ops_bash：adb emulator-5554 pm clear com.kaoyan.wordhelper，am start 该 APK，等待后 dump/pull UI XML，再用 Python 读取文本；不含 Windows 进程终止或 WB 端口。 |
| 13:45:37.728 | A | 5073 | job/registered，jobId=bash-1fcfa46c457b4363，label='Clear data and relaunch, read first screen'。该记录无 PID/父 PID。 |
| 13:45:37.728（合成回填） | A | 5074 | interrupted-tool-result-call_00_Wo3i2A9RqbbDTQD0kLFf7289-5074，isError=true，说明已记开始但没有持久化结果，outcome unknown。 |
| 13:45:37.728（合成回填） | A | 5075–5076 | step/end；turn/end reason.kind='interrupted'。 |
| 13:46:31.002 | B | 1759 | session/end-seed，data={}。 |
| 13:46:33.966 | A | 5077 | session/end-seed，data={}。 |
| 13:46:36.062 | A | 5082 | user/message source.kind='continue'：Continue the interrupted task from where it stopped. Do not repeat work that is already complete. 这是系统续跑，不是真人要求关闭 WB。 |
| 13:46:39.770 | C | 444 | session/end-seed，data={}。 |
| 13:46:45.224 | A | 5085 | llm/retry，wb-proxy，502 acp-proxy failure: connect-refused ECONNREFUSED 127.0.0.1:8788。 |
| 13:46:55.582 | A | 5088 | 同上，retry=2。 |
| 13:47:12.514 | A | 5091 | 同上，retry=3。 |
| 13:47:35.297 | A | 5094 | 503 acp-proxy failure: connect-refused 127.0.0.1:8788 (circuit open, next probe +38s)，retry=4。熔断状态不等于此刻监听仍不存在。 |

5072 命令的相关完整内容（仅工具命令，不代表本次执行）：

```bash
A=D:/Android/Sdk/platform-tools/adb.exe
$A -s emulator-5554 shell pm clear com.kaoyan.wordhelper >/dev/null; sleep 1
$A -s emulator-5554 shell am start -n com.kaoyan.wordhelper/.MainActivity >/dev/null 2>&1; sleep 25
$A -s emulator-5554 shell uiautomator dump //sdcard/w4.xml >/dev/null 2>&1; $A -s emulator-5554 pull //sdcard/w4.xml C:/temp/w4.xml >/dev/null 2>&1
PYTHONIOENCODING=utf-8 python -c "
import re,io
s=io.open(r'C:\temp\w4.xml',encoding='utf-8').read()
print(' | '.join(t.strip() for t in re.findall(r'text=\"([^\"]*)\"',s) if t.strip())[:200])"
```

5074 合成结果原文：

> The tool call was interrupted after it was recorded, but no result was durably recorded. Its outcome is unknown. Decide whether to retry from the tool semantics: retry only if the operation is read-only or idempotent; if it may have side effects, first verify external state or ask the user. Do not retry blindly.

## 唯一显式取消：排除为本次直接原因

A seq4150，13:01:44.422：ops_bash，run_in_background=true：

```bash
cd "/d/deepseek harness/work_anki_enligish/tools/cardback-editor" && PYTHONIOENCODING=utf-8 python server.py
```

seq4151 于 13:01:44.439 注册 bash-38aa8b68de87413a，label='Restart cardback+rewrite service'；seq4152 返回 started background job。

A seq4868，13:36:44.673：job_kill({job_id:'bash-38aa8b68de87413a',reason:'重启以加载 pipeline 修复'})。seq4869 于 13:36:44.693 返回 requested cancellation of job；seq4871 于 13:36:44.726 为 job/settled，status='killed'，cause='kill'，detail='exit code: 1; 重启以加载 pipeline 修复'。

seq4874 于 13:36:48.265 重新运行 python tools/cardback-editor/server.py 并 GET 127.0.0.1:8765/rewrite/status；seq4877 于 13:36:52.742 返回 HTTP 200、exit code 0。此处的重启对象是 8765 Python 服务。WB 后续仍能提供 13:45:37.704 的成功请求，时间链不支持这个取消是此次 13:46 左右停服的直接命令。

必要真人上下文：A seq4842，13:36:09.812，source.kind='user'，原话“现在是什么情况？？ 你是卡在那里了？？？”。该句没有要求停止 WB。未把泛化的项目继续指令解释成用户主动关闭网关。

## 实现语义核证

仅查看当前安装的 @deepseek-ai/dsh-session/lib/index.js，未更改：

C:/Users/ouquan/AppData/Roaming/dsh-tauri/dependencies/dsh/node_modules/@deepseek-ai/dsh-session/lib/index.js

- 1396–1399：static fromRestore(id, seed, header, inheritedEventCount, eventState, projections) 调用 new Session(...seed...)。
- 1427–1428：已有 seed 恢复时追加 session/end-seed；fork inherited 标记为 inherited:true，而本次 3 条 data={}。
- 740–748：注释明确 openTurnClosers 是针对 loaded durable log/crash tail 生成 synthetic closers，timestamps reuse last real event。
- 774–778：const last = events.at(-1); const time = last.time。
- 853–856：ToolCallRecovery.results() 的 const time = this.last.time。
- 907–908：interruptedTurnClosers(events) 调用 openTurnClosers(events,{kind:'interrupted'})。

这是当前安装实现的语义验证；它解释记录的生成方式，不单独证明 13:46 的宿主被哪个操作者/命令关闭。

## 仍未覆盖的归因

本任务不包含 Windows 进程创建审计、内核 Job Object 历史、应用 UI 点击者身份和其他工作区全部终端历史。会话中未找到直接 kill WB 命令不等于系统上绝无其他终止来源。辅助池 29624 一直存活以及宿主 restart 的树终止范围，需要父级用启动链/宿主日志进一步闭合。上午 stub8790 事故没有混入本次命令判断。

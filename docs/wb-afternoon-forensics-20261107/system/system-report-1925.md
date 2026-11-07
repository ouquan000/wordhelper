# Windows 系统层取证结果（2026-11-07）

结论：现有系统层日志无法确认 WB 主进程 PID 38084 与入口 Node PID 44332 的终止者，也无法在程序崩溃、系统/人工清理、守护动作之间作出确定归因。

调查时间窗为北京时间 2026-11-07 13:35:00–13:50:00。系统时区经核对为 China Standard Time，UTC+08:00。目标主程序为 `D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb2api.exe`；aux PID 29624 未退出、13:47 恢复新入口 PID 31960 与主 PID 17980 为上级提供的关联事实，本系统子调查未独立重建进程存活轨迹。

## 关键证据

- Application 的 Event ID 1000 / 1001 在整个目标窗零匹配，返回 `NoMatchingEventsFound,Microsoft.PowerShell.Commands.GetWinEventCommand`。未得到 Windows 崩溃模块、异常码、WER 报告 ID 等直接崩溃证据。零记录不能证明未崩溃。
- Security 的 4688 / 4689 在整个目标窗对所有进程均零匹配，因而无法获取目标退出码、创建者/父进程审计链。当前通过只读 `auditpol.exe /get` 查询，Process Creation 与 Process Termination 两项均为 `No Auditing`；当前策略不能倒推历史策略。
- `Microsoft-Windows-TaskScheduler/Operational` 当前 `IsEnabled=false`、`RecordCount=null`。目标窗实际查询同样为 `NoMatchingEventsFound`，没有任务超时、任务结束、动作结束等历史记录可供关联。
- `Microsoft-Windows-Sysmon/Operational` 不存在，返回 `NoMatchingLogsFound,Microsoft.PowerShell.Commands.GetWinEventCommand`。
- System 指定关机、重启、服务故障/终止事件筛选仅有两条 BITS 启动类型变化 7040。未得到 WB 服务故障/终止、关机或异常重启证据。
- WER ReportArchive、ReportQueue 内，目录名匹配 wb2api 或 node.exe 且目录 LastWriteTime 落在目标窗的目录均为 0。只读了目录元数据，未打开 WER 内容；此索引不完整，不能据此否定崩溃。

## 日志保留范围

采样时三类日志均启用、循环覆盖模式、最大 20,971,520 字节；起止范围均覆盖事发窗口。

| 日志 | 最早保留事件（北京时间） | 最新事件采样（北京时间） | RecordCount 初始采样 |
| --- | --- | --- | ---: |
| Application | 2026-09-22 12:06:55.5079325 | 2026-11-07 19:23:27.6570553 | 20911 |
| System | 2026-06-25 02:03:04.8212114 | 2026-11-07 19:13:55.3142372 | 41005 |
| Security | 2026-11-07 03:52:49.4324775 | 2026-11-07 19:23:41.7767394 | 35911 |

采样时间不同，计数与最新时间不是同一个瞬间。保留范围覆盖目标窗只证明端点覆盖，不能证明每种审计都曾启用或所有事件都完整。

System 检查 ID：12,13,41,1074,1076,6005,6006,6008,7000,7001,7009,7011,7022,7023,7024,7031,7034,7035,7036,7040,7045。

两条 7040 原文：

- 13:35:16.7734888：Background Intelligent Transfer Service 服务的启动类型从 按需启动 更改为 自动启动。
- 13:45:22.9166896：Background Intelligent Transfer Service 服务的启动类型从 自动启动 更改为 按需启动。

未建立这些 BITS 事件与 WB 退出之间的因果关联。全窗口 System 事件类别计数为 SCM/7040=2、nhi/9007=4、nhi/9008=4；Application 为 Security-SPP/16384=3、Security-SPP/16394=3、edgeupdate/0=1、VBScriptDeprecationAlert/4096=1。无关事件只取类别计数，未采集消息内容。

## 计划任务当前状态

以下为约 19:24–19:25 的现状，不能解释为 13:45 的任务历史。六项当前均 Ready、LastTaskResult=0、MultipleInstances=IgnoreNew、AllowHardTerminate=true、StopAtDurationEnd=false。

| 任务 | LastRunTime（北京时间） | ExecutionTimeLimit | 切电池时停止 | 电池时不启动 | 重复间隔 |
| --- | --- | --- | --- | --- | --- |
| WB-AutoRelease-4.1 | 10-28 23:06:00 | PT72H | true | true | 无；10-28 两个一次性起点 |
| WB-Config-Bom-Watch | 11-06 16:52:55 | PT0S | false | false | 登录触发 |
| WB-Degrade-Watch | 11-07 19:22:01 | PT72H | true | true | PT5M |
| WB-Gateway-Guard | 11-07 19:22:01 | PT72H | true | true | PT5M |
| WB-Gateway-Restart-0405 | 10-28 04:05:00 | PT72H | false | false | 无；一次性起点 |
| WB-Router-Guard | 11-07 19:21:01 | PT72H | true | true | PT5M |

Config-Bom-Watch 的 RestartCount=999、RestartInterval=PT1M；其余 RestartCount=0。Gateway-Restart-0405 的 StartWhenAvailable=true，其余 false。动作仅提取脚本路径 token，未导出任意完整参数。Router-Guard 执行 wscript.exe 指向 `D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/start-silent.vbs`；其他相关动作路径已记入 JSON。

- PT72H 是当前设置，没有历史超时事件或对应旧实例开始时刻，不能证明本次由任务超时造成。
- 当前 LastTaskResult=0 是最新一次运行结果；周期任务已经被晚间运行覆盖，不能证明 13:45 的运行成功。
- IgnoreNew、StopAtDurationEnd=false 本身不表示新触发会终止旧进程。
- 电池配置不能证明当时切换电源，更不能证明任务被停止及其子进程被结束。
- 当前父进程关系、VBS 包装器是否等待、进程树终止行为需结合原始脚本与会话操作；本系统证据中未取得当时进程树终止记录。

## 采集与边界

所有系统命令经 ops_bash 显式调用 powershell.exe -NoProfile。没有开启日志/审计、修改计划任务、杀进程、修复系统、读取凭证或 Clash 内容，也没有发送模型请求。停止扩展查询后仅整理此次结果。

早期几次含 PowerShell 变量的双引号命令被外层 Bash 展开，出现 `EmptyPipeElement`、`MissingVariableNameAfterForeach` 或 `/usr/bin/bash.Actions.Execute` 解析错误，没有取证结果或生产修改；改为外层单引号后查询成功。另一次通过管道调用 Get-ScheduledTaskInfo 时遇到 null 日期，改用显式 TaskName/TaskPath 与字符串转换后取得上述现状。首条目录探测因尚无报告目录退出 1，之后只在授权目录新建文件。

配套 JSON 为脱敏整理的证据记录，包含成功原始查询命令与其关键返回、结构化值、查询失败/可观察性边界；不是完整终端输出的逐字转储。无关事件、机器标识、用户标识与任意动作参数均不导出。

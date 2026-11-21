# cloudflared 安装位置 / 配置 / 常用命令（1.4.7 配套）

> 写于 2026-11-09，**本机尚未安装 cloudflared，本文所有命令都没在这台机器上跑过**。
> 路径里的 `D:\cloudflared` 是建议值；你放别处也行，但**下面每条命令的路径要跟着改**，别一半改一半不改。
> 主流程（谁做、成功标志、失败怎么办）见 [`../../docs/runbook-remote-1.4.7.md`](../../docs/runbook-remote-1.4.7.md)。

---

## 1 为什么建议装 D 盘

- 项目红线：**C 盘不堆东西**，大文件放 D 盘。
- cloudflared 是单文件 exe（约 20–40 MB，以实际为准），换机器直接拷走就能用，不写注册表。
- 日志、tunnel 凭据 json 也会一直长，放 D 盘好清理。

建议目录：

```
D:\cloudflared\
├─ cloudflared.exe          # 主程序（从官方 release 下 zip 解出来）
├─ config.yml               # 配置（见 §3）
├─ anki-<TUNNEL-UUID>.json  # cloudflared tunnel create 生成的凭据，别外传
└─ logs\
   └─ cloudflared.log       # 自己重定向的日志（见 §5）
```

> `cert.pem` 不一定在这里：`cloudflared tunnel login` 默认写到 `%USERPROFILE%\.cloudflared\cert.pem`。
> 想让它也落在 D 盘，可以设环境变量 `TUNNEL_ORIGIN_CERT=D:\cloudflared\cert.pem` 再登录（**未在本机验证**）。

---

## 2 装法（二选一，都需要你本人操作）

**A. winget（最省事，需要网络能连到微软源）**

```powershell
winget install --id Cloudflare.cloudflared -e
```

装完新开一个 PowerShell 窗口，`cloudflared --version` 能看到版本号就算成。
（包 id 以 `winget search cloudflared` 的结果为准。）

**B. 手动 zip（网络不好时用这个）**

1. 去 Cloudflare 官方 release 页下 Windows 版 `cloudflared-windows-amd64.exe`；
2. 改名成 `cloudflared.exe`，放进 `D:\cloudflared\`；
3. 想全局能用就把 `D:\cloudflared` 加进 PATH（用户变量即可，不用管理员）；
   不想改 PATH 也行 —— 所有命令用全路径 `D:\cloudflared\cloudflared.exe`。

> 本机 `~/.cloudflared` 里已经有一个**旧的 config.yml 但没有 cert.pem**（HANDOFF §15.4）。
> 别直接复用那个目录，容易两套配置打架；本项目统一用 `D:\cloudflared`，命令里显式 `--config`。

---

## 3 config.yml 样例（named tunnel）

`D:\cloudflared\config.yml`：

```yaml
# anki.example.com → 本机 8765
tunnel: anki
credentials-file: D:\cloudflared\anki-<TUNNEL-UUID>.json

ingress:
  # 只有这个域名会转到本机；其它 host 一律 404，避免变成开放代理
  - hostname: anki.example.com
    service: http://127.0.0.1:8765
    originRequest:
      connectTimeout: 10s
      # 重写接口偶尔慢（调模型），超时别设太短，否则手机看到的是 502 而不是「正在重写」
      # httpHostHeader: anki.example.com   # 只有后端挑 Host 时才需要，现在不用
  # 兜底规则必须是最后一条，且必须有个 catch-all
  - service: http_status:404
```

要点：

- `tunnel:` 填**名字**（`anki`）或 **UUID** 都行；填名字时 cloudflared 会去凭据文件里找。
- `credentials-file` 用**绝对路径**，因为开机自启时工作目录不一定是 `D:\cloudflared`。
- `ingress` 是**从上往下第一个匹配**；最后一条没有 `hostname` 的才是兜底。删掉兜底会启动失败。
- 改了 config.yml 必须重启 cloudflared 才生效（和 8765 一个道理）。

自检：

```powershell
D:\cloudflared\cloudflared.exe tunnel ingress validate --config D:\cloudflared\config.yml
D:\cloudflared\cloudflared.exe tunnel ingress rule https://anki.example.com/health --config D:\cloudflared\config.yml
```

第一条没报错 = 语法对；第二条会打印命中了哪条规则（应该是 `http://127.0.0.1:8765`）。

> Cloudflare 免费档的回源只认 HTTP/HTTPS，`service` 写 `http://127.0.0.1:8765` 就对，
> 不要写成 `https://`（8765 本身是明文 HTTP，写了会 502）。

---

## 4 常用命令

全部假设 exe 在 `D:\cloudflared\`、配置在 `D:\cloudflared\config.yml`。
已经在 PATH 里的可以省掉 `D:\cloudflared\` 前缀。

| 目的 | 命令 |
|---|---|
| 看版本 | `D:\cloudflared\cloudflared.exe --version` |
| **临时验证**（Quick Tunnel，不用登录、不用域名） | `D:\cloudflared\cloudflared.exe tunnel --url http://127.0.0.1:8765` |
| 登录（写 cert.pem） | `D:\cloudflared\cloudflared.exe tunnel login` |
| 看已有 tunnel 列表 | `D:\cloudflared\cloudflared.exe tunnel list` |
| 建 tunnel | `D:\cloudflared\cloudflared.exe tunnel create anki` |
| 把域名绑到 tunnel | `D:\cloudflared\cloudflared.exe tunnel route dns anki anki.example.com` |
| 跑 named tunnel（前台） | `D:\cloudflared\cloudflared.exe tunnel --config D:\cloudflared\config.yml run anki` |
| 看某个 tunnel 的连接 | `D:\cloudflared\cloudflared.exe tunnel info anki` |
| 校验配置 | `D:\cloudflared\cloudflared.exe tunnel ingress validate --config D:\cloudflared\config.yml` |
| 装成 Windows 服务（管理员） | `D:\cloudflared\cloudflared.exe service install` |
| 卸掉服务 | `D:\cloudflared\cloudflared.exe service uninstall` |
| 删 tunnel（确定不用了才做） | `D:\cloudflared\cloudflared.exe tunnel delete anki` |

### 国内网络两个常用开关

| 症状 | 试这个 |
|---|---|
| 日志一直 `Retrying connection…` / 连上又断 | `--protocol http2`（默认会试 QUIC，部分宽带/路由器把 UDP 7844 掐了） |
| 同上，且本机 IPv6 是坏的 | 再加 `--edge-ip-version 4` |

完整命令示例：

```powershell
D:\cloudflared\cloudflared.exe tunnel --config D:\cloudflared\config.yml --protocol http2 run anki
```

> 这两个开关**没在本机验证**（本机还没装）。先用默认参数，断了再加，能少一个变量。

---

## 5 日志放哪、怎么看

**跑在控制台里（阶段 A/B 用这个）** —— 输出就在窗口里，同时重定向到文件：

```powershell
D:\cloudflared\cloudflared.exe tunnel --config D:\cloudflared\config.yml run anki *> D:\cloudflared\logs\cloudflared.log
```

- `*>` 会把 stdout+stderr 一起写进文件（PowerShell 语法），窗口里就看不到实时输出了；
  想既看又存，用 `Tee-Object`，或干脆先不重定向、等自启方案定下来再上日志文件。
- 也可以给 cloudflared 自己的参数：`--logfile D:\cloudflared\logs\cloudflared.log --loglevel info`（**未验证**，不同版本可能有差异）。

**装成 Windows 服务后** —— 不再有控制台输出，日志进系统日志：

- 「事件查看器」→「Windows 日志」→「应用程序」，来源找 `cloudflared`（以实际为准）；
- 服务自己的 stdout 重定向不一定生效，排查时优先看事件查看器。

**看日志时认这几行：**

| 日志里出现 | 意思 |
|---|---|
| `Registered tunnel connection` | 隧道连上了，正常 |
| `Connection terminated` / `Retrying connection` | 隧道断了在重连，先等 1 分钟，一直不好就查网络/换 `--protocol http2` |
| `failed to serve tunnel connection` + `connection refused` | **隧道没问题，是本机 8765 没起**（最常见） |
| `Requesting new certificate` / `Cannot determine default origin certificate path` | 没登录过，回去做 `tunnel login` |

---

## 6 和本项目相关的三个坑

1. **不要用 `cloudflared service install` 之后又手动跑一个前台实例** —— 两个进程抢同一条 tunnel，
   日志会互相打架，看起来像「随机断线」。二选一。
2. **服务模式读的是 `C:\Windows\System32\config\systemprofile\.cloudflared\config.yml`**（以实际为准），
   不是你的 `D:\cloudflared\config.yml`。要么用 `--config` 显式指定（服务模式里未必吃这个参数），
   要么把配置拷到系统目录 —— 所以**推荐用「任务计划程序 + 登录时触发」**，配置路径可控。
   具体步骤见 runbook「开机自启」一节。
3. **`tunnel route dns` 会改 Cloudflare 的 DNS 记录**。这一步属于「动域名」，
   必须等 NS 已经改到 Cloudflare、zone 状态变成 Active 之后再执行，否则会报找不到 zone。

---

## 7 卸载 / 回退

```powershell
# 1. 停掉自启任务（任务计划程序里禁用，或）
Stop-ScheduledTask -TaskName "Cardback Cloudflared"   # 名字以你建的实际为准
Unregister-ScheduledTask -TaskName "Cardback Cloudflared" -Confirm

# 2. 如果是服务方式装的
D:\cloudflared\cloudflared.exe service uninstall

# 3. 删掉 D:\cloudflared（凭据 json 一并删，等于作废这条隧道）
```

回退到「同 WiFi」不需要动云端：手机上把地址换回 `http://<电脑IP>:8765#<token>` 即可。

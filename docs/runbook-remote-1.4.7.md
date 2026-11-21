# 远程同步 Runbook（1.4.7）—— 从「只能同 WiFi」到「手机在哪都能连」

> 写于 2026-11-09。**这是操作手册，不是已完成的记录**：本文所有步骤都还没在真机上走过，
> 凡是「未验证」的地方都标了。现状依据见 [`../HANDOFF.md`](../HANDOFF.md) §15.3–§15.8。
>
> 目标：把电脑上的 8765 通过 Cloudflare 隧道暴露成一个 HTTPS 域名，
> 手机在任何网络（4G / 别人的 WiFi）都能同步、重写、回拉，而**手机什么都不用装**。
> 决策背景见 [`decisions-1.4.7.md`](decisions-1.4.7.md)。

---

## 0 先读这一页（一分钟版）

| 你是谁 | 你要做什么 | 大概多久 |
|---|---|---|
| 只想先用起来 | 做**阶段 A**（临时隧道）。不改域名、不登录、不碰 DNS，手机立刻能用（但地址重启就变） | 15 分钟 |
| 想长期稳定用 | 阶段 A 通了再做**阶段 B**（正式域名）。中间有 3 个地方**必须你本人**操作 | 30–60 分钟 + 等 DNS 生效 |

**图例**

- 【你】= 必须你本人动手（装软件 / 登录 / 授权 / 改 DNS）。代理和脚本**不会**替你做。
- 【自动】= 已经能由脚本或电脑自己完成。
- 🔴 = **停手点**。到这一步必须停下来等你，别人代做等于拿你的账号和域名冒险。

**三条红线（写死在这儿，后面不再重复）**

1. 不碰你正在用的 8765 服务：不随意重启，改了 `server.py` 才需要重启。
2. 到 Cloudflare 登录 / 2FA / Authorize / 改 NS / 腾讯云实名或备案 → 🔴 停手。
3. C 盘不堆东西，cloudflared 和日志都放 D 盘。

---

## 1 现状：现在到底卡在哪

| 项 | 现在的事实 | 这意味着什么 |
|---|---|---|
| 手机连接方式 | 只能 `http://<电脑局域网IP>:8765#<token>` | 手机离开这个 WiFi 就连不上 |
| 电脑 IP | DHCP，会变（§14.1 的旧账） | 路由器重启后IP一变，手机就得重填地址 |
| 域名 `example.com` | 已在腾讯云买（38 元）、实名通过 | 域名在手，但**还没接上任何服务** |
| NS（域名服务器） | 还是 DNSPod 的 `swallow/baseball.dnspod.net` | 域名现在归 DNSPod 管，Cloudflare 还管不到它 |
| 解析状态 | 注册局 1–2 个工作日恢复解析（11-09 仍在处理） | 现在查 DNS 可能查不到，**不是你的错** |
| `anki.example.com` 本机解析 | 显示 `198.18.0.180` | ⚠️ 这是代理软件的**假 IP**，**不能当成「域名已通」** |
| `cloudflared` | **没装**；`~/.cloudflared` 只有旧 config.yml，没有 `cert.pem` | 隧道程序还没上，登录也没做过 |
| Cloudflare 账号 | **未登录**这个域名 | 阶段 B 必须你本人登录 |
| 8765 服务 | 在跑（2026-11-09 16:38 重启过，PID **21084**，绑定 `0.0.0.0:8765`；重启脚本与日志在 `_recon/1.4.7/`） | 服务本身是好的，缺的只是「怎么从外面进来」 |
| token | `tools/cardback-editor/.cardback_token`，22 字符 | 手机连接串 `#` 后面那一截就是它 |
| `/health` 接口 | 1.4.6.2 **发布版没有**；1.4.7 工作区已加上，且 8765 已于 16:38 重启，**线上现在返回 200**（`protocol=1`，`capabilities=rewrite/feedback/sync_push/sync_cursor`，`version` 仍 `1.4.2`） | 旧进程访问 `/health` 才会 404；现在若见 404，说明跑的还是旧进程。⚠️ 它和别的接口同一套鉴权：**从公网访问必须带 token，手机浏览器直接打开会 401**，不是坏了（§6、§7） |

### 1.1 为什么不能直接「把域名指到家里」

家里宽带通常没有固定公网 IP，光猫/路由器还挡在前面。Cloudflare Tunnel 的做法是：
**由电脑主动对外建立一条长连接**，别人访问域名时 Cloudflare 顺着这条连接把请求送回来。
所以不需要公网 IP、不需要在路由器上开端口、也不需要 DDNS。

### 1.2 两个必须先接受的前提

- `.cn` 域名 + 家宽对外提供 Web，**有可能**被要求 ICP 备案，也可能因为链路原因在国内访问不稳。
  所以本文第一阶段只做**技术验证**，不通就按 §10 回退，别硬扛。
- 电脑必须开着、且连着网。电脑睡了/关机了，手机就连不上 —— 这是这套方案的固有代价。

---

## 2 开跑之前：5 项自检

在电脑上逐项确认（都做完再往下，能省掉一半的「为什么连不上」）：

| # | 检查 | 怎么做 | 通过的标志 |
|---|---|---|---|
| 1 | 8765 活着 | 电脑浏览器打开 `http://127.0.0.1:8765/` | 出现卡背编辑器页面 |
| 2 | token 在手 | 用记事本打开 `tools\cardback-editor\.cardback_token` | 看到一行 22 个字符的乱码（多一个换行没关系，服务端会去掉空白） |
| 3 | PowerShell 能用 | 开始菜单搜 `PowerShell` 打开 | 能敲命令、不报红字 |
| 4 | D 盘可写 | 资源管理器进 `D:\` 建个空文件夹试试 | 能建 |
| 5 | 手机当前能用（同 WiFi） | 手机连家里 WiFi，App 里用 `http://<电脑IP>:8765#<token>` | 能拉到卡背 |

> 第 5 项很重要：**先在局域网把「App + token」这条路走通**，再上隧道。
> 不然隧道通了但不work，你分不清是隧道的问题还是 App 的问题。

---

## 3 阶段 A：临时隧道（Cloudflare Quick Tunnel）

**目的**：不登录、不改域名、不碰 DNS，先验证「隧道 → 8765 → token → App」这条路是通的。
**代价**：地址是随机的 `https://xxx.trycloudflare.com`，**cloudflared 一重启就换一个**。
**结论**：只用于验证，**不要拿它当日常**。

### A1 装 cloudflared

| 项 | 内容 |
|---|---|
| 谁做 | 【你】装软件属于动你的电脑 |
| 在哪做 | 电脑，PowerShell |
| 怎么做 | `winget install --id Cloudflare.cloudflared -e`；网络不好就走手动 zip（见 [`../tools/cardback-editor/cloudflared-notes.md`](../tools/cardback-editor/cloudflared-notes.md) §2） |
| 成功标志 | 新开一个 PowerShell 窗口，`cloudflared --version` 打印出版本号 |
| 失败怎么办 | ① `winget` 不认识 → 用手动 zip；② 装完命令找不到 → 关掉窗口重开（PATH 要新窗口才生效），或直接用全路径 `D:\cloudflared\cloudflared.exe` |

> 建议放 `D:\cloudflared\`，别装 C 盘（红线 3）。下面命令都按这个路径写，你放别处记得改。

### A2 起临时隧道

| 项 | 内容 |
|---|---|
| 谁做 | 【你】（要开一个窗口并保持它开着） |
| 在哪做 | 电脑，新开一个 PowerShell 窗口 |
| 怎么做 | 见下方命令 |
| 成功标志 | 窗口里出现一行 `https://<随机词>.trycloudflare.com`，并且出现 `Registered tunnel connection` |
| 失败怎么办 | 一直 `Retrying connection` → 加 `--protocol http2` 再试（部分宽带掐 UDP）；报 `connection refused` → **不是隧道问题，是 8765 没起**，回去做 §2 第 1 项 |

```powershell
D:\cloudflared\cloudflared.exe tunnel --url http://127.0.0.1:8765
```

> ⚠️ 这个窗口**别关**。关掉 = 隧道断 = 手机立刻连不上。
> 地址每次重开都会变，变了就得重新粘给手机。

### A3 先在电脑上自检（别急着掏手机）

把 `<临时地址>` 换成 A2 里那串 `https://xxx.trycloudflare.com`。
**注意：不要在命令行里直接写 token**，从文件读进变量，token 就不会进命令历史。

```powershell
# 1) 把 token 读进变量（token 本身不出现命令行里）
$t = (Get-Content "D:\deepseek harness\work_anki_enligish\tools\cardback-editor\.cardback_token" -Raw).Trim()

# 2) 不带 token —— 应该是 401
curl.exe -s -o NUL -w "%{http_code}" https://<临时地址>/cardback.json

# 3) 带 token —— 应该是 200
curl.exe -s -o NUL -w "%{http_code}" -H "X-Cardback-Token: $t" https://<临时地址>/cardback.json
```

| 结果 | 说明 |
|---|---|
| `401` 然后 `200` | ✅ 隧道通了，鉴权也在正常工作。继续 A4 |
| 两次都 `401` | token 读错了（多了引号/空格）；确认 `.cardback_token` 里那串就是手机现在用的那个 |
| 两次都 `200` | 🔴 **鉴权坏了**，别往下走。正常的服务端对「没有 token 的公网请求」必须回 401（`server.py:863` 的 `_is_local()` 逻辑）。立刻停下来报告 |
| `502` / `530` | 隧道在，但回源到 8765 失败 → 8765 没起，或 config 的 service 地址写错 |
| 一直转圈/超时 | 这个临时地址在国内可能不通，直接进 §3.5 的排查 |

### A4 手机上试

| 项 | 内容 |
|---|---|
| 谁做 | 【你】 |
| 在哪做 | 手机 |
| 怎么做 | ① **关掉 WiFi，只用 4G**（这是关键，不关 WiFi 测的还是局域网）<br>② 关掉手机上任何 VPN/代理 App<br>③ App 里「卡背结构地址」**整行**粘：`https://<临时地址>#<token>`<br>④ 存 → 拉一次卡背 |
| 成功标志 | 能拉到卡背 / 能同步；电脑上 `tools\cardback-editor\server.log` 里能看到这次请求 |
| 失败怎么办 | 见 §7 故障表。先确认「电脑上 A3 的 curl 通不通」——电脑上都不通，手机一定不通 |

> ⚠️ **必须手动粘贴**，别用 `adb input text` 之类的命令行注入：`#` 在 shell 里是注释符，
> 会被吃掉，粘进去的是一行没有 token 的地址（HANDOFF §14.6 踩过）。

> 顺便说一句：**用手机浏览器直接打开那个地址会看到 401，这是对的。**
> token 是 App 自己拆出来放到请求头里的，浏览器不会带。看到 401 反而说明安全策略生效了。

### A5 收尾

- 记下这次的临时地址（截图/记事本），下次要重来。
- 验证完就 **Ctrl+C 关掉这个窗口**，别让它一直裸着跑。
- 阶段 A 通了，只证明「隧道方案可行」，**不证明「长期可用」**——长期要 stage B。

---

## 4 阶段 B：正式隧道（named tunnel + `anki.example.com`）

**和阶段 A 的区别**：地址固定、可以用开机自启、Cloudflare 那边有一条属于你的 tunnel 记录。
**代价**：要动域名 NS，中间三步必须你本人。

### 4.0 顺序很重要（先看这个再动手）

```
B1 在 Cloudflare 添加站点  →  B2 🔴 去腾讯云把 NS 改成 Cloudflare 的
        ↓                              ↓
        拿到两个 NS 名字           等 zone 变 Active（几分钟 ~ 24h）
                                              ↓
B3 登录 cloudflared → B4 建 tunnel → B5 绑域名 → B6 写配置 → B7 跑起来 → B8 手机换地址
```

**为什么不能先绑域名**：`cloudflared tunnel route dns` 是往 Cloudflare 的 DNS 里写记录，
如果 `example.com` 还没被 Cloudflare 接管（NS 还没改），它会直接报找不到 zone。
**先改 NS，等 Active，再绑域名**，可以少一次「明明照做了却报错」。

### B1 在 Cloudflare 添加站点

| 项 | 内容 |
|---|---|
| 谁做 | 【你】登录 Cloudflare 账号 |
| 在哪做 | 浏览器 → `dash.cloudflare.com` → Add a site → 输入 `example.com` → 选 **Free** 套餐 |
| 成功标志 | 页面显示 **Pending Nameserver Update**，并给出**两个** NS 名字，形如 `xxx.ns.cloudflare.com` / `yyy.ns.cloudflare.com` |
| 失败怎么办 | 提示域名已被别人添加 → 说明这个域名在别的 CF 账号下，先解决归属；只是加载慢 → 刷新页面重试 |

📌 **把这两个 NS 名字抄下来**，下一步要用。

> 这一步只是「把域名登记到 Cloudflare」，**还没有真的接管**。域名现在仍然由 DNSPod 回答。

### B2 🔴 改 NS（必须你本人）

| 项 | 内容 |
|---|---|
| 谁做 | 🔴 **【你本人】**。这是动你域名根的地方，代理不代做 |
| 在哪做 | 腾讯云控制台 → 域名与网站 → 我的域名 → `example.com` → **DNS 解析 / DNS 服务器（修改）** |
| 怎么做 | 把原来的 `swallow.dnspod.net`、`baseball.dnspod.net` 换成 B1 抄下来的**两个 Cloudflare NS**；有第三/第四个也一并换 |
| 成功标志 | 腾讯云页面显示 NS 已改为 Cloudflare 的；稍后 Cloudflare 面板从 Pending 变 **Active** |
| 失败怎么办 | ① 提示需要实名/验证 → 先按腾讯云提示完成（**也是你本人**）；② 找不到改 NS 的入口 → 腾讯云里叫「DNS 服务器」或「修改 DNS」，不同版本位置略有差别；③ 改了但一直 Pending → 见 B3 |

> ⚠️ 改 NS 之后，DNSPod 里原来的解析记录就不再生效了。
> 如果你有**别的服务**（邮箱、别的子域名）也挂在 DNSPod 上，改之前先把那些记录抄下来，
> 之后在 Cloudflare 里重建。**这一步做错会影响你其它服务，所以必须你自己决定。**

### B3 等生效 + 怎么确认真的生效了

| 项 | 内容 |
|---|---|
| 谁做 | 【你】等；确认动作可以自己在电脑/手机上做 |
| 在哪做 | 电脑 PowerShell 或手机 |
| 怎么做 | 见下方命令 |
| 成功标志 | 查到的 NS 是 Cloudflare 那两个；Cloudflare 面板显示 **Active** |
| 失败怎么办 | ① 不到 24 小时都算正常，等；② 超过 24 小时还是 Pending → 回腾讯云确认 NS 是否**真的保存**了；③ 域名状态异常（注册局未恢复解析）→ 只能等注册局，见 §1 |

```powershell
# 指定公共 DNS 查，绕开本机代理的假解析
nslookup -type=NS example.com 8.8.8.8
```

> ⚠️ 在本机直接 `ping anki.example.com` 可能得到 `198.18.0.180`，
> 那是代理软件的假 IP（§15.4），**不代表域名已经通**。判断「通没通」请用：
> ① 上面的 `nslookup ... 8.8.8.8` 看 NS 是否换成 Cloudflare；
> ② Cloudflare 面板是否 Active；
> ③ 手机 4G 下能不能打开（手机没有那个代理）。

### B4 登录 cloudflared（写 cert.pem）

| 项 | 内容 |
|---|---|
| 谁做 | 🔴 **【你本人】**。会弹出浏览器让你登录 + 可能要 2FA + 点 Authorize |
| 在哪做 | 电脑 PowerShell + 弹出的浏览器 |
| 怎么做 | `D:\cloudflared\cloudflared.exe tunnel login` → 浏览器里选 `example.com` → **Authorize** |
| 成功标志 | 终端打印成功；`%USERPROFILE%\.cloudflared\cert.pem` 出现（这是「这台电脑被授权管理这个域名」的凭证） |
| 失败怎么办 | ① 浏览器没弹 → 命令会打印一个 URL，手动复制到浏览器打开；② 登录页要求 2FA → 按你账号的验证方式过；③ 列表里没有 `example.com` → 回 B1/B2，zone 还没加载好 |

> 🔴 到「登录 / 2FA / Authorize」就停手：这是你的账号授权，别人代做等于拿到你域名的管理权。
> `cert.pem` 等同于密码，**不要发群、不要截图、不要提交进仓库**。

### B5 建 tunnel

| 项 | 内容 |
|---|---|
| 谁做 | 【你】敲一条命令 |
| 在哪做 | 电脑 PowerShell |
| 怎么做 | `D:\cloudflared\cloudflared.exe tunnel create anki` |
| 成功标志 | 打印出 **Tunnel ID（一串 UUID）**，并在 `.cloudflared\` 下生成 `<UUID>.json` 凭据文件 |
| 失败怎么办 | 提示 not logged in → 回 B4；提示名字已存在 → 说明以前建过，用 `tunnel list` 看看，别重复建 |

📌 记下 **Tunnel ID**。凭据 json 建议拷一份到 `D:\cloudflared\anki-<UUID>.json`（config.yml 里要指它）。

### B6 把域名绑到 tunnel

| 项 | 内容 |
|---|---|
| 谁做 | 【你】敲一条命令（它会往你 Cloudflare 的 DNS 里写一条记录） |
| 在哪做 | 电脑 PowerShell |
| 怎么做 | `D:\cloudflared\cloudflared.exe tunnel route dns anki anki.example.com` |
| 成功标志 | 命令成功；Cloudflare 的 DNS 页面出现一条 `anki` 的 **CNAME**，指向 `<TUNNEL-UUID>.cfargotunnel.com`（橙色云朵=已代理） |
| 失败怎么办 | 报找不到 zone / 域名不存在 → zone 还没 Active，回 B3 等；报已存在同名记录 → 去 DNS 页面删掉旧的 `anki` 记录再执行 |

### B7 写配置

| 项 | 内容 |
|---|---|
| 谁做 | 【你】建文件、贴内容 |
| 在哪做 | `D:\cloudflared\config.yml` |
| 怎么做 | 照抄 [`../tools/cardback-editor/cloudflared-notes.md`](../tools/cardback-editor/cloudflared-notes.md) §3 的样例，把 `<TUNNEL-UUID>` 换成 B5 那串 |
| 成功标志 | `cloudflared tunnel ingress validate --config D:\cloudflared\config.yml` 不报错 |
| 失败怎么办 | 报 YAML 语法错 → 缩进必须用**空格**不能用 Tab；报缺 ingress 兜底 → 最后那条 `- service: http_status:404` 别漏 |

### B8 跑起来

| 项 | 内容 |
|---|---|
| 谁做 | 【你】 |
| 在哪做 | 电脑 PowerShell（先手工跑通，再做自启） |
| 怎么做 | `D:\cloudflared\cloudflared.exe tunnel --config D:\cloudflared\config.yml run anki` |
| 成功标志 | 日志出现 `Registered tunnel connection`；紧接着用 B3 的方法确认域名能访问（下方命令） |
| 失败怎么办 | 见 §7 故障表；最常见是「隧道通了但 8765 没起」（日志会有 connection refused） |

```powershell
# 域名侧自检（同样：token 从文件读，不进命令行）
$t = (Get-Content "D:\deepseek harness\work_anki_enligish\tools\cardback-editor\.cardback_token" -Raw).Trim()
curl.exe -s -o NUL -w "%{http_code}" https://anki.example.com/cardback.json                  # 期望 401
curl.exe -s -o NUL -w "%{http_code}" -H "X-Cardback-Token: $t" https://anki.example.com/cardback.json  # 期望 200
```

> **`/health` 的两种情况**：跑着旧进程 → 404；跑着新版 → 但**它也要 token**（`_guard` 对所有路由一视同仁），
> 所以公网自检要写成 `curl -H "X-Cardback-Token: $t" https://anki.example.com/health`，
> **用手机浏览器直接打开会看到 401，那是设计如此，不是域名没通**（域名通不通看 `/cardback.json` 那两条）。

### B9 手机换成正式地址

```
https://anki.example.com#<token>
```

整行粘，`#` 后面是 22 字符 token，**不要在中间加路径**（App 会自己补 `/cardback.json`）。
换完之后把阶段 A 那个临时隧道窗口关掉。

### B10 阶段 B 的完成标准

- 手机 **4G** 下能拉卡背、能提交一次反馈；
- 电脑重启后**不用手工点任何东西**，手机仍然能用（做完 §5 才算）；
- 故意写错 token → 手机报 401 且**不会**疯狂重试；
- 上面三条都满足，才算「远程同步真的可用了」。

---

## 5 开机自启与存活

**为什么必须做**：电脑重启后如果 cloudflared 没起，手机看到的是「连不上」，
而你看到的是「电脑明明开着」——这种「看起来没事其实断了」最难查。

### 5.1 方案一（推荐给小白）：任务计划程序

用图形界面，不用记命令。**要做两次**：一次给 cloudflared，一次给 8765（可选）。

**A. 给 cloudflared 建任务**

1. 按 `Win + R`，输入 `taskschd.msc`，回车（打开「任务计划程序」）。
2. 左侧点「任务计划程序库」，右侧点「**创建任务**」（不是「创建基本任务」）。
3. **常规**页：
   - 名称：`Cardback Cloudflared`
   - 选「**只在用户登录时运行**」（更简单，不用存密码）
   - **不要**勾「使用最高权限运行」（cloudflared 不需要管理员）
4. **触发器**页 → 新建：
   - 开始任务：**登录时**
   - 勾「延迟任务时间」：**30 秒**（等网络就绪，否则刚登录时 DNS 还没好，会失败一次）
5. **操作**页 → 新建：
   - 程序或脚本：`D:\cloudflared\cloudflared.exe`
   - 添加参数：`tunnel --config D:\cloudflared\config.yml run anki`
   - 起始于：`D:\cloudflared`
6. **设置**页：
   - 勾「**如果任务失败，按以下频率重新启动**」：`1 分钟`，`最多 999 次`
   - 勾「如果任务已在运行，则以下规则适用」→ 选「**不启动新任务**」
   - **取消**勾「如果计算机改用电池电源，则停止」（笔记本上这条会让隧道莫名其妙断）
   - 取消「如果任务运行时间超过…则停止任务」
7. 确定保存（会问你要 Windows 密码，输入即可）。
8. 右键这个任务 →「**运行**」，然后看它的「上次运行结果」是不是 `0x0`。

**B. 给 8765 建任务（可选）**

同样步骤，只是：

- 程序：`C:\Windows\System32\cmd.exe`
- 参数：`/c "D:\deepseek harness\work_anki_enligish\tools\cardback-editor\启动编辑器.bat"`
- 触发器同样「登录时 + 延迟 30 秒」

> ⚠️ 8765 是常驻服务（红线 1）。做成自启可以，但**不要给它配「失败自动重启」** ——
> 一旦 `server.py` 有语法错，它会陷入无限重启，日志刷爆盘，而且你会以为「服务在跑」。
> 8765 挂了应该**报警让人看**，不是自动反复重启。

**命令行等价写法（参考，未在本机执行，引号容易出错，建议还是用图形界面）**

```powershell
schtasks /Create /TN "Cardback Cloudflared" /SC ONLOGON /DELAY 0000:30 /TR "\"D:\cloudflared\cloudflared.exe\" tunnel --config \"D:\cloudflared\config.yml\" run anki" /RL LIMITED /F
```

### 5.2 方案二：`cloudflared service install`（不推荐）

管理员 PowerShell 执行 `cloudflared service install`，它会把 cloudflared 装成 Windows 服务。
**为什么默认不推荐**：

- 服务以 SYSTEM 身份跑，**读的是系统目录下的 config.yml**，不是你的 `D:\cloudflared\config.yml`；
- 服务模式的日志进「事件查看器」，没有控制台，小白排查起来更绕；
- 卸载/排错都多一层。

如果你就是想要「开机不用登录也跑」，用方案二；否则用方案一。

### 5.3 睡眠 / 断电 / 换网络

| 情况 | 实际表现 | 要做什么 |
|---|---|---|
| 电脑睡眠 | 隧道断，唤醒后 cloudflared 一般会自己重连（`Retrying connection` → `Registered`） | 等 1 分钟；长期建议把「自动睡眠」关掉，或接受每次唤醒后等一会儿 |
| 拔网线 / 换 WiFi | 同上，自动重连 | 等；一直不好就重启那个计划任务 |
| 断电重启 | 计划任务在登录后拉起 | 不做任何手工操作，只验证（§9 第 4 条） |
| 路由器换 IP（局域网地址变了） | 隧道**不受影响**（隧道不依赖局域网 IP） | 手机不用改地址 —— 这正是隧道比局域网强的地方 |

### 5.4 什么时候才需要重启 8765

- **改过 `tools\cardback-editor\server.py`** → 必须重启才生效（HANDOFF §15.3 的事故就是这么来的：旧进程没有新路由，App 打了 186 次 404）。
- 8765 进程没了（`healthcheck.ps1` 报连不上）→ 重启。
- 其它情况**不要重启**：重启会让正在进行的重写中断，手机会看到超时。
- 重启后终端会**重新打印手机那一行**（含 token），以那行为准。

---

## 6 健康检查脚本 `healthcheck.ps1`

文件：[`../tools/cardback-editor/healthcheck.ps1`](../tools/cardback-editor/healthcheck.ps1)

**它干什么**：请求一个地址，看 HTTP 状态 + 返回 JSON 里的 `ok` / `version`；
异常就写一行带时间戳的日志，并（可选）重启你指定的计划任务。

### 6.1 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `-Url` | `http://127.0.0.1:8765/health` | 要探测的地址。查域名时换成 `https://anki.example.com/health` |
| `-LogPath` | 脚本同目录 `healthcheck.log` | 日志路径。建议指到仓库外，如 `D:\cardback-logs\healthcheck.log` |
| `-TaskName` | 空 | 异常时要重启的计划任务名。**不填 = 只报警不重启**（推荐先这样用） |
| `-FallbackUrl` | `http://127.0.0.1:8765/cardback.json` | `/health` 返回 404 时的退路，用来区分「旧版服务」和「服务挂了」 |
| `-ExpectVersion` | 空 | 期望版本。`/health` 有两个版本字段（`version="1.4.2"`、`server="CardbackEditor/1.4.2"`），**写 `1.4.2` 或 `CardbackEditor/1.4.2` 都认**；不一致算异常（多半是改了 `server.py` 没重启） |
| `-Token` / `-TokenFile` | 空 | 只有查公网域名时才需要；优先用 `-TokenFile`，别把 token 写进任务参数 |
| `-TimeoutSec` | 5 | 单次请求超时 |
| `-RestartWaitSec` | 8 | 重启后等几秒复查；`0` = 不复查 |
| `-MaxLogBytes` | 1048576 | 日志超过 1 MB 就滚成 `.1`（只留一份备份） |
| `-LogSuccess` | 关 | 正常时也写日志（默认只在异常/恢复时写，免得日志堆满盘） |
| `-NoProxy` | 关 | 绕过系统代理（**只在本机 pwsh 7+ 生效**）。有代理软件且「服务活着却连不上」时试它 |
| `-DryRun` | 关 | 演练：照常探测/记日志，**绝不重启任务**。只影响重启，不影响退出码 |

**退出码**：`0` = 正常（含「活着但没 `/health`」和「活着但没带 token」）；`1` = 异常；`2` = 参数/环境问题。

### 6.1.1 它怎么判、什么时候才重启（重要）

| 探测结果 | 判定 | 退出码 | 会重启吗 |
|---|---|---|---|
| 200 且 `ok=true`、版本对得上 | healthy | 0 | 不会 |
| 200 但 `ok≠true` / 版本不对（改了 `server.py` 没重启） | not-ok / version-mismatch | 1 | **会**（重启正是解药） |
| 404（旧进程没有 `/health`）+ 退路有任何 HTTP 响应 | old-server | **0** | 不会 |
| 401 且**没给** token（查公网域名的常态） | auth-required | **0** | 不会 |
| 401 且**给了** token 仍被拒（token 错/只换了一端） | bad-token | 1 | **不会**（重启治不了，还会打断同步） |
| 429 | rate-limited | 1 | **不会**（等窗口，别折腾） |
| 连不上 / 端口没响应 | unreachable / down | 1 | 会（`-TaskName` 给了才动） |

> 一句话：**只有「进程真挂了」和「版本对不上」才重启**。401/429/4xx 一律只报警 ——
> 这是为了不踩「8765 别乱重启」的坑（§5.4）。

### 6.2 四个常用例子

```powershell
# 1) 现在就能跑：查本机 8765。
#    新版：200 + version；旧进程：404 会记一条 WARN「old-server」，仍返回 0（= 活着、只是缺接口）。
.\healthcheck.ps1

# 2) 演练：异常也不重启（先观察几天，看日志里到底会不会报）
.\healthcheck.ps1 -DryRun -LogSuccess

# 3) 定时任务里用：异常时重启 cloudflared 的计划任务
.\healthcheck.ps1 -TaskName "Cardback Cloudflared"

# 4) 查公网域名：/health 要 token，用 -TokenFile 从文件读，别写进任务参数
.\healthcheck.ps1 -Url "https://anki.example.com/health" `
                  -FallbackUrl "https://anki.example.com/cardback.json" `
                  -TokenFile "D:\deepseek harness\work_anki_enligish\tools\cardback-editor\.cardback_token"
```

### 6.3 挂到「任务计划程序」（每 5 分钟一次）

1. 创建任务 → 名称 `Cardback Healthcheck`
2. **触发器** → 新建 → 「**每天**」→ 重复任务间隔 `5 分钟`、持续时间 `无限期`
3. **操作** → 新建：
   - 程序：`powershell.exe`
   - 参数：`-NoProfile -ExecutionPolicy Bypass -File "D:\deepseek harness\work_anki_enligish\tools\cardback-editor\healthcheck.ps1" -TaskName "Cardback Cloudflared" -LogPath "D:\cardback-logs\healthcheck.log"`
   - 起始于：`D:\deepseek harness\work_anki_enligish\tools\cardback-editor`
4. **设置** → 勾「如果任务失败，按以下频率重新启动」`5 分钟` / `3 次`
5. 保存后在列表里右键「运行」，看「上次运行结果」：`0x0` 正常，`0x1` 表示检查发现异常（不是脚本坏了）

> ⚠️ 任务计划程序的「上次运行结果」把退出码 1 显示成 `0x1`，那正是「 unhealthy」的意思，
> 不要当成脚本崩溃。真崩溃一般是 `0x2` 或别的码。

### 6.4 两个必须知道的坑

1. **别用它去重启 8765**：默认 URL 查的就是 8765，如果填 `-TaskName "8765 的任务"`，
   一旦 8765 因为 `server.py` 有语法错而起不来，就会变成「无限重启 + 日志刷屏」（见 §5.1 B）。
   推荐：健康检查只**报警**，重启留给人工；或者只让它重启 **cloudflared** 那个任务。
2. **日志不会进 git（已核实）**：根 `.gitignore` 第 6 行是 `*.log`，`server.log`、`healthcheck.log` 都被忽略。
   仍然建议把 `-LogPath` 指到仓库外（如 `D:\cardback-logs\`）：仓库可能被整体备份/同步，日志放外面更干净。

---

## 7 故障表：出现什么，第一步查什么

按「先内后外」查：**手机 → 电脑本机 8765 → 隧道 → 域名/DNS**。
每一步都有个「本机能通吗」的判断，能立刻把问题缩到一半。

| 现象 | 多半是 | **第一步查什么** | 接着怎么办 |
|---|---|---|---|
| **401** | token 错 / 没带 token / 换了 token 只换一端 | 电脑上 `curl` 带 token 打 `/cardback.json` 是不是 200（§3 A3） | 是 200 → 手机那行粘错了（少了 `#`、`#` 被吃掉、末尾多了空格）；不是 → 回 §8 轮换 token |
| **401 但 curl 也 200 不了，且本机 `127.0.0.1:8765` 也 401** | 用了 `-TokenFile` 读到旧 token，或 token 文件被删过又生成 | 看 `.cardback_token` 的修改时间；看 8765 启动时打印的那行 | 重启 8765 会沿用文件里的 token；删文件重启才会换新的 |
| **404**（尤其 `/rewrite/feedback`） | 电脑端 8765 还是**旧进程**，没有新路由 | 8765 是几点启动的？`server.py` 最后修改时间是几点？ | 改了 `server.py` 就必须重启（§5.4）。1.4.7 会做版本自报，现在只能靠日志判断 |
| **404 访问 `/health`** | 跑着**旧进程**（1.4.6.2 没有这个接口） | `curl -i http://127.0.0.1:8765/health` 看是不是我们自己的 404 文案 | 别当成故障：改过 `server.py` 就重启 8765（§5.4）；或者继续用 `/cardback.json` 自检 |
| **公网 `/health` 返回 401**（本机同一地址却 200） | **正常**：`_guard` 对所有路由都要 token，公网没有 loopback 豁免 | 同一条命令加上 `-H "X-Cardback-Token: $t"` 再试 | 手机浏览器**不可能**带上这个头，所以别拿浏览器当自检工具；用 curl 或 `healthcheck.ps1 -TokenFile` |
| **429** | 重试太密被限流（POST `/rewrite*`、`/api/publish`、`/api/rollback`：单 IP 每分钟 20 次 / 每天 200 次） | 看返回里的 `Retry-After` 秒数；看 `server.log` 是不是同一 IP 连续打 | **等**，别反复点。重启 8765 能清掉计数，但那只是掩盖问题；根治是让 App 退避（1.4.7） |
| **DNS 查不到 / 打不开域名** | NS 没改、还没生效、注册局还没恢复解析 | `nslookup -type=NS example.com 8.8.8.8`；Cloudflare 面板是否 Active | 没 Active 就等（≤24h）；NS 还是 DNSPod → 回 B2；域名状态异常 → 等注册局 |
| **域名解析成 `198.18.x.x`** | 代理软件的假 IP，**不是真实解析** | 换手机 4G 或 `8.8.8.8` 查 | 别拿它当「已通」的证据（§15.4 明确记过这个坑） |
| **超时 / 连不上** | 电脑关了/睡了、cloudflared 没起、隧道断了 | 电脑上 `http://127.0.0.1:8765/` 打得开吗？cloudflared 窗口/任务在跑吗？ | 本机通+隧道没起 → 起隧道；本机也不通 → 起 8765；都通 → 看 cloudflared 日志是不是 `Retrying connection`（试 `--protocol http2`） |
| **502 / 530** | 隧道在，但回源失败 | cloudflared 日志找 `connection refused` | = 8765 没起，或 config 的 `service` 写错（必须 `http://127.0.0.1:8765`） |
| **能连上但数据不动** | 书/词不在本机，拉取时跳过并推进游标 | App 里拉的是哪本书？电脑端 `published\cardback.json` 里有没有这个词？ | 现在的版本会「跳过并推进游标」，所以看起来像没动；先手动重写/重拉一次。真正的修复（跳行回补）在 1.4.7 |
| **手机 WiFi 能用、4G 不能用** | 地址还是局域网 IP | 看手机里那行地址是 `http://10.x` 还是 `https://anki...` | 换地址（§4 B9）；顺带确认没开 VPN/代理 |
| **4G 能用、WiFi 不能用** | 家里路由器把 `anki.example.com` 解析到了奇怪的地方（DNS 劫持/家长控制） | 手机 WiFi 下 `nslookup anki.example.com` | 路由器换 DNS；或临时用 4G |

---

## 8 token 轮换（换钥匙）

**什么时候要换**：怀疑泄露（截图发过群、日志里出现过）、换手机、或者就是想定期换。

**铁律：两端必须一起换。只换一端 = 401。**

| 步骤 | 谁做 | 怎么做 | 成功标志 |
|---|---|---|---|
| 1 | 【你】 | 关掉 8765（Ctrl+C 或结束那个计划任务） | 窗口没了 |
| 2 | 【你】 | 删掉 `tools\cardback-editor\.cardback_token` | 文件消失 |
| 3 | 【你】 | 重新启动 8765（`启动编辑器.bat` 或计划任务「运行」） | 终端打印出新的「手机：粘贴这一行 → …」，**`#` 后面那 22 个字符就是新 token** |
| 4 | 【你】 | 手机上把那行**整行**重新粘一次（域名换成 `https://anki.example.com#新token`） | App 能拉到卡背 |
| 5 | 【你】 | 用旧 token 试一次，应该 401 | 说明旧的确实失效了 |

> 注意：重启后终端打印的是**局域网地址**（`http://10.x.x.x:8765#token`）。
> 用隧道时你要保留 `#` 后面那截 token，自己拼成 `https://anki.example.com#<token>`。
> 1.4.7 之后应该会直接打印域名行，现在还没有。

---

## 9 验收清单（1.4.7 交付时逐项过）

> 来自 HANDOFF §15.7 的十条，改写成可打勾 + 可操作的版本。
> **现在一条都还没验**；「谁来验」里 【你】= 必须真机上手，【自动】= 脚本/日志能判。

| # | 场景 | 怎么验（照着做） | 谁 | 结果 |
|---|---|---|---|---|
| 1 | 4G ⇄ WiFi 切换 | 手机 4G 同步一次 → 开 WiFi 再同步一次 → 再切回 4G。看 App 有没有重复提交、队列有没有丢 | 【你】 | ☐ |
| 2 | 断网 → 重连 | 手机飞行模式 2 分钟，期间点几次操作 → 关飞行模式 → 1 分钟内应自动补发；`server.log` 里不应出现 404/429 风暴 | 【你】+【自动】看日志 | ☐ |
| 3 | App 杀进程 / 冷启动 | 跳过几个词 → 强杀 App → 重开 → 跳过的词应回来；电脑端记录数不应翻倍 | 【你】 | ☐ |
| 4 | 电脑重启 | 重启电脑 → **不做任何手工操作** → 手机 4G 能同步；`healthcheck.ps1` 退出码 0 | 【你】 | ☐ |
| 5 | 隧道重启 / 地址变化 | 任务计划程序里「结束」再「运行」cloudflared → 手机**不用改地址**就能恢复（named tunnel 地址固定；quick tunnel 不适用本条） | 【你】 | ☐ |
| 6 | 旧 8765（无 feedback 路由） | 临时跑一个改过的旧 `server.py`（或用旧进程）→ App 应提示「电脑端版本过旧」，而不是静默 404 | 【自动】需 1.4.7 实现 | ☐ |
| 7 | 错 token | 手机 token 改一个字符 → 401 + 有换 token 指引；`server.log` 里请求次数**不应无限增长** | 【你】+【自动】 | ☐ |
| 8 | 429 | 连续触发重写超过 20 次/分钟 → 状态页显示「等待 N 秒」；等过窗口后自动恢复 | 【你】 | ☐ |
| 9 | 24h soak | 挂一天：看 8765 进程内存、`server.log` 大小、`sync.json` 大小是否持续增长；重试是否有上限 | 【自动】+【你】看数 | ☐ |
| 10 | 四条链路 | 在 `https://anki.example.com` 下各走一次：远程重写 / 跳过 / 反馈 / 回拉 | 【你】 | ☐ |

**判定规则**：10 项全过才算 1.4.7 远程同步可用。任何一项不过，按 §7 定位；
定位不了就**保持局域网可用**（§10），别让「远程拿不到」变成「本机也用不了」。

---

## 10 回退：随时能退回「同 WiFi」

远程这条路挂了不影响局域网，回退很快：

1. 手机 App 里把地址换回 `http://<电脑IP>:8765#<token>`
   （IP 变了就在 8765 启动的那行输出里看）。
2. 隧道可以留着不管（反正手机不用它），也可以停掉：
   任务计划程序里对 `Cardback Cloudflared` 右键「结束」/「禁用」。
3. 彻底不想要了：见 [`../tools/cardback-editor/cloudflared-notes.md`](../tools/cardback-editor/cloudflared-notes.md) §7。

> 域名那边**不用动**。NS 改到 Cloudflare 之后，只是「这个域名没人用」，不影响你别的服务
> ——除非你原本在 DNSPod 上还有邮件/其它子域记录（回 B2 的警告）。

---

## 11 没验证的、要你拍板的

### 11.1 本文里「没验证」的项（不要当成已知事实）

| 项 | 状态 |
|---|---|
| cloudflared 安装（winget / 手动 zip） | **本机未安装、未执行**。包 id、版本号以实际为准 |
| Quick Tunnel 在国内家宽的可达性 | **未实测**。可能超时/被拦，属预期风险 |
| `anki.example.com` 能否长期在国内访问 | **未实测**；`.cn` + 家宽 Web 可能被要求备案 |
| `tunnel route dns` / `tunnel login` 的具体交互 | **未执行**，按官方流程写的；版本差异可能会导致菜单位置不同 |
| 任务计划程序的两套配置 | **未在本机创建过**；`schtasks` 那条命令行未执行，引号容易翻车 |
| `healthcheck.ps1` | 只做了**语法解析检查**（未执行），首次使用请带 `-DryRun` |
| `/health` 接口 | 1.4.6.2 发布版**没有**；1.4.7 版本已写进工作区 `server.py`（**未发版、未在本机重启验证**）。脚本的 `old-server` 分支就是给旧进程准备的 |
| 服务模式（`service install`）的配置路径 | **未验证**，文中已标注「以实际为准」 |

### 11.2 要你拍板的事

1. **用不用这个 `.cn` 域名**？接受「可能被要求备案 / 可能国内访问不稳」的风险吗？
   不接受 → 方案改成 Tailscale Funnel（备选，国内连通性未实测）或换非 `.cn` 域名。
2. **同不同意把 NS 改到 Cloudflare**？（这会改变域名的管理方，见 B2 的警告）
3. **允不允许装计划任务、日志放哪、留几天**？（计划任务会改你机器的开机行为）
4. **1.4.7 是先只做隧道验证，还是连 App 侧状态页/outbox 一起做**？
   （App 侧不动的话，§9 第 1/2/3/6/7/8 条没法真正验收）

---

## 附：文件清单

| 文件 | 作用 |
|---|---|
| 本文 | 主流程：谁做、在哪做、成功标志、失败怎么办 |
| [`../tools/cardback-editor/cloudflared-notes.md`](../tools/cardback-editor/cloudflared-notes.md) | 装在哪、config.yml 样例、常用命令、日志位置 |
| [`../tools/cardback-editor/healthcheck.ps1`](../tools/cardback-editor/healthcheck.ps1) | 定时健康检查 + 可选重启计划任务 |
| [`decisions-1.4.7.md`](decisions-1.4.7.md) | 为什么选 Cloudflare、为什么不选 Tailscale |
| [`../HANDOFF.md`](../HANDOFF.md) §15 | 现状、事故、红线的原始记录 |

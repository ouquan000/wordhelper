# Cloudflare 接入前置（1.4.7）

> 写于 2026-11-09。**本文只是调研 + 操作说明，不是已完成记录。**
> 写作过程中：没有登录任何账号、没有安装 cloudflared、没有改代码、没有动服务器；
> 唯一的"实操"是对本机 `127.0.0.1:8765` 做了几条只读探测（结果见 §5.4）。
>
> 目标：把电脑上的 `127.0.0.1:8765` 通过 Cloudflare Tunnel 变成一个固定的 HTTPS 域名
> `https://anki.example.com`，**手机零安装**，任何网络都能同步。
>
> 相关文档：[`runbook-remote-1.4.7.md`](runbook-remote-1.4.7.md)（完整操作手册）、
> [`decisions-1.4.7.md`](decisions-1.4.7.md)（为什么选这条路线）。
> 本文是它们**之前**的那一步：先把"人要做什么、机器要做什么、哪里可能翻车"讲清楚。

---

## 0 三分钟版：这事到底分几段

```
[你] 注册/登录 CF  →  添加站点  →  抄 2 个 NS  →  🔴 回腾讯云改 NS  →  等 Active
                                                                        ↓
[我] 装 cloudflared  →  🔴 你点 Authorize  →  建 tunnel  →  写 config  →  绑 DNS  →  跑起来
                                                                        ↓
[一起] 验收：curl 三步  →  手机 4G 实测  →  开机自启
```

| 阶段 | 谁做 | 预计耗时 | 停手点 |
|---|---|---|---|
| A. Cloudflare 账号 + 添加站点 | 【你】 | 10 分钟 | 注册、邮箱验证、2FA |
| B. 改 NS（腾讯云） | 🔴 **【你本人】** | 3 分钟 | **AI 不代点、不代填** |
| C. 等 zone 变 Active | 【你】等，我帮看 | 5 分钟 ~ 24 小时 | — |
| D. cloudflared 登录授权 | 🔴 **【你本人】** | 2 分钟 | 浏览器里点 **Authorize** |
| E. 建隧道 / 写配置 / 绑 DNS | 【我】 | 15 分钟 | — |
| F. 装机自启 + 验收 | 【我】做，【你】点 UAC | 15 分钟 | 管理员授权弹窗 |

**三条红线（和 runbook 一致，不再重复）**

1. 不碰正在用的 8765 服务（只有改了 `server.py` 才需要重启）。
2. 到登录 / 2FA / Authorize / 改 NS / 腾讯云实名 → 🔴 停手，等你本人。
3. C 盘不堆东西：cloudflared、日志、凭据都放 `D:\cloudflared\`。

---

## 1 「你只需点几下」的最小操作清单

### 1.0 开始前 60 秒自检

| # | 检查 | 怎么查 | 通过标志 |
|---|---|---|---|
| 1 | 腾讯云账号能登录 | 浏览器打开 `console.cloud.tencent.com` | 能进控制台（这一步现在就可以先登一次，省得后面找密码） |
| 2 | 域名状态正常 | 腾讯云 → 域名注册 → 我的域名 → `example.com` | 状态是「正常 / ok」，**不是** ServerHold（据现有记录已解除） |
| 3 | 注册邮箱可用 | 打开你买域名/注册 CF 要用的邮箱 | 能收信（CF 会发验证邮件） |
| 4 | 手机能上 4G | 关掉 WiFi，用流量打开任意 https 网站 | 能打开（后面所有"通没通"的判断都以 4G 为准） |
| 5 | 电脑 8765 活着 | 电脑浏览器打开 `http://127.0.0.1:8765/` | 出现卡背编辑器页面 |

> 第 4 项特别重要：**你这台电脑上装了代理**，本机的 DNS 会被劫持成 `198.18.x.x` 假地址
> （我实测：连 `nslookup ... 223.5.5.5` 都返回 `198.18.0.23`）。
> 所以「域名通没通」在本机测**不可信**，只能看 Cloudflare 面板 + 手机 4G。

---

### 1.1 注册 / 登录 Cloudflare（约 5 分钟）

| 项 | 内容 |
|---|---|
| 打开 | 浏览器访问 `https://dash.cloudflare.com/sign-up` |
| 你会看到 | 一个注册表单：Email、Password，下面一个 `Create Account` 按钮（也可能写作 `Sign up`） |
| 你要点 | 填邮箱 + 密码 → 点 **Create Account** → 去邮箱点验证链接 → 回到 CF |
| 成功标志 | 进入 Cloudflare 控制台首页（左上角是你的邮箱，左边一列菜单：Websites / Domains / Zero Trust …） |
| 卡住了 | ① 收不到验证邮件 → 查垃圾箱，或在登录页点 `Resend`；② 提示邮箱已注册 → 直接去 `https://dash.cloudflare.com/login` 登录，别重复注册 |

**2FA（建议开，但不是必须）**

| 项 | 内容 |
|---|---|
| 在哪 | 右上角头像 → `My Profile` → `Authentication`（也可能叫 `Security`）→ `Two-Factor Authentication` → `Manage` |
| 你会看到 | 让你选验证方式：Authenticator app（扫码）/ Security key / 短信备份码 |
| 建议 | 用手机上的验证器 App 扫码，**并把恢复码抄在纸上** |
| 为什么现在做 | 后面 `cloudflared tunnel login` 的授权页可能要求 2FA；先配好，省得当场卡住 |

> 账号是你自己的，**密码、2FA、恢复码都不要发给我**。我全程不需要你的账号密码。

---

### 1.2 添加站点 `example.com`（约 3 分钟）

| 项 | 内容 |
|---|---|
| 在哪 | 登录后的控制台首页 |
| 你会看到 | 右上角一个 **`Add a site`** 按钮（新版界面也可能是 `Add` → `Connect a domain` / `+ Add site`） |
| 你要点 | `Add a site` → 输入框里填 **`example.com`**（只填根域名，**不要**填 `www` 或 `anki.`）→ 点 `Continue` |
| 下一步 | 选套餐页：`Free` / `Pro` / `Business` / `Enterprise`。**选 Free（$0）**，点 `Continue` |
| 再下一步 | CF 会「扫描现有 DNS 记录」，因为你的域名现在是 **0 条解析**，扫出来是空的；直接点 `Continue` / `Add records` 跳过 |
| 成功标志 | 页面标题大意是 **「Change your nameservers」/「Pending Nameserver Update」**，并且给出**两个** NS 名字 |

> ⚠️ 界面文案会随 Cloudflare 改版变化。**判断标准不是按钮叫什么，而是你有没有看到两个
> `*.ns.cloudflare.com` 的名字 + 状态 `Pending Nameserver Update`。**

**失败对照**

| 现象 | 含义 | 怎么办 |
|---|---|---|
| `This domain is already added to Cloudflare` | 这个域名已经挂在某个 CF 账号下 | 先确认是不是你以前注册过另一个 CF 账号；是的话回那个账号，不要重复添加 |
| 提示域名无效 / 无法添加 | 注册商侧状态不对（欠费、ServerHold、实名未完成） | 回腾讯云看域名状态；这条**本文未验证**，遇到请把完整报错留给我 |
| 一直转圈 | 网络问题 | 换个网络（手机热点）再试；CF 面板在国内偶尔慢 |

---

### 1.3 抄下这两个 NS（30 秒）

在刚才那个页面上，你会看到类似：

```
ada.ns.cloudflare.com
bob.ns.cloudflare.com
```

（名字是随机分配的，**你的两个名字和上面不一样，以你屏幕上看到的为准**。）

| 项 | 内容 |
|---|---|
| 你要做 | 把这两个名字**原样抄到记事本**，一个字符都别改（不要加 `http://`，不要加结尾的点） |
| 别做 | 不要点页面上的「立即检查 / Re-check now」试图跳过改 NS —— 没改 NS 永远不会 Active |
| 小提示 | 这两个名字以后不会变；`cert.pem` 丢了也不用重新拿 NS |

---

### 1.4 🔴 回腾讯云改 NS（**必须你本人操作，AI 不代点**）

> **为什么必须你本人**：这一步把整个域名的解析权交给 Cloudflare。
> 交出去之后，谁拿到你的 CF 账号就等于拿到你域名的解析权。所以这一步只能你自己点，
> 我不能代填、不能代确认，也不该知道你的腾讯云登录态。

| 项 | 内容 |
|---|---|
| 在哪 | `console.cloud.tencent.com` → **域名注册**（或「域名与网站」）→ **我的域名** |
| 你会看到 | 域名列表里有 `example.com`，右侧有 `管理` / `解析` |
| 你要点 | 进 `管理` → 找 **`DNS 服务器`**（有的版本在「DNS 解析」页顶部，叫「DNS 服务器」或「修改 DNS」）→ 点 **修改** |
| 选什么 | 选择「**使用非腾讯云 DNS / 自定义 DNS**」，把默认的 `swallow.dnspod.net`、`baseball.dnspod.net` **替换成 1.3 抄的两个 CF 名字**（如果原来有第三、第四个，也一并替换或删掉） |
| 确认 | 点保存；腾讯云可能要求**短信 / 微信扫码 / 交易密码**二次确认 —— 这也是你本人 |
| 成功标志 | 腾讯云页面显示 DNS 服务器已变成那两个 `*.ns.cloudflare.com`；域名状态仍是「正常」 |
| 官方步骤 | 腾讯云文档：[修改域名 DNS 服务器](https://cloud.tencent.com/document/product/302/5518) |

**改之前必须知道的三件事**

1. **DNSPod 里原来的解析记录会立刻失效。** 你现在是 0 条，所以没有损失。
   但如果你以后在 DNSPod 上还挂过邮箱（MX）、别的子域名，**先截图抄下来**，之后在 CF 里重建。
2. **改完不是马上生效。** 注册局（`.cn` 是 CNNIC）要刷新，几分钟到 24 小时都正常。
3. **这一步之后，域名解析就归 Cloudflare 管了。** 想反悔就是再改回 DNSPod 的 NS，随时可以。

**失败对照**

| 现象 | 含义 | 怎么办 |
|---|---|---|
| 找不到「修改 DNS」入口 | 腾讯云改版 | 按关键词找：`DNS 服务器`、`DNS 修改`、`自定义 DNS`；或问腾讯云在线客服「怎么把域名 DNS 改成非腾讯云的」 |
| 提示需要实名 / 域名状态异常 | 注册局侧问题 | 按腾讯云提示走完实名（也是你本人）；`.cn` 的实名信息要和域名持有人一致 |
| 保存后过一天还是 Pending | NS 没真的保存，或注册局还没刷新 | 回腾讯云再确认一次；`.cn` 的 NS 刷新可能比其他 TLD 慢（**未验证**） |

---

### 1.5 等 Cloudflare 显示 Active（5 分钟 ~ 24 小时）

| 项 | 内容 |
|---|---|
| 你要做 | 什么都不用做，等。CF 会自己去查你的 NS |
| 怎么确认（**推荐**） | 回 Cloudflare 面板 → 该域名 → **Overview**，状态从 `Pending Nameserver Update` 变成 **`Active`** |
| 怎么确认（辅助） | 手机 4G 打开 `https://example.com/cdn-cgi/trace`，如果返回一段 `fl=... / h=example.com / ip=... / loc=CN` 的纯文本，说明域名已经在 CF 上（这个端点是 CF 边缘自己回的，**不经过你家电脑**；本项目尚未走到这一步，**未验证**） |
| ❌ 不要用 | 本机 `nslookup` / `ping`。实测本机连 `nslookup example.com 223.5.5.5` 都会被代理劫持成 `198.18.x.x`，看不出真假 |
| 超时 | 常见的说法是**最多 24 小时**（社区与早期文档口径，**本文未在 2026 版官方文档里逐字核实**）。超过 24 小时仍是 Pending → 回 1.4 检查 NS 是否真的保存、注册局侧是否还没刷新 |

> 到 Active 为止，**隧道还不用建**。顺序不能反：CF 没接管域名时，
> `cloudflared tunnel route dns` 会直接报「找不到 zone」。

---

## 2 我（AI）能替你做的那一半

### 2.0 分工边界（先说清楚，避免误会）

| 动作 | 谁 | 为什么 |
|---|---|---|
| 下载/解压 cloudflared 到 `D:\cloudflared\` | 我 | 只是放文件，不需要账号 |
| 写 `D:\cloudflared\config.yml` | 我 | 纯本地文件 |
| `cloudflared tunnel login` | 🔴 **你** | 会打开浏览器，要你登录 + 选域名 + 点 **Authorize**；这是账号授权 |
| `cloudflared tunnel create anki` | 我（拿到 `cert.pem` 之后） | 只读不到你的密码，用的是你授权出来的证书 |
| `cloudflared tunnel route dns ...` | 我 | 往**你已经授权**的 zone 里写一条 CNAME |
| `cloudflared tunnel run` 前台试跑 | 我 | 本地进程 |
| `cloudflared service install` | 我跑命令，**你点 UAC 允许** | 装 Windows 服务需要管理员权限 |
| 腾讯云改 NS / CF 注册 / 2FA | 🔴 **你** | 见 §1.4 |

> 一句话：**凡是"代表你向 Cloudflare 授权"的动作，都由你点；凡是"你授权之后的技术活"，我来做。**

### 2.1 装 cloudflared（我做）

**推荐：手动 zip，直接放 `D:\cloudflared\`**（符合"C 盘不堆东西"的红线）

1. 打开 `https://github.com/cloudflare/cloudflared/releases/latest`
2. 下载 **`cloudflared-windows-amd64.exe`**
3. 重命名成 `cloudflared.exe`，放到 `D:\cloudflared\`
4. 验证：

```powershell
D:\cloudflared\cloudflared.exe --version
```

**备选：winget**（省事，但**装到 `C:\Program Files\cloudflared\`**，不在 D 盘）

```powershell
winget install --id Cloudflare.cloudflared -e
where.exe cloudflared        # 用它确认实际装在哪
```

| 项 | 内容 |
|---|---|
| 成功标志 | 打印版本号（形如 `cloudflared version 2026.x.x`） |
| ⚠️ 两条路的 exe 路径不同 | zip → `D:\cloudflared\cloudflared.exe`；winget → `C:\Program Files\cloudflared\cloudflared.exe`。**后面所有命令里的路径按你实际装的位置替换** |
| 装完命令找不到 | 关掉 PowerShell 重开（PATH 要新窗口才生效），或直接用全路径 |
| 为什么不装 C 盘 | 你自己的红线；而且服务模式会往 `C:\Windows\System32\config\systemprofile\` 写配置，路径越短越不容易错 |

### 2.2 🔴 `cloudflared tunnel login`（你点一下）

```powershell
D:\cloudflared\cloudflared.exe tunnel login
```

| 项 | 内容 |
|---|---|
| 会发生什么 | 命令会**自动打开浏览器**（没弹就手动复制终端里打印的 URL）；你登录 CF → **选择 `example.com`** → 点 **`Authorize`** |
| 成功标志 | 终端打印成功；`%USERPROFILE%\.cloudflared\cert.pem` 出现 |
| 这个文件是什么 | 「这台电脑被授权管理这个域名」的凭证，**等同密码**：不要发群、不要截图、不要提交进仓库 |
| 失败 | ① 浏览器没弹 → 复制终端 URL 手动开；② 列表里没有你的域名 → zone 还没 Active，回 §1.5 等；③ 要 2FA → 用你 §1.1 配的验证器 |

> 这一步做完，`cert.pem` 会一直在。以后换电脑才需要重新 login。

### 2.3 建 named tunnel（我做）

```powershell
D:\cloudflared\cloudflared.exe tunnel create anki
D:\cloudflared\cloudflared.exe tunnel list
```

| 项 | 内容 |
|---|---|
| 成功标志 | 打印 **Tunnel ID（UUID）**，并在 `%USERPROFILE%\.cloudflared\` 生成 `<UUID>.json` |
| 我会做 | 把凭据 json 复制一份到 `D:\cloudflared\anki-<UUID>.json`，并把 UUID 记进交付记录 |
| 名字冲突 | 报 `already exists` → 说明以前建过，先 `tunnel list` 看看，别重复建 |
| 注意 | 建完**还没有任何连接**，这是正常的；连接在 §2.5 跑起来时才有 |

### 2.4 写 `D:\cloudflared\config.yml`（我做）

```yaml
tunnel: <TUNNEL-UUID>
credentials-file: D:\cloudflared\anki-<TUNNEL-UUID>.json

ingress:
  - hostname: anki.example.com
    service: http://127.0.0.1:8765
  - service: http_status:404
```

| 项 | 说明 |
|---|---|
| 为什么是 `127.0.0.1` 而不是 `0.0.0.0` | cloudflared 和 8765 在同一台机器上，走回环最稳，也不经过局域网 |
| 最后一行能不能省 | **不能**。官方要求 ingress 必须以一条兜底规则结尾（这里回 404） |
| 校验 | `cloudflared tunnel ingress validate --config D:\cloudflared\config.yml` |
| 常见错 | YAML 缩进**只能用空格**，不能用 Tab；路径里有空格要加引号（本项目路径无空格） |

### 2.5 绑域名（我做）

```powershell
D:\cloudflared\cloudflared.exe tunnel route dns anki anki.example.com
```

| 项 | 内容 |
|---|---|
| 它做了什么 | 在 Cloudflare 的 DNS 里写一条 **CNAME**：`anki` → `<TUNNEL-UUID>.cfargotunnel.com`（橙云=已代理） |
| 成功标志 | 命令成功；CF 面板 → DNS → Records 里能看到这条 CNAME |
| 常见错 | 报找不到 zone → zone 还没 Active；报同名记录已存在 → 去 DNS 页面删掉旧的 `anki` 记录再执行 |

### 2.6 跑起来 + 装机自启（我做，你点 UAC）

**第一步：前台试跑，先确认能连**

```powershell
D:\cloudflared\cloudflared.exe tunnel --config D:\cloudflared\config.yml run anki
```

成功标志：日志出现 `Registered tunnel connection`（通常 4 条连接）。

**如果连不上（一直 `Retrying connection`）**，按这个顺序调，别乱改：

```powershell
# 1) 强制走 TCP，绕开 UDP/QUIC 被运营商干扰
D:\cloudflared\cloudflared.exe tunnel --protocol http2 --config D:\cloudflared\config.yml run anki

# 2) 再不行，强制 IPv4（有些网络 IPv6 是坏的）
D:\cloudflared\cloudflared.exe tunnel --protocol http2 --edge-ip-version 4 --config D:\cloudflared\config.yml run anki
```

**第二步：改成 Windows 服务（这样电脑重启后自动起）**

```bat
:: 管理员 CMD 里执行（路径按你实际安装位置）
cd /d D:\cloudflared
cloudflared.exe service install
```

（PowerShell 里等价写法：`Set-Location D:\cloudflared; .\cloudflared.exe service install`）

> ⚠️ **Windows 服务有个坑（官方文档明写）**：服务是以 `LocalSystem` 身份跑的，
> 它读的配置目录是 `C:\Windows\System32\config\systemprofile\.cloudflared\`，
> **不是**你平时用的 `C:\Users\你\.cloudflared\`。
> 所以要把 `config.yml`、`cert.pem`、`<UUID>.json` 复制过去，否则服务起来就报找不到配置。
> （官方文档示例里把程序放 `C:\Cloudflared\bin`，本项目放 `D:\cloudflared`，位置无所谓，**配置目录必须复制对**。）
> 来源：[Windows 服务文档](https://developers.cloudflare.com/tunnel/features/locally-managed-tunnels/as-a-service/windows/)

```powershell
# 管理员权限执行：把配置搬进服务账号的目录
$src = "D:\cloudflared"
$dst = "C:\Windows\System32\config\systemprofile\.cloudflared"
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item "$src\config.yml" $dst -Force
Copy-Item "$src\anki-<UUID>.json" $dst -Force    # <UUID> 换成真实值
Copy-Item "$env:USERPROFILE\.cloudflared\cert.pem" $dst -Force
```

**验证服务**

```powershell
Get-Service cloudflared | Format-List Name, Status, StartType
# 看日志
Get-Content "C:\Windows\System32\config\systemprofile\.cloudflared\cloudflared.log" -Tail 20
```

> 服务模式默认把日志写到配置目录下的 `cloudflared.log`；
> 若日志为空，先看服务状态是不是 `Running`，再看 Windows 事件查看器 → 应用程序日志。

如果不想动系统服务，也可以用**任务计划程序**（runbook §5.1 有现成写法），效果一样，卸载更干净。

### 2.7 我产出/改动的文件清单

| 文件 | 谁建 | 内容 |
|---|---|---|
| `D:\cloudflared\cloudflared.exe` | 我 | 程序本体 |
| `D:\cloudflared\config.yml` | 我 | 见 §2.4 |
| `D:\cloudflared\anki-<UUID>.json` | 我（从 `%USERPROFILE%\.cloudflared\` 复制） | 隧道凭据，**等同密码** |
| `%USERPROFILE%\.cloudflared\cert.pem` | 🔴 你的 login 生成 | 账号级证书，**等同密码** |
| Windows 服务 / 计划任务 | 我执行，你点 UAC | 开机自启 |

**我不会碰**：`tools/cardback-editor/server.py`、`.cardback_token`、App 代码、README/HANDOFF。

---

## 3 关键风险与事实核查

> 下面每条都写清「结论 / 依据 / 置信度」。**标「未验证」的，请不要当成事实用。**
> 所有链接访问于 2026-11-09。

### 3.1 `.cn` 域名 + Cloudflare 免费档，能不能正常托管？

**结论：能。** 加站点、改 NS、拿通用证书这套流程和 `.com` 没有区别；
Cloudflare 免费档不因为 TLD 是 `.cn` 而拒绝（我没有找到任何官方"不支持 .cn"的说明）。

| 项 | 内容 |
|---|---|
| 依据（官方） | Cloudflare 的要求只有一条：域名的 NS 必须指向它分配的那两个。见 [Update nameservers](https://developers.cloudflare.com/dns/nameservers/update-nameservers/) |
| 依据（实践） | 腾讯云买的域名交给 CF 托管的完整流程（改 NS → 面板变 Active）：[wanna-code 博客](https://wanna-code.com/archives/019bdb86-815c-75ce-a92c-727f42919b3b)；国内域名接 CF 的步骤说明：[CSDN](https://blog.csdn.net/zweizhao/article/details/143790827) |
| 置信度 | **中**。社区实操可行；但 Cloudflare 官方没有 `.cn` 专项文档，**未验证**极端情况（例如注册局延迟、TLD 被风控） |
| 真正的风险不在"能不能加" | 而在"加上之后国内访问稳不稳"——见 3.3 |

**两个容易误会的点**

- **Cloudflare Registrar 不支持 `.cn` 的注册/转入**，但这不影响你把域名**托管**到 CF。域名继续留在腾讯云，只是 NS 换掉。
- `.cn` 的注册局是 CNNIC，NS 刷新可能比 `.com` 慢一些（**未验证**，但遇到"改了 NS 半天不 Active"先别慌）。

---

### 3.2 备案：`.cn` 域名 + 家宽开 Web 服务，到底什么风险？

这是整份文档里**最需要说清楚**的一段。分三层看，别混在一起。

#### 第一层：法律文本怎么说

Cloudflare 自己的文档写得很直接：

> "To operate a website in China, you need government permission called an Internet Content Provider (ICP) number...
> **This applies whether the site is hosted on a server in Mainland China or delivered to visitors in China through a CDN.**"
> —— [Cloudflare: Internet Content Provider (ICP)](https://developers.cloudflare.com/china-network/concepts/icp/)

也就是说，按字面：**只要你的网站面向中国访客，就需要 ICP**，哪怕服务器在境外、走 CDN。

#### 第二层：备案实务上是什么口径

备案（ICP 备案）是**绑定"境内接入商"**的：你要有境内服务器/境内 CDN，才能提交。
Cloudflare 不是境内接入商，所以你**根本没有提交工信备案的通道**。

阿里云的官方 FAQ 说得很具体：

> "如域名解析指向服务器为**非中国内地服务器**，则**无需对网站进行工信备案**，但是**需要进行公安备案**。"
> —— [阿里云：未备案域名解析至不同地区是否可以访问](https://help.aliyun.com/zh/icp-filing/not-for-the-record-dns-can-access-to-different-areas)

#### 第三层：落到你这个场景

| 问题 | 结论 | 说明 |
|---|---|---|
| 要不要工信备案（ICP） | **技术上办不了，实务上不需要** | CF 免费档的节点在境外（香港/日本/新加坡/美西等）。CF 的中国大陆节点属于 China Network（京东云合作），是企业级付费产品，还要求 ICP —— 见 [China Authoritative DNS](https://developers.cloudflare.com/china-network/concepts/china-dns/) |
| 要不要公安备案 | **形式上要**（通常要求开通后 30 日内，具体以当地网安为准，**未验证**） | 阿里云 FAQ 明确写了这一条；个人自用服务现实中很少有人去办 |
| 家宽 80/443 会不会被封 | **会，但这条基本绕开了** | 运营商封家宽**入站** 80/443 是普遍现象（社区经验，**未找到运营商官方文件**）。Tunnel 的方案是：**电脑只对外发起出站连接，不监听任何公网端口**，所以没有"入站端口"可被封 |
| Tunnel 是不是"规避备案" | **它规避的是端口检测和接入商检测，不是法律义务** | 别把它当合规方案。它只是让"技术上不需要备案"这件事成立 |
| 实际被查概率 | **低，但不能承诺** | 个人、token 鉴权、不公开索引、流量极小、没有内容分发 —— 和"公开网站"不是一回事。但风险不为零，且这不是我能替你承担的 |

**所以文档层面的建议（不是法律意见）**

1. **不公开**：不发朋友圈/群/社交平台，不做 SEO，不加 sitemap，不让搜索引擎收录。
2. **不做大流量内容分发**：只同步你自己的卡背 JSON，不拿它当网盘/图床/视频站。
3. **随时能下线**：真要整改，停掉隧道就回到"只能同 WiFi"，本地数据一点不受影响。
4. 如果哪天你想把它当**长期对外的公开服务**，正确路径不是硬扛，而是：换非 `.cn` 域名 + 境外托管，或者老老实实备案。

---

### 3.3 `trycloudflare.com` 在国内的连通性 vs named tunnel

#### Quick Tunnel（临时隧道）官方定位

| 限制 | 官方原文/数值 | 对你的意义 |
|---|---|---|
| 用途 | "Quick Tunnels are for **testing and development**. For production, create a Cloudflare Tunnel." | 只配当探针，不配当日常 |
| 并发 | 每个 Quick Tunnel **最多 200 个 in-flight 请求**，超出返回 **429** | 单手机够用，但它不是给生产用的 |
| 可用性 | **没有 uptime 保证** | 断了就是断了 |
| 域名 | 每次启动都换一个随机 `*.trycloudflare.com` | 手机要反复改地址 |
| SSE | 不支持 | 本项目用不到 |
| 来源 | [Quick Tunnels 文档](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) | — |

#### 国内连通性：诚实的答案是「未验证」

| 说法 | 状态 |
|---|---|
| `trycloudflare.com` 在中国大陆被墙 / 被限速 | **未验证**。GreatFire 上没有该域名的测试记录（[GreatFire](https://en.greatfire.org/trycloudflare.com)），我也没找到运营商层面的公开证据 |
| Quick Tunnel 域名"名声不好"，可能被安全软件/企业网络拦 | **有间接证据**：`trycloudflare.com` 被大量用于钓鱼，公开威胁情报里有收录（[PhishStats](https://phishstats.info/search/16442fd02565d10da3cb4334955f13407760370de00678bf904ac5b29974e180)） |
| 国内直连 Cloudflare 免费节点，稳定性因运营商而异 | **社区普遍经验**。国内教程要专门做「优选 IP / 分线路」才能把延迟压下来（[CSDN：CF Tunnel + 分线路优选 IP](https://openeuler.csdn.net/6a2d579c10ee7a33f27c632d.html)），侧面说明**默认线路不好** |

**结论**：Quick Tunnel 只用来做**一次 5 分钟的探针**（验证"隧道→8765→token→App"这条链路），
探针通过就立刻转 named tunnel。**不要拿它当日常**，也不要因为它慢就判定"方案不行"。

#### Named tunnel 走 7844，是不是更稳？

| 事实 | 内容 |
|---|---|
| 端口 | cloudflared 出站连 Cloudflare **7844**；`quic` 走 **UDP**，`http2` 走 **TCP** |
| 官方要求 | 防火墙必须放行 7844 的 **TCP 和 UDP**。见 [Tunnel with firewall](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-with-firewall/) |
| 协议选择 | `auto`（默认，能 UDP 就用 quic，不能就回落 http2）、`quic`、`http2`。见 [run parameters](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/configure-tunnels/cloudflared-parameters/run-parameters/) |
| 国内现实 | QUIC（UDP 7844）在国内被 QoS/干扰的概率**高于** TCP。所以"更稳"的顺序是：**先 auto 试 10 分钟 → 不稳就 `--protocol http2` → 再不行加 `--edge-ip-version 4`** |
| 为什么 named 比 quick 稳 | 不是端口不同，而是：固定域名、固定凭据、可以装服务自启、有 4 条连接、CF 侧有 Healthy 状态可看 |

> ⚠️ 「7844 比 443 更隐蔽所以不会被墙」这种说法是**推测，未验证**。
> 官方文档只保证 7844；如果你的网络把 7844 也掐了，那就只能走 §4 的回退。

---

### 3.4 免费档的限制，够不够 57 KB 的 JSON 同步 + 重写轮询？

**结论：够，而且余量大到可以忽略。** 真正的瓶颈是线路质量和你电脑是否常开，不是额度。

| 限制项 | 免费档 | 你的用量 | 余量 |
|---|---|---|---|
| Tunnel 本身 | **所有套餐都可用**（[Tunnel 概览](https://developers.cloudflare.com/tunnel/) 写的 "Available on all plans"） | 1 条隧道 | — |
| 请求体/上传大小 | **100 MB**（Free/Pro 都是 100 MB，见 [Error 413 文档](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/4xx-client-error/error-413/)） | 57 KB | **约 1800 倍** |
| WebSocket | **所有套餐都支持**（[WebSockets 文档](https://developers.cloudflare.com/network/websockets/)） | 本项目走普通 HTTP GET/POST，不依赖 WS | — |
| 并发 in-flight | Quick Tunnel 200；named tunnel 没有这个限制（官方没写） | 1 台手机 | — |
| 带宽 / 流量 | **官方没有公布硬上限**（我没找到数值，**未验证**）；按公平使用 | 见下表 | 很大 |
| 请求速率 | 免费档没有公布的 QPS 上限；异常突发可能触发 CF 的风控/挑战 | 低频轮询 | 很大 |

**流量测算（按 57 KB/次）**

| 轮询频率 | 每天请求数 | 每天流量 | 每月流量 |
|---|---|---|---|
| 每 30 秒 | 2,880 | ≈ 164 MB | ≈ 4.9 GB |
| 每 1 分钟 | 1,440 | ≈ 82 MB | ≈ 2.5 GB |
| 每 5 分钟 | 288 | ≈ 16 MB | ≈ 0.5 GB |
| 每小时 | 24 | ≈ 1.4 MB | ≈ 41 MB |

> 实测参考：当前 `GET /cardback.json` 实际只有 **1,895 字节**（§5.4），
> 所以上面的测算是按你说的 57 KB 上限做的**保守估计**，真实用量还要小一个数量级。
>
> 唯一要注意的是**别把轮询做到秒级**：App 的重试必须是指数退避（1.4.7 已实现），
> 否则隧道挂掉时手机端会把请求堆成一串 429。

---

## 4 回退顺序：三档，每档都有明确的触发条件

原则：**每一档都不改 App 协议**（还是 `base#token`），只是换 base 地址；
而且每一档都保留"回得去"的能力。

### 档 0：先调参，别急着换方案

| 触发条件（可观测） | 动作 |
|---|---|
| 4G 下持续 `1033 / 530`，但电脑上 cloudflared 日志有 `Registered tunnel connection` | 不是隧道问题，是 CF→你 的回源问题：查 `config.yml` 的 `service` 是否是 `http://127.0.0.1:8765`，8765 是否在跑 |
| 日志一直 `Retrying connection` / `precheck` 全红 | `--protocol http2`；再加 `--edge-ip-version 4` |
| 手机 4G 打开域名超时/TLS reset，但 CF 面板 Active | 换手机网络（联通/电信卡）复测，判断是不是单一运营商到 CF 边缘不通 |

### 档 1：named tunnel 不通 → Tailscale Funnel

| 项 | 内容 |
|---|---|
| 触发条件 | 调参后仍连续 **> 30 分钟** 无法在 4G 下拿到 200；或 `1033` 反复出现 |
| 动作 | 电脑装 Tailscale（手机**不用装**）→ 开 Funnel → 得到一个固定的 `*.ts.net` 地址 → App 里换成 `https://xxx.ts.net#token` |
| 代价 | 多一个账号/客户端（只在电脑侧）；地址是 `ts.net` 不是你的域名；国内连通性**未验证** |
| 还能回去吗 | 能。Tailscale 不占 8765，也不改 DNS；随时关掉 |

### 档 2：局域网 + 手动切地址

| 项 | 内容 |
|---|---|
| 触发条件 | 上面两档都不稳，或者你只是偶尔在外面用一下 |
| 动作 | App 里保留「上次成功地址 + 候选地址」，在家用 `http://<局域网IP>:8765#token`，在外面就认命 |
| 代价 | 只能同 WiFi；电脑 IP 变了要重填（DHCP 老问题） |
| 还能回去吗 | 这已经是兜底，不需要回退 |

> **顺序不要跳**：档 1 是在档 0 调参无效之后才用。
> 很多"CF 不通"其实是 QUIC 被掐，换成 `http2` 就好了，不必换方案。

---

## 5 验收：一条命令分辨「隧道没通」还是「鉴权坏了」

### 5.1 先把链路拆成 4 层，别用一个状态码猜所有事

```
[L0 DNS/TLS]      [L1 CF 边缘]        [L2 cloudflared]      [L3 8765 + token]
 域名解析到 CF  →  CF 找得到隧道  →  连得上本机 8765  →  无 token 401 / 有 token 200
```

| 层 | 坏了会看到 | 典型原因 |
|---|---|---|
| L0 | 超时 / TLS reset / `000` | 域名没 Active、国内到 CF 边缘不通 |
| L1 | **1033 / 530** | cloudflared 没跑、隧道没连上、凭据失效 |
| L2 | **502 / 521** | 8765 没起、`config.yml` 的 service 写错 |
| L3 | **无 token 也 200** | 🔴 鉴权坏了（`_is_local()` 误判），必须停 |

官方对 1033 的定义：「Cloudflare 找不到健康的 cloudflared 实例来接这个请求」
（[Error 1033](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1033/)）。

### 5.2 命令矩阵（照抄就行）

**第 0 步：连通性（只看"到没到应用"）**

```powershell
curl.exe -I https://anki.example.com/health
```

| 你看到的 | 结论 |
|---|---|
| `HTTP/2 501` + `server: CardbackEditor/1.4.2` | ✅ **隧道通、回源通、应用在**。501 是应用自己回的（它不支持 HEAD，§5.4 实测） |
| `HTTP/2 1033` 或 `530` | ❌ 隧道没通（L1） |
| `HTTP/2 502` / `521` | ❌ 隧道通，但回源失败（L2） |
| 超时 / `curl: (35)` TLS 错 | ❌ 到 CF 边缘就不通（L0） |

> ⚠️ **不要用 `-I` 判断鉴权**。HEAD 请求在 8765 上直接 501，**根本没走到鉴权那一步**（我实测过，见 §5.4）。
>
> 另一个小概率情况：如果 Cloudflare 或中间层把 HEAD 改写成 GET 转发，
> 你会看到 `200`（带了 cookie/token）或 `401` —— **那同样说明"隧道通、应用在"**，
> 只是"501 这个特征"不成立了。判断隧道通不通，看的是**有没有拿到应用自己的响应**，不是固定哪个码。

**第 1 步：鉴权（必须用 GET）**

```powershell
# 不带 token —— 公网必须是 401
curl.exe -s -o NUL -w "%{http_code}`n" https://anki.example.com/health

# 带 token —— 必须是 200
$t = (Get-Content "D:\deepseek harness\work_anki_enligish\tools\cardback-editor\.cardback_token" -Raw).Trim()
curl.exe -s -o NUL -w "%{http_code}`n" -H "X-Cardback-Token: $t" https://anki.example.com/health
```

**期望输出**

```
401
200
```

401 的响应体应该长这样（本机实测原文）：

```json
{"ok": false, "error": "token_required", "hint": "核对口令"}
```

**第 2 步：看内容对不对（带 token）**

```powershell
curl.exe -s -H "X-Cardback-Token: $t" https://anki.example.com/health
```

期望能看到 `"protocol": 1`、`"capabilities": ["rewrite","feedback","sync_push","sync_cursor"]`、
`"version"`、`"started_at"`、`"pid"`、`"port": 8765`。

### 5.3 一张表定性（抄下来贴墙上）

| 第 0 步（`-I`） | 第 1 步（无 token GET） | 第 1 步（带 token GET） | 结论 | 下一步 |
|---|---|---|---|---|
| 501 | 401 | 200 | ✅ 全通 | 去手机 4G 实测 |
| 1033 / 530 | 401/200 都拿不到 | — | 隧道没通 | 看 cloudflared 窗口/`tunnel list` 是否 Healthy |
| 502 / 521 | 522/超时 | — | 回源失败 | 8765 在不在跑；config 的 service |
| 501 | **200** | 200 | 🔴 **鉴权坏了** | 立刻停，查 `server.py:863` 的 `_is_local()` |
| 超时 | 超时 | 超时 | 到 CF 边缘不通 | 换网络复测；试 http2 |
| 200，但内容不是 `/health` 的 JSON | — | — | 命中了别的记录 / CNAME 指错 | CF → DNS 检查 `anki` 那条记录 |

> **为什么"无 token 401"这么重要**：服务端规则是「只有 loopback 且**没有** `CF-Connecting-IP` 才免票」
> （`tools/cardback-editor/server.py:863`）。一旦请求从公网进来，CF 一定带 `CF-Connecting-IP`，
> 所以必须 401。如果公网无 token 还能 200，说明这条规则失效了 —— 那是安全事故，不是配置问题。

### 5.4 本机实测记录（2026-11-09 20:39，只读探测）

这些是我在**本机** `127.0.0.1:8765` 上跑出来的，用来校准"期望值"，**不代表公网已经通**：

| 请求 | 结果 | 说明 |
|---|---|---|
| `GET /health`（本机，无 token） | **200** | loopback 免票，符合设计 |
| `GET /health` + `CF-Connecting-IP: 1.2.3.4`（无 token） | **401** + `token_required` | **模拟公网**：这条证明鉴权逻辑是好的 |
| `GET /health` + `X-Cardback-Token` | **200** + JSON（`protocol=1`、4 项 capabilities） | 正常 |
| `HEAD /health` | **501** `Unsupported method ('HEAD')` | 服务端没实现 HEAD；所以 `curl -I` 只能测"到没到应用" |
| `GET /nope` | **404** | 兜底正常 |
| `GET /cardback.json`（带 token） | **200**，**1,895 字节** | 比 57 KB 的估算小得多 |

**本机对照命令（不经过隧道，用来排除"是 App 问题还是隧道问题"）**

```powershell
# 模拟"来自公网"：加一个 CF-Connecting-IP 头，应该 401
curl.exe -s -o NUL -w "%{http_code}`n" -H "CF-Connecting-IP: 1.2.3.4" http://127.0.0.1:8765/health
```

### 5.5 手机端验收（最终标准）

| # | 动作 | 通过标准 |
|---|---|---|
| 1 | 手机**关 WiFi 用 4G**，App 里填 `https://anki.example.com#<token>` | 能拉到卡背 |
| 2 | 提交一次反馈 / 触发一次重写 | 电脑端能看到，且 App 不报错 |
| 3 | 故意把 token 改错一位 | 手机报 401，**且不会疯狂重试**（指数退避生效） |
| 4 | 电脑重启（不手工点任何东西） | 手机仍能用 |
| 5 | 关掉 cloudflared 进程 | 手机在 X 秒内显示"连不上"，而不是卡死 |

> 1–5 全过，才算"远程同步真的可用"。只过 1 不算。

### 5.6 三个最容易误判的坑

| 坑 | 真相 |
|---|---|
| `ping anki.example.com` 得到 `198.18.0.180`，以为通了 | 那是**代理的假 IP**。连 `nslookup ... 223.5.5.5` 在本机都会被劫持（我实测 `argotunnel.com` 也返回 `198.18.x.x`） |
| 本机 `curl http://127.0.0.1:8765/health` 返回 200，以为公网也 200 | loopback 免票。公网必须 401 |
| 用 `curl -I` 看到 401/200 就下结论 | HEAD 在 8765 上直接 501，走不到鉴权；必须用 GET |

---

## 6 仍未验证 / 需要你拍板

### 6.1 本文里「未验证」的项（别当事实用）

| # | 项 | 现状 |
|---|---|---|
| 1 | `trycloudflare.com` 在中国大陆是否被墙/限速 | **未验证**，无权威来源；GreatFire 无记录 |
| 2 | `.cn` 在 Cloudflare 免费档是否有隐藏限制 | **未验证**，没找到官方说明；社区实操可行 |
| 3 | 家宽入站 80/443 被运营商封锁 | 社区普遍经验，**未找到运营商官方文件** |
| 4 | Cloudflare 免费档的带宽硬上限 | **未找到官方数值**；官方只写"所有套餐可用" |
| 5 | 腾讯云 `.cn` 改 NS 的实际生效时间 | **未验证**；官方口径是最多 24h，CNNIC 可能更慢 |
| 6 | `/cdn-cgi/trace` 在 tunnel 主机名上的行为 | **未验证**（CF 标准端点，但本项目没走到这一步） |
| 7 | Tailscale Funnel 在国内的连通性 | **未验证** |
| 8 | 4G 下到 CF 边缘的可达性 | **本机测不了**（有代理），必须手机实机测 |
| 9 | 公安备案的具体时限/流程 | 只知"需要"，细节**未验证** |

### 6.2 要你拍板的事

1. **改 NS 的时间窗**：改完到 Active 之间，`example.com` 上如果有别的服务会受影响。你现在是 0 条解析，理论上无损——确认一下有没有邮箱/别的子域名挂在 DNSPod。
2. **2FA 现在配还是以后配**：现在配，后面 login 不卡；不配也能走，但账号安全性差。
3. **开机自启用系统服务还是任务计划**：服务更稳但卸载麻烦（还要往 `systemprofile` 复制配置）；任务计划更好删。runbook 推荐任务计划。
4. **要不要先跑一次 Quick Tunnel 探针**：5 分钟，能提前知道"你家网络到 CF 通不通"，避免改完 NS 才发现线路不通。**建议跑。**

---

## 7 参考来源（访问于 2026-11-09）

**Cloudflare 官方**

- [Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) — 200 in-flight / 无 uptime 保证 / 仅测试用
- [Cloudflare Tunnel 概览](https://developers.cloudflare.com/tunnel/) — "Available on all plans"
- [Tunnel with firewall](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-with-firewall/) — 出站 7844 TCP/UDP
- [cloudflared run parameters](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/configure-tunnels/cloudflared-parameters/run-parameters/) — `--protocol` / `--edge-ip-version`
- [Create a locally-managed tunnel](https://developers.cloudflare.com/tunnel/features/locally-managed-tunnels/create-local-tunnel/) — login / create / route dns 的官方步骤
- [Windows 服务](https://developers.cloudflare.com/tunnel/features/locally-managed-tunnels/as-a-service/windows/) — systemprofile 配置坑
- [Configuration file](https://developers.cloudflare.com/tunnel/features/locally-managed-tunnels/configuration-file/) — ingress + 兜底规则
- [Update nameservers](https://developers.cloudflare.com/dns/nameservers/update-nameservers/)
- [Error 413](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/4xx-client-error/error-413/) — 免费档上传 100 MB
- [WebSockets](https://developers.cloudflare.com/network/websockets/) — 所有套餐支持
- [Error 1033](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1033/) — 隧道未连接
- [ICP 概念](https://developers.cloudflare.com/china-network/concepts/icp/) — "hosted in Mainland China **or delivered through a CDN**"
- [China Authoritative DNS](https://developers.cloudflare.com/china-network/concepts/china-dns/) — 中国节点走京东云，属 China Network

**国内合规 / 注册商**

- [阿里云：未备案域名解析至不同地区是否可以访问](https://help.aliyun.com/zh/icp-filing/not-for-the-record-dns-can-access-to-different-areas) — 非境内服务器无需工信备案，但需公安备案
- [腾讯云：修改域名 DNS 服务器](https://cloud.tencent.com/document/product/302/5518)

**社区实践（非官方，仅作参考）**

- [腾讯云域名交给 Cloudflare 托管（含改 NS 截图路径）](https://wanna-code.com/archives/019bdb86-815c-75ce-a92c-727f42919b3b)
- [国内域名如何与 Cloudflare 关联](https://blog.csdn.net/zweizhao/article/details/143790827)
- [Cloudflare Tunnel + 分线路优选 IP（说明默认线路需优化）](https://openeuler.csdn.net/6a2d579c10ee7a33f27c632d.html)
- [trycloudflare 钓鱼滥用记录（PhishStats）](https://phishstats.info/search/16442fd02565d10da3cb4334955f13407760370de00678bf904ac5b29974e180)

---

## 附：命令速查（全程 `curl.exe`，别用 PowerShell 的 `curl` 别名）

```powershell
# ── 验收 ─────────────────────────────────────────────
curl.exe -I https://anki.example.com/health                 # 只看"到没到应用"：501=通了
$t = (Get-Content "D:\deepseek harness\work_anki_enligish\tools\cardback-editor\.cardback_token" -Raw).Trim()
curl.exe -s -o NUL -w "%{http_code}`n" https://anki.example.com/health                       # 期望 401
curl.exe -s -o NUL -w "%{http_code}`n" -H "X-Cardback-Token: $t" https://anki.example.com/health  # 期望 200

# ── 本机对照（排除 App 问题）────────────────────────
curl.exe -s -o NUL -w "%{http_code}`n" -H "CF-Connecting-IP: 1.2.3.4" http://127.0.0.1:8765/health   # 期望 401

# ── cloudflared ──────────────────────────────────────
D:\cloudflared\cloudflared.exe tunnel login
D:\cloudflared\cloudflared.exe tunnel create anki
D:\cloudflared\cloudflared.exe tunnel route dns anki anki.example.com
D:\cloudflared\cloudflared.exe tunnel --config D:\cloudflared\config.yml run anki
D:\cloudflared\cloudflared.exe tunnel --protocol http2 --edge-ip-version 4 --config D:\cloudflared\config.yml run anki
```

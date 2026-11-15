# 评审包说明（review/）

本目录是给外部 AI 做「架构 + 前端界面」评审的补充材料，全部来自 1.4.7.2 验收截图与美学原型，不含密钥/个人数据。

## screens/ —— 界面截图（14 张）

| 文件 | 页面 / 说明 |
|---|---|
| `01-launch-home.png` | 首页：启动后主界面 |
| `02-learning-front.png` | 学习页 · 认词卡正面（单词 + 选项） |
| `03-card-back-1.png` | 学习页 · 卡背（释义、记忆思路、搭配、例句） |
| `04-card-back-bottom.png` | 学习页 · 卡背底部操作区 |
| `05-recognition-1of20.png` | 认词模式（20 词一组，进度 1/20） |
| `06-mode-menu.png` | 学习模式选择菜单 |
| `07-spelling.png` | 拼写页 |
| `08-spelling-keyboard-after.png` | 拼写页弹键盘后（1.4.7.2 键盘顶栏修复相关） |
| `09-cardback-before-rewrite.png` | 卡背内联重写：触发前 |
| `10-cardback-after-rewrite.png` | 卡背内联重写：结果展示 |
| `11-after-restart.png` | 杀进程重启后的状态恢复 |
| `12-library.png` | 词库页 |
| `13-profile.png` | 我的（设置）页 |
| `14-profile-sync-section.png` | 设置页 · 同步设置区 |

## prototypes/ —— 三套界面风格原型（HTML，无外部依赖）

| 文件 | 方案 |
|---|---|
| `index.html` | 三套风格方向对比导航页 |
| `proto-A.html` | A ·「纸页」Quiet Paper：米白纸、墨黑字、朱砂红，纸张物理动效 |
| `proto-B.html` | B ·「弹跳」Bouncy Play：活泼弹跳动效 |
| `proto-C.html` | C ·「夜读」Mono Night：夜间/等宽风格 |

手机画布 390×844，可直接在浏览器打开；翻卡 → 点「不认识/模糊/认识」看反馈 → 底部切页。

## 阅读顺序建议

1. 先读 `REVIEW-BRIEF.md`（项目、结构、构建、问题清单）。
2. 再看 `screens/` 了解现状界面。
3. 对照 `prototypes/` 三套方向评估「更好看/更顺手」的落地建议。

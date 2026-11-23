# v1.5.3v2 基线定妆照（开工前留档）

- 仓库基线：`main` @ `7e71f51`（v1.5.3 / vc30 / Room 26），工作树 clean。
- 拍摄设备：`mf` 模拟器（emulator-5554，API34，1080×2340）。
- 拍摄包：`com.kaoyan.wordhelper.dev` v1.5.3（dev release，签名与仓库当前构建一致，versionCode=30）。
- 拍摄时间：2026-11-23 09:50–09:57（Asia/Shanghai）。
- 目的：完工后对比验证「除本轮约定改动外，既有正面卡与背词主流程保持不变」。

| 文件 | 内容 | 状态说明 |
|---|---|---|
| `01_front_card.png` | legitimate 正面卡 | 背词主流程正面，无改动 |
| `02_back_top.png` | legitimate 卡背顶部（词性/释义开头） | 背词主流程背面 |
| `03_back_mid.png` | legitimate 卡背中部（搭配+例句） | 背词主流程背面 |
| `04_back_bottom.png` | legitimate 卡背底部（操作区：重写思路/有帮助/没帮助/编辑/锁定 + 三段胶囊） | 背词主流程背面 |
| `05_after_rewrite_gray_skip.png` | legitimate 重写完成后 DONE 状态 | **v1.5.3 bug 现场**：右下角灰色「跳过」占坑，底部三段胶囊被挤窄（模块 C-1 修复目标） |

- 「历史 DONE 无跳过」场景：appropriate 词（DONE，rev=25），以同日 09:35 拍摄的
  `G:\我的云端硬盘\Agent-Inbox\GPT-Handoff\work_anki_enligish\WAE-20261123-001\screenshots\v153_no_skip_appropriate.png`
  为准（同设备、同包、1080×2340，右下角无跳过按钮，三段胶囊铺满）。

# 1.4.3.2 规格与验收（2026-11-08）

## 一、这一版做了什么

1. **重写条改成竖排整行**（`ui/screen/RewriteBar.kt`）
   - 第一行：`重写思路 / 再重写一次` 按钮，整行宽（`fillMaxWidth`，不再 `weight(1f)`）；
   - 第二行：模型下拉，整行宽；
   - 第三行：输入框常显（1–4 行，占位「哪里有问题？留空＝整段重写」）；
   - 有标记时：进度条 + 状态行（DONE 才有「撤销」）。
   - 目的：按钮文字不再被模型下拉挤成一行短句。

2. **重写条进卡背滚动内容**（`ui/screen/LearningScreen.kt`）
   - `CardBackBlocks` 新增 `backActions` 槽，渲染在正文块的最后一个，随 `verticalScroll` 一起滚；
   - 删除原来固定贴卡片底边的 `bottomActions`；
   - 卡正面不显示，也不占高度。

3. **学习记录一次性清空**（`data/repository/WordRepository.kt` + `SettingsRepository.kt`）
   - 清表：`tb_progress`、`tb_early_review_ref`、`tb_new_word_ref`、`tb_daily_stats`、`tb_study_log`、`word_ml_stats`、`training_samples`；
   - 同时清「今日新词计划」，让当天重新排；
   - 只在升级后第一次启动执行一次，DataStore 标记 `cleanup_132_done`；**不可逆**。

4. **20 词测试书**（`assets/test20_cards.json` + `CardSeedLoader.loadTest` + `WordRepository.ensureTestDeck`）
   - 从六级记忆卡抽 20 词（保留记忆思路），书名「测试·20词」；
   - 只建书、**不切换当前学习书**；导入过一次就记版本号，用户删了不会复活。

## 二、边界与不做

- 完整词库 / 完整词库（精选）**没有删**（用户决定先不删）。
- 生词本仍在，但这次的记录清理会把「加进生词本的引用」一并清空（用户同意）。
- 不发 release、不装真机、不提交 git（用户冻结项）。

## 三、验收（轻量，按用户要求只做冒烟）

| 项 | 结果 |
| --- | --- |
| 升级安装（vc7 → vc8） | 通过，`versionCode=8 / versionName=1.4.3.2` |
| 冷启动 | 通过，无崩溃 |
| 卡正面 | 无重写条 |
| 卡背 | 滚到底可见竖排重写区：按钮整行 `[110,1436][970,1546]`、模型整行 `[110,1585][970,1647]`，文字没有被挤 |
| 记录清理 | `tb_progress=0`、`tb_daily_stats=0`、`tb_new_word_ref=0` |
| 测试书 | `tb_book` 出现 `测试·20词 / type=1 / total=20 / is_active=0`，当前书仍是六级记忆卡 |

## 四、包信息

- `dist/english-app-1.4.3.2.apk`
- 33,139,773 B
- md5 `e3bd6b57c4fb9a0016aac6ccb5be6183`
- `versionCode 8 / versionName 1.4.3.2`
- 下载：`http://<电脑 IP>:18795/english-app-1.4.3.2.apk`

## 五、已知限制

- 竖排后重写区约占 290px，卡背正文需要滚一下才能看到重写区（这是「随卡背滚」的代价）。
- 清记录不可逆；只执行一次。

# W6-A 词包确定性扫描工具

只读扫描 `cet6_cards.json`，不调用模型、不修改词包或生产数据。纯 Python 标准库。

## 用法

```bash
python tools/word-audit/scan.py \
  --cards app/src_repo/app/src/main/assets/cet6_cards.json \
  --out _recon/1.4.7.2/W6/scan.json \
  --md _recon/1.4.7.2/W6/scan-report.md

python tools/word-audit/review.py \
  --scan _recon/1.4.7.2/W6/scan.json \
  --out _recon/1.4.7.2/W6/review-list.md \
  --json _recon/1.4.7.2/W6/review-list.json \
  --per-category 8
```

`scan.py` 的 `--feedback` 可重复传入 JSONL/JSON；默认自动读取
`tools/rewrite-service/data/feedback.jsonl` 与 `_recon/*/feedback.jsonl`。
`--book-id` 默认 `1`（六级记忆卡），其他书的反馈不参与点赞保护。

## 输出

- `scan.json`：每个词一条记录，含
  `word/book/lineNo/category/severity/evidence/protected/status/priority`，
  以及 `issues/lineChecks/metrics/protection/history/source`。
- `scan-report.md`：5228 词扫描计数、规则口径、P0/P1 样本、点赞保护、
  语义队列、耗时口径与未覆盖项；同时列原始文件 hash 与规范化文本 hash。
- `review-list.md` / `review-list.json`：P0 全量、P1 索引与按正文行数
  分层的样本、P2 格式/语义样本、40 个 `status=ok` 正常词分层样本、点赞保护与冲突；
  供人工或后续模型审查。Markdown 截断处标注「完整列表见 JSON」。
- `revision-plan.json`：W6-B 定向修订队列，默认收 `history_down_current` 与
  `history_conflict`（当前正文被点踩/同文赞踩冲突），逐条保留旧正文、问题、
  历史反馈、保护标记与 `pending -> candidate -> approved/rejected` 状态字段；
  由 `plan_revisions.py` 生成，不调用模型、不改词包。

## 规则边界

确定性规则覆盖：空词/重复、释义与词性缺失、词性未分行、正文行数、末行箭头、
倒数第二行旁注、中间箭头、内部术语、元素/编者标记、整卡无英文线索、超长行、
超长词、编号连续性、占位文本、历史 up/down 与当前正文忽略空白差异的匹配。

语义判断不在本阶段下结论：谐音是否过音标、字形拆分是否自然、词义是否真正对应、
词根词源是否真实、用户历史 instruction 是否满足。详见扫描报告第 8 节。

## 测试

```bash
python -m unittest discover -s tools/word-audit/tests
```

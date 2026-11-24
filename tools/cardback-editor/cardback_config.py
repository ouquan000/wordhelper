"""卡背配置的默认值、校验、历史版本与原子发布。"""

from __future__ import annotations

import json
import math
import os
import shutil
import time
from datetime import datetime


def _now():
    return datetime.now().astimezone()


def mark_process_start(ctx):
    """真正开始服务时对一次表：import 到 main() 之间还隔着参数解析，别把那段算进 uptime。"""
    ctx._STARTED_AT = datetime.now().astimezone()
    ctx._STARTED_MONO = time.monotonic()


def _iso(dt):
    return dt.isoformat(timespec="seconds")


def _is_int(value):
    # bool 是 int 的子类，得排掉，不然 true 会被当成 1
    return isinstance(value, int) and not isinstance(value, bool)


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _show(value):
    try:
        text = json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        text = repr(value)
    return text if len(text) <= 40 else text[:40] + "…"


def _load_json(path):
    """读 JSON；不存在/坏了都返回 None，不抛异常。"""
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def _atomic_write_json(path, data):
    """先写 .tmp 再 os.replace，避免手机读到写了一半的文件。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)


def _parse_stamp(stamp):
    try:
        return datetime.strptime(stamp, "%Y%m%d-%H%M%S").astimezone()
    except ValueError:
        return None


def default_config(ctx):
    blocks = []
    for block_id, block_type, title in ctx.DEFAULT_BLOCKS:
        blocks.append(
            {
                "id": block_id,
                "type": block_type,
                "visible": True,
                "title": title,
                "style": None,
            }
        )
    return {
        "schemaVersion": 1,
        "book": "六级记忆卡",
        "revision": 1,
        "updatedAt": ctx._iso(ctx._now()),
        "spacing": 12,
        "motion": dict(ctx.DEFAULT_MOTION),
        "blocks": blocks,
    }


def validate_config(ctx, data):
    """返回 (errors, warnings)。errors 非空就是 400。"""
    errors = []
    warnings = []

    # 0. 顶层必须是个对象
    if not isinstance(data, dict):
        return ["配置必须是一个 JSON 对象（顶层是 { ... }）"], warnings

    # 2. schemaVersion 是 int 且 <= 1
    schema_version = data.get("schemaVersion")
    if not ctx._is_int(schema_version):
        errors.append("schemaVersion 必须是整数（当前：%s）" % ctx._show(schema_version))
    elif schema_version > 1:
        errors.append(
            "schemaVersion 只能是 1 或更小，手机端会整份拒收（当前：%d）" % schema_version
        )

    # 3. blocks 是数组且非空
    blocks = data.get("blocks")
    if not isinstance(blocks, list):
        errors.append("blocks 必须是数组")
        blocks = []
    elif not blocks:
        errors.append("blocks 不能是空数组（卡背不能空着，手机端会退回上一版）")

    # 4~6. 逐个 block
    seen_ids = {}
    for index, block in enumerate(blocks, start=1):
        label = "block #%d" % index
        if not isinstance(block, dict):
            errors.append("%s: 必须是一个对象" % label)
            continue

        block_id = block.get("id")
        if not isinstance(block_id, str) or not block_id.strip():
            errors.append("%s: id 必须是非空字符串" % label)
        else:
            label = "block #%d (id=%s)" % (index, block_id)
            if block_id in seen_ids:
                errors.append(
                    "%s: id 重复（和 block #%d 撞了，DIVIDER 这种也要唯一）"
                    % (label, seen_ids[block_id])
                )
            else:
                seen_ids[block_id] = index

        block_type = block.get("type")
        if not isinstance(block_type, str) or not block_type.strip():
            errors.append("%s: type 必须是非空字符串" % label)
        elif block_type not in ctx.ALLOWED_TYPES:
            # 手机端不认识就跳过并提示，所以这里放行
            warnings.append("%s: 未知类型 %s（手机端会跳过）" % (label, block_type))

        if "visible" in block and not isinstance(block["visible"], bool):
            errors.append("%s: visible 必须是 true 或 false" % label)

        if "title" in block:
            title = block["title"]
            if title is not None and not isinstance(title, str):
                errors.append(
                    '%s: title 必须是字符串或 null（空串 "" 表示不显示标题）' % label
                )

        style = block.get("style")
        if style is not None:
            if not isinstance(style, dict):
                errors.append("%s: style 必须是对象或 null" % label)
            else:
                if "color" in style and style["color"] is not None:
                    if style["color"] not in ctx.COLOR_NAMES:
                        errors.append(
                            "%s: style.color 只能是 %s"
                            % (label, "/".join(ctx.COLOR_NAMES))
                        )
                if "size" in style and style["size"] is not None:
                    if style["size"] not in ctx.SIZE_NAMES:
                        errors.append("%s: style.size 只能是 S/M/L" % label)

    # 7. spacing：4..32 的整数
    spacing = data.get("spacing")
    if spacing is not None:
        if not ctx._is_int(spacing):
            errors.append("spacing 必须是整数（当前：%s）" % ctx._show(spacing))
        elif not 4 <= spacing <= 32:
            errors.append("spacing 必须在 4..32 之间（当前：%d）" % spacing)

    # 8. motion 各字段是数字
    motion = data.get("motion")
    if motion is not None:
        if not isinstance(motion, dict):
            errors.append("motion 必须是对象")
        else:
            for key, value in motion.items():
                if key not in ctx.MOTION_FIELDS:
                    continue  # 不认识的动效字段忽略，方便以后加
                if not ctx._is_number(value) or not math.isfinite(float(value)):
                    errors.append("motion.%s 必须是数字（当前：%s）" % (key, ctx._show(value)))
                elif key == "overshootPx":
                    # 契约 §5 的默认值就是 -1.0（先冲过再回落），负号是语义的一部分
                    if float(value) == 0:
                        errors.append(
                            "motion.overshootPx 不能是 0（默认 -1.0，负值表示先上冲再回落）"
                        )
                elif float(value) <= 0:
                    errors.append("motion.%s 必须是正数（当前：%s）" % (key, ctx._show(value)))

    return errors, warnings


def format_errors(errors, limit=8):
    if len(errors) <= limit:
        return "；".join(errors)
    return "；".join(errors[:limit]) + "；…还有 %d 处" % (len(errors) - limit)


def _history_entries(ctx):
    """history 目录下的所有版本，新的在前。"""
    entries = []
    try:
        names = os.listdir(ctx.HISTORY_DIR)
    except OSError:
        return []
    for name in names:
        match = ctx.HISTORY_RE.match(name)
        if not match:
            continue
        revision = int(match.group(1))
        path = os.path.join(ctx.HISTORY_DIR, name)
        when = ctx._parse_stamp(match.group(2))
        if when is None:
            try:
                when = datetime.fromtimestamp(os.path.getmtime(path)).astimezone()
            except OSError:
                continue
        entries.append(
            {"revision": revision, "savedAt": ctx._iso(when), "path": path, "_ts": when, "_name": name}
        )
    entries.sort(key=lambda it: (it["_ts"], it["revision"], it["_name"]), reverse=True)
    return entries


def _history_public(ctx):
    return [{"revision": it["revision"], "savedAt": it["savedAt"]} for it in ctx._history_entries()]


def _find_history(ctx, revision):
    for entry in ctx._history_entries():
        if entry["revision"] == revision:
            return entry
    return None


def _current_revision(ctx):
    """配置文件和 state.json 里取大的那个，保证 revision 只增不减。"""
    candidates = []
    cfg = ctx._load_json(ctx.CONFIG_PATH)
    if isinstance(cfg, dict) and ctx._is_int(cfg.get("revision")):
        candidates.append(cfg["revision"])
    state = ctx._load_json(ctx.STATE_PATH)
    if isinstance(state, dict) and ctx._is_int(state.get("revision")):
        candidates.append(state["revision"])
    return max(candidates) if candidates else 1


def _write_state(ctx, revision, updated_at, published_at=None):
    state = {
        "revision": revision,
        "updatedAt": updated_at or ctx._iso(ctx._now()),
        "publishedAt": published_at or ctx._iso(ctx._now()),
    }
    ctx._atomic_write_json(ctx.STATE_PATH, state)
    return state


def ensure_initialized(ctx):
    """首次启动写默认清单；顺手把 state.json 对齐。返回要打印的提示行。"""
    notes = []
    with ctx._LOCK:
        os.makedirs(ctx.HISTORY_DIR, exist_ok=True)

        if not os.path.exists(ctx.CONFIG_PATH):
            cfg = ctx.default_config()
            ctx._atomic_write_json(ctx.CONFIG_PATH, cfg)
            ctx._write_state(cfg["revision"], cfg["updatedAt"], cfg["updatedAt"])
            notes.append(
                "首次启动：已写入内置默认清单（%d 个块，revision=1）" % len(cfg["blocks"])
            )
            return notes

        cfg = ctx._load_json(ctx.CONFIG_PATH)
        if not isinstance(cfg, dict):
            notes.append(
                "警告：published/cardback.json 不是合法 JSON，保持原样没覆盖 —— "
                "手机端会退回上一版，请手动修复或删掉它后重启"
            )
            return notes

        revision = cfg.get("revision") if ctx._is_int(cfg.get("revision")) else 1
        updated = cfg.get("updatedAt") if isinstance(cfg.get("updatedAt"), str) else ""
        state = ctx._load_json(ctx.STATE_PATH)
        prev_published = ""
        if isinstance(state, dict) and isinstance(state.get("publishedAt"), str):
            prev_published = state["publishedAt"]
        if (
            not isinstance(state, dict)
            or state.get("revision") != revision
            or not prev_published
        ):
            ctx._write_state(revision, updated or ctx._iso(ctx._now()), prev_published)
        return notes


def _archive_current(ctx, revision, when):
    """把当前这份拷进 history：cardback-<revision>-<yyyymmdd-HHMMSS>.json"""
    os.makedirs(ctx.HISTORY_DIR, exist_ok=True)
    stamp = when.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(ctx.HISTORY_DIR, "cardback-%d-%s.json" % (revision, stamp))
    suffix = 2
    while os.path.exists(dest):
        dest = os.path.join(ctx.HISTORY_DIR, "cardback-%d-%s-%d.json" % (revision, stamp, suffix))
        suffix += 1
    with open(ctx.CONFIG_PATH, "rb") as src, open(dest, "wb") as dst:
        shutil.copyfileobj(src, dst)
    return dest


def publish_config(ctx, data):
    """校验已过。旧版进 history → 写新版 → revision+1 → 落 state。"""
    with ctx._LOCK:
        current = ctx._current_revision()
        now = ctx._now()
        if os.path.exists(ctx.CONFIG_PATH):
            ctx._archive_current(current, now)

        new_revision = current + 1
        # revision 由服务端说了算，不信前端传的，否则手机那边可能不认新版本
        out = dict(data)
        out["revision"] = new_revision
        if not isinstance(out.get("updatedAt"), str) or not out["updatedAt"].strip():
            out["updatedAt"] = ctx._iso(now)

        ctx._atomic_write_json(ctx.CONFIG_PATH, out)
        ctx._write_state(new_revision, out["updatedAt"], ctx._iso(now))
        return new_revision


def rollback_to(ctx, target):
    with ctx._LOCK:
        entry = ctx._find_history(target)
        if entry is None:
            raise ctx.ApiError("history 里没有 revision=%d 的版本" % target)
        cfg = ctx._load_json(entry["path"])
        if not isinstance(cfg, dict):
            raise ctx.ApiError("history 里 revision=%d 的文件读不出 JSON，换一版试试" % target)

        current = ctx._current_revision()
        now = ctx._now()
        # 取 max(当前, 该版)+1：手机只会看「比手上新」，不能让它以为退化了
        new_revision = max(current, target) + 1

        if os.path.exists(ctx.CONFIG_PATH):
            ctx._archive_current(current, now)

        out = dict(cfg)
        out["revision"] = new_revision
        updated = out.get("updatedAt")
        if not isinstance(updated, str) or not updated.strip():
            updated = ctx._iso(now)
            out["updatedAt"] = updated

        ctx._atomic_write_json(ctx.CONFIG_PATH, out)
        ctx._write_state(new_revision, updated, ctx._iso(now))
        return new_revision


def state_snapshot(ctx):
    cfg = ctx._load_json(ctx.CONFIG_PATH)
    if not isinstance(cfg, dict):
        cfg = {}
    state = ctx._load_json(ctx.STATE_PATH)
    if not isinstance(state, dict):
        state = {}

    revision = ctx._current_revision()
    updated = state.get("updatedAt")
    if not isinstance(updated, str) or not updated:
        updated = cfg.get("updatedAt") if isinstance(cfg.get("updatedAt"), str) else ""
    published = state.get("publishedAt")
    if not isinstance(published, str) or not published:
        published = updated
    if not updated:
        try:
            updated = ctx._iso(datetime.fromtimestamp(os.path.getmtime(ctx.CONFIG_PATH)).astimezone())
        except OSError:
            updated = ""
    if not published:
        published = updated

    return {
        "revision": revision,
        "updatedAt": updated,
        "publishedAt": published,
        "historyCount": len(ctx._history_entries()),
    }

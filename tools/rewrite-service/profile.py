#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""六招的跨词 Beta 画像：用 Thompson Sampling 决定下一张卡先试哪几招。

为什么是 Beta：
- 每招只有「用户留下/用户流失」两种结果，正好是 Beta-Bernoulli 共轭；
- 冷启动用先验表达「哪招通常更稳」，不靠硬编码排序；
- 每次推荐独立采样，exploration 与 exploitation 自然平衡，不会把某一招焊死。

落盘 data/profile.json，原子写（临时文件 + os.replace），线程锁保护。
对外同时提供类与模块级默认实例，pipeline/service 直接 import 就能用。
"""

from __future__ import annotations

import copy
import json
import math
import os
import random
import tempfile
import threading
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))

# 顺序就是默认展示顺序；推荐结果按采样值重排。
METHODS = ("sound", "root", "contrast", "shape", "polysemy", "meaning")

METHOD_LABELS = {
    "sound": "谐音",
    "root": "词根",
    "contrast": "对比",
    "shape": "字形",
    "polysemy": "熟词生义",
    "meaning": "含义",
}

# 冷启动先验：sound 最稳（alpha=6/beta=1），shape/contrast 次之，
# root/meaning/polysemy 更容易翻车，先压低。
PRIORS = {
    "sound": (6.0, 1.0),
    "root": (1.5, 4.0),
    "contrast": (3.0, 2.0),
    "shape": (3.0, 2.0),
    "polysemy": (1.5, 4.0),
    "meaning": (1.5, 4.0),
}

_ALIASES = {
    "谐音": "sound", "谐音联想": "sound",
    "词根": "root", "词根词缀": "root",
    "对比": "contrast", "对比辨析": "contrast",
    "字形": "shape", "字形联想": "shape",
    "熟词生义": "polysemy",
    "含义": "meaning", "含义联想": "meaning",
}


def normalize_method(method):
    """中文招名 / 英文键 → 六招英文键；认不出返回空串。"""
    text = str(method or "").strip().lower()
    if text in METHODS:
        return text
    return _ALIASES.get(text, "")


def default_data_dir():
    return os.environ.get("REWRITE_DATA_DIR") or os.path.join(HERE, "data")


def _now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def _prior_state():
    return {
        "version": 1,
        "methods": {
            method: {"alpha": float(PRIORS[method][0]), "beta": float(PRIORS[method][1])}
            for method in METHODS
        },
        "feedbackIds": [],
        "memoryIds": [],
        "updatedAt": "",
    }


def _atomic_write_json(path, data):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".profile-", suffix=".tmp", dir=os.path.dirname(path) or ".")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=1)
            fh.write("\n")
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


# ------------------------------------------------------ 反馈份数（count）折算
# 旧客户端一条反馈就是一条；新客户端可以把「同一次判断里按了几次」折成 count
# 一起送上来。服务端只做温和放大：喊得多算加分，但不许把先验喊翻。
FEEDBACK_COUNT_MIN = 1
FEEDBACK_COUNT_MAX = 50
# down 的增量一直是 up 的 1.2 倍（1.4.6 起就是 alpha+1.0 / beta+1.2），
# count 只是在这个比例上等比放大，所以 count=1 时和旧行为逐位一致。
DOWN_FACTOR = 1.2


def weight_count(count):
    """把外部传进来的 count 收敛成 [1, 50] 的整数；bool / 非整数 / 空 → 1。"""
    if isinstance(count, bool) or not isinstance(count, int):
        return FEEDBACK_COUNT_MIN
    if count < FEEDBACK_COUNT_MIN:
        return FEEDBACK_COUNT_MIN
    if count > FEEDBACK_COUNT_MAX:
        return FEEDBACK_COUNT_MAX
    return count


def feedback_weight(count=1):
    """权重 = 1 + min(3.0, log2(max(1, count)))：1→1、2→2、4→3、8→4（封顶 4）。

    用对数而不是线性：多喊两声该加分（一条反馈里按了 8 次确实比按 1 次更笃定），
    但线性会让一条 50 次的反馈直接盖掉整个先验，调参就失去意义。
    """
    n = max(1, weight_count(count))
    return 1.0 + min(3.0, math.log2(n))


class Profile:
    """单个 data/profile.json 的读写句柄；线程安全。"""

    def __init__(self, path=None):
        self.path = path or os.path.join(default_data_dir(), "profile.json")
        self._lock = threading.RLock()
        self._data = _prior_state()
        self.load()

    # ---------------------------------------------------------- 读写

    def load(self):
        with self._lock:
            data = None
            try:
                with open(self.path, "r", encoding="utf-8-sig") as fh:
                    data = json.load(fh)
            except (OSError, ValueError):
                data = None
            state = _prior_state()
            if isinstance(data, dict):
                methods = data.get("methods")
                if isinstance(methods, dict):
                    for method in METHODS:
                        row = methods.get(method)
                        if not isinstance(row, dict):
                            continue
                        try:
                            alpha = float(row.get("alpha", PRIORS[method][0]))
                            beta = float(row.get("beta", PRIORS[method][1]))
                        except (TypeError, ValueError):
                            continue
                        if alpha > 0 and beta > 0:
                            state["methods"][method] = {"alpha": alpha, "beta": beta}
                for key in ("feedbackIds", "memoryIds"):
                    rows = data.get(key)
                    if isinstance(rows, list):
                        state[key] = [str(x) for x in rows if str(x).strip()][-5000:]
                state["updatedAt"] = str(data.get("updatedAt") or "")
            self._data = state
            return self

    def save(self):
        with self._lock:
            self._data["updatedAt"] = _now()
            _atomic_write_json(self.path, self._data)
            return self

    def _mark_seen(self, key, event_id):
        """同一条反馈/记忆信号只记一次；返回 False 表示之前已生效。"""
        if not event_id:
            return True
        event_id = str(event_id).strip()
        if not event_id:
            return True
        bucket = self._data[key]
        if event_id in bucket:
            return False
        bucket.append(event_id)
        if len(bucket) > 5000:
            del bucket[: len(bucket) - 5000]
        return True

    # ---------------------------------------------------------- 更新

    def update_feedback(self, method, vote, event_id=None, count=1):
        """up → alpha 加权重；down → beta 加权重×1.2。未知方法/票型抛 ValueError。

        `count`（1.4.6 兼容 / 1.5.2 起支持）：同一张卡一次点了 👍 几次就记几票。
        权重 = 1 + min(3.0, log2(max(1, count)))，即 1→1、2→2、4→3、8→4、再往上封顶 4。
        用 log 是为了「多喊两声算加分，但不许把一个词的先验喊翻」——线性会让狂点
        十次直接压过所有基础先验。count 缺省（老客户端不传）= 1，行为与 1.4.6 完全一致。
        """
        method = normalize_method(method)
        if not method:
            raise ValueError("未知方法：%s" % method)
        vote = str(vote or "").strip().lower()
        if vote not in ("up", "down"):
            raise ValueError("vote 只能是 up/down")
        weight = feedback_weight(count)
        with self._lock:
            if not self._mark_seen("feedbackIds", event_id):
                return {"applied": False, "reason": "duplicate", "method": method, "vote": vote}
            row = self._data["methods"][method]
            if vote == "up":
                row["alpha"] = float(row["alpha"]) + weight
            else:
                row["beta"] = float(row["beta"]) + weight * DOWN_FACTOR
            self.save()
            return {
                "applied": True,
                "method": method,
                "vote": vote,
                "count": weight_count(count),
                "weight": weight,
                "alpha": row["alpha"],
                "beta": row["beta"],
            }

    def update_memory_signal(self, method, event, event_id=None):
        """retained → alpha+1.5；lapsed → beta+1.5。"""
        method = normalize_method(method)
        if not method:
            raise ValueError("未知方法：%s" % method)
        event = str(event or "").strip().lower()
        if event not in ("retained", "lapsed"):
            raise ValueError("event 只能是 retained/lapsed")
        with self._lock:
            if not self._mark_seen("memoryIds", event_id):
                return {"applied": False, "reason": "duplicate", "method": method, "event": event}
            row = self._data["methods"][method]
            if event == "retained":
                row["alpha"] = float(row["alpha"]) + 1.5
            else:
                row["beta"] = float(row["beta"]) + 1.5
            self.save()
            return {"applied": True, "method": method, "event": event, "alpha": row["alpha"], "beta": row["beta"]}

    # ---------------------------------------------------------- 推荐

    def recommend_ranking(self, word_history=None):
        """Thompson Sampling：六招各采样一次，按采样值降序返回。

        word_history 只作为签名保留（调用方传同词历史），
        采样本身不读它 —— 跨词画像才是这一层的信号源，同词约束由
        pipeline 的用户原话/历史黑名单负责。
        """
        with self._lock:
            rows = [
                (method, random.betavariate(
                    float(self._data["methods"][method]["alpha"]),
                    float(self._data["methods"][method]["beta"]),
                ))
                for method in METHODS
            ]
        rows.sort(key=lambda pair: pair[1], reverse=True)
        return [method for method, _value in rows]

    def recommend_top(self, n=2, word_history=None):
        count = max(1, int(n or 2))
        return self.recommend_ranking(word_history)[:count]

    def snapshot(self, word_history=None):
        """测试/诊断用：返回方法与参数的深拷贝，推荐结果单独给出。"""
        with self._lock:
            data = copy.deepcopy(self._data)
        data["ranking"] = self.recommend_ranking(word_history)
        return data


# ------------------------------------------------------------------ 模块级默认实例

_instances = {}
_instances_lock = threading.Lock()


def get_profile(path=None):
    path = path or os.path.join(default_data_dir(), "profile.json")
    path = os.path.abspath(path)
    with _instances_lock:
        instance = _instances.get(path)
        if instance is None:
            instance = Profile(path)
            _instances[path] = instance
        return instance


def update_feedback(method, vote, event_id=None, count=1):
    return get_profile().update_feedback(method, vote, event_id=event_id, count=count)


def update_memory_signal(method, event, event_id=None):
    return get_profile().update_memory_signal(method, event, event_id=event_id)


def recommend_ranking(word_history=None):
    return get_profile().recommend_ranking(word_history)


def recommend_top(n=2, word_history=None):
    return get_profile().recommend_top(n, word_history)


def snapshot(word_history=None):
    return get_profile().snapshot(word_history)

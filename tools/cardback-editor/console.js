/* 卡背控制台 · 同源 API 驱动，无第三方依赖 */
(function () {
  "use strict";

  var $ = function (selector, root) { return (root || document).querySelector(selector); };
  var state = {
    worker: null,
    taste: { rules: [], lockedRules: [] },
    busy: {}
  };
  var MAX_RULES = 8;
  var MAX_RULE_CHARS = 60;
  var MAX_TOTAL_CHARS = 300;

  function setText(id, value) {
    var node = document.getElementById(id);
    if (node) node.textContent = value;
  }

  function toast(message, bad) {
    var host = $("#toasts");
    if (!host) return;
    var node = document.createElement("div");
    node.className = "toast" + (bad ? " bad" : "");
    node.textContent = message;
    host.appendChild(node);
    window.setTimeout(function () {
      node.style.opacity = "0";
      node.style.transform = "translateY(6px)";
      window.setTimeout(function () { node.remove(); }, 220);
    }, 3200);
  }

  function api(path, options) {
    var opts = options || {};
    var init = { method: opts.method || "GET", headers: {} };
    if (opts.body !== undefined) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(opts.body);
    }
    return fetch(path, init).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (data) {
        if (!response.ok || data.ok === false) {
          throw new Error(data.error || data.message || ("HTTP " + response.status));
        }
        return data;
      });
    });
  }

  function busy(button, flag, label) {
    if (!button) return;
    if (flag) {
      button.dataset.label = button.textContent;
      button.textContent = label || "处理中…";
      button.disabled = true;
    } else {
      if (button.dataset.label) button.textContent = button.dataset.label;
      button.disabled = false;
    }
  }

  function pct(value, limit) {
    if (!limit || limit <= 0) return 0;
    return Math.max(0, Math.min(100, (Number(value) || 0) / limit * 100));
  }

  function setBar(name, value, limit) {
    var bar = document.getElementById("quota-" + name + "-bar");
    var label = document.getElementById("quota-" + name + "-text");
    if (bar) bar.style.width = pct(value, limit).toFixed(1) + "%";
    if (label) {
      label.textContent = limit > 0 ? (Number(value) || 0) + " / " + limit : "不限";
    }
  }

  function formatTime(value) {
    if (!value) return "尚未更新";
    var date = new Date(value);
    if (isNaN(date.getTime())) return String(value);
    return date.toLocaleString("zh-CN", {
      month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
    });
  }

  /* ------------------------------------------------------------ 服务状态 */
  function loadServer() {
    return api("/api/state").then(function (data) {
      var dot = $("#server-dot");
      if (dot) dot.className = "lamp on";
      setText("server-text", "服务在线 · rev " + (data.revision != null ? data.revision : "—"));
    }).catch(function () {
      var dot = $("#server-dot");
      if (dot) dot.className = "lamp bad";
      setText("server-text", "服务未响应");
    });
  }

  /* ------------------------------------------------------------ 飞轮中控 */
  function loadWorker() {
    return api("/rewrite/worker/status").then(function (status) {
      state.worker = status;
      renderWorker();
      setText("version-chip", "v" + (status.version || "—"));
      return api("/rewrite/worker/preview").catch(function () {
        return { candidates: [] };
      });
    }).then(function (preview) {
      renderPreview(preview && preview.candidates ? preview.candidates : []);
    }).catch(function (error) {
      var lamp = $("#worker-lamp");
      if (lamp) lamp.className = "lamp bad";
      setText("worker-state", "读取失败");
      setText("version-chip", "v—");
      renderPreview([]);
      toast("飞轮状态读取失败：" + error.message, true);
    });
  }

  function renderWorker() {
    var status = state.worker;
    if (!status) return;
    var lamp = $("#worker-lamp");
    var toggle = $("#worker-toggle");
    var check = $("#include-review");
    if (lamp) {
      lamp.className = "lamp " + (status.enabled ? "on" : "idle");
    }
    setText("worker-state", status.enabled
      ? (status.running ? "飞轮运转中" : "飞轮已开启 · 待命")
      : "飞轮关闭");
    if (toggle) {
      toggle.setAttribute("aria-checked", status.enabled ? "true" : "false");
      setText("worker-toggle-label", status.enabled ? "飞轮开启" : "飞轮关闭");
    }
    if (check) check.checked = !status.includeReview;
    setBar("total", status.attemptsToday, status.maxPerDay);
    setBar("new", status.newWordsToday, status.maxNewPerDay);
    setBar("hour", status.runsLastHour, status.maxPerHour);
  }

  function renderPreview(rows) {
    var list = $("#preview-list");
    if (!list) return;
    list.textContent = "";
    setText("preview-count", rows.length ? rows.length + " 个候选" : "暂无候选");
    if (!rows.length) {
      var empty = document.createElement("li");
      empty.className = "empty";
      empty.textContent = "当前没有可洗的新词。明日名单同步后会自动出现。";
      list.appendChild(empty);
      return;
    }
    rows.forEach(function (row, index) {
      var item = document.createElement("li");
      var rank = document.createElement("span");
      rank.className = "preview-rank";
      rank.textContent = String(index + 1).padStart(2, "0");
      var body = document.createElement("div");
      var word = document.createElement("span");
      word.className = "preview-word";
      word.textContent = row.word || "—";
      var meaning = document.createElement("span");
      meaning.className = "preview-meaning";
      meaning.textContent = row.meaning || "";
      body.appendChild(word);
      body.appendChild(meaning);
      var badge = document.createElement("span");
      badge.className = "badge " + (row.new ? "new" : "review");
      badge.textContent = row.new ? "新词" : "复习";
      item.appendChild(rank);
      item.appendChild(body);
      item.appendChild(badge);
      list.appendChild(item);
    });
  }

  function toggleWorker() {
    if (!state.worker) return;
    var target = !state.worker.enabled;
    var button = $("#worker-toggle");
    busy(button, true, target ? "启动中…" : "停止中…");
    api("/rewrite/worker/toggle", {
      method: "POST",
      body: { enabled: target, includeReview: state.worker.includeReview }
    }).then(function () {
      toast(target ? "飞轮已开启" : "飞轮已关闭");
      return loadWorker();
    }).catch(function (error) {
      toast("切换失败：" + error.message, true);
    }).then(function () { busy(button, false); });
  }

  function toggleReview(checked) {
    if (!state.worker) return;
    var includeReview = !checked;
    var box = $("#include-review");
    if (box) box.disabled = true;
    api("/rewrite/worker/toggle", {
      method: "POST",
      body: { enabled: state.worker.enabled, includeReview: includeReview }
    }).then(function () {
      toast(includeReview ? "已允许复习词" : "已切回只洗新词");
      return loadWorker();
    }).catch(function (error) {
      toast("保存失败：" + error.message, true);
      if (box) box.checked = !includeReview;
    }).then(function () { if (box) box.disabled = false; });
  }

  /* ------------------------------------------------------------ 一键发布 */
  function publishCards() {
    var button = $("#publish");
    busy(button, true, "发布中…");
    api("/cards/publish", { method: "POST", body: {} }).then(function (data) {
      var manifest = data.manifest || {};
      var stats = manifest.stats || {};
      setText("publish-status", "发布完成 · " + (manifest.count || 0) + " 张词卡");
      setText("publish-version", "v" + (manifest.version || "—"));
      setText("stat-merged", manifest.merged != null ? manifest.merged : "—");
      setText("stat-accepted", stats.accepted != null ? stats.accepted : "—");
      setText("stat-rejected", stats.rejected != null ? stats.rejected : "—");
      setText("stat-broken", stats.broken_files != null ? stats.broken_files : "—");
      setText("publish-sha", manifest.sha256
        ? "SHA-256 · " + String(manifest.sha256).slice(0, 24) + "…"
        : "SHA-256 · —");
      var result = $("#publish-result");
      if (result) result.hidden = false;
      toast("发布成功，已合并 " + (manifest.merged || 0) + " 条");
    }).catch(function (error) {
      toast("发布失败：" + error.message, true);
    }).then(function () { busy(button, false); });
  }

  /* ------------------------------------------------------------ 审美画像 */
  function loadTaste() {
    return api("/rewrite/taste").then(function (data) {
      applyTaste(data.profile || {});
    }).catch(function (error) {
      setText("taste-meta", "画像读取失败");
      toast("审美画像读取失败：" + error.message, true);
    });
  }

  function applyTaste(profile) {
    var rules = Array.isArray(profile.rules) ? profile.rules.slice() : [];
    var locked = Array.isArray(profile.lockedRules) ? profile.lockedRules.slice() : [];
    state.taste = { rules: rules, lockedRules: locked, meta: profile };
    setText("taste-meta", "基于 " + (profile.sourceFeedbackCount || 0) +
      " 条反馈 · 更新于 " + formatTime(profile.updatedAt));
    renderTaste();
  }

  function isLocked(rule) {
    return state.taste.lockedRules.indexOf(rule) !== -1;
  }

  function renderTaste(focusIndex) {
    var list = $("#rule-list");
    if (!list) return;
    list.textContent = "";
    var rules = state.taste.rules;
    setText("rule-count", rules.length + " / " + MAX_RULES);
    if (!rules.length) {
      var empty = document.createElement("p");
      empty.className = "rule-empty";
      empty.textContent = "还没有规则。可以手动添加，或点「蒸馏画像」从近期反馈生成。";
      list.appendChild(empty);
      return;
    }
    rules.forEach(function (rule, index) {
      var row = document.createElement("div");
      row.className = "rule" + (isLocked(rule) ? " locked" : "");
      var number = document.createElement("span");
      number.className = "rule-index";
      number.textContent = String(index + 1).padStart(2, "0");
      var input = document.createElement("input");
      input.className = "rule-input";
      input.type = "text";
      input.maxLength = MAX_RULE_CHARS;
      input.value = rule;
      input.setAttribute("aria-label", "第 " + (index + 1) + " 条审美规则");
      input.readOnly = isLocked(rule);
      input.addEventListener("input", function () {
        state.taste.rules[index] = input.value;
      });
      var lock = document.createElement("button");
      lock.type = "button";
      lock.className = "icon-btn" + (isLocked(rule) ? " is-locked" : "");
      lock.title = isLocked(rule) ? "解锁规则" : "锁定规则";
      lock.setAttribute("aria-pressed", isLocked(rule) ? "true" : "false");
      lock.innerHTML = isLocked(rule)
        ? '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="4" y="9" width="12" height="8" rx="2"/><path d="M7 9V6.5a3 3 0 0 1 6 0V9"/></svg>'
        : '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="4" y="9" width="12" height="8" rx="2"/><path d="M7 9V6.5a3 3 0 0 1 5.7-1.3"/></svg>';
      lock.addEventListener("click", function () {
        toggleLock(index);
      });
      var remove = document.createElement("button");
      remove.type = "button";
      remove.className = "icon-btn remove";
      remove.title = "删除规则";
      remove.innerHTML = '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M5 6h10M8 6V4.5h4V6M7 9v6M10 9v6M13 9v6M6 6l.7 10h6.6L14 6"/></svg>';
      remove.addEventListener("click", function () {
        removeRule(index);
      });
      row.appendChild(number);
      row.appendChild(input);
      row.appendChild(lock);
      row.appendChild(remove);
      list.appendChild(row);
      if (focusIndex === index) {
        window.setTimeout(function () { input.focus(); }, 0);
      }
    });
  }

  function toggleLock(index) {
    var rule = state.taste.rules[index];
    if (rule === undefined) return;
    var at = state.taste.lockedRules.indexOf(rule);
    if (at === -1) {
      state.taste.lockedRules.push(rule);
    } else {
      state.taste.lockedRules.splice(at, 1);
    }
    renderTaste();
  }

  function removeRule(index) {
    var rule = state.taste.rules[index];
    if (isLocked(rule)) {
      toast("先解锁这条规则，再删除", true);
      return;
    }
    state.taste.rules.splice(index, 1);
    renderTaste();
  }

  function addRule() {
    if (state.taste.rules.length >= MAX_RULES) {
      toast("最多 " + MAX_RULES + " 条规则", true);
      return;
    }
    state.taste.rules.push("");
    renderTaste(state.taste.rules.length - 1);
  }

  function saveTaste() {
    var rules = state.taste.rules.map(function (rule) { return String(rule || "").trim(); })
      .filter(function (rule) { return rule.length > 0; });
    var total = rules.reduce(function (sum, rule) { return sum + rule.length; }, 0);
    if (rules.length < 5) {
      toast("至少保留 5 条规则", true);
      return;
    }
    if (rules.length > MAX_RULES) {
      toast("最多 " + MAX_RULES + " 条规则", true);
      return;
    }
    if (rules.some(function (rule) { return rule.length > MAX_RULE_CHARS; })) {
      toast("单条规则不能超过 " + MAX_RULE_CHARS + " 字", true);
      return;
    }
    if (total > MAX_TOTAL_CHARS) {
      toast("规则总字数不能超过 " + MAX_TOTAL_CHARS + " 字", true);
      return;
    }
    var locked = state.taste.lockedRules.filter(function (rule) { return rules.indexOf(rule) !== -1; });
    var button = $("#save-taste");
    busy(button, true, "保存中…");
    api("/rewrite/taste", { method: "POST", body: { rules: rules, lockedRules: locked } })
      .then(function (data) {
        applyTaste(data.profile || {});
        toast("审美规则已保存");
      }).catch(function (error) {
        toast("保存失败：" + error.message, true);
      }).then(function () { busy(button, false); });
  }

  function distillTaste() {
    if (!window.confirm("蒸馏会用近期反馈重写全部未锁定规则，锁定规则原样保留。继续？")) return;
    var button = $("#distill");
    busy(button, true, "蒸馏中…");
    api("/rewrite/taste/distill", { method: "POST", body: { model: "ds-v41-flash" } })
      .then(function (data) {
        applyTaste(data.profile || {});
        toast("蒸馏完成，规则已更新");
      }).catch(function (error) {
        toast("蒸馏失败：" + error.message, true);
      }).then(function () { busy(button, false); });
  }

  /* ------------------------------------------------------------ 锚点池 */
  function loadAnchors() {
    return api("/rewrite/anchors/count").then(function (data) {
      setText("anchor-count", data.count != null ? data.count : "—");
    }).catch(function () {
      setText("anchor-count", "—");
    });
  }

  /* ------------------------------------------------------------ 事件绑定 */
  function bind() {
    $("#worker-toggle").addEventListener("click", toggleWorker);
    $("#include-review").addEventListener("change", function (event) {
      toggleReview(event.target.checked);
    });
    $("#publish").addEventListener("click", publishCards);
    $("#save-taste").addEventListener("click", saveTaste);
    $("#add-rule").addEventListener("click", addRule);
    $("#distill").addEventListener("click", distillTaste);
    $("#taste-refresh").addEventListener("click", function () {
      loadTaste().then(function () { toast("画像已重新读取"); });
    });
    $("#refresh-all").addEventListener("click", function () {
      var button = $("#refresh-all");
      busy(button, true, "刷新中…");
      Promise.all([loadServer(), loadWorker(), loadTaste(), loadAnchors()])
        .then(function () { toast("已刷新"); })
        .then(function () { busy(button, false); });
    });
  }

  function init() {
    var today = new Date().toLocaleDateString("zh-CN", {
      month: "long", day: "numeric", weekday: "long"
    });
    setText("today-line", today + " · 学习日 04:00 切换 · 飞轮只在手动队列空闲时工作");
    bind();
    loadServer();
    loadWorker();
    loadTaste();
    loadAnchors();
    window.setInterval(function () {
      if (document.visibilityState === "visible") loadWorker();
    }, 30000);
  }

  init();
})();

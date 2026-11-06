/* 卡背结构编辑器 · 1.4.2
 * ---------------------------------------------------------------------------
 * 纯原生 JS，零依赖、零外链。
 * 契约（块类型 / style 语义 / HTTP API）见 docs/cardback-schema-1.4.2.md，
 * 这里是它的前端实现：左组件库 + 中块列表（可拖动排序）+ 右实时预览。
 * ---------------------------------------------------------------------------
 */
(function () {
  'use strict';

  /* =========================================================================
     常量
     ========================================================================= */

  var STORAGE_KEY = 'cardback-editor.draft.v1';
  var SCHEMA_VERSION = 1;
  var DEFAULT_BOOK = '六级记忆卡';
  var DEFAULT_SPACING = 12;
  var MIN_SPACING = 4;
  var MAX_SPACING = 32;

  var DEFAULT_MOTION = {
    titleDurationMs: 820,
    staggerGapMs: 90,
    entryDurationMs: 220,
    overshootPercent: 72,
    overshootPx: -1.0,
    overshootScale: 1.0015
  };

  /* 块类型表，顺序 = 组件库顺序 = 契约 §2 的顺序 */
  var BLOCK_TYPES = [
    { type: 'TITLE',        label: '单词标题', glyph: 'Aa', hue: 'g-blue',   source: 'word.word',        defaultTitle: null },
    { type: 'PHONETIC',     label: '音标',     glyph: '//', hue: 'g-blue',   source: 'word.phonetic',    defaultTitle: null },
    { type: 'DIVIDER',      label: '分隔线',   glyph: '—',  hue: 'g-slate',  source: 'divider',          defaultTitle: null },
    { type: 'MEANING',      label: '释义',     glyph: '义', hue: 'g-amber',  source: 'word.meaning',     defaultTitle: '释义' },
    { type: 'MEMORY',       label: '记忆思路', glyph: '记', hue: 'g-violet', source: 'word.approach[]',  defaultTitle: '记忆思路' },
    { type: 'COLLOCATIONS', label: '常用搭配', glyph: '搭', hue: 'g-green',  source: 'word.collocations[]', defaultTitle: '常用搭配' },
    { type: 'EXAMPLE',      label: '例句',     glyph: '例', hue: 'g-slate',  source: 'word.example',     defaultTitle: '例句' },
    { type: 'PHRASES',      label: '短语',     glyph: '短', hue: 'g-green',  source: 'word.phrases',     defaultTitle: '短语' },
    { type: 'SYNONYMS',     label: '近义词',   glyph: '近', hue: 'g-violet', source: 'word.synonyms',    defaultTitle: '近义词' },
    { type: 'REL_WORDS',    label: '同根词',   glyph: '根', hue: 'g-amber',  source: 'word.relWords',    defaultTitle: '同根词' },
    { type: 'EF_STATS',     label: '记忆进度', glyph: 'EF', hue: 'g-slate',  source: 'word.progress',    defaultTitle: '' }
  ];

  var TYPE_MAP = {};
  BLOCK_TYPES.forEach(function (t) { TYPE_MAP[t.type] = t; });

  /* 默认块表 = 现在手机上写死的卡背（顺序与像素都不许变）。
     11 块：EF_STATS 面板自带上方分隔线，前面不再重复放 DIVIDER。 */
  var DEFAULT_LAYOUT = [
    'TITLE', 'PHONETIC', 'DIVIDER',
    'MEANING', 'MEMORY', 'COLLOCATIONS', 'EXAMPLE',
    'PHRASES', 'SYNONYMS', 'REL_WORDS',
    'EF_STATS'
  ];

  var COLORS = [
    { value: 'default', label: '默认' },
    { value: 'primary', label: '主色' },
    { value: 'secondary', label: '次级' },
    { value: 'accent', label: '强调' },
    { value: 'muted', label: '弱化' },
    { value: 'warning', label: '警示' }
  ];

  /* 语义名 → 预览里的 CSS 变量（绝不出现硬编码 hex） */
  var COLOR_VARS = {
    primary: '--cb-primary',
    secondary: '--cb-secondary',
    accent: '--cb-accent',
    muted: '--cb-muted',
    warning: '--cb-warning'
  };

  var SIZES = [
    { value: '', label: '默认' },
    { value: 'S', label: 'S 小' },
    { value: 'M', label: 'M 中' },
    { value: 'L', label: 'L 大' }
  ];

  /* 手机上 size 走 TextStyle.scaledBy()：S ×0.9，L ×1.15，M/缺省不缩放 */
  var SIZE_SCALE = { S: 0.9, M: 1, L: 1.15 };

  /* TITLE / PHONETIC / DIVIDER / EF_STATS 在手机上不渲染块标题 */
  var TITLE_CAPABLE = new Set(['MEANING', 'MEMORY', 'COLLOCATIONS', 'EXAMPLE', 'PHRASES', 'SYNONYMS', 'REL_WORDS']);

  var ICON = {
    up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5.5 11.5 12 5l6.5 6.5"/></svg>',
    down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14M5.5 12.5 12 19l6.5-6.5"/></svg>',
    trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M10 7V5.5A1.5 1.5 0 0 1 11.5 4h1A1.5 1.5 0 0 1 14 5.5V7M6.5 7l.8 11.2A2 2 0 0 0 9.3 20h5.4a2 2 0 0 0 2-1.8L17.5 7M10 11v5M14 11v5"/></svg>',
    reset: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3.5 12a8.5 8.5 0 1 0 2.6-6.1"/><path d="M3.5 4.5v5h5"/></svg>'
  };

  var JSON_HEADERS = { 'Content-Type': 'application/json' };

  /* =========================================================================
     状态
     ========================================================================= */

  var state = {
    doc: null,
    baselineSig: null,        // 上次发布/从服务端载入时的签名；null = 没和线上对齐过
    dirty: true,
    invalidIds: new Set(),
    sampleIndex: 0,
    samples: [],
    lastSavedAt: null,
    server: {
      status: 'connecting',
      revision: null,
      lanUrl: null,
      updatedAt: null,
      publishedAt: null,
      historyCount: null
    }
  };

  var idSeq = 12;             // 默认结构已占用 blk_1..blk_12
  var draftTimer = null;
  var lastFocusSync = 0;

  /* =========================================================================
     小工具
     ========================================================================= */

  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function nowIso() { return new Date().toISOString(); }
  function clampSpacing(value) {
    var n = Math.round(Number(value));
    if (!isFinite(n)) return DEFAULT_SPACING;
    return Math.min(MAX_SPACING, Math.max(MIN_SPACING, n));
  }
  function formatTime(value) {
    var d = value ? new Date(value) : null;
    if (!d || isNaN(d.getTime())) return value ? String(value) : '时间未知';
    function p(n) { return n < 10 ? '0' + n : String(n); }
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }
  function toLines(value) {
    if (Array.isArray(value)) {
      return value.map(function (item) { return String(item == null ? '' : item).trim(); }).filter(Boolean);
    }
    if (typeof value === 'string') {
      return value.split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
    }
    return [];
  }
  function str(value) {
    return typeof value === 'string' ? value : (value == null ? '' : String(value));
  }

  function AppError(message, kind) {
    this.name = 'AppError';
    this.message = message;
    this.kind = kind || 'api';
  }
  AppError.prototype = Object.create(Error.prototype);

  /* =========================================================================
     文档模型
     ========================================================================= */

  function makeBlock(type) {
    return {
      id: null,
      type: type,
      visible: true,
      title: null,
      style: { color: 'default' }
    };
  }

  function createDefaultDoc() {
    var used = new Set();
    var blocks = DEFAULT_LAYOUT.map(function (type, index) {
      var block = makeBlock(type);
      block.id = 'blk_' + (index + 1);
      used.add(block.id);
      return block;
    });
    return {
      schemaVersion: SCHEMA_VERSION,
      book: DEFAULT_BOOK,
      revision: 0,
      updatedAt: nowIso(),
      spacing: DEFAULT_SPACING,
      motion: Object.assign({}, DEFAULT_MOTION),
      blocks: blocks
    };
  }

  function uniqueId(used) {
    var id;
    do {
      idSeq += 1;
      id = 'blk_' + idSeq;
    } while (used.has(id));
    used.add(id);
    return id;
  }

  function nextId() {
    var used = new Set(state.doc.blocks.map(function (b) { return b.id; }));
    return uniqueId(used);
  }

  /* 把任意来源（草稿 / /cardback.json）的 JSON 揉成编辑器认的模型。
     未知 type 原样保留（发布时校验会拦住），不静默丢掉用户的数据。 */
  function normalizeDoc(raw) {
    if (!raw || typeof raw !== 'object' || !Array.isArray(raw.blocks)) return null;

    var used = new Set();
    var maxNum = 0;
    var blocks = [];

    raw.blocks.forEach(function (item) {
      if (!item || typeof item !== 'object') return;
      if (typeof item.type !== 'string' || !item.type) return;

      var id = typeof item.id === 'string' ? item.id.trim() : '';
      if (!id || used.has(id)) id = uniqueId(used);
      else used.add(id);

      var m = /^blk_(\d+)$/.exec(id);
      if (m) maxNum = Math.max(maxNum, Number(m[1]));

      var rawStyle = item.style && typeof item.style === 'object' ? item.style : {};
      var color = 'default';
      COLORS.forEach(function (c) { if (c.value === rawStyle.color) color = c.value; });

      var style = { color: color };
      if (rawStyle.size === 'S' || rawStyle.size === 'M' || rawStyle.size === 'L') style.size = rawStyle.size;

      blocks.push({
        id: id,
        type: item.type,
        visible: item.visible !== false,
        title: item.title === undefined || item.title === null ? null : String(item.title),
        style: style
      });
    });

    idSeq = Math.max(idSeq, 12, maxNum);

    return {
      schemaVersion: SCHEMA_VERSION,
      book: typeof raw.book === 'string' && raw.book.trim() ? raw.book : DEFAULT_BOOK,
      revision: isFinite(Number(raw.revision)) ? Number(raw.revision) : 0,
      updatedAt: typeof raw.updatedAt === 'string' ? raw.updatedAt : nowIso(),
      spacing: clampSpacing(raw.spacing),
      motion: Object.assign({}, DEFAULT_MOTION, raw.motion && typeof raw.motion === 'object' ? raw.motion : {}),
      blocks: blocks
    };
  }

  /* 发布 / 存草稿用的完整配置 */
  function serialize() {
    return {
      schemaVersion: SCHEMA_VERSION,
      book: str(state.doc.book).trim() || DEFAULT_BOOK,
      revision: Number(state.doc.revision) || 0,
      updatedAt: nowIso(),
      spacing: clampSpacing(state.doc.spacing),
      motion: Object.assign({}, DEFAULT_MOTION, state.doc.motion || {}),
      blocks: state.doc.blocks.map(function (b) {
        var style = { color: b.style && b.style.color ? b.style.color : 'default' };
        if (b.style && b.style.size) style.size = b.style.size;
        return {
          id: b.id,
          type: b.type,
          visible: b.visible !== false,
          title: b.title === undefined ? null : b.title,
          style: style
        };
      })
    };
  }

  /* 判断"有没有未发布改动"时不看 updatedAt（它每次序列化都会变） */
  function signature() {
    var d = serialize();
    return JSON.stringify({ book: d.book, spacing: d.spacing, motion: d.motion, blocks: d.blocks });
  }

  function computeDirty() {
    if (!state.baselineSig) return true;
    return signature() !== state.baselineSig;
  }

  /* =========================================================================
     本地草稿
     ========================================================================= */

  function loadDraft() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      var parsed = JSON.parse(raw);
      if (!parsed || !parsed.doc) return null;
      var doc = normalizeDoc(parsed.doc);
      if (!doc) return null;
      return { doc: doc, savedAt: parsed.savedAt || null, sampleIndex: parsed.sampleIndex };
    } catch (err) {
      console.warn('[cardback] 读取草稿失败', err);
      return null;
    }
  }

  function saveDraft(explicit) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        savedAt: nowIso(),
        sampleIndex: state.sampleIndex,
        doc: serialize()
      }));
      state.lastSavedAt = Date.now();
      if (explicit) {
        toast('草稿已保存到本机（换浏览器/清缓存会丢）', { tone: 'success', duration: 4000 });
        renderChrome();
      }
      return true;
    } catch (err) {
      if (explicit) toast('草稿保存失败：' + (err && err.message ? err.message : err), { tone: 'error', duration: 8000 });
      return false;
    }
  }

  function scheduleDraftSave() {
    clearTimeout(draftTimer);
    draftTimer = setTimeout(function () { saveDraft(false); }, 600);
  }

  /* =========================================================================
     渲染：组件库
     ========================================================================= */

  function renderLibrary() {
    var counts = {};
    state.doc.blocks.forEach(function (b) { counts[b.type] = (counts[b.type] || 0) + 1; });

    $('#library-tag').textContent = BLOCK_TYPES.length + ' 种';
    $('#library-list').innerHTML = BLOCK_TYPES.map(function (t) {
      var n = counts[t.type] || 0;
      return '' +
        '<button class="lib-item" type="button" data-add="' + t.type + '" title="添加「' + esc(t.label) + '」到末尾">' +
          '<span class="lib-glyph ' + t.hue + '" aria-hidden="true">' + esc(t.glyph) + '</span>' +
          '<span class="lib-text">' +
            '<span class="lib-name">' + esc(t.label) +
              (n ? '<span class="lib-count">' + n + '</span>' : '') +
            '</span>' +
            '<span class="lib-meta">' + esc(t.source) + '</span>' +
          '</span>' +
          '<span class="lib-plus" aria-hidden="true">+</span>' +
        '</button>';
    }).join('');
  }

  /* =========================================================================
     渲染：块列表
     ========================================================================= */

  function blockById(id) {
    for (var i = 0; i < state.doc.blocks.length; i += 1) {
      if (state.doc.blocks[i].id === id) return state.doc.blocks[i];
    }
    return null;
  }

  function blockIndexOf(id) {
    for (var i = 0; i < state.doc.blocks.length; i += 1) {
      if (state.doc.blocks[i].id === id) return i;
    }
    return -1;
  }

  function titlePlaceholder(block) {
    if (!TITLE_CAPABLE.has(block.type)) return '该块没有标题';
    if (block.title === null) {
      var info = TYPE_MAP[block.type];
      var dt = info ? info.defaultTitle : null;
      return dt ? '默认「' + dt + '」' : '（无标题，可填）';
    }
    if (block.title === '') return '不显示标题';
    return '标题';
  }

  function chipFor(block, index) {
    if (!block.visible) return { cls: 'chip--muted', text: '已隐藏' };
    if (!TYPE_MAP[block.type]) return { cls: 'chip--skip', text: '未知类型 · 手机跳过' };
    if (block.type === 'DIVIDER') return { cls: 'chip--always', text: '始终显示' };
    if (!hasContent(block, currentSample())) return { cls: 'chip--skip', text: '该词无内容 · 跳过' };
    if (block.type === 'EF_STATS') {
      var prev = state.doc.blocks[index - 1];
      if (prev && prev.visible && prev.type === 'DIVIDER') {
        return {
          cls: 'chip--skip',
          text: '双分隔线',
          title: '手机端 EF 块自带一条分隔线，前面再放 DIVIDER 会连出两条'
        };
      }
    }
    return { cls: 'chip--ok', text: '显示' };
  }

  function blockRowHtml(block, index) {
    var info = TYPE_MAP[block.type];
    var name = info ? info.label : (block.type || '未知块');
    var titleCapable = TITLE_CAPABLE.has(block.type);
    var chip = chipFor(block, index);
    var titleValue = block.title === null ? '' : String(block.title);
    var color = block.style && block.style.color ? block.style.color : 'default';
    var size = block.style && block.style.size ? block.style.size : '';
    var total = state.doc.blocks.length;

    var colorOptions = COLORS.map(function (c) {
      return '<option value="' + c.value + '"' + (c.value === color ? ' selected' : '') + '>' + c.label + '</option>';
    }).join('');

    var sizeOptions = SIZES.map(function (s) {
      return '<option value="' + s.value + '"' + (s.value === size ? ' selected' : '') + '>' + s.label + '</option>';
    }).join('');

    return '' +
      '<article class="block-row' + (block.visible ? '' : ' is-hidden') +
        (state.invalidIds.has(block.id) ? ' is-invalid' : '') +
        '" data-id="' + esc(block.id) + '" data-type="' + esc(block.type || '') + '" tabindex="-1">' +

        '<div class="block-head">' +
          '<button class="grip" type="button" data-drag-handle aria-label="按住拖动排序" title="按住拖动排序">' +
            '<svg viewBox="0 0 9 15" fill="currentColor" aria-hidden="true">' +
              '<circle cx="2" cy="2.5" r="1.25"/><circle cx="7" cy="2.5" r="1.25"/>' +
              '<circle cx="2" cy="7.5" r="1.25"/><circle cx="7" cy="7.5" r="1.25"/>' +
              '<circle cx="2" cy="12.5" r="1.25"/><circle cx="7" cy="12.5" r="1.25"/>' +
            '</svg>' +
          '</button>' +
          '<span class="block-index">' + (index + 1) + '</span>' +
          '<span class="type-tag">' + esc(block.type || '?') + '</span>' +
          '<span class="block-name">' + esc(name) + '</span>' +
          '<span class="block-chip ' + chip.cls + '" title="' + esc(chip.title || '') + '">' + esc(chip.text) + '</span>' +
          '<div class="block-actions">' +
            '<button class="icon-btn" type="button" data-act="up" title="上移" aria-label="上移"' + (index === 0 ? ' disabled' : '') + '>' + ICON.up + '</button>' +
            '<button class="icon-btn" type="button" data-act="down" title="下移" aria-label="下移"' + (index === total - 1 ? ' disabled' : '') + '>' + ICON.down + '</button>' +
            '<button class="icon-btn is-danger" type="button" data-act="delete" title="删除这一块" aria-label="删除这一块">' + ICON.trash + '</button>' +
          '</div>' +
        '</div>' +

        '<div class="block-controls">' +
          '<label class="ctl ctl-title' + (titleCapable ? '' : ' is-disabled') + '">' +
            '<span>标题</span>' +
            '<div class="title-wrap">' +
              '<input class="title-input" type="text" maxlength="40" autocomplete="off" spellcheck="false"' +
                ' value="' + esc(titleValue) + '" placeholder="' + esc(titlePlaceholder(block)) + '"' +
                (titleCapable ? '' : ' disabled title="这一块在手机上不渲染标题"') +
                ' aria-label="块标题">' +
              '<button class="title-reset" type="button" title="恢复默认标题" aria-label="恢复默认标题"' +
                (block.title === null || !titleCapable ? ' disabled' : '') + '>' + ICON.reset + '</button>' +
            '</div>' +
          '</label>' +
          '<label class="ctl">' +
            '<span>颜色</span>' +
            '<select class="color-select" aria-label="块颜色" title="手机上 color 只染「标题」这类主文字（例句是整段、EF 是主行）；正文颜色不变，DIVIDER 不受影响">' + colorOptions + '</select>' +
          '</label>' +
          '<label class="ctl">' +
            '<span>字号</span>' +
            '<select class="size-select" aria-label="块内容字号" title="只作用于内容文字；块标题固定 labelLarge，单词标题按词长自适应">' + sizeOptions + '</select>' +
          '</label>' +
          '<label class="switch" title="关掉后手机上这一块不渲染">' +
            '<input class="visible-toggle" type="checkbox"' + (block.visible ? ' checked' : '') + ' aria-label="显示这一块">' +
            '<span class="switch-track" aria-hidden="true"></span>' +
            '<span class="switch-label">' + (block.visible ? '显示' : '隐藏') + '</span>' +
          '</label>' +
        '</div>' +
      '</article>';
  }

  function renderBlocks() {
    var list = $('#block-list');
    var scrollTop = list.scrollTop;
    var count = state.doc.blocks.length;

    if (!count) {
      list.innerHTML = '<div class="empty-state"><b>还没有块</b>从左边组件库点一个加进来，或者点左下角「重置为默认结构」。</div>';
    } else {
      list.innerHTML = state.doc.blocks.map(blockRowHtml).join('');
    }

    list.scrollTop = scrollTop;
    $('#block-count').textContent = count + ' 块';
  }

  /* =========================================================================
     渲染：预览（尽量 1:1 复刻 WordCard 的 showFront=false 分支）
     ========================================================================= */

  function currentSample() {
    return state.samples[state.sampleIndex] || state.samples[0] || { word: '' };
  }

  function hasContent(block, word) {
    switch (block.type) {
      case 'TITLE': return !!str(word.word).trim();
      case 'PHONETIC': return !!str(word.phonetic).trim();
      case 'DIVIDER': return true;
      case 'MEANING': return !!str(word.meaning).trim();
      case 'MEMORY': return toLines(word.approach).length > 0;
      case 'COLLOCATIONS': return toLines(word.collocations).length > 0;
      case 'EXAMPLE': return !!str(word.example).trim();
      case 'PHRASES': return toLines(word.phrases).length > 0;
      case 'SYNONYMS': return toLines(word.synonyms).length > 0;
      case 'REL_WORDS': return toLines(word.relWords).length > 0;
      case 'EF_STATS': return !!(word.progress && typeof word.progress === 'object');
      default: return false;
    }
  }

  function titleOf(block) {
    if (block.title !== null) return block.title;
    var info = TYPE_MAP[block.type];
    return info ? info.defaultTitle : null;
  }

  function hasTitle(block) {
    var t = titleOf(block);
    return typeof t === 'string' && t.trim() !== '';
  }

  function blockAttrs(block, extraProps) {
    var props = [];
    var varName = COLOR_VARS[block.style && block.style.color];
    if (varName) props.push('--blk:var(' + varName + ')');
    var size = block.style && block.style.size;
    var scale = SIZE_SCALE[size];
    /* 手机上 size 不是换字号档位，而是 fontSize/lineHeight 一起缩放 */
    if (scale && scale !== 1) props.push('--cb-scale:' + scale);
    if (extraProps) props.push(extraProps);
    var attrs = props.length ? ' style="' + props.join(';') + '"' : '';
    if (size) attrs += ' data-size="' + size + '"';
    return attrs;
  }

  function titleHtml(block) {
    return hasTitle(block) ? '<div class="cb-title">' + esc(titleOf(block)) + '</div>' : '';
  }

  /* app 里 adaptiveWordText()：词长 > 12 时字符间插零宽空格，避免长词被硬断 */
  function adaptiveWord(word) {
    var w = str(word);
    if (w.trim().length <= 12) return w;
    return Array.from(w).join('\u200B');
  }

  /* backWordStyle：按 trim 后的长度决定单词字号 */
  function wordStyleFor(length) {
    if (length <= 16) return { size: 20, line: 28 };
    if (length <= 24) return { size: 16, line: 24 };
    if (length <= 32) return { size: 14, line: 20 };
    return { size: 18, line: 26 };
  }

  /* formatMeaningByPos：在词性标记前断行 */
  var POS_LOOKAHEAD = '(?=(?:n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|aux)\\.\\s)';
  var MEANING_SPLIT = new RegExp('[；;]\\s*' + POS_LOOKAHEAD + '|\\s+' + POS_LOOKAHEAD, 'g');

  function formatMeaningByPos(text) {
    return str(text).replace(MEANING_SPLIT, '\n').trim();
  }

  function efNumber(value, fallback) {
    var n = Number(value);
    return isFinite(n) ? n : fallback;
  }

  function renderBlockPreview(block, word) {
    var attrs = blockAttrs(block);

    switch (block.type) {
      case 'TITLE': {
        var w = str(word.word).trim();
        if (!w) return '';
        var ws = wordStyleFor(w.length);
        return '<div class="cb-block cb-block--title"' + attrs + '>' +
          '<div class="cb-word" style="font-size:' + ws.size + 'px;line-height:' + ws.line + 'px">' +
            esc(adaptiveWord(str(word.word))) +
          '</div>' +
        '</div>';
      }

      case 'PHONETIC': {
        var phonetic = str(word.phonetic).trim();
        if (!phonetic) return '';
        return '<div class="cb-block"' + blockAttrs(block) + '>' +
          '<div class="cb-phonetic">' + esc(phonetic) + '</div>' +
        '</div>';
      }

      case 'DIVIDER':
        return '<div class="cb-block cb-block--divider"' + attrs + '><div class="cb-divider"></div></div>';

      case 'MEANING': {
        var meaning = formatMeaningByPos(word.meaning);
        if (!meaning) return '';
        return '<div class="cb-block cb-block--meaning"' + attrs + '>' +
          titleHtml(block) +
          '<div class="cb-content">' + esc(meaning) + '</div>' +
        '</div>';
      }

      case 'MEMORY':
      case 'COLLOCATIONS': {
        var lines = toLines(block.type === 'MEMORY' ? word.approach : word.collocations);
        if (!lines.length) return '';
        return '<div class="cb-block cb-block--section"' + attrs + '>' +
          titleHtml(block) +
          '<div class="cb-box">' +
            lines.map(function (line) { return '<div class="cb-content">' + esc(line) + '</div>'; }).join('') +
          '</div>' +
        '</div>';
      }

      case 'EXAMPLE': {
        var example = str(word.example).trim();
        if (!example) return '';
        /* 手机端是一个 Text("例句\n" + example)，标题在盒子里、与正文同字号同色 */
        var head = hasTitle(block) ? str(titleOf(block)).trim() : '';
        var text = head ? head + '\n' + str(word.example) : str(word.example);
        return '<div class="cb-block cb-block--example"' + attrs + '>' +
          '<div class="cb-box cb-box--example"><div class="cb-content">' + esc(text) + '</div></div>' +
        '</div>';
      }

      case 'PHRASES':
      case 'SYNONYMS':
      case 'REL_WORDS': {
        var source = block.type === 'PHRASES' ? word.phrases
          : block.type === 'SYNONYMS' ? word.synonyms
          : word.relWords;
        var extraLines = toLines(source);
        if (!extraLines.length) return '';
        return '<div class="cb-block cb-block--extra"' + attrs + '>' +
          '<div class="cb-box cb-box--extra">' +
            titleHtml(block) +
            /* 手机里正文是一整段 Text，行间只有 lineHeight，没有额外 gap */
            '<div class="cb-content">' + esc(extraLines.join('\n')) + '</div>' +
          '</div>' +
        '</div>';
      }

      case 'EF_STATS': {
        var progress = word.progress;
        if (!progress || typeof progress !== 'object') return '';
        var ef = efNumber(progress.easeFactor, 2.5);
        var offset = ef - 2.5;
        var offsetText = (offset >= 0 ? '+' : '') + offset.toFixed(2);
        var interval = efNumber(progress.intervalDays, 0);
        var reviews = efNumber(progress.reviewCount, 0);
        return '<div class="cb-block cb-block--ef"' + attrs + '>' +
          /* 手机端 EF_STATS 自己会先画一条分隔线（Kotlin 实现如此，契约 §2 没写），照实复刻 */
          '<div class="cb-divider"></div>' +
          '<div class="cb-box cb-box--ef">' +
            '<div class="cb-ef-main">EF系数因子：' + ef.toFixed(2) + '</div>' +
            '<div class="cb-ef-sub">相对基准2.50偏移：' + offsetText + '</div>' +
            '<div class="cb-ef-sub">当前间隔：' + interval + '天 · 复习次数：' + reviews + '</div>' +
          '</div>' +
        '</div>';
      }

      default:
        /* 1.4.2 不认识的 type：手机上跳过，这里也跳过 */
        return '';
    }
  }

  function renderPreview() {
    var word = currentSample();
    var body = $('#cb-body');
    var total = state.doc.blocks.length;
    var shown = 0;
    var hidden = 0;
    var skipped = 0;
    var parts = [];

    body.style.setProperty('--cb-spacing', clampSpacing(state.doc.spacing) + 'px');

    state.doc.blocks.forEach(function (block) {
      if (!block.visible) { hidden += 1; return; }
      var html = renderBlockPreview(block, word);
      if (!html) { skipped += 1; return; }
      shown += 1;
      parts.push(html);
    });

    if (!parts.length) {
      parts.push('<div class="cb-empty-hint">这张卡什么都不显示<br>所有块都隐藏了，或者这个词没有对应内容</div>');
    }

    body.innerHTML = parts.join('');

    var notes = [];
    if (hidden) notes.push(hidden + ' 块已隐藏');
    if (skipped) notes.push(skipped + ' 块该词无内容');
    $('#preview-note').textContent = (word.word || '示例词') + ' · 显示 ' + shown + '/' + total + ' 块' +
      (notes.length ? ' · ' + notes.join(' · ') : '');
  }

  /* =========================================================================
     渲染：顶栏 / 底栏
     ========================================================================= */

  function renderFields() {
    var book = $('#book-input');
    var spacing = $('#spacing-input');
    if (document.activeElement !== book) book.value = str(state.doc.book);
    if (document.activeElement !== spacing) spacing.value = String(clampSpacing(state.doc.spacing));
  }

  function renderServerState() {
    var dot = $('#server-dot');
    var text = $('#server-state-text');
    var s = state.server;

    dot.className = 'dot';
    if (s.status === 'online') {
      dot.classList.add('online');
      text.textContent = '本地服务在线';
      text.title = s.lanUrl || '';
    } else if (s.status === 'offline') {
      dot.classList.add('offline');
      text.textContent = '未连接服务 · 仅本地编辑';
      text.title = '启动 server.py 后刷新页面';
    } else {
      dot.classList.add('connecting');
      text.textContent = '连接服务中…';
      text.title = '';
    }
  }

  function renderChrome() {
    var s = state.server;
    var revision = Number(state.doc.revision) || 0;
    if (!revision && s.revision != null) revision = Number(s.revision);

    $('#revision-badge').textContent = revision ? String(revision) : '—';

    var url = s.lanUrl;
    $('#lan-url').textContent = url || (s.status === 'offline' ? '未连接服务' : '未获取');
    $('#lan-url').title = url || '启动本地服务后自动获取局域网地址';

    var draftState = $('#draft-state');
    if (!state.baselineSig) {
      draftState.textContent = state.lastSavedAt ? '草稿已自动保存' : '本地编辑 · 未发布';
      draftState.dataset.tone = 'muted';
    } else if (computeDirty()) {
      draftState.textContent = '有未发布修改';
      draftState.dataset.tone = 'warn';
    } else {
      draftState.textContent = '与线上一致';
      draftState.dataset.tone = 'ok';
    }

    renderServerState();
  }

  function renderAll() {
    renderFields();
    renderLibrary();
    renderBlocks();
    renderPreview();
    renderChrome();
  }

  /* =========================================================================
     Toast
     ========================================================================= */

  function toast(message, options) {
    var opts = options || {};
    var tone = opts.tone || 'info';
    var duration = typeof opts.duration === 'number' ? opts.duration : 6000;
    var root = $('#toasts');

    var el = document.createElement('div');
    el.className = 'toast toast--' + tone;
    el.setAttribute('role', tone === 'error' ? 'alert' : 'status');

    var icon = tone === 'error' ? '!' : tone === 'success' ? '✓' : 'i';
    el.innerHTML =
      '<span class="toast-icon" aria-hidden="true">' + icon + '</span>' +
      '<div class="toast-body"><div class="toast-msg"></div></div>' +
      (opts.action ? '<button class="toast-action" type="button"></button>' : '') +
      '<button class="toast-close" type="button" aria-label="关闭">×</button>';

    el.querySelector('.toast-msg').textContent = String(message);

    var closed = false;
    function close() {
      if (closed) return;
      closed = true;
      clearTimeout(timer);
      el.classList.add('is-leaving');
      setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 180);
    }

    el.querySelector('.toast-close').addEventListener('click', close);

    if (opts.action) {
      var actionBtn = el.querySelector('.toast-action');
      actionBtn.textContent = opts.action.label;
      actionBtn.addEventListener('click', function () {
        close();
        try { opts.action.onClick(); } catch (err) { console.error('[cardback] toast action', err); }
      });
    }

    root.appendChild(el);
    while (root.children.length > 4) root.removeChild(root.firstElementChild);

    var timer = setTimeout(close, duration);
    el.addEventListener('mouseenter', function () { clearTimeout(timer); });
    return el;
  }

  function clearInvalidMarks() {
    if (!state.invalidIds.size) return;
    state.invalidIds.clear();
    $$('.block-row.is-invalid').forEach(function (row) { row.classList.remove('is-invalid'); });
  }

  /* =========================================================================
     改动提交
     ========================================================================= */

  function touch() {
    clearInvalidMarks();
    state.dirty = computeDirty();
    scheduleDraftSave();
    renderChrome();
  }

  function commit(options) {
    var opts = options || {};
    touch();
    renderPreview();
    if (opts.blocks) renderBlocks();
    if (opts.library) renderLibrary();
    if (opts.fields) renderFields();
  }

  /* =========================================================================
     块操作
     ========================================================================= */

  function addBlock(type) {
    var block = makeBlock(type);
    block.id = nextId();
    state.doc.blocks.push(block);
    commit({ blocks: true, library: true });

    var row = $('.block-row[data-id="' + block.id + '"]');
    if (row) {
      row.classList.add('is-new');
      if (row.scrollIntoView) row.scrollIntoView({ block: 'nearest' });
    }
    toast('已添加「' + (TYPE_MAP[type] ? TYPE_MAP[type].label : type) + '」到末尾', { tone: 'info', duration: 2600 });
  }

  function deleteBlock(id) {
    var index = blockIndexOf(id);
    if (index < 0) return;
    var removed = state.doc.blocks[index];
    state.doc.blocks.splice(index, 1);
    commit({ blocks: true, library: true });

    var label = TYPE_MAP[removed.type] ? TYPE_MAP[removed.type].label : removed.type;
    toast('已删除「' + label + '」', {
      tone: 'info',
      duration: 6000,
      action: {
        label: '撤销',
        onClick: function () {
          var at = Math.min(index, state.doc.blocks.length);
          state.doc.blocks.splice(at, 0, removed);
          commit({ blocks: true, library: true });
          toast('已恢复「' + label + '」', { tone: 'info', duration: 2400 });
        }
      }
    });
  }

  function moveBlock(id, delta) {
    var from = blockIndexOf(id);
    var to = from + delta;
    if (from < 0 || to < 0 || to >= state.doc.blocks.length) return;
    var moved = state.doc.blocks.splice(from, 1)[0];
    state.doc.blocks.splice(to, 0, moved);
    commit({ blocks: true });
  }

  function resetToDefaults() {
    var used = new Set();
    state.doc.blocks = DEFAULT_LAYOUT.map(function (type) {
      var block = makeBlock(type);
      block.id = uniqueId(used);
      return block;
    });
    commit({ blocks: true, library: true });
    toast('已重置为默认的 ' + DEFAULT_LAYOUT.length + ' 个块', { tone: 'info', duration: 3500 });
  }

  /* =========================================================================
     拖动排序（Pointer Events；不碰 HTML5 drag）
     ========================================================================= */

  var drag = null;

  function onListPointerDown(event) {
    var handle = event.target.closest('[data-drag-handle]');
    if (!handle || event.button !== 0) return;
    var row = handle.closest('.block-row');
    if (!row) return;

    event.preventDefault();

    var list = $('#block-list');
    var rect = row.getBoundingClientRect();

    drag = {
      id: row.dataset.id,
      row: row,
      list: list,
      startX: event.clientX,
      startY: event.clientY,
      rowWidth: rect.width,
      rowLeft: rect.left,
      offsetY: event.clientY - rect.top,
      pointerId: event.pointerId,
      active: false,
      ghost: null,
      indicator: null,
      targetIndex: -1
    };

    try { handle.setPointerCapture(event.pointerId); } catch (err) { /* 老浏览器忽略 */ }

    window.addEventListener('pointermove', onListPointerMove);
    window.addEventListener('pointerup', onListPointerUp);
    window.addEventListener('pointercancel', onListPointerCancel);
    window.addEventListener('keydown', onDragKeydown, true);
  }

  function startGhost() {
    var ghost = drag.row.cloneNode(true);
    ghost.classList.add('drag-ghost');
    ghost.classList.remove('is-new');
    ghost.style.width = drag.rowWidth + 'px';
    ghost.style.left = drag.rowLeft + 'px';
    ghost.style.top = drag.startY + 'px';

    /* cloneNode 只复制 value 属性，不复制用户刚改过的值；手动同步一次 */
    var srcFields = drag.row.querySelectorAll('input, select');
    var dstFields = ghost.querySelectorAll('input, select');
    for (var i = 0; i < srcFields.length; i += 1) {
      var src = srcFields[i];
      var dst = dstFields[i];
      if (!dst) continue;
      if (src.type === 'checkbox') dst.checked = src.checked;
      else dst.value = src.value;
    }
    ghost.querySelectorAll('button, input, select').forEach(function (node) {
      node.setAttribute('tabindex', '-1');
    });

    document.body.appendChild(ghost);

    var indicator = document.createElement('div');
    indicator.className = 'drop-indicator';
    drag.list.appendChild(indicator);

    drag.ghost = ghost;
    drag.indicator = indicator;
    drag.row.classList.add('drag-source');
    document.body.classList.add('is-dragging');
  }

  function updateGhost(clientY) {
    if (!drag.ghost) return;
    drag.ghost.style.top = (clientY - drag.offsetY) + 'px';
  }

  function autoScroll(clientY) {
    var listRect = drag.list.getBoundingClientRect();
    var edge = 48;
    if (clientY < listRect.top + edge) {
      drag.list.scrollTop -= 14;
    } else if (clientY > listRect.bottom - edge) {
      drag.list.scrollTop += 14;
    }
  }

  function updateDropTarget(clientY) {
    var listRect = drag.list.getBoundingClientRect();
    var rows = $$('.block-row', drag.list).filter(function (row) { return row !== drag.row; });
    var index = rows.length;
    var viewportY = null;

    for (var i = 0; i < rows.length; i += 1) {
      var rect = rows[i].getBoundingClientRect();
      if (clientY < rect.top + rect.height / 2) {
        index = i;
        viewportY = rect.top - 4;
        break;
      }
    }

    if (viewportY === null) {
      var last = rows[rows.length - 1];
      viewportY = last ? last.getBoundingClientRect().bottom + 4 : listRect.top + 12;
    }

    drag.targetIndex = index;
    drag.indicator.style.top = Math.max(2, viewportY - listRect.top + drag.list.scrollTop) + 'px';
  }

  function onListPointerMove(event) {
    if (!drag || event.pointerId !== drag.pointerId) return;

    if (!drag.active) {
      if (Math.abs(event.clientY - drag.startY) < 4 && Math.abs(event.clientX - drag.startX) < 4) return;
      drag.active = true;
      startGhost();
    }

    updateGhost(event.clientY);
    autoScroll(event.clientY);
    updateDropTarget(event.clientY);
  }

  function endDrag() {
    if (!drag) return;
    var hadGhost = !!drag.ghost;

    if (drag.ghost && drag.ghost.parentNode) drag.ghost.parentNode.removeChild(drag.ghost);
    if (drag.indicator && drag.indicator.parentNode) drag.indicator.parentNode.removeChild(drag.indicator);
    drag.row.classList.remove('drag-source');
    document.body.classList.remove('is-dragging');

    window.removeEventListener('pointermove', onListPointerMove);
    window.removeEventListener('pointerup', onListPointerUp);
    window.removeEventListener('pointercancel', onListPointerCancel);
    window.removeEventListener('keydown', onDragKeydown, true);

    var result = { id: drag.id, targetIndex: drag.targetIndex, active: drag.active };
    drag = null;
    return { hadGhost: hadGhost, result: result };
  }

  function onListPointerUp(event) {
    if (!drag || event.pointerId !== drag.pointerId) return;
    var info = endDrag();
    if (!info || !info.result.active) return;

    var from = blockIndexOf(info.result.id);
    var to = info.result.targetIndex;
    if (from < 0) return;
    if (to > state.doc.blocks.length - 1) to = state.doc.blocks.length - 1;
    if (to === from) return;

    var moved = state.doc.blocks.splice(from, 1)[0];
    state.doc.blocks.splice(to, 0, moved);
    commit({ blocks: true });
  }

  function onListPointerCancel() {
    endDrag();
  }

  function onDragKeydown(event) {
    if (event.key === 'Escape' && drag) {
      event.preventDefault();
      endDrag();
    }
  }

  /* =========================================================================
     事件：组件库 / 块列表 / 顶栏 / 底栏
     ========================================================================= */

  function onListInput(event) {
    var input = event.target;
    if (!input.classList || !input.classList.contains('title-input')) return;
    var row = input.closest('.block-row');
    var block = row ? blockById(row.dataset.id) : null;
    if (!block) return;

    /* 清空 = 显式不显示标题（title: ""），点 ↺ 才回到 null＝默认标题 */
    block.title = input.value;
    var reset = row.querySelector('.title-reset');
    if (reset) reset.disabled = block.title === null;
    input.placeholder = titlePlaceholder(block);

    touch();
    renderPreview();
  }

  function onListChange(event) {
    var target = event.target;
    var row = target.closest ? target.closest('.block-row') : null;
    var block = row ? blockById(row.dataset.id) : null;
    if (!block) return;

    if (target.classList.contains('color-select')) {
      block.style.color = target.value;
      commit({});
    } else if (target.classList.contains('size-select')) {
      if (target.value) block.style.size = target.value;
      else delete block.style.size;
      commit({});
    } else if (target.classList.contains('visible-toggle')) {
      block.visible = target.checked;
      commit({ blocks: true });
    }
  }

  function onListClick(event) {
    var target = event.target;
    var row = target.closest ? target.closest('.block-row') : null;
    if (!row) return;
    var id = row.dataset.id;

    var actBtn = target.closest('[data-act]');
    if (actBtn) {
      var act = actBtn.dataset.act;
      if (act === 'up') moveBlock(id, -1);
      else if (act === 'down') moveBlock(id, 1);
      else if (act === 'delete') deleteBlock(id);
      return;
    }

    var resetBtn = target.closest('.title-reset');
    if (resetBtn) {
      event.preventDefault();
      var block = blockById(id);
      if (!block || block.title === null) return;
      block.title = null;
      commit({ blocks: true });
      toast('标题已恢复为默认', { tone: 'info', duration: 2200 });
    }
  }

  function onListKeydown(event) {
    if (!event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return;
    var row = event.target.closest ? event.target.closest('.block-row') : null;
    if (!row) return;
    event.preventDefault();
    moveBlock(row.dataset.id, event.key === 'ArrowUp' ? -1 : 1);
    var again = $('.block-row[data-id="' + row.dataset.id + '"]');
    if (again) again.focus();
  }

  function onListPointerDownOnGrip(event) {
    onListPointerDown(event);
  }

  /* =========================================================================
     HTTP
     ========================================================================= */

  function requestJson(url, options) {
    var opts = options || {};
    return fetch(url, opts).catch(function (err) {
      throw new AppError('连不上本地服务：' + (err && err.message ? err.message : '网络错误'), 'network');
    }).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        if (text) {
          try { data = JSON.parse(text); } catch (err) { data = null; }
        }

        if (!res.ok) {
          var apiMessage = data && data.error ? String(data.error) : '';
          var message = apiMessage || ('请求失败：HTTP ' + res.status + (text && !data ? '（返回的不是 JSON）' : ''));
          throw new AppError(message, apiMessage ? 'api' : 'http');
        }

        if (data && data.ok === false) {
          throw new AppError(String(data.error || '服务端返回失败'), 'api');
        }
        return data;
      });
    });
  }

  function syncState(options) {
    var opts = options || {};
    var silent = opts.silent !== false;

    state.server.status = 'connecting';
    renderChrome();

    return requestJson('/api/state', { cache: 'no-store' }).then(function (data) {
      var payload = data || {};
      state.server = {
        status: 'online',
        revision: isFinite(Number(payload.revision)) ? Number(payload.revision) : null,
        updatedAt: payload.updatedAt || null,
        publishedAt: payload.publishedAt || null,
        lanUrl: typeof payload.lanUrl === 'string' && payload.lanUrl ? payload.lanUrl : null,
        historyCount: isFinite(Number(payload.historyCount)) ? Number(payload.historyCount) : null
      };
      if (!Number(state.doc.revision) && state.server.revision != null) {
        state.doc.revision = state.server.revision;
      }
      renderChrome();
      return true;
    }).catch(function (err) {
      state.server.status = 'offline';
      renderChrome();
      if (!silent || err.kind === 'api') {
        toast(err.message, { tone: 'error', duration: 9000 });
      }
      return false;
    });
  }

  /* 从服务端拉当前线上配置（GET /cardback.json，没有 ok 包装） */
  function pullConfig() {
    return requestJson('/cardback.json', { cache: 'no-store' }).then(function (raw) {
      var doc = normalizeDoc(raw);
      if (!doc) throw new AppError('服务端的 cardback.json 结构不认识（缺 blocks？）', 'api');
      state.doc = doc;
      state.baselineSig = signature();
      state.dirty = false;
      renderAll();
      return doc;
    });
  }

  /* =========================================================================
     校验 + 发布
     ========================================================================= */

  function validate() {
    var errors = [];
    var blocks = state.doc.blocks;

    if (!blocks.length) {
      errors.push({ id: null, message: '至少要有一个块，空卡背不能发布。' });
      return errors;
    }

    var seen = new Set();
    blocks.forEach(function (block) {
      if (!block.id || typeof block.id !== 'string') {
        errors.push({ id: block.id, message: '有块没有 id。' });
      } else if (seen.has(block.id)) {
        errors.push({ id: block.id, message: '块 id 重复：' + block.id });
      } else {
        seen.add(block.id);
      }

      if (!TYPE_MAP[block.type]) {
        errors.push({ id: block.id, message: '块类型不合法：' + (block.type || '(空)') + '（1.4.2 不认识这个类型，手机端也会跳过）' });
      }

      if (typeof block.title !== 'string' && block.title !== null) {
        errors.push({ id: block.id, message: '块标题必须是文字或留空。' });
      }
    });

    return errors;
  }

  function showValidation(errors) {
    var first = errors[0];
    state.invalidIds = new Set(errors.map(function (e) { return e.id; }).filter(Boolean));
    renderBlocks();

    var row = first.id ? $('.block-row[data-id="' + first.id + '"]') : null;
    if (row && row.scrollIntoView) row.scrollIntoView({ block: 'center' });

    toast(errors.length > 1 ? first.message + '（还有 ' + (errors.length - 1) + ' 个问题）' : first.message, {
      tone: 'error',
      duration: 9000
    });
  }

  function setBusy(button, busy) {
    if (!button) return;
    button.classList.toggle('is-busy', !!busy);
    button.disabled = !!busy;
  }

  function publish() {
    var errors = validate();
    if (errors.length) {
      showValidation(errors);
      return;
    }

    var button = $('#publish');
    setBusy(button, true);

    var payload = serialize();
    /* 服务端可能用 revision+1 生成新版本；取两边最大值，避免把线上版本号写回去 */
    payload.revision = Math.max(Number(state.doc.revision) || 0, Number(state.server.revision) || 0);

    requestJson('/api/publish', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(payload)
    }).then(function (data) {
      var result = data || {};
      if (isFinite(Number(result.revision))) state.doc.revision = Number(result.revision);
      else state.doc.revision = payload.revision + 1;

      state.baselineSig = signature();
      state.dirty = false;
      state.lastSavedAt = null;

      /* 已发布的配置就是服务端的真源，草稿没必要留着 */
      try { localStorage.removeItem(STORAGE_KEY); } catch (err) { /* ignore */ }
      clearTimeout(draftTimer);

      renderChrome();
      toast('已发布 revision ' + state.doc.revision + ' · 手机上点刷新即生效', { tone: 'success', duration: 5500 });
      return syncState({ silent: true });
    }).catch(function (err) {
      toast(err.message, { tone: 'error', duration: 9000 });
    }).then(function () {
      setBusy(button, false);
    });
  }

  /* =========================================================================
     历史版本 / 回退
     ========================================================================= */

  function openHistory() {
    var dialog = $('#history-dialog');
    if (!dialog) return;
    if (typeof dialog.showModal === 'function') {
      if (!dialog.open) dialog.showModal();
    } else {
      dialog.setAttribute('open', '');
    }
    loadHistory();
  }

  function closeHistory() {
    var dialog = $('#history-dialog');
    if (!dialog) return;
    if (typeof dialog.close === 'function') dialog.close();
    else dialog.removeAttribute('open');
  }

  function loadHistory() {
    var list = $('#history-list');
    list.innerHTML = '<div class="history-empty">读取中…</div>';

    requestJson('/api/history', { cache: 'no-store' }).then(function (data) {
      var items = Array.isArray(data) ? data : (data && Array.isArray(data.history) ? data.history : (data && Array.isArray(data.items) ? data.items : []));
      var current = state.server.revision != null ? state.server.revision : (Number(state.doc.revision) || 0);

      items = items.filter(function (it) { return it && isFinite(Number(it.revision)); });
      items.sort(function (a, b) { return Number(b.revision) - Number(a.revision); });

      $('#history-title').textContent = '历史版本 · ' + items.length + ' 版';

      if (!items.length) {
        list.innerHTML = '<div class="history-empty">还没有历史版本。<br>发布两次以上之后，旧版本会出现在这里。</div>';
        return;
      }

      list.innerHTML = items.map(function (item) {
        var rev = Number(item.revision);
        var isCurrent = rev === current;
        return '' +
          '<div class="history-item' + (isCurrent ? ' is-current' : '') + '">' +
            '<span class="history-rev">v' + rev + '</span>' +
            '<div class="history-main">' +
              '<div class="history-time">' + esc(formatTime(item.savedAt)) + '</div>' +
              '<div class="history-sub">' + (isCurrent ? '当前线上版本' : '回退到此版本') + '</div>' +
            '</div>' +
            '<button class="btn btn-sm btn-outline" type="button" data-rollback="' + rev + '"' +
              (isCurrent ? ' disabled' : '') + '>' + (isCurrent ? '当前' : '回退') + '</button>' +
          '</div>';
      }).join('');
    }).catch(function (err) {
      list.innerHTML = '<div class="history-error">' + esc(err.message) + '</div>';
    });
  }

  var armedButton = null;
  var armTimer = null;

  function disarm() {
    clearTimeout(armTimer);
    if (armedButton) {
      armedButton.classList.remove('is-armed');
      armedButton.textContent = '回退';
      armedButton.dataset.armed = '';
    }
    armedButton = null;
  }

  function onHistoryClick(event) {
    var target = event.target;

    if (target.closest('[data-close]')) {
      closeHistory();
      return;
    }
    if (target.closest('#history-reload')) {
      disarm();
      loadHistory();
      return;
    }

    var btn = target.closest('[data-rollback]');
    if (!btn || btn.disabled) return;
    var rev = Number(btn.dataset.rollback);

    if (btn.dataset.armed === '1') {
      var confirmed = btn;
      disarm();
      rollbackTo(rev, confirmed);
      return;
    }

    disarm();
    armedButton = btn;
    btn.dataset.armed = '1';
    btn.classList.add('is-armed');
    btn.textContent = '确认回退？';
    armTimer = setTimeout(disarm, 4000);
  }

  function rollbackTo(revision, button) {
    setBusy(button, true);

    requestJson('/api/rollback', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ revision: revision })
    }).then(function () {
      closeHistory();
      return syncState({ silent: true }).then(function () {
        return pullConfig().catch(function (err) {
          toast('回退已执行，但读取新配置失败：' + err.message, { tone: 'error', duration: 9000 });
          return null;
        });
      });
    }).then(function (doc) {
      if (!doc) return;
      try { localStorage.removeItem(STORAGE_KEY); } catch (err) { /* ignore */ }
      toast('已回退到 revision ' + revision + ' · 手机上刷新即生效', { tone: 'success', duration: 5500 });
    }).catch(function (err) {
      toast(err.message, { tone: 'error', duration: 9000 });
    }).then(function () {
      setBusy(button, false);
    });
  }

  /* =========================================================================
     剪贴板
     ========================================================================= */

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text).then(function () { return true; }).catch(function () {
        return fallbackCopy(text);
      });
    }
    return Promise.resolve(fallbackCopy(text));
  }

  function fallbackCopy(text) {
    var area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.top = '0';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (err) { ok = false; }
    document.body.removeChild(area);
    return ok;
  }

  /* =========================================================================
     绑定
     ========================================================================= */

  function bindEvents() {
    var list = $('#block-list');

    $('#library-list').addEventListener('click', function (event) {
      var item = event.target.closest('[data-add]');
      if (item) addBlock(item.dataset.add);
    });

    list.addEventListener('pointerdown', onListPointerDownOnGrip);
    list.addEventListener('input', onListInput);
    list.addEventListener('change', onListChange);
    list.addEventListener('click', onListClick);
    list.addEventListener('keydown', onListKeydown);

    $('#sample-select').addEventListener('change', function (event) {
      state.sampleIndex = Number(event.target.value) || 0;
      renderPreview();
      renderBlocks();
      scheduleDraftSave();
    });

    $('#book-input').addEventListener('input', function (event) {
      state.doc.book = event.target.value;
      touch();
    });

    $('#spacing-input').addEventListener('input', function (event) {
      var n = Number(event.target.value);
      if (isFinite(n) && n >= MIN_SPACING && n <= MAX_SPACING) {
        state.doc.spacing = Math.round(n);
        touch();
        renderPreview();
      }
    });

    $('#spacing-input').addEventListener('change', function (event) {
      var value = clampSpacing(event.target.value);
      event.target.value = String(value);
      state.doc.spacing = value;
      touch();
      renderPreview();
    });

    $('#show-all').addEventListener('click', function () {
      var hidden = 0;
      state.doc.blocks.forEach(function (block) {
        if (!block.visible) hidden += 1;
        block.visible = true;
      });
      if (!hidden) {
        toast('所有块都已经是显示状态', { tone: 'info', duration: 2400 });
        return;
      }
      commit({ blocks: true });
      toast('已恢复显示 ' + hidden + ' 个块', { tone: 'info', duration: 2400 });
    });

    var resetButton = $('#reset-layout');
    var resetHtml = resetButton.innerHTML;
    var resetTimer = null;
    resetButton.addEventListener('click', function () {
      if (resetButton.dataset.armed === '1') {
        clearTimeout(resetTimer);
        resetButton.dataset.armed = '';
        resetButton.innerHTML = resetHtml;
        resetButton.classList.remove('is-armed');
        resetToDefaults();
        return;
      }
      resetButton.dataset.armed = '1';
      resetButton.textContent = '再点一次确认重置';
      resetButton.classList.add('is-armed');
      resetTimer = setTimeout(function () {
        resetButton.dataset.armed = '';
        resetButton.innerHTML = resetHtml;
        resetButton.classList.remove('is-armed');
      }, 4000);
    });

    $('#save-draft').addEventListener('click', function () {
      saveDraft(true);
    });

    $('#publish').addEventListener('click', publish);
    $('#open-history').addEventListener('click', openHistory);

    $('#copy-url').addEventListener('click', function () {
      if (!state.server.lanUrl) {
        toast('还没有地址：先启动本地服务（server.py），再刷新页面。', { tone: 'error', duration: 7000 });
        return;
      }
      copyText(state.server.lanUrl).then(function (ok) {
        toast(ok ? '地址已复制：' + state.server.lanUrl : '复制失败，请手动选中复制', {
          tone: ok ? 'success' : 'error',
          duration: 4200
        });
      });
    });

    $('#copy-json').addEventListener('click', function () {
      var text = JSON.stringify(serialize(), null, 2);
      copyText(text).then(function (ok) {
        toast(ok ? '已复制完整 JSON（' + state.doc.blocks.length + ' 块）' : '复制失败，试试 Ctrl+S 存草稿', {
          tone: ok ? 'success' : 'error',
          duration: 4200
        });
      });
    });

    $('#history-dialog').addEventListener('click', function (event) {
      if (event.target === event.currentTarget) {
        closeHistory();
        return;
      }
      onHistoryClick(event);
    });

    document.addEventListener('keydown', function (event) {
      var mod = event.ctrlKey || event.metaKey;
      if (mod && (event.key === 's' || event.key === 'S')) {
        event.preventDefault();
        saveDraft(true);
      }
    });

    window.addEventListener('focus', function () {
      var now = Date.now();
      if (now - lastFocusSync < 8000) return;
      lastFocusSync = now;
      if (state.server.status === 'online') syncState({ silent: true });
    });

    window.addEventListener('beforeunload', function () {
      saveDraft(false);
    });

    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden') saveDraft(false);
    });
  }

  /* =========================================================================
     启动
     ========================================================================= */

  function init() {
    var provided = window.CARDBACK_SAMPLES;
    state.samples = Array.isArray(provided) && provided.length ? provided : [{
      word: 'sample',
      phonetic: '/ˈsɑːmpl/',
      meaning: 'n. 示例；样品',
      example: 'This is a sample sentence.\n这是一句示例。',
      approach: [],
      collocations: [],
      phrases: '',
      synonyms: '',
      relWords: '',
      progress: null
    }];

    var draft = loadDraft();
    if (draft) {
      state.doc = draft.doc;
      if (Number.isInteger(draft.sampleIndex) && draft.sampleIndex >= 0 && draft.sampleIndex < state.samples.length) {
        state.sampleIndex = draft.sampleIndex;
      }
    } else {
      state.doc = createDefaultDoc();
    }

    state.baselineSig = null;
    state.dirty = true;

    renderSampleSelect();
    renderAll();
    bindEvents();

    if (draft) {
      toast('已恢复 ' + formatTime(draft.savedAt) + ' 的本地草稿', { tone: 'info', duration: 4500 });
    }

    syncState({ silent: true }).then(function (online) {
      if (online && !draft) {
        pullConfig().catch(function (err) {
          /* 没有已发布版本是正常情况（404），只有结构错误才值得一提 */
          if (err.kind === 'api') toast('读取线上配置失败：' + err.message, { tone: 'error', duration: 8000 });
        });
      }
    });
  }

  function renderSampleSelect() {
    var select = $('#sample-select');
    select.innerHTML = state.samples.map(function (sample, index) {
      return '<option value="' + index + '">' + esc(sample.word || ('示例 ' + (index + 1))) + '</option>';
    }).join('');
    select.value = String(state.sampleIndex);
  }

  /* 调试/自动化钩子：读当前文档、读状态、加块，不改变任何行为 */
  window.CardbackEditor = {
    getDoc: function () { return serialize(); },
    getStatus: function () {
      return {
        blocks: state.doc.blocks.length,
        revision: state.doc.revision,
        serverRevision: state.server.revision,
        online: state.server.status === 'online',
        dirty: computeDirty(),
        sampleIndex: state.sampleIndex,
        invalid: state.invalidIds.size
      };
    },
    addBlock: addBlock,
    reset: resetToDefaults
  };

  init();
})();

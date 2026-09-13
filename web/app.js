/* ===== 牛逼笔记本 前端逻辑 ===== */
"use strict";

const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];

// ---------- 状态 ----------
const state = {
  tree: null,            // 分类树
  notes: [],             // 当前列表
  scopeFilter: "",       // 当前筛选 scope
  catFilter: "",         // 当前筛选 categoryId
  selected: new Set(),   // 勾选的笔记 id
  editing: null,         // 当前编辑笔记 id（null=新建）
  editMode: "edit",      // edit | source
  ollamaOk: false,
  extractImages: [],     // 提取出的图
  cropRect: null,
  cropImage: null,
};

// ---------- API ----------
async function api(path, opts = {}) {
  const res = await fetch(path, opts);
  const ct = res.headers.get("content-type") || "";
  if (ct.includes("application/json")) {
    const j = await res.json();
    if (!res.ok || j.ok === false) throw new Error(j.error || ("HTTP " + res.status));
    return j;
  }
  if (!res.ok) throw new Error("HTTP " + res.status);
  return await res.text();
}
const postJson = (path, body) => api(path, {
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body)
});

// ---------- 初始化 ----------
async function init() {
  bindEvents();
  try {
    const st = await api("/api/status");
    state.ollamaOk = !!st.model;
    const pill = $("#ollamaStatus");
    if (state.ollamaOk) { pill.textContent = "AI: " + st.model + " ✓"; pill.classList.remove("err"); }
    else { pill.textContent = "AI 未连接"; pill.classList.add("err"); }
  } catch (e) {
    $("#ollamaStatus").textContent = "AI 未连接"; $("#ollamaStatus").classList.add("err");
  }
  await loadTree();
  await loadNotes();
}

async function loadTree() {
  state.tree = await api("/api/tree");
  renderTree();
  renderScopeOptions();
}
async function loadNotes() {
  const q = new URLSearchParams();
  if (state.scopeFilter) q.set("scope", state.scopeFilter);
  if (state.catFilter) q.set("categoryId", state.catFilter);
  const j = await api("/api/notes?" + q.toString());
  state.notes = j.notes;
  renderNotes();
}

// ---------- 分类树 ----------
function nodeCount(node, scope) {
  let n = 0;
  for (const nt of state.notes) {
    if (!scope || nt.scope === scope) {
      if (node.id === "root" || nt.categoryId === node.id) n++;
    }
  }
  return n;
}
function renderTree() {
  const root = $("#treeRoot");
  root.innerHTML = "";
  const scopeCounts = {};
  for (const n of state.notes) scopeCounts[n.scope || "默认"] = (scopeCounts[n.scope || "默认"] || 0) + 1;
  const rootRow = row("root", "📁 全部笔记", state.notes.length, state.scopeFilter === "" && state.catFilter === "");
  root.appendChild(rootRow);
  const rootKids = document.createElement("div");
  rootKids.className = "tree-children";
  for (const scope of (state.tree.children || [])) {
    rootKids.appendChild(scopeNodeEl(scope));
  }
  root.appendChild(rootKids);
}
function scopeNodeEl(scopeNode) {
  const wrap = document.createElement("div");
  wrap.className = "tree-node";
  const cnt = scopeCountOf(scopeNode);
  const r = row(scopeNode.id, "📂 " + scopeNode.name, cnt,
    state.scopeFilter === scopeNode.name && state.catFilter === "");
  r.dataset.scope = scopeNode.name; r.dataset.kind = "scope";
  wrap.appendChild(r);
  const kids = document.createElement("div");
  kids.className = "tree-children";
  if (cnt === 0) kids.classList.add("collapsed");
  for (const c of (scopeNode.children || [])) kids.appendChild(catNodeEl(c, scopeNode));
  wrap.appendChild(kids);
  return wrap;
}
function scopeCountOf(scopeNode) {
  let n = 0;
  for (const nt of state.notes) if (nt.scope === scopeNode.name) n++;
  return n;
}
function catNodeEl(node, scopeNode) {
  const wrap = document.createElement("div");
  wrap.className = "tree-node";
  const cnt = countByCat(node.id);
  const r = row(node.id, node.name, cnt,
    state.scopeFilter === scopeNode.name && state.catFilter === node.id);
  r.dataset.scope = scopeNode.name; r.dataset.cat = node.id; r.dataset.kind = "cat";
  wrap.appendChild(r);
  const kids = document.createElement("div");
  kids.className = "tree-children";
  if (cnt === 0) kids.classList.add("collapsed");
  for (const c of (node.children || [])) kids.appendChild(catNodeEl(c, scopeNode));
  wrap.appendChild(kids);
  return wrap;
}
function countByCat(catId) {
  let n = 0;
  for (const nt of state.notes) if (nt.categoryId === catId) n++;
  return n;
}
function row(id, name, cnt, active) {
  const r = document.createElement("div");
  r.className = "tree-row" + (active ? " active" : "");
  r.dataset.id = id;
  const arrow = document.createElement("span");
  arrow.className = "arrow";
  arrow.textContent = "▶";
  const nm = document.createElement("span");
  nm.className = "name"; nm.textContent = name;
  const c = document.createElement("span");
  c.className = "cnt"; c.textContent = cnt;
  r.append(arrow, nm, c);
  return r;
}

// ---------- 笔记列表 ----------
function renderNotes() {
  const grid = $("#noteGrid");
  grid.innerHTML = "";
  const q = ($("#searchInput").value || "").trim().toLowerCase();
  const list = state.notes.filter(n => !q || (n.title || "").toLowerCase().includes(q) || (n.content || "").toLowerCase().includes(q));
  $("#noteCount").textContent = list.length + " 条";
  $("#emptyState").classList.toggle("hidden", list.length > 0);
  grid.classList.toggle("hidden", list.length === 0);
  if (q) state.selected.clear(), $("#checkAll").checked = false;
  for (const n of list) grid.appendChild(card(n));
  updatePrintCount();
}
function card(n) {
  const el = document.createElement("div");
  el.className = "note-card" + (state.selected.has(n.id) ? " selected" : "");
  const t = document.createElement("div"); t.className = "t"; t.textContent = n.title || "无标题";
  const path = document.createElement("div"); path.className = "path";
  path.textContent = [n.scope, ...(n.category || [])].filter(Boolean).join(" › ");
  const meta = document.createElement("div"); meta.className = "meta";
  const time = document.createElement("span"); time.textContent = (n.updated || "").slice(0, 16);
  const badges = document.createElement("span"); badges.className = "media-badges";
  if (n.media) {
    if (n.media.video) badges.innerHTML += '<span class="mb video">🎬 视频</span>';
    if (n.media.audio) badges.innerHTML += '<span class="mb audio">🎵 音频</span>';
    if (n.media.images > 0) badges.innerHTML += `<span class="mb">🖼${n.media.images}</span>`;
    if (n.media.video || n.media.audio) badges.innerHTML += '<span class="mb warn" title="打印时将去除">打印去除</span>';
  }
  meta.append(time, badges);
  const chk = document.createElement("input");
  chk.type = "checkbox"; chk.className = "chk";
  chk.checked = state.selected.has(n.id);
  chk.addEventListener("click", ev => ev.stopPropagation());
  chk.addEventListener("change", () => {
    state.selected.has(n.id) ? state.selected.delete(n.id) : state.selected.add(n.id);
    el.classList.toggle("selected", state.selected.has(n.id));
    updatePrintCount();
  });
  el.append(chk, t, path, meta);
  el.addEventListener("click", () => openEditor(n.id));
  return el;
}
function updatePrintCount() {
  const c = state.selected.size;
  $("#printCount").textContent = c;
  $("#btnPrint").classList.toggle("primary", c > 0);
}

// ---------- 编辑器 ----------
async function openEditor(id) {
  state.editing = id;
  const meta = await api("/api/notes/" + id);
  $("#noteTitle").value = meta.title || "";
  $("#noteScope").value = meta.scope || "默认";
  $("#noteCategory").value = (meta.category || []).join(" › ");
  $("#srcBox").value = meta.content || "";
  syncPreviewFromSource();
  showEditor();
  $("#aiResult").classList.add("hidden");
}
function newNote() {
  state.editing = null;
  $("#noteTitle").value = "";
  $("#noteScope").value = state.scopeFilter || "默认";
  $("#noteCategory").value = "";
  $("#srcBox").value = "";
  syncPreviewFromSource();
  showEditor();
  $("#aiResult").classList.add("hidden");
  $("#noteTitle").focus();
}
function showEditor() { $("#editorPane").classList.remove("hidden"); }
function closeEditor() { /* 保留面板，方便连续记录 */ }

function syncPreviewFromSource() {
  $("#previewBox").innerHTML = $("#srcBox").value || "";
}
function syncSourceFromPreview() {
  $("#srcBox").value = $("#previewBox").innerHTML;
}

/** 确保当前笔记已落盘，返回 id；新建则先创建草稿 */
async function ensureSaved() {
  if (state.editing) return state.editing;
  const title = $("#noteTitle").value.trim() || "无标题笔记";
  const scope = $("#noteScope").value || "默认";
  const content = $("#srcBox").value || "";
  const meta = await postJson("/api/notes", { title, scope, content, autoClassify: 0 });
  state.editing = meta.id;
  return meta.id;
}

async function saveNote(autoClassify = false) {
  if (state.editMode === "source") syncPreviewFromSource(); else syncSourceFromPreview();
  const content = $("#srcBox").value;
  const title = $("#noteTitle").value.trim() || "无标题笔记";
  const scope = $("#noteScope").value || "默认";
  const isNew = !state.editing;
  let meta;
  if (isNew) {
    meta = await postJson("/api/notes", { title, scope, content, autoClassify: autoClassify ? 1 : 0 });
    state.editing = meta.id;
  } else {
    meta = await api("/api/notes/" + state.editing, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title, scope, content }) });
  }
  if (autoClassify) {
    const res = await postJson("/api/notes/classify-again", { id: state.editing, scope });
    if (res.ok && res.path && res.path.length) {
      $("#noteCategory").value = res.path.join(" › ");
      showAiResult("✨ AI 归类为：" + res.path.join(" › ") + (res.reason ? "（" + res.reason + "）" : ""), false);
    } else {
      showAiResult("AI 分类失败：" + (res.error || "未知错误"), true);
    }
  }
  await loadTree();
  await loadNotes();
  return meta;
}

function showAiResult(msg, isErr) {
  const el = $("#aiResult");
  el.textContent = msg;
  el.className = "ai-result" + (isErr ? " err" : "");
  el.classList.remove("hidden");
}

async function deleteNote() {
  if (!state.editing) return;
  if (!confirm("确定删除这篇笔记？不可恢复。")) return;
  await api("/api/notes/" + state.editing, { method: "DELETE" });
  state.selected.delete(state.editing);
  state.editing = null;
  $("#editorPane").classList.add("hidden");
  await loadTree(); await loadNotes();
}

// ---------- 编辑工具（插入 HTML 到光标） ----------
function insertHtml(html) {
  const box = $("#previewBox");
  box.focus();
  box.contentEditable = "true";
  const sel = window.getSelection();
  if (sel.rangeCount === 0) { box.innerHTML += html; }
  else {
    const range = sel.getRangeAt(0);
    range.deleteContents();
    const frag = range.createContextualFragment(html);
    const last = frag.lastChild;
    range.insertNode(frag);
    if (last) { range.setStartAfter(last); range.collapse(true); sel.removeAllRanges(); sel.addRange(range); }
  }
  syncSourceFromPreview();
}
const wrapSel = (open, close) => {
  const box = $("#previewBox"); box.focus();
  const sel = window.getSelection();
  const selText = sel.toString() || "内容";
  insertHtml(open + selText + close);
};
const toolCmds = {
  h2: () => insertHtml("<h2>小节标题</h2>"),
  h3: () => insertHtml("<h3>小标题</h3>"),
  bold: () => wrapSel("<b>", "</b>"),
  italic: () => wrapSel("<i>", "</i>"),
  ul: () => insertHtml("<ul><li>条目</li><li>条目</li></ul>"),
  ol: () => insertHtml("<ol><li>第一步</li><li>第二步</li></ol>"),
  table: () => insertHtml("<table><tr><th>名称</th><th>要点</th></tr><tr><td></td><td></td></tr></table>"),
  code: () => wrapSel("<pre><code>", "</code></pre>"),
  hr: () => insertHtml("<hr>"),
};

// ---------- 上传媒体 ----------
async function uploadMedia(file, noteId) {
  const fd = new FormData();
  fd.append("file", file);
  fd.append("noteId", noteId);
  const j = await api("/api/notes/upload", { method: "POST", body: fd });
  return j.url;
}

// ---------- 打印 ----------
async function doPrint() {
  const ids = [...state.selected];
  if (ids.length === 0) { alert("请先勾选要打印的笔记"); return; }
  // 提示含视频/音频的笔记
  const mediaNotes = state.notes.filter(n => ids.includes(n.id) && n.media && (n.media.video || n.media.audio));
  if (mediaNotes.length > 0) {
    const names = mediaNotes.map(n => "《" + n.title + "》").join("、");
    if (!confirm("以下笔记含有视频/音频，打印时将被自动去除（只保留文字和图片）：\n\n" + names + "\n\n继续打印？")) return;
  }
  try {
    const html = await api("/api/print", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ids }) });
    // 优先新窗口（桌面浏览器）；被拦截时（如手机 WebView）退回 iframe 打印
    const w = window.open("", "_blank", "width=900,height=1000");
    if (w) {
      w.document.open();
      w.document.write(html);
      w.document.close();
    } else {
      const iframe = document.createElement("iframe");
      iframe.style.cssText = "position:fixed;right:0;bottom:0;width:100%;height:100%;border:0;z-index:99999;background:#fff";
      document.body.appendChild(iframe);
      const doc = iframe.contentDocument || iframe.contentWindow.document;
      doc.open(); doc.write(html); doc.close();
      setTimeout(() => { try { iframe.contentWindow.focus(); iframe.contentWindow.print(); } catch (e) { alert("请使用浏览器菜单的打印功能"); } }, 800);
    }
  } catch (e) { alert("打印失败：" + e.message); }
}

// ---------- AI 整理（批量重分类） ----------
async function aiOrganize() {
  const ids = [...state.selected];
  if (ids.length === 0) { alert("请先勾选要 AI 整理的笔记"); return; }
  $("#btnAI").disabled = true;
  let done = 0;
  for (const id of ids) {
    try {
      const res = await postJson("/api/notes/classify-again", { id });
      done++;
    } catch (e) { console.warn(id, e); }
  }
  $("#btnAI").disabled = false;
  alert(`AI 整理完成：${done}/${ids.length} 篇已重新归类`);
  state.selected.clear(); $("#checkAll").checked = false;
  await loadTree(); await loadNotes();
}

// ---------- 卷子提图 ----------
function openExtractPanel() {
  $("#extractResults").innerHTML = "";
  $("#extractCanvasBox").classList.add("hidden");
  $("#extractFile").value = "";
  $("#extractPanel").classList.remove("hidden");
  const target = state.editing;
  const tn = $("#extractTargetNote");
  if (target) {
    const cur = state.notes.find(n => n.id === target);
    tn.classList.remove("hidden");
    $("#extractNoteName").textContent = cur ? cur.title : target;
  } else tn.classList.add("hidden");
}
async function handleExtractFile(file) {
  if (!file) return;
  const mode = document.querySelector("input[name=emode]:checked").value;
  const fd = new FormData();
  fd.append("file", file);
  fd.append("mode", mode);
  if (mode === "crop" && state.cropRect) {
    fd.append("x", state.cropRect.x); fd.append("y", state.cropRect.y);
    fd.append("w", state.cropRect.w); fd.append("h", state.cropRect.h);
  }
  $("#extractResults").innerHTML = '<div class="extract-hint">提取中…</div>';
  try {
    const j = await api("/api/extract-image", { method: "POST", body: fd });
    state.extractImages = j.images;
    renderExtractResults();
  } catch (e) {
    $("#extractResults").innerHTML = `<div class="extract-hint" style="color:var(--danger)">提取失败：${e.message}</div>`;
  }
}
function renderExtractResults() {
  const box = $("#extractResults");
  box.innerHTML = "";
  if (state.extractImages.length === 0) {
    box.innerHTML = '<div class="extract-hint">没有检测到图片区域。可以切到「手动框选」模式自己圈选。</div>';
    return;
  }
  state.extractImages.forEach((img, i) => {
    const el = document.createElement("div");
    el.className = "extract-item";
    el.innerHTML = `<img src="${img.url}"><div class="ei-name">图 ${i + 1} · ${img.width}×${img.height}px${state.editing ? " · 点击插入" : ""}</div>`;
    el.addEventListener("click", async () => {
      if (!state.editing) { alert("请先在右侧打开或新建一篇笔记，再插入图片"); return; }
      const id = await ensureSaved();
      const j = await postJson("/api/notes/adopt/extract", { noteId: id, urls: [img.url] });
      const url = j.urls[0];
      insertHtml(`<img src="${url}" alt="卷子图片">`);
      state.extractImages = state.extractImages.filter(x => x !== img);
      renderExtractResults();
      if (state.extractImages.length === 0) alert("已全部插入当前笔记 ✓");
    });
    box.appendChild(el);
  });
}

// ---------- 导入手机离线采集包 ----------
function openImportZipModal() {
  openModal("📦 导入手机采集包",
    `<p style="margin-bottom:10px">选择手机「离线采集」导出的 zip 压缩包（微信传到电脑后保存到本地再选）：</p>
     <label style="font-size:12px;color:var(--sub)">归入范围：
       <select id="importZipScope">${scopeOptionsHtml()}</select></label>
     <label style="display:flex;align-items:center;gap:6px;margin-top:10px;font-size:13px">
       <input type="checkbox" id="importZipAI" checked> 保存后 AI 自动整理分类</label>
     <input type="file" id="importZipFile" accept=".zip,application/zip" style="margin-top:12px;width:100%">`,
    async () => {
      const file = $("#importZipFile").files[0];
      if (!file) { alert("请先选择 zip 压缩包"); return; }
      const scope = $("#importZipScope").value;
      const auto = $("#importZipAI").checked ? 1 : 0;
      const fd = new FormData();
      fd.append("file", file);
      fd.append("scope", scope);
      fd.append("title", file.name.replace(/\.zip$/i, ""));
      fd.append("autoClassify", auto);
      const btn = $("#modalOk");
      btn.disabled = true; btn.textContent = "导入中…";
      try {
        const r = await api("/api/import-zip", { method: "POST", body: fd });
        closeModal();
        await loadTree(); await loadNotes();
        alert(`导入成功 ✓ 「${r.title}」\n${r.images} 张图片 · ${r.items} 个条目` +
              (r.aiReason ? "\nAI 归类理由：" + r.aiReason : ""));
      } catch (e) {
        btn.disabled = false; btn.textContent = "开始导入";
        alert("导入失败：" + e.message);
      }
    }, "开始导入");
}

// ---------- 导入知识结构 ----------
function openImportModal() {
  openModal("导入已有知识结构",
    `<p style="margin-bottom:8px">粘贴知识结构 JSON（会合并到右侧"范围"下，同名分类自动合并，不会覆盖已有内容）：</p>
     <label style="font-size:12px;color:var(--sub)">归入范围：
       <select id="importScope">${scopeOptionsHtml()}</select></label>
     <textarea id="importJson" placeholder='{"name":"植物学","children":[{"name":"维管束","children":[]},{"name":"光合作用","children":[]}]}'></textarea>`,
    async () => {
      const scope = $("#importScope").value;
      let structure;
      try { structure = JSON.parse($("#importJson").value); }
      catch (e) { alert("JSON 格式错误：" + e.message); return; }
      await postJson("/api/tree/import", { scope, structure });
      await loadTree(); await loadNotes();
      closeModal();
      alert("导入成功 ✓ 已合并到「" + scope + "」");
    });
}
function scopeOptionsHtml() {
  const scopes = (state.tree.children || []).map(s => s.name);
  if (!scopes.includes("默认")) scopes.unshift("默认");
  return scopes.map(s => `<option ${s === state.scopeFilter ? "selected" : ""}>${s}</option>`).join("");
}
function renderScopeOptions() {
  const sel = $("#noteScope");
  sel.innerHTML = scopeOptionsHtml();
}

// ---------- 弹窗 ----------
let modalOkCb = null;
function openModal(title, bodyHtml, okCb, okText = "确定") {
  $("#modalTitle").textContent = title;
  $("#modalBody").innerHTML = bodyHtml;
  $("#modalOk").textContent = okText;
  modalOkCb = okCb;
  $("#modalMask").classList.remove("hidden");
}
function closeModal() { $("#modalMask").classList.add("hidden"); modalOkCb = null; }

// ---------- 事件绑定 ----------
function bindEvents() {
  // 分类树点击
  $("#treeRoot").addEventListener("click", e => {
    const r = e.target.closest(".tree-row");
    if (!r) return;
    const kind = r.dataset.kind;
    if (kind === "scope") {
      if (state.scopeFilter === r.dataset.scope && !state.catFilter) { resetFilter(); }
      else { state.scopeFilter = r.dataset.scope; state.catFilter = ""; }
    } else if (kind === "cat") {
      state.scopeFilter = r.dataset.scope; state.catFilter = r.dataset.cat;
    } else if (r.dataset.id === "root") { resetFilter(); }
    // 折叠/展开
    const kids = r.nextElementSibling;
    if (kids && kids.classList.contains("tree-children")) {
      if (kind === "scope" || kind === "cat") {
        kids.classList.toggle("collapsed");
        r.classList.toggle("open", !kids.classList.contains("collapsed"));
      }
    }
    renderTree(); loadNotes();
  });
  function resetFilter() { state.scopeFilter = ""; state.catFilter = ""; }

  $("#searchInput").addEventListener("input", renderNotes);

  $("#checkAll").addEventListener("change", e => {
    state.selected.clear();
    if (e.target.checked) state.notes.forEach(n => state.selected.add(n.id));
    renderNotes();
  });

  $("#btnNewNote").addEventListener("click", newNote);
  $("#btnSave").addEventListener("click", () => saveNote(false));
  $("#btnDelete").addEventListener("click", deleteNote);
  $("#noteTitle").addEventListener("keydown", e => { if (e.key === "Enter") saveNote(false); });

  $$(".tool[data-cmd]").forEach(b => b.addEventListener("click", () => toolCmds[b.dataset.cmd] && toolCmds[b.dataset.cmd]()));

  $("#btnPreview").addEventListener("click", () => {
    syncSourceFromPreview();
    state.editMode = "edit";
    $("#previewBox").classList.remove("hidden"); $("#srcBox").classList.add("hidden");
    $("#btnPreview").classList.add("active"); $("#btnSource").classList.remove("active");
  });
  $("#btnSource").addEventListener("click", () => {
    syncPreviewFromSource();
    state.editMode = "source";
    $("#srcBox").classList.remove("hidden"); $("#previewBox").classList.add("hidden");
    $("#btnSource").classList.add("active"); $("#btnPreview").classList.remove("active");
  });

  // 上传
  async function onUpload(input, tagBuilder) {
    const file = input.files[0];
    if (!file) return;
    try {
      const id = await ensureSaved();
      const url = await uploadMedia(file, id);
      insertHtml(tagBuilder(url, file));
      if (!state.editing) state.editing = id;
      refreshGallery(id);
      await loadTree(); await loadNotes();
      alert("已插入 ✓（" + (file.type.startsWith("video") || file.type.startsWith("audio")
        ? "注意：视频/音频打印时会被自动去除" : "打印时会按原比例自动适配") + "）");
    } catch (e) { alert("上传失败：" + e.message); }
    input.value = "";
  }
  $("#imgUpload").addEventListener("change", e => onUpload(e.target, url => `<img src="${url}" alt="图片">`));
  $("#videoUpload").addEventListener("change", e => onUpload(e.target, url => `<video src="${url}" controls preload="metadata"></video>`));
  $("#audioUpload").addEventListener("change", e => onUpload(e.target, url => `<audio src="${url}" controls></audio>`));

  $("#mediaGallery").addEventListener("change", e => {
    const url = e.target.value;
    if (url) { insertHtml(`<img src="${url}" alt="图片">`); e.target.value = ""; }
  });

  // 打印 / AI 整理
  $("#btnPrint").addEventListener("click", doPrint);
  $("#btnAI").addEventListener("click", aiOrganize);
  $("#btnImport").addEventListener("click", openImportModal);
  $("#btnImportZip").addEventListener("click", openImportZipModal);
  $("#btnNewCategory").addEventListener("click", async () => {
    const name = prompt("新建顶层分类（范围）名称：");
    if (!name || !name.trim()) return;
    const tree = state.tree;
    tree.children = tree.children || [];
    if (tree.children.some(c => c.name === name.trim())) { alert("已存在同名分类"); return; }
    tree.children.push({ name: name.trim(), id: "scope-" + Date.now(), children: [] });
    await postJson("/api/tree/import", { scope: name.trim(), structure: { name: name.trim(), children: [] } });
    await loadTree(); await loadNotes();
  });

  // 提图
  $("#btnExtract").addEventListener("click", openExtractPanel);
  $("#extractClose").addEventListener("click", () => $("#extractPanel").classList.add("hidden"));
  $("#dropZone").addEventListener("click", () => $("#extractFile").click());
  $("#dropZone").addEventListener("dragover", e => { e.preventDefault(); $("#dropZone").classList.add("drag"); });
  $("#dropZone").addEventListener("dragleave", () => $("#dropZone").classList.remove("drag"));
  $("#dropZone").addEventListener("drop", e => {
    e.preventDefault(); $("#dropZone").classList.remove("drag");
    if (e.dataTransfer.files[0]) setupCrop(e.dataTransfer.files[0]);
  });
  $("#extractFile").addEventListener("change", e => { if (e.target.files[0]) setupCrop(e.target.files[0]); });
  document.querySelectorAll("input[name=emode]").forEach(r => r.addEventListener("change", () => {
    if (r.value === "auto" && state.cropImage) { handleExtractFile(state.cropImage.file); }
  }));

  // 弹窗
  $("#modalCancel").addEventListener("click", closeModal);
  $("#modalOk").addEventListener("click", () => { if (modalOkCb) modalOkCb(); });
  $("#modalMask").addEventListener("click", e => { if (e.target === $("#modalMask")) closeModal(); });

  // 快捷键
  document.addEventListener("keydown", e => {
    if ((e.ctrlKey || e.metaKey) && e.key === "s") { e.preventDefault(); saveNote(false); }
  });
}

// ---------- 手动框选 ----------
function setupCrop(file) {
  const mode = document.querySelector("input[name=emode]:checked").value;
  if (mode !== "crop") { handleExtractFile(file); return; }
  const reader = new FileReader();
  reader.onload = () => {
    const img = new Image();
    img.onload = () => {
      const canvas = $("#extractCanvas");
      const maxW = Math.min(820, $("#extractPanel .panel-body").clientWidth - 40);
      const scale = Math.min(1, maxW / img.width);
      canvas.width = Math.round(img.width * scale);
      canvas.height = Math.round(img.height * scale);
      const ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      state.cropImage = { file, scale, img };
      state.cropRect = null;
      $("#extractCanvasBox").classList.remove("hidden");
      bindCropCanvas(canvas);
    };
    img.src = reader.result;
  };
  reader.readAsDataURL(file);
}
function bindCropCanvas(canvas) {
  const ctx = canvas.getContext("2d");
  let dragging = false, sx = 0, sy = 0;
  const draw = () => {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(state.cropImage.img, 0, 0, canvas.width, canvas.height);
    if (state.cropRect) {
      const r = state.cropRect;
      ctx.strokeStyle = "#2f6df6"; ctx.lineWidth = 2;
      ctx.strokeRect(r.x, r.y, r.w, r.h);
      ctx.fillStyle = "rgba(47,109,246,.15)";
      ctx.fillRect(r.x, r.y, r.w, r.h);
    }
  };
  canvas.onmousedown = e => {
    const rect = canvas.getBoundingClientRect();
    sx = (e.clientX - rect.left) * canvas.width / rect.width;
    sy = (e.clientY - rect.top) * canvas.height / rect.height;
    dragging = true; state.cropRect = null;
  };
  canvas.onmousemove = e => {
    if (!dragging) return;
    const rect = canvas.getBoundingClientRect();
    const cx = (e.clientX - rect.left) * canvas.width / rect.width;
    const cy = (e.clientY - rect.top) * canvas.height / rect.height;
    state.cropRect = { x: Math.min(sx, cx), y: Math.min(sy, cy), w: Math.abs(cx - sx), h: Math.abs(cy - sy) };
    draw();
  };
  canvas.onmouseup = () => {
    dragging = false;
    if (state.cropRect && state.cropRect.w > 5 && state.cropRect.h > 5) {
      const s = state.cropImage.scale;
      const r = state.cropRect;
      state.cropRect = { x: Math.round(r.x / s), y: Math.round(r.y / s), w: Math.round(r.w / s), h: Math.round(r.h / s) };
      draw();
      handleExtractFile(state.cropImage.file);
    } else { state.cropRect = null; draw(); }
  };
  draw();
}

async function refreshGallery(noteId) {
  try {
    const meta = await api("/api/notes/" + noteId);
    const sel = $("#mediaGallery");
    const m = meta.media || {};
    const imgs = m.images || 0;
    if (imgs === 0) { sel.innerHTML = '<option value="">—</option>'; return; }
    // 从正文里提取 img src
    const srcs = [...new Set((meta.content || "").match(/src="(\/media\/[^"]+)"/g) || [])].map(s => s.slice(5, -1));
    sel.innerHTML = '<option value="">图库(' + srcs.length + ')</option>' +
      srcs.map(u => `<option value="${u}">${u.split("/").pop()}</option>`).join("");
  } catch (e) { /* 忽略 */ }
}

init();

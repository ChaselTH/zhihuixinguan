/* Progressive enhancement only: normal links/forms remain usable without JavaScript. ES3 syntax. */
(function () {
  function on(el, name, fn) {
    if (el.addEventListener) el.addEventListener(name, fn, false);
    else if (el.attachEvent) el.attachEvent("on" + name, fn);
  }
  function has(el, name) { return (" " + el.className + " ").indexOf(" " + name + " ") >= 0; }
  function stop(e) { e = e || window.event; if (e.preventDefault) e.preventDefault(); e.returnValue = false; }
  function find(el, tag, name) {
    var all = el.getElementsByTagName(tag), i;
    for (i = 0; i < all.length; i++) if (all[i].name === name) return all[i];
    return null;
  }
  function scrollPair(bottom) {
    var table = bottom.getElementsByTagName("table")[0];
    if (!table) return;
    var top = document.createElement("div"), inner = document.createElement("div"), busy = false;
    top.className = "table-top-scroll"; top.tabIndex = 0;
    top.setAttribute("aria-label", "表格横向滚动条");
    top.title = "左右拖动查看表格";
    inner.style.height = "1px"; top.appendChild(inner);
    bottom.parentNode.insertBefore(top, bottom);
    function size() {
      inner.style.width = table.scrollWidth + "px";
      top.style.display = table.scrollWidth > bottom.clientWidth ? "block" : "none";
      top.scrollLeft = bottom.scrollLeft;
    }
    function sync(from, to) {
      if (busy || from.scrollLeft === to.scrollLeft) return;
      busy = true; to.scrollLeft = from.scrollLeft; busy = false;
    }
    on(top, "scroll", function () { sync(top, bottom); });
    on(bottom, "scroll", function () { sync(bottom, top); });
    on(window, "resize", size); size();
  }
  function importForm(form) {
    var mode = find(form, "select", "mode"), confirmed = find(form, "input", "confirmed"), note;
    var ps = form.getElementsByTagName("p"), i;
    for (i = 0; i < ps.length; i++) if (has(ps[i], "import-selected-count")) note = ps[i];
    function updated() { return parseInt(form.getAttribute(mode.value === "overwrite" ? "data-overwrite" : "data-preserve"), 10); }
    function summary() {
      return "新增 " + form.getAttribute("data-new") + " 条，重复 " + form.getAttribute("data-duplicates") + " 条；预计更新 " + updated() + " 条，保持不变 " + (parseInt(form.getAttribute("data-duplicates"), 10) - updated()) + " 条。";
    }
    function change() { if (note) { if ("textContent" in note) note.textContent = summary(); else note.innerText = summary(); } confirmed.value = ""; }
    on(mode, "change", change); change();
    on(form, "submit", function (e) {
      var warning = mode.value === "overwrite" ? "\n使用上传值覆盖，空白也会清空原填写内容。" : "\n保留已有填写，只补充空白字段。";
      if (!window.confirm("确定导入本批数据吗？\n" + summary() + warning)) { confirmed.value = ""; stop(e); return; }
      confirmed.value = "yes";
    });
  }
  function init() {
    var divs = document.getElementsByTagName("div"), tables = [], i, links, forms;
    for (i = 0; i < divs.length; i++) if (has(divs[i], "table-scroll")) tables.push(divs[i]);
    for (i = 0; i < tables.length; i++) scrollPair(tables[i]);
    links = document.getElementsByTagName("a");
    for (i = 0; i < links.length; i++) if (links[i].getAttribute("data-back") === "yes") on(links[i], "click", function (e) {
      var ref = document.createElement("a"); ref.href = document.referrer;
      if (document.referrer && ref.protocol === window.location.protocol && ref.host === window.location.host && window.history.length > 1) { stop(e); window.history.back(); }
    });
    forms = document.getElementsByTagName("form");
    for (i = 0; i < forms.length; i++) if (has(forms[i], "import-bulk-confirm")) importForm(forms[i]);
  }
  on(window, "load", init);
}());

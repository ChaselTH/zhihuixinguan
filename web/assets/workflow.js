/* Progressive enhancement for unified workflow tables; ES3/IE-compatible. */
(function () {
  function on(el, name, fn) {
    if (el.addEventListener) el.addEventListener(name, fn, false);
    else if (el.attachEvent) el.attachEvent("on" + name, fn);
  }
  function has(el, name) { return (" " + el.className + " ").indexOf(" " + name + " ") >= 0; }
  function init() {
    var forms = document.getElementsByTagName("form"), i, form, dirty = false;
    for (i = 0; i < forms.length; i++) if (has(forms[i], "workflow-edit-form") || forms[i].action.indexOf("/workflow/draft/save") >= 0 || forms[i].action.indexOf("/update-batch") >= 0) {
      form = forms[i];
      (function (target) {
        var controls = target.getElementsByTagName("input"), j, selects = target.getElementsByTagName("select"), textareas = target.getElementsByTagName("textarea");
        function changed() { dirty = true; }
        for (j = 0; j < controls.length; j++) on(controls[j], "change", changed);
        for (j = 0; j < selects.length; j++) on(selects[j], "change", changed);
        for (j = 0; j < textareas.length; j++) { on(textareas[j], "change", changed); on(textareas[j], "keyup", changed); }
        on(target, "submit", function () { dirty = false; });
      }(form));
    }
    function leave(e) { if (!dirty) return; e = e || window.event; var message = "当前页面还有未保存的填写，离开前请选择保存或留在当前页。"; if (e) e.returnValue = message; return message; }
    if (window.addEventListener) window.addEventListener("beforeunload", leave, false);
    else if (window.attachEvent) window.attachEvent("onbeforeunload", leave);
  }
  on(window, "load", init);
}());

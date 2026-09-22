/* Unsaved-input guard for all unified edit forms; ES3/attachEvent compatible. */
(function () {
  function on(el, name, fn) {
    if (el.addEventListener) el.addEventListener(name, fn, false);
    else if (el.attachEvent) el.attachEvent("on" + name, fn);
  }
  function has(el, name) { return (" " + el.className + " ").indexOf(" " + name + " ") >= 0; }
  function init() {
    var forms = document.getElementsByTagName("form"), tracked = [], submitting = null, i, j, k;
    for (i = 0; i < forms.length; i++) if (has(forms[i], "workflow-edit-form")) {
      var entry = { form: forms[i], controls: [], values: [] }, tags = ["textarea", "select", "input"];
      for (j = 0; j < tags.length; j++) {
        var controls = forms[i].getElementsByTagName(tags[j]);
        for (k = 0; k < controls.length; k++) {
          var el = controls[k], type = (el.type || "").toLowerCase();
          if (type === "hidden" || type === "submit" || type === "button" || el.disabled || el.readOnly) continue;
          entry.controls.push(el); entry.values.push(el.value);
        }
      }
      tracked.push(entry);
      (function (target) { on(target, "submit", function (e) {
        e = e || window.event;
        if (!e || (!e.defaultPrevented && e.returnValue !== false)) submitting = target;
      }); }(forms[i]));
    }
    on(window, "pageshow", function () { submitting = null; });
    on(window, "beforeunload", function (e) {
      var a, b;
      for (a = 0; a < tracked.length; a++) {
        if (tracked[a].form === submitting) continue;
        for (b = 0; b < tracked[a].controls.length; b++) if (tracked[a].controls[b].value !== tracked[a].values[b]) {
          e = e || window.event;
          var message = "当前页面还有未保存的填写，请取消离开并先保存草稿或提交修改。";
          if (e) e.returnValue = message;
          return message;
        }
      }
    });
  }
  on(window, "load", init);
}());

/* Review "return" popup: ask for a mandatory reason and submit the row form; ES3/IE compatible. */
function workflowRejectReason(form) {
  if (!form) return false;
  var reason = window.prompt("请输入退回原因（必填，最多 2000 字）：", "");
  if (reason === null) return false;
  reason = reason.replace(/^\s+|\s+$/g, "");
  if (reason === "") { window.alert("退回原因不能为空。"); return false; }
  if (reason.length > 2000) { window.alert("退回原因不能超过 2000 字。"); return false; }
  var field = form.elements ? form.elements["reason"] : null;
  if (field) field.value = reason;
  return true;
}

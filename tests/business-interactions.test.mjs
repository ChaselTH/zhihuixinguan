import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source = await fs.readFile(new URL('../web/assets/business.js', import.meta.url), 'utf8');
let assertions = 0;
function check(value, label) { assertions++; assert.ok(value, label); }
function fixture(legacy) {
  function element(tag, cls = '') {
    const e = { tag, className: cls, children: [], style: {}, events: {}, attrs: {}, scrollLeft: 0, clientWidth: 900,
      getAttribute(k) { return this.attrs[k] ?? null; },
      setAttribute(k, v) { this.attrs[k] = v; },
      appendChild(c) { c.parentNode = this; this.children.push(c); },
      insertBefore(c, before) { c.parentNode = this; this.children.splice(this.children.indexOf(before), 0, c); },
      getElementsByTagName(name) {
        const result = [];
        for (const child of this.children) { if (child.tag === name) result.push(child); result.push(...child.getElementsByTagName(name)); }
        return result;
      },
      fire(name, ev = {}) { for (const fn of this.events[name] || []) fn(ev); return ev; }
    };
    if (legacy) e.attachEvent = function (name, fn) { (this.events[name.slice(2)] ||= []).push(fn); };
    else e.addEventListener = function (name, fn) { (this.events[name] ||= []).push(fn); };
    return e;
  }
  const body = element('body'), bottom = element('div', 'table-scroll'), table = element('table');
  table.scrollWidth = 4200; bottom.appendChild(table); body.appendChild(bottom);
  const back = element('a'); back.attrs['data-back'] = 'yes'; body.appendChild(back);
  const form = element('form', 'import-bulk-confirm');
  Object.assign(form.attrs, { 'data-new': '3', 'data-duplicates': '5', 'data-preserve': '2', 'data-overwrite': '4' });
  const select = element('select'); select.name = 'mode'; select.value = 'preserve'; form.appendChild(select);
  const confirmed = element('input'); confirmed.name = 'confirmed'; confirmed.value = ''; form.appendChild(confirmed);
  const note = element('p', 'import-selected-count'); if (!legacy) note.textContent = ''; form.appendChild(note);
  body.appendChild(form);
  const win = element('window'); win.location = { protocol: 'http:', host: '127.0.0.1:2874', href: 'http://127.0.0.1:2874/details?month=2026-09' };
  let accepted = false, prompt = '';
  win.confirm = text => { prompt = text; return accepted; };
  const doc = {
    referrer: 'http://127.0.0.1:2874/?month=2026-09',
    getElementsByTagName: tag => body.getElementsByTagName(tag),
    createElement(tag) {
      const e = element(tag);
      if (tag === 'a') Object.defineProperty(e, 'href', { get() { return this.url; }, set(v) { const u = new URL(v); this.url = u.href; this.protocol = u.protocol; this.host = u.host; } });
      return e;
    }
  };
  vm.runInNewContext(source, { document: doc, window: win }); win.fire('load');
  return { body, bottom, table, form, select, confirmed, note, win, doc, back,
    top: body.children[0], accept(v) { accepted = v; }, prompt: () => prompt };
}
for (const legacy of [false, true]) {
  const f = fixture(legacy);
  check(f.top.className === 'table-top-scroll' && f.top.children[0].style.width === '4200px', 'native top scrollbar matches table width');
  f.top.scrollLeft = 710; f.top.fire('scroll'); check(f.bottom.scrollLeft === 710, 'top scroll drives bottom');
  f.bottom.scrollLeft = 960; f.bottom.fire('scroll'); check(f.top.scrollLeft === 960, 'bottom scroll drives top');
  f.table.scrollWidth = 600; f.win.fire('resize'); check(f.top.style.display === 'none', 'no redundant scrollbar for fitting table');
  f.table.scrollWidth = 1900; f.win.fire('resize'); check(f.top.style.display === 'block' && f.top.children[0].style.width === '1900px', 'resize restores scrollbar');
  check((f.note.textContent || f.note.innerText).includes('预计更新 2 条'), 'preserve counts initialised');
  f.select.value = 'overwrite'; f.select.fire('change'); check((f.note.textContent || f.note.innerText).includes('保持不变 1 条'), 'choice updates counts without separate save');
  const cancelled = f.form.fire('submit'); check(cancelled.returnValue === false && f.confirmed.value === '', 'cancel stops submission');
  f.accept(true); const accepted = f.form.fire('submit'); check(accepted.returnValue !== false && f.confirmed.value === 'yes', 'OK submits explicit confirmed flag');
  check(f.prompt().includes('空白也会清空') && f.prompt().includes('预计更新 4 条'), 'overwrite prompt includes counts and clearing warning');
  f.select.value = 'preserve'; f.select.fire('change'); f.form.fire('submit'); check(!f.prompt().includes('空白也会清空'), 'preserve prompt does not claim destructive clearing');
  check(f.back.fire('click').returnValue !== false, 'back link uses its explicit server-rendered href rather than browser referrer');
}
check(!source.includes('document.referrer'), 'back navigation never guesses the origin from browser referrer');
check(!/\b(?:const|let|Promise|fetch)\b|=>|\.classList|\.closest\(/.test(source), 'production interactions avoid modern-only syntax and APIs');
console.log('BUSINESS_INTERACTIONS_OK assertions=' + assertions + ' modern/attachEvent DOM stubs; not real IE verification');

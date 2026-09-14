import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';

const source = await fs.readFile(new URL('../web/assets/identity.js', import.meta.url), 'utf8');
let assertions = 0;
function check(value, label) { assertions++; assert.ok(value, label); }
function fixture(initialRole, modern) {
  const role = { value: initialRole, form: {} };
  const fields = { style: {} }, branch = { value: '', disabled: false };
  const elements = { personRole: role, personOrganizationFields: fields, personOrganization: branch };
  let restored;
  const window = modern ? { addEventListener(name, fn) { check(name === 'pageshow', 'restored-page handler'); restored = fn; } }
    : { attachEvent(name, fn) { check(name === 'onload', 'legacy IE load handler'); restored = fn; } };
  vm.runInNewContext(source, { document: { getElementById(id) { return elements[id]; } }, window });
  return { role, fields, branch, restore() { restored(); } };
}
const f = fixture('DIVISION_ADMIN', false);
check(f.fields.style.display === 'none' && f.branch.disabled, 'division initially hides and omits branch field');
f.role.value = 'OPERATOR'; f.role.onchange();
check(f.fields.style.display === '' && !f.branch.disabled && f.branch.value === '', 'branch role requires explicit branch choice');
f.branch.value = 'JINTAN'; f.role.value = 'DIVISION_ADMIN'; f.role.onchange();
check(f.fields.style.display === 'none' && f.branch.disabled, 'switch to division hides selected branch');
f.role.value = 'REVIEWER'; f.role.onchange();
check(!f.branch.disabled && f.branch.value === 'JINTAN', 'switch back preserves previous branch choice');
f.role.value = 'DIVISION_ADMIN'; f.role.onkeyup();
check(f.branch.disabled, 'keyboard role changes synchronize controls');
f.role.value = 'BRANCH_ADMIN'; f.role.form.onsubmit();
check(!f.branch.disabled && f.fields.style.display === '', 'submit synchronizes a restored role value');
f.role.value = 'DIVISION_ADMIN'; f.restore();
check(f.branch.disabled, 'legacy IE page load synchronizes role');
const modern = fixture('REVIEWER', true);
check(!modern.branch.disabled && modern.fields.style.display === '', 'existing branch user editor starts visible');
modern.role.value = 'DIVISION_ADMIN'; modern.restore();
check(modern.branch.disabled, 'back-forward restoration resynchronizes controls');
vm.runInNewContext(source, { document: { getElementById() { return null; } }, window: {} });
check(true, 'script is inert outside personnel forms');
console.log('IDENTITY_FORM_OK assertions=' + assertions + ' isolated DOM stubs, not real IE verification');

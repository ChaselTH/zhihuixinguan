/* Local ES3-compatible role/branch controls, including legacy IE. */
(function () {
  var role = document.getElementById('personRole');
  var fields = document.getElementById('personOrganizationFields');
  var branch = document.getElementById('personOrganization');
  if (!role || !fields || !branch) { return; }

  function updateOrganization() {
    var isDivision = role.value === 'DIVISION_ADMIN';
    fields.style.display = isDivision ? 'none' : '';
    branch.disabled = isDivision;
  }

  role.onchange = updateOrganization;
  role.onkeyup = updateOrganization;
  if (role.form) { role.form.onsubmit = updateOrganization; }
  if (window.addEventListener) {
    window.addEventListener('pageshow', updateOrganization, false);
  } else if (window.attachEvent) {
    window.attachEvent('onload', updateOrganization);
  }
  updateOrganization();
}());

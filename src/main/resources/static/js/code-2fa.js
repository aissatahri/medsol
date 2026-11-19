// Code de double authentification en 6 cases (amélioration progressive : sans script, le champ unique reste).
// Saisie chiffre par chiffre avec passage à la case suivante, effacement vers la précédente, flèches, collage ou
// remplissage automatique du code entier (SMS/gestionnaire), envoi automatique au 6e chiffre. Sur la page de connexion,
// un lien bascule vers le champ unique pour un code de secours (ABCDE-FGHJK).
document.querySelectorAll('input[data-cases]').forEach(function (champ) {
  var form = champ.form;
  var libelle = champ.dataset.chiffre || 'Chiffre';
  var groupe = document.createElement('div');
  groupe.className = 'cases-2fa';
  groupe.setAttribute('role', 'group');
  groupe.setAttribute('aria-label', champ.dataset.groupe || 'Code à 6 chiffres');
  var cases = [];
  for (var i = 0; i < 6; i++) {
    var c = document.createElement('input');
    c.type = 'text';
    c.inputMode = 'numeric';
    c.maxLength = 6;   // un code collé ou rempli automatiquement arrive en entier dans une case
    c.autocomplete = i === 0 ? 'one-time-code' : 'off';
    c.setAttribute('aria-label', libelle + ' ' + (i + 1));
    c.dataset.index = i;
    groupe.appendChild(c);
    cases.push(c);
  }
  champ.insertAdjacentElement('afterend', groupe);
  champ.classList.add('visuellement-cache');
  champ.required = false;
  champ.tabIndex = -1;
  champ.setAttribute('aria-hidden', 'true');
  var modeCases = true;

  function synchroniser() { champ.value = cases.map(function (c) { return c.value; }).join(''); }

  function repartir(depuis, chiffres) {
    for (var k = 0; k < chiffres.length && depuis + k < 6; k++) { cases[depuis + k].value = chiffres[k]; }
    synchroniser();
    var suivante = Math.min(depuis + chiffres.length, 5);
    cases[suivante].focus();
    if (champ.value.length === 6 && form) {
      if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
    }
  }

  cases.forEach(function (c, i) {
    c.addEventListener('input', function () {
      var chiffres = c.value.replace(/\D/g, '');
      c.value = '';
      if (chiffres) { repartir(i, chiffres.split('')); } else { synchroniser(); }
    });
    c.addEventListener('keydown', function (e) {
      if (e.key === 'Backspace' && !c.value && i > 0) { cases[i - 1].value = ''; cases[i - 1].focus(); synchroniser(); e.preventDefault(); }
      else if (e.key === 'ArrowLeft' && i > 0) { cases[i - 1].focus(); e.preventDefault(); }
      else if (e.key === 'ArrowRight' && i < 5) { cases[i + 1].focus(); e.preventDefault(); }
    });
    c.addEventListener('paste', function (e) {
      var texte = (e.clipboardData || window.clipboardData).getData('text').replace(/\D/g, '');
      if (texte) { e.preventDefault(); repartir(texte.length >= 6 ? 0 : i, texte.split('')); }
    });
    c.addEventListener('focus', function () { c.select(); });
  });

  if (form) {
    form.addEventListener('submit', function (e) {
      if (!modeCases) { return; }
      synchroniser();
      if (champ.value.length < 6) {
        e.preventDefault();
        var vide = cases.find(function (c) { return !c.value; });
        (vide || cases[0]).focus();
      }
    });
  }

  // Code de secours : retour au champ unique (lettres et tiret)
  var bascule = document.querySelector('[data-secours-pour="' + champ.id + '"]');
  if (bascule) {
    bascule.hidden = false;
    bascule.addEventListener('click', function () {
      modeCases = !modeCases;
      groupe.hidden = !modeCases;
      champ.classList.toggle('visuellement-cache', modeCases);
      champ.tabIndex = modeCases ? -1 : 0;
      champ.required = !modeCases;
      champ.removeAttribute('aria-hidden');
      if (modeCases) { champ.setAttribute('aria-hidden', 'true'); }
      champ.value = '';
      cases.forEach(function (c) { c.value = ''; });
      bascule.textContent = modeCases ? bascule.dataset.secours : bascule.dataset.application;
      (modeCases ? cases[0] : champ).focus();
    });
  }

  if (champ.autofocus) { cases[0].focus(); }
});

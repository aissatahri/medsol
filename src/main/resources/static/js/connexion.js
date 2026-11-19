// Inscription : jauge de robustesse du mot de passe (longueur, variété des caractères) ; indicative, la règle
// imposée reste « 10 caractères minimum » côté serveur.
document.querySelectorAll('[data-force]').forEach(function (champ) {
  var jauge = document.getElementById(champ.getAttribute('data-force'));
  if (!jauge) { return; }
  var aide = jauge.querySelector('small'), texteAide = aide ? aide.textContent : '';
  champ.addEventListener('input', function () {
    var v = champ.value, types = 0;
    [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/].forEach(function (r) { if (r.test(v)) { types++; } });
    var niveau = !v ? 0 : v.length < 10 ? 1 : (v.length >= 14 && types >= 3) || types === 4 ? 3 : types >= 2 ? 2 : 1;
    jauge.setAttribute('data-niveau', niveau);
    if (aide) { aide.textContent = niveau === 0 ? texteAide : [null, jauge.dataset.faible, jauge.dataset.moyen, jauge.dataset.fort][niveau]; }
  });
});

// Page de connexion : bouton « Afficher / Masquer » du mot de passe (proposé seulement si le script est chargé).
document.querySelectorAll('[data-afficher]').forEach(function (bouton) {
  var champ = document.getElementById(bouton.getAttribute('data-afficher'));
  if (!champ) { return; }
  bouton.hidden = false;
  bouton.addEventListener('click', function () {
    var visible = champ.type === 'password';
    champ.type = visible ? 'text' : 'password';
    bouton.textContent = visible ? (bouton.dataset.masquer || 'Masquer') : (bouton.dataset.montrer || 'Afficher');
    bouton.setAttribute('aria-pressed', visible ? 'true' : 'false');
    champ.focus();
  });
});

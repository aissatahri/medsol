// Textes de l'interface fournis par la page (élément #textes-js, attributs data-t-*), dans la langue de l'utilisateur ;
// le français sert de secours. Pas de script en ligne : la politique de sécurité l'interdit.
function texte(cle, defaut) {
  var el = document.getElementById('textes-js');
  return (el && el.dataset[cle]) || defaut;
}
// Jeton CSRF ajouté à chaque requête htmx (POST, PUT, DELETE) ; lu dans les balises meta de la page.
document.addEventListener('htmx:configRequest', function (e) {
  var jeton = document.querySelector('meta[name="_csrf"]');
  var entete = document.querySelector('meta[name="_csrf_header"]');
  if (jeton && entete) {
    e.detail.headers[entete.content] = jeton.content;
  }
});

// Après un ajout à la salle d'attente : recherche vidée et prête pour le patient suivant.
document.addEventListener('htmx:afterRequest', function (e) {
  if (!e.detail.successful || !e.detail.elt.closest('[data-vider-recherche]')) {
    return;
  }
  var champ = document.getElementById('recherche-ajout');
  var trouves = document.getElementById('trouves');
  if (champ) { champ.value = ''; champ.focus(); }
  if (trouves) { trouves.innerHTML = ''; }
});

// Application installable : service « hors connexion » (aucune donnée du cabinet n'est mise en cache)
if (false) {
  window.addEventListener('load', function () { navigator.serviceWorker.register('/sw.js').catch(function () { /* facultatif */ }); });
}

// Déconnexion automatique après 25 min sans action : les pages qui se rafraîchissent seules (salle d'attente,
// planning) gardent la session du serveur ouverte, c'est donc le poste qui ferme la session laissée sans surveillance.
(function () {
  var formulaire = document.querySelector('form[action$="/logout"]');
  if (!formulaire) { return; }
  var DUREE = 25 * 60 * 1000;
  var PREAVIS = 2 * 60 * 1000;
  var derniere = Date.now();
  var bandeau = null;
  function activite() {
    derniere = Date.now();
    if (bandeau) { bandeau.remove(); bandeau = null; }
  }
  ['mousedown', 'keydown', 'touchstart', 'wheel'].forEach(function (t) {
    document.addEventListener(t, activite, { passive: true });
  });
  setInterval(function () {
    var ecoule = Date.now() - derniere;
    if (ecoule >= DUREE) {
      formulaire.submit();
    } else if (ecoule >= DUREE - PREAVIS && !bandeau) {
      bandeau = document.createElement('div');
      bandeau.className = 'bandeau-inactivite';
      bandeau.setAttribute('role', 'alert');
      bandeau.textContent = texte('tSessionWarning', 'Sans action de votre part, la session va se fermer dans deux minutes.') + ' ';
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'bouton petit principal';
      b.textContent = texte('tStay', 'Rester connecté');
      b.addEventListener('click', activite);
      bandeau.appendChild(b);
      document.body.appendChild(bandeau);
    }
  }, 15000);
})();

// Recherche globale : « / » ou Ctrl+K place le curseur ; Échap ou un clic ailleurs ferme les résultats ;
// flèches haut/bas pour parcourir, Entrée pour ouvrir le patient choisi.
(function () {
  var champ = document.getElementById('recherche-globale');
  if (!champ) { return; }
  function resultats() { return document.getElementById('resultats-globaux'); }
  function fermer() { var r = resultats(); if (r) { r.innerHTML = ''; } }
  document.addEventListener('keydown', function (e) {
    var cible = e.target;
    var saisie = cible && (cible.tagName === 'INPUT' || cible.tagName === 'TEXTAREA' || cible.tagName === 'SELECT' || cible.isContentEditable);
    if ((e.key === '/' && !saisie) || (e.key.toLowerCase() === 'k' && (e.ctrlKey || e.metaKey))) {
      e.preventDefault();
      champ.focus();
      champ.select();
    }
  });
  champ.addEventListener('keydown', function (e) {
    var liens = Array.prototype.slice.call(resultats().querySelectorAll('a.resultat-global'));
    if (e.key === 'Escape') { fermer(); champ.blur(); return; }
    if ((e.key === 'ArrowDown' || e.key === 'Enter') && liens.length && e.key === 'ArrowDown') { e.preventDefault(); liens[0].focus(); }
  });
  document.addEventListener('keydown', function (e) {
    var actif = document.activeElement;
    if (!actif || !actif.classList.contains('resultat-global')) { return; }
    var liens = Array.prototype.slice.call(resultats().querySelectorAll('a.resultat-global'));
    var i = liens.indexOf(actif);
    if (e.key === 'ArrowDown' && i < liens.length - 1) { e.preventDefault(); liens[i + 1].focus(); }
    if (e.key === 'ArrowUp') { e.preventDefault(); (i > 0 ? liens[i - 1] : champ).focus(); }
    if (e.key === 'Escape') { fermer(); champ.focus(); }
  });
  document.addEventListener('click', function (e) {
    if (!e.target.closest('.recherche-globale')) { fermer(); }
  });
})();

// Panneaux ouverts à la demande (ordonnances types…) : « Fermer » vide le panneau, qui se rouvre au clic suivant.
// (Pas de onclick dans les pages : la politique de sécurité du site n'autorise que les scripts du site.)
document.addEventListener('click', function (e) {
  var bouton = e.target.closest('[data-fermer-panneau]');
  var panneau = bouton && bouton.closest('.panneau-modeles');
  if (panneau) { panneau.replaceChildren(); }
});

// Messagerie du cabinet (médecin ↔ assistante) : la page interroge le serveur toutes les quelques secondes ;
// un nouveau message de l'autre personne déclenche un double « ding », une pastille et le titre de l'onglet.
// Le son reste muet tant que le navigateur n'a vu aucun clic ou touche sur la page (règle des navigateurs) :
// le premier clic le réveille. Les messages s'affichent avec textContent : jamais de HTML venu d'un message.
(function () {
  var racine = document.querySelector('[data-chat]');
  if (!racine) { return; }
  var panneau = racine.querySelector('.chat-panneau');
  var liste = racine.querySelector('.chat-liste');
  var vide = racine.querySelector('.chat-vide');
  var bouton = racine.querySelector('[data-chat-ouvrir]');
  var badge = racine.querySelector('.chat-badge');
  var formulaire = racine.querySelector('.chat-saisie');
  var champ = formulaire.querySelector('textarea');
  var boutonSon = racine.querySelector('[data-chat-son]');
  var titreOrigine = document.title;
  var dernier = 0;
  var nonLus = 0;
  var ouvert = false;
  var arrete = false;
  var enCours = false;
  var contexte = null;

  function lire(cle) { try { return window.localStorage.getItem(cle); } catch (e) { return null; } }
  function ecrire(cle, valeur) { try { window.localStorage.setItem(cle, valeur); } catch (e) { /* stockage refusé */ } }
  function jeton() { var m = document.querySelector('meta[name="_csrf"]'); return m ? m.content : ''; }
  function enteteJeton() { var m = document.querySelector('meta[name="_csrf_header"]'); return m ? m.content : 'X-CSRF-TOKEN'; }

  // ── Son ────────────────────────────────────────────────────────────────
  function sonActif() { return lire('chat-son') !== '0'; }
  function majBoutonSon() {
    var actif = sonActif();
    boutonSon.setAttribute('aria-pressed', actif ? 'true' : 'false');
    boutonSon.title = actif ? texte('tSoundOn', 'Son des nouveaux messages : activé') : texte('tSoundOff', 'Son des nouveaux messages : coupé');
    boutonSon.classList.toggle('coupe', !actif);
  }
  function contexteAudio() {
    if (!contexte) {
      var Classe = window.AudioContext || window.webkitAudioContext;
      if (!Classe) { return null; }
      try { contexte = new Classe(); } catch (e) { return null; }
    }
    return contexte;
  }
  function reveillerSon() {
    var c = contexteAudio();
    if (c && c.state === 'suspended' && c.resume) { try { c.resume(); } catch (e) { /* refusé : sans clic préalable */ } }
  }
  ['pointerdown', 'keydown', 'touchstart'].forEach(function (nom) { document.addEventListener(nom, reveillerSon, { passive: true }); });
  function note(c, frequence, debut, duree) {
    var o = c.createOscillator();
    var g = c.createGain();
    o.type = 'sine';
    o.frequency.value = frequence;
    g.gain.setValueAtTime(0.0001, c.currentTime + debut);
    g.gain.exponentialRampToValueAtTime(0.25, c.currentTime + debut + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + debut + duree);
    o.connect(g);
    g.connect(c.destination);
    o.start(c.currentTime + debut);
    o.stop(c.currentTime + debut + duree + 0.05);
  }
  function sonner() {
    if (!sonActif()) { return; }
    var c = contexteAudio();
    if (!c) { return; }
    reveillerSon();
    try { note(c, 880, 0, 0.18); note(c, 1318, 0.2, 0.28); } catch (e) { /* audio indisponible */ }
  }
  boutonSon.addEventListener('click', function () {
    ecrire('chat-son', sonActif() ? '0' : '1');
    majBoutonSon();
    if (sonActif()) { sonner(); } // aperçu du son à la réactivation
  });
  majBoutonSon();

  // ── Affichage ──────────────────────────────────────────────────────────
  function majBadge() {
    badge.textContent = nonLus > 99 ? '99+' : String(nonLus);
    badge.hidden = nonLus <= 0;
    racine.classList.toggle('chat-alerte', nonLus > 0);
    document.title = nonLus > 0 && document.hidden ? '(' + nonLus + ') ' + titreOrigine : titreOrigine;
  }
  document.addEventListener('visibilitychange', majBadge);

  function bulle(m) {
    var b = document.createElement('div');
    b.className = 'chat-message ' + (m.miens ? 'miens' : 'autre');
    b.setAttribute('data-id', m.id);
    if (!m.miens) {
      var qui = document.createElement('span');
      qui.className = 'chat-auteur';
      qui.textContent = m.nom;
      b.appendChild(qui);
    }
    var texte = document.createElement('span');
    texte.className = 'chat-texte';
    // Les liens vers un patient « [[patient:12|Nom]] » deviennent des liens ; tout le reste reste du texte brut
    var motif = /\[\[patient:([0-9a-f-]{36})\|([^\]\[|]{0,100})\]\]/g;
    var position = 0;
    var trouve;
    while ((trouve = motif.exec(m.texte)) !== null) {
      if (trouve.index > position) { texte.appendChild(document.createTextNode(m.texte.slice(position, trouve.index))); }
      var lien = document.createElement('a');
      lien.className = 'chat-patient-lien';
      lien.href = '/patients/' + trouve[1];
      lien.textContent = trouve[2];
      texte.appendChild(lien);
      position = motif.lastIndex;
    }
    if (position < m.texte.length) { texte.appendChild(document.createTextNode(m.texte.slice(position))); }
    b.appendChild(texte);
    var heure = document.createElement('time');
    heure.className = 'chat-heure';
    heure.textContent = (m.jour ? m.jour + ' ' : '') + m.heure;
    b.appendChild(heure);
    return b;
  }
  function ajouter(messages) {
    var basDeListe = liste.scrollHeight - liste.scrollTop - liste.clientHeight < 60;
    messages.forEach(function (m) {
      if (!liste.querySelector('[data-id="' + m.id + '"]')) { liste.appendChild(bulle(m)); }
      if (m.id > dernier) { dernier = m.id; }
    });
    vide.hidden = liste.childElementCount > 0;
    if (basDeListe || messages.some(function (m) { return m.miens; })) { liste.scrollTop = liste.scrollHeight; }
  }

  // ── Échanges avec le serveur ───────────────────────────────────────────
  function arreter() { arrete = true; }
  function reponseJson(r) {
    // Session expirée : le serveur renvoie la page de connexion, pas du JSON
    if (r.redirected || r.status === 401 || r.status === 403) { arreter(); return Promise.reject(new Error('session')); }
    if (!r.ok) { return Promise.reject(new Error('http ' + r.status)); }
    return r.json();
  }
  function marquerLus() {
    var entetes = {};
    entetes[enteteJeton()] = jeton();
    return fetch('/chat/lus', { method: 'POST', credentials: 'same-origin', headers: entetes }).then(function () {
      nonLus = 0;
      majBadge();
    }).catch(function () { /* nouvel essai à la prochaine interrogation */ });
  }
  function interroger() {
    if (enCours || arrete) { return Promise.resolve(); }
    enCours = true;
    return fetch('/chat/messages?apres=' + dernier, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(reponseJson)
      .then(function (r) {
        var recus = r.messages.filter(function (m) { return !m.miens; });
        ajouter(r.messages);
        nonLus = r.nonLus;
        if (ouvert && !document.hidden && nonLus > 0) { marquerLus(); }
        majBadge();
        // Alerte : un message de l'autre plus récent que la dernière alerte (gardée entre les pages et les onglets,
        // pour ne sonner ni à chaque changement de page ni deux fois)
        var vu = parseInt(lire('chat-alerte') || '-1', 10);
        var maxRecu = recus.reduce(function (a, m) { return Math.max(a, m.id); }, 0);
        if (vu < 0) {
          // Première utilisation sur ce poste : rien ne sonne pour l'historique
          ecrire('chat-alerte', String(r.dernier));
        } else if (maxRecu > vu) {
          ecrire('chat-alerte', String(maxRecu));
          sonner();
        }
      })
      .catch(function () { /* réseau coupé : nouvel essai plus tard */ })
      .then(function () { enCours = false; });
  }
  function planifier() {
    if (arrete) { return; }
    window.setTimeout(function () { interroger().then(planifier); }, document.hidden ? 15000 : 5000);
  }

  // ── Ouverture, fermeture, envoi ────────────────────────────────────────
  function ouvrir() {
    ouvert = true;
    panneau.hidden = false;
    bouton.setAttribute('aria-expanded', 'true');
    liste.scrollTop = liste.scrollHeight;
    champ.focus();
    if (nonLus > 0) { marquerLus(); }
  }
  function fermer() {
    ouvert = false;
    panneau.hidden = true;
    bouton.setAttribute('aria-expanded', 'false');
  }
  bouton.addEventListener('click', function () { if (ouvert) { fermer(); } else { ouvrir(); } });
  racine.querySelector('[data-chat-fermer]').addEventListener('click', fermer);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && ouvert) { fermer(); bouton.focus(); } });

  // ── Patient joint : le message contient un lien vers son dossier ────────
  var joint = racine.querySelector('.chat-joint');
  var jointNom = racine.querySelector('.chat-joint-nom');
  var recherche = racine.querySelector('.chat-recherche');
  var champRecherche = racine.querySelector('.chat-recherche-champ');
  var resultats = racine.querySelector('.chat-recherche-liste');
  var patientJoint = null;
  var minuterie = null;
  function joindre(p) {
    patientJoint = p;
    jointNom.textContent = p ? p.nom : '';
    joint.hidden = !p;
  }
  function fermerRecherche() {
    recherche.hidden = true;
    champRecherche.value = '';
    resultats.replaceChildren();
  }
  racine.querySelector('[data-chat-patient]').addEventListener('click', function () {
    if (!recherche.hidden) { fermerRecherche(); return; }
    recherche.hidden = false;
    champRecherche.focus();
  });
  racine.querySelector('[data-chat-joint-retirer]').addEventListener('click', function () { joindre(null); champ.focus(); });
  champRecherche.addEventListener('input', function () {
    window.clearTimeout(minuterie);
    var q = champRecherche.value.trim();
    if (q.length < 2) { resultats.replaceChildren(); return; }
    minuterie = window.setTimeout(function () {
      fetch('/chat/patients?q=' + encodeURIComponent(q), { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
        .then(reponseJson)
        .then(function (l) {
          resultats.replaceChildren();
          l.forEach(function (p) {
            var li = document.createElement('li');
            var b = document.createElement('button');
            b.type = 'button';
            b.textContent = p.nom;
            b.addEventListener('click', function () { joindre(p); fermerRecherche(); champ.focus(); });
            li.appendChild(b);
            resultats.appendChild(li);
          });
          if (!l.length) {
            var vide2 = document.createElement('li');
            vide2.className = 'chat-vide';
            vide2.textContent = texte('tNoPatient', 'Aucun patient trouvé.');
            resultats.appendChild(vide2);
          }
        })
        .catch(function () { /* recherche indisponible : réessayer */ });
    }, 250);
  });
  champRecherche.addEventListener('keydown', function (e) { if (e.key === 'Escape') { e.stopPropagation(); fermerRecherche(); champ.focus(); } });
  racine.querySelectorAll('[data-chat-rapide]').forEach(function (b) {
    b.addEventListener('click', function () { envoyer(b.getAttribute('data-chat-rapide')); });
  });

  function envoyer(rapide) {
    var texte = (typeof rapide === 'string' ? rapide : champ.value).trim();
    // Un patient seul se suffit : « Patient : NOM Prénom » ; sinon le lien suit le texte
    if (patientJoint) {
      var lien = '[[patient:' + patientJoint.id + '|' + patientJoint.nom.replace(/[\[\]|]/g, ' ') + ']]';
      texte = texte ? texte + ' ' + lien : lien;
    }
    if (!texte) { return; }
    var corps = new URLSearchParams();
    corps.set('texte', texte);
    var entetes = { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' };
    entetes[enteteJeton()] = jeton();
    champ.disabled = true;
    fetch('/chat/messages', { method: 'POST', credentials: 'same-origin', headers: entetes, body: corps.toString() })
      .then(reponseJson)
      .then(function (m) {
        if (typeof rapide !== 'string') { champ.value = ''; }
        champ.style.height = '';
        joindre(null);
        ajouter([m]);
      })
      .catch(function () {
        var erreur = document.createElement('div');
        erreur.className = 'chat-erreur';
        erreur.textContent = texte('tNotSent', 'Message non envoyé : vérifiez la connexion puis réessayez.');
        liste.appendChild(erreur);
        liste.scrollTop = liste.scrollHeight;
        window.setTimeout(function () { erreur.remove(); }, 6000);
      })
      .then(function () { champ.disabled = false; champ.focus(); });
  }
  formulaire.addEventListener('submit', function (e) { e.preventDefault(); envoyer(); });
  champ.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); envoyer(); }
  });
  champ.addEventListener('input', function () {
    champ.style.height = 'auto';
    champ.style.height = Math.min(champ.scrollHeight, 110) + 'px';
  });

  interroger().then(planifier);
})();

// Bouton du menu (hamburger) : replie le menu en icônes pour donner toute la largeur à la page ;
// le choix est gardé sur ce poste.
(function () {
  var app = document.querySelector('.app');
  var bouton = document.querySelector('[data-menu-bascule]');
  if (!app || !bouton) { return; }
  function lire() { try { return window.localStorage.getItem('menu-replie') === '1'; } catch (e) { return false; } }
  function appliquer(replie) {
    app.classList.toggle('menu-replie', replie);
    bouton.setAttribute('aria-expanded', replie ? 'false' : 'true');
  }
  appliquer(lire());
  // Téléphone : le même bouton ouvre le menu complet en panneau ; le voile, Échap ou un lien le referment
  var telephone = window.matchMedia('(max-width: 720px)');
  function ouvrir(ouvert) {
    app.classList.toggle('menu-ouvert', ouvert);
    bouton.setAttribute('aria-expanded', ouvert ? 'true' : 'false');
    document.body.classList.toggle('sans-defilement', ouvert);
  }
  if (telephone.matches) { bouton.setAttribute('aria-expanded', 'false'); }
  document.querySelectorAll('[data-menu-fermer]').forEach(function (v) { v.addEventListener('click', function () { ouvrir(false); }); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && app.classList.contains('menu-ouvert')) { ouvrir(false); bouton.focus(); } });
  document.querySelectorAll('.menu a').forEach(function (l) { l.addEventListener('click', function () { if (telephone.matches) { ouvrir(false); } }); });
  bouton.addEventListener('click', function () {
    if (telephone.matches) { ouvrir(!app.classList.contains('menu-ouvert')); return; }
    var replie = !app.classList.contains('menu-replie');
    appliquer(replie);
    try { window.localStorage.setItem('menu-replie', replie ? '1' : '0'); } catch (e) { /* stockage refusé : le choix vaut pour cette page */ }
    // Le planning recalcule sa largeur
    window.dispatchEvent(new Event('resize'));
  });
})();

// Dossier médical : la consultation choisie (souvent la dernière, en bas de la liste) est amenée à l'écran
(function () {
  var choisie = document.querySelector('.dossier-consultations a.actif');
  var liste = choisie && choisie.closest('.dossier-consultations');
  if (liste && liste.scrollHeight > liste.clientHeight) {
    // Seule la liste défile (pas la page) : écart entre le bas de la ligne et le bas de la liste
    var depasse = choisie.getBoundingClientRect().bottom - liste.getBoundingClientRect().bottom;
    if (depasse > 0) { liste.scrollTop += depasse + 8; }
  }
})();

// Fenêtres de saisie du dossier médical (« Saisir les constantes », « Saisir les résultats »), comme sur le bureau :
// un bouton data-ouvrir="id" ouvre la fenêtre <dialog id>, data-fermer ou Échap la referme. Fermer sans enregistrer
// remet les valeurs d'origine : sinon une saisie abandonnée partirait avec l'enregistrement suivant.
(function () {
  function retablir(fenetre) {
    Array.prototype.forEach.call(fenetre.querySelectorAll('input, textarea'), function (c) { c.value = c.defaultValue; });
  }
  document.addEventListener('click', function (e) {
    var ouvrir = e.target.closest('[data-ouvrir]');
    if (ouvrir) {
      var fenetre = document.getElementById(ouvrir.getAttribute('data-ouvrir'));
      if (fenetre && fenetre.showModal) {
        fenetre.showModal();
        var premier = fenetre.querySelector('input:not([type=hidden]), textarea');
        if (premier) { premier.focus(); }
      }
      return;
    }
    var fermer = e.target.closest('[data-fermer]');
    if (fermer) {
      var ouverte = fermer.closest('dialog');
      if (ouverte) { retablir(ouverte); ouverte.close(); }
    }
  });
  // Échap : l'événement « cancel » ne remonte pas, d'où l'écoute en capture
  document.addEventListener('cancel', function (e) {
    if (e.target && e.target.tagName === 'DIALOG') { retablir(e.target); }
  }, true);
})();

// Onglets (fiche patient) : clic ou flèches gauche/droite ; l'onglet choisi est gardé dans l'adresse (#visites…),
// et une adresse qui vise un élément d'un panneau (#antecedents, #fichiers) ouvre ce panneau.
(function () {
  var liste = document.querySelector('[data-onglets]');
  if (!liste) { return; }
  var onglets = Array.prototype.slice.call(liste.querySelectorAll('[role="tab"]'));
  function ouvrir(onglet, garder) {
    onglets.forEach(function (o) {
      var actif = o === onglet;
      o.setAttribute('aria-selected', actif ? 'true' : 'false');
      o.tabIndex = actif ? 0 : -1;
      var panneau = document.getElementById(o.getAttribute('aria-controls'));
      if (panneau) { panneau.hidden = !actif; }
    });
    if (garder && window.history.replaceState) {
      var nom = onglet.getAttribute('aria-controls').replace('onglet-', '');
      window.history.replaceState(null, '', nom === 'dossier' ? window.location.pathname + window.location.search : '#' + nom);
    }
  }
  onglets.forEach(function (o, i) {
    o.addEventListener('click', function () { ouvrir(o, true); });
    o.addEventListener('keydown', function (e) {
      var j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? onglets.length - 1 : null;
      if (j === null) { return; }
      e.preventDefault();
      var cible = onglets[(j + onglets.length) % onglets.length];
      ouvrir(cible, true);
      cible.focus();
    });
  });
  var ancre = window.location.hash.slice(1);
  if (ancre) {
    var panneau = document.getElementById('onglet-' + ancre) || (document.getElementById(ancre) || { closest: function () { return null; } }).closest('[role="tabpanel"]');
    var onglet = panneau && liste.querySelector('[aria-controls="' + panneau.id + '"]');
    if (onglet) { ouvrir(onglet, false); }
  }
})();

// Session expirée pendant une requête htmx : retour à la page de connexion au lieu d'un fragment vide.
document.addEventListener('htmx:responseError', function (e) {
  if (e.detail.xhr.status === 401 || e.detail.xhr.status === 403) {
    window.location.href = '/login';
  }
});

// Écrans pas encore repris de SGCM-Cardio (classe .ancien) : confirmation déclarée par data-confirm.
document.addEventListener('submit', function (e) {
  var msg = e.target.getAttribute && e.target.getAttribute('data-confirm');
  if (msg && !window.confirm(msg)) { e.preventDefault(); }
});
// Paramètres > Utilisateurs : « inviter par e-mail » masque les mots de passe (l'assistante choisira le sien).
document.addEventListener('change', function (e) {
  if (!e.target.matches || !e.target.matches('[data-invitation]')) return;
  var form = e.target.closest('form');
  var champs = form && form.querySelector('.champs-mdp');
  if (!champs) return;
  champs.hidden = e.target.checked;
  champs.querySelectorAll('input').forEach(function (i) { i.required = !e.target.checked; if (e.target.checked) i.value = ''; });
});
// Bouton « Copier » (lien de parrainage…) : copie le champ ciblé, confirme brièvement sur le bouton.
document.addEventListener('click', function (e) {
  var bouton = e.target.closest('[data-copier]');
  if (!bouton) { return; }
  var champ = document.getElementById(bouton.getAttribute('data-copier'));
  if (!champ) { return; }
  champ.select();
  var fini = function () {
    var avant = bouton.textContent;
    bouton.textContent = bouton.dataset.copie || '✓';
    setTimeout(function () { bouton.textContent = avant; }, 1800);
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(champ.value).then(fini, function () { document.execCommand('copy'); fini(); });
  } else {
    document.execCommand('copy');
    fini();
  }
});

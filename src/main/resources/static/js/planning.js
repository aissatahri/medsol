// Planning : calendrier FullCalendar (jour / semaine / mois / liste). Les adresses viennent des attributs data-*
// de #calendrier ; les actions passent par htmx (panneau) ou fetch (glisser-déposer), jeton CSRF compris.
(function () {
  var el = document.getElementById('calendrier');
  if (!el || !window.FullCalendar) { return; }

  function jeton() {
    return {
      nom: document.querySelector('meta[name="_csrf_header"]').content,
      valeur: document.querySelector('meta[name="_csrf"]').content
    };
  }
  function statutsCoches() {
    return Array.prototype.map.call(document.querySelectorAll('#filtres-statut input:checked'), function (i) { return i.value; });
  }
  function deux(n) { return (n < 10 ? '0' : '') + n; }
  // Heure locale sans fuseau : le serveur enregistre l'heure du cabinet telle quelle
  function local(d) {
    return d.getFullYear() + '-' + deux(d.getMonth() + 1) + '-' + deux(d.getDate()) + 'T' + deux(d.getHours()) + ':' + deux(d.getMinutes()) + ':00';
  }
  // Tiroir du rendez-vous : ouvert dès qu'un contenu y est chargé, fermé par ×, le voile ou Échap
  var tiroir = document.getElementById('panneau');
  var voile = document.getElementById('voile-panneau');
  function ouvrir() { tiroir.hidden = false; voile.hidden = false; requestAnimationFrame(function () { tiroir.classList.add('ouvert'); voile.classList.add('ouvert'); }); }
  function fermer() {
    tiroir.classList.remove('ouvert'); voile.classList.remove('ouvert');
    setTimeout(function () { if (!tiroir.classList.contains('ouvert')) { tiroir.hidden = true; voile.hidden = true; } }, 200);
  }
  document.getElementById('fermer-panneau').addEventListener('click', fermer);
  voile.addEventListener('click', fermer);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !tiroir.hidden) { fermer(); } });
  document.body.addEventListener('htmx:afterSwap', function (e) {
    if (e.detail.target && e.detail.target.id === 'panneau-contenu') { ouvrir(); }
  });
  function panneau(url) { htmx.ajax('GET', url, { target: '#panneau-contenu', swap: 'innerHTML' }); }
  function message(texte, erreur) {
    var p = document.getElementById('panneau-contenu');
    var a = document.createElement('p');
    a.className = 'alerte ' + (erreur ? 'erreur' : 'succes');
    a.textContent = texte;
    p.insertBefore(a, p.firstChild);
  }
  var periode = null;
  function indicateurs() {
    if (!periode) { return; }
    htmx.ajax('GET', el.dataset.indicateurs + '?start=' + encodeURIComponent(periode.startStr) + '&end=' + encodeURIComponent(periode.endStr),
      { target: '#indicateurs', swap: 'outerHTML' });
  }

  function deplacer(info) {
    var j = jeton();
    var corps = new URLSearchParams({ attendu: info.event.extendedProps.statut, debut: local(info.event.start), fin: local(info.event.end) });
    var entetes = { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' };
    entetes[j.nom] = j.valeur;
    fetch(el.dataset.panneau + info.event.id + '/deplacer', { method: 'POST', headers: entetes, body: corps, credentials: 'same-origin' })
      .then(function (r) { if (r.status === 401 || r.status === 403) { window.location.href = '/login'; } return r.json(); })
      .then(function (res) {
        if (!res.ok) { info.revert(); }
        calendrier.refetchEvents();
        indicateurs();
        panneau(el.dataset.panneau + info.event.id);
        setTimeout(function () { message(res.message, !res.ok); }, 250);
      })
      .catch(function () { info.revert(); message(el.dataset.tMoveFailed, true); });
  }

  var etroit = window.matchMedia('(max-width: 720px)').matches;
  // Vue demandée (?vue=jour|semaine|mois|liste) ou dernière vue utilisée sur ce poste
  var VUES = { jour: 'timeGridDay', semaine: 'timeGridWeek', mois: 'dayGridMonth', liste: 'listWeek' };
  var vueInitiale = VUES[new URLSearchParams(window.location.search).get('vue')];
  if (!vueInitiale) { try { vueInitiale = window.localStorage.getItem('planning-vue'); } catch (e) { vueInitiale = null; } }
  if (!vueInitiale || (etroit && vueInitiale === 'timeGridWeek')) { vueInitiale = etroit ? 'listWeek' : 'timeGridWeek'; }
  // Un rendez-vous enregistré hors horaires (« enregistrer quand même ») ne doit jamais disparaître de la vue :
  // la plage horaire et les jours affichés s'élargissent pour le montrer.
  var baseMin = el.dataset.min || '08:00';
  var baseMax = el.dataset.max || '20:00';
  var baseFermes = JSON.parse(el.dataset.fermes || '[]');
  function heure(iso) { return iso && iso.length >= 16 ? iso.substring(11, 16) : null; }
  function elargir(evenements) {
    var c = window.calendrierCabinet;
    if (!c) { return; }
    var min = baseMin, max = baseMax, jours = {};
    evenements.forEach(function (e) {
      var d = heure(e.start), f = heure(e.end) || d;
      if (d && d < min) { min = d.substring(0, 3) + '00'; }
      if (f && f > max) { max = f.substring(0, 2) === '23' ? '24:00' : ('0' + (parseInt(f.substring(0, 2), 10) + 1)).slice(-2) + ':00'; }
      jours[new Date(e.start).getDay()] = true;
    });
    var fermes = baseFermes.filter(function (j) { return !jours[j]; });
    setTimeout(function () {
      if (c.getOption('slotMinTime') !== min) { c.setOption('slotMinTime', min); }
      if (c.getOption('slotMaxTime') !== max) { c.setOption('slotMaxTime', max); }
      if (JSON.stringify(c.getOption('hiddenDays')) !== JSON.stringify(fermes)) { c.setOption('hiddenDays', fermes); }
    }, 0);
  }  var horaires = JSON.parse(el.dataset.horaires || '[]');
  var contraint = el.dataset.contraint === 'true' && horaires.length > 0;
  var calendrier = new FullCalendar.Calendar(el, {
    // langue de l'utilisateur ; textes des boutons fournis par la page (data-t-*)
    locale: ['fr', 'es'].indexOf(el.dataset.langue) >= 0 ? el.dataset.langue : 'en',
    firstDay: 1,
    initialView: vueInitiale,
    headerToolbar: etroit
      ? { left: 'prev,next', center: 'title', right: 'today' }
      : { left: 'prev,next today', center: 'title', right: 'timeGridDay,timeGridWeek,dayGridMonth,listWeek' },
    footerToolbar: etroit ? { center: 'timeGridDay,listWeek,dayGridMonth' } : false,
    buttonIcons: false,
    buttonText: { prev: '‹', next: '›', today: el.dataset.tToday, day: el.dataset.tDay, week: el.dataset.tWeek, month: el.dataset.tMonth, list: el.dataset.tList },
    buttonHints: { prev: el.dataset.tPrev, next: el.dataset.tNext },
    slotDuration: '00:15:00',
    slotLabelInterval: '01:00',
    slotLabelFormat: { hour: '2-digit', minute: '2-digit' },
    eventTimeFormat: { hour: '2-digit', minute: '2-digit' },
    slotMinTime: el.dataset.min || '08:00',
    slotMaxTime: el.dataset.max || '20:00',
    scrollTime: el.dataset.min || '08:00',
    businessHours: horaires.length ? horaires : false,
    // Jours sans consultation masqués ; création et déplacement limités aux horaires du cabinet
    hiddenDays: JSON.parse(el.dataset.fermes || '[]'),
    selectConstraint: contraint ? 'businessHours' : undefined,
    eventConstraint: contraint ? 'businessHours' : undefined,
    nowIndicator: true,
    allDaySlot: false,
    height: 'auto',
    expandRows: true,
    stickyHeaderDates: true,
    dayMaxEvents: 4,
    navLinks: true,
    selectable: true,
    selectMirror: true,
    editable: true,
    snapDuration: '00:05:00',
    noEventsContent: el.dataset.tNone,
    events: function (info, reussite, echec) {
      var p = new URLSearchParams({ start: info.startStr, end: info.endStr });
      statutsCoches().forEach(function (s) { p.append('statuts', s); });
      fetch(el.dataset.evenements + '?' + p.toString(), { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
        .then(function (r) { if (r.status === 401) { window.location.href = '/login'; } return r.json(); })
        .then(function (evenements) { elargir(evenements); reussite(evenements); }).catch(echec);
    },
    datesSet: function (info) {
      periode = info;
      indicateurs();
      try { window.localStorage.setItem('planning-vue', info.view.type); } catch (e) { /* stockage indisponible */ }
    },
    select: function (info) {
      panneau(el.dataset.nouveau + '?debut=' + encodeURIComponent(local(info.start)) + '&fin=' + encodeURIComponent(local(info.end)));
      calendrier.unselect();
    },
    eventClick: function (info) { info.jsEvent.preventDefault(); panneau(el.dataset.panneau + info.event.id); },
    eventDrop: deplacer,
    eventResize: deplacer
  });
  calendrier.render();
  window.calendrierCabinet = calendrier;

  // Une action du panneau (création, statut, report, arrivée) a changé un rendez-vous
  document.body.addEventListener('rdvChange', function () { calendrier.refetchEvents(); indicateurs(); });
  document.getElementById('filtres-statut').addEventListener('change', function () { calendrier.refetchEvents(); });
  // Rendez-vous pris depuis un autre poste : relecture toutes les 60 s
  setInterval(function () { if (!document.hidden) { calendrier.refetchEvents(); } }, 60000);
})();

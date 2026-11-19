// Captures 1920x1080 de MedSol en local (données de démonstration fictives) pour la vidéo de présentation.
// node captures-video.mjs <base> <email> <motdepasse> <dossier>
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';

const [base, email, mdp, dossier] = process.argv.slice(2);
mkdirSync(dossier, { recursive: true });
const attendre = ms => new Promise(r => setTimeout(r, ms));

async function session() {
  const page = await fetch(base + '/login', { redirect: 'manual' });
  const c1 = page.headers.getSetCookie().map(c => c.split(';')[0]).join('; ');
  const csrf = (await page.text()).match(/name="_csrf" value="([^"]+)"/)[1];
  const r = await fetch(base + '/login', { method: 'POST', redirect: 'manual',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: c1 },
    body: new URLSearchParams({ username: email, password: mdp, _csrf: csrf }) });
  const cookie = r.headers.getSetCookie().find(c => c.startsWith('JSESSIONID'));
  if (!cookie) throw new Error('connexion refusée : ' + r.status);
  return cookie.split(';')[0].split('=')[1];
}

const chrome = spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',
  ['--headless=new', '--remote-debugging-port=9336', '--user-data-dir=' + dossier + '/profil', '--no-first-run', '--hide-scrollbars', 'about:blank'],
  { stdio: 'ignore' });
let version;
for (let i = 0; i < 40 && !version; i++) { try { version = await (await fetch('http://127.0.0.1:9336/json/version')).json(); } catch { await attendre(250); } }
const ws = new WebSocket(version.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let n = 0; const attente = new Map();
ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (m.id && attente.has(m.id)) { attente.get(m.id)(m); attente.delete(m.id); } });
const cdp = (method, params = {}, sessionId) => new Promise(r => { const id = ++n; attente.set(id, r); ws.send(JSON.stringify({ id, method, params, sessionId })); });
const { result: { targetId } } = await cdp('Target.createTarget', { url: 'about:blank' });
const { result: { sessionId: s } } = await cdp('Target.attachToTarget', { targetId, flatten: true });
await cdp('Page.enable', {}, s);
await cdp('Network.enable', {}, s);
await cdp('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false }, s);
const hote = new URL(base).hostname;
await cdp('Network.clearBrowserCookies', {}, s);
await cdp('Network.setCookie', { name: 'medsol-lang', value: 'fr', domain: hote, path: '/' }, s);

async function capture(nom, chemin, script) {
  await cdp('Page.navigate', { url: base + chemin }, s);
  await attendre(2500);
  if (script) { await cdp('Runtime.evaluate', { expression: script }, s); await attendre(900); }
  const { result: { data } } = await cdp('Page.captureScreenshot', { format: 'png' }, s);
  writeFileSync(dossier + '/' + nom + '.png', Buffer.from(data, 'base64'));
  console.log(nom);
}

// Page publique (visiteur)
await capture('01-accueil', '/');
await capture('08-securite', '/', "document.getElementById('securite').scrollIntoView(); window.scrollBy(0,-90)");
await capture('09-fin', '/', "document.getElementById('tarif').scrollIntoView({block:'center'})");

// Espace du médecin
await cdp('Network.setCookie', { name: 'JSESSIONID', value: await session(), domain: hote, path: '/' }, s);
// cabinet de démonstration créé avant l'accord de sous-traitance : acceptation une fois
await cdp('Page.navigate', { url: base + '/accepter-conditions' }, s);
await attendre(1500);
await cdp('Runtime.evaluate', { expression: "(()=>{const c=document.querySelector('input[name=accepte]'); if(c){c.checked=true; c.form.submit();}})()" }, s);
await attendre(2000);
await capture('02-tableau', '/');
await cdp('Page.navigate', { url: base + '/patients' }, s);
await attendre(1500);
const r = await cdp('Runtime.evaluate', { expression: "new URL(document.querySelector('a[href*=\"/dossier\"]').href).pathname", returnByValue: true }, s);
const dossierPatient = r.result.result.value;
await capture('03-dossier', dossierPatient);
await capture('04-ordonnance', dossierPatient + '?onglet=ordonnances');
await capture('05-impression', '/settings/impression');
await capture('06-agenda', '/planning');
await capture('07-caisse', '/caisse?impayes=true');
ws.close();
chrome.kill();

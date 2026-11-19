# Montage de la vidéo de présentation MedSol (1920x1080, 30 i/s, H.264 + AAC) :
# générique, 9 scènes (capture avec zoom lent, titre, sous-titre incrusté, voix), fondus, carte de fin.
import json, pathlib, re, subprocess, sys, textwrap
import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont, ImageFilter

D = pathlib.Path(sys.argv[1])
LOGO = pathlib.Path(sys.argv[2])
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
W, H, FPS = 1920, 1080, 30
NUIT, BLEU, BLANC = (11, 23, 48), (42, 91, 224), (255, 255, 255)
F = lambda nom, t: ImageFont.truetype(f"C:/Windows/Fonts/{nom}", t)
GRAS, NORMAL, SEMI = "segoeuib.ttf", "segoeui.ttf", "seguisb.ttf"
scenes = json.loads((D / "scenario.json").read_text(encoding="utf-8"))
(D / "travail").mkdir(exist_ok=True)

def duree(fichier):
    sortie = subprocess.run([FFMPEG, "-i", str(fichier)], capture_output=True, text=True).stderr
    h, m, s = re.search(r"Duration: (\d+):(\d+):([\d.]+)", sortie).groups()
    return int(h) * 3600 + int(m) * 60 + float(s)

def ffmpeg(*args):
    r = subprocess.run([FFMPEG, "-y", "-loglevel", "error", *args], capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(r.stderr)

def carte(texte_grand, lignes, sortie, accent=None):
    """Carte plein écran (générique / fin) : dégradé bleu nuit, logo, titre, lignes."""
    img = Image.new("RGB", (W, H), NUIT)
    d = ImageDraw.Draw(img)
    for y in range(H):   # dégradé vertical
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(11 + 11 * t), int(23 + 25 * t), int(48 + 59 * t)))
    halo = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(halo).ellipse([W * 0.55, -H * 0.4, W * 1.25, H * 0.5], fill=(74, 124, 255, 70))
    img = Image.alpha_composite(img.convert("RGBA"), halo.filter(ImageFilter.GaussianBlur(120)))
    d = ImageDraw.Draw(img)
    logo = Image.open(LOGO).convert("RGBA").resize((170, 170))
    img.alpha_composite(logo, ((W - 170) // 2, 230))
    f1 = F(GRAS, 96)
    d.text((W // 2, 470), texte_grand, font=f1, fill=BLANC, anchor="mm")
    y = 570
    for i, (txt, taille, couleur) in enumerate(lignes):
        d.text((W // 2, y), txt, font=F(SEMI if i == 0 else NORMAL, taille), fill=couleur, anchor="mm")
        y += taille + 26
    if accent:
        f = F(GRAS, 44)
        larg = d.textlength(accent, font=f) + 80
        d.rounded_rectangle([(W - larg) / 2, y + 20, (W + larg) / 2, y + 110], radius=45, fill=BLEU)
        d.text((W // 2, y + 65), accent, font=f, fill=BLANC, anchor="mm")
    img.convert("RGB").save(sortie)

def surimpression(s, sortie):
    """Calque transparent : titre en haut à gauche, sous-titre (texte de la voix) en bas."""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ft, fs = F(GRAS, 46), F(NORMAL, 28)
    if s["titre"]:
        larg = max(d.textlength(s["titre"], font=ft), d.textlength(s["sous_titre"], font=fs)) + 90
        d.rounded_rectangle([60, 60, 60 + larg, 210], radius=22, fill=(11, 23, 48, 225))
        d.rounded_rectangle([60, 60, 72, 210], radius=6, fill=BLEU + (255,))
        d.text((100, 92), s["titre"], font=ft, fill=BLANC)
        d.text((100, 158), s["sous_titre"], font=fs, fill=(182, 194, 218))
    lignes = textwrap.wrap(s["texte"], 70)
    fsub = F(SEMI, 36)
    hauteur = 56 * len(lignes) + 30
    haut = H - 70 - hauteur
    largeur = max(d.textlength(l, font=fsub) for l in lignes) + 70
    d.rounded_rectangle([(W - largeur) / 2, haut, (W + largeur) / 2, H - 70], radius=18, fill=(0, 0, 0, 170))
    for i, l in enumerate(lignes):
        d.text((W // 2, haut + 15 + 56 * i + 28), l, font=fsub, fill=BLANC, anchor="mm")
    img.save(sortie)

segments = []

# Générique (3 s, silencieux)
carte("MedSol", [("Le logiciel de cabinet médical pensé pour le Maroc", 44, (219, 228, 245))], D / "travail/generique.png")
ffmpeg("-loop", "1", "-t", "3", "-i", str(D / "travail/generique.png"), "-f", "lavfi", "-t", "3", "-i", "anullsrc=r=44100:cl=stereo",
       "-vf", f"fps={FPS},format=yuv420p,fade=in:0:15,fade=out:st=2.5:d=0.5", "-c:v", "libx264", "-preset", "medium", "-crf", "18",
       "-c:a", "aac", "-b:a", "192k", "-shortest", str(D / "travail/00-generique.mp4"))
segments.append(D / "travail/00-generique.mp4")

for s in scenes:
    audio = D / "audio" / f"{s['id']}.mp3"
    dv = round(duree(audio) + 1.1, 2)            # 0,4 s avant la voix, 0,7 s après
    frames = int(dv * FPS)
    calque = D / "travail" / f"{s['id']}-calque.png"
    surimpression(s, calque)
    zoom = f"zoompan=z='min(1+0.0004*on,1.07)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d={frames}:s={W}x{H}:fps={FPS}"
    filtre = (f"[0:v]scale={W * 2}:{H * 2},{zoom}[v];[v][1:v]overlay=0:0,format=yuv420p,"
              f"fade=in:0:9,fade=out:st={dv - 0.35}:d=0.35[vo];"
              f"[2:a]adelay=400|400,apad,atrim=0:{dv},afade=out:st={dv - 0.3}:d=0.3,aresample=44100[ao]")
    sortie = D / "travail" / f"{s['id']}.mp4"
    ffmpeg("-loop", "1", "-i", str(D / "images" / f"{s['id']}.png"), "-loop", "1", "-i", str(calque), "-i", str(audio),
           "-filter_complex", filtre, "-map", "[vo]", "-map", "[ao]", "-t", str(dv),
           "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-r", str(FPS), "-c:a", "aac", "-b:a", "192k", "-ac", "2", str(sortie))
    segments.append(sortie)
    print(s["id"], dv, "s")

# Carte de fin (5 s, silencieuse)
carte("med.appsol.app", [("30 jours d'essai gratuit · sans carte bancaire", 46, (219, 228, 245)),
                          ("Dossiers · Ordonnances · Agenda · Rendez-vous en ligne · Caisse", 34, (182, 194, 218))],
      D / "travail/fin.png", accent="Créez votre cabinet")
ffmpeg("-loop", "1", "-t", "5", "-i", str(D / "travail/fin.png"), "-f", "lavfi", "-t", "5", "-i", "anullsrc=r=44100:cl=stereo",
       "-vf", f"fps={FPS},format=yuv420p,fade=in:0:15,fade=out:st=4.4:d=0.6", "-c:v", "libx264", "-preset", "medium", "-crf", "18",
       "-c:a", "aac", "-b:a", "192k", "-shortest", str(D / "travail/99-fin.mp4"))
segments.append(D / "travail/99-fin.mp4")

# Assemblage, puis volume de la voix normalisé (norme de diffusion -16 LUFS)
liste = D / "travail/liste.txt"
liste.write_text("".join(f"file '{p.as_posix()}'\n" for p in segments), encoding="utf-8")
ffmpeg("-f", "concat", "-safe", "0", "-i", str(liste), "-c:v", "copy", "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
       "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(D / "MedSol-presentation.mp4"))
print("durée totale :", round(duree(D / "MedSol-presentation.mp4"), 1), "s")

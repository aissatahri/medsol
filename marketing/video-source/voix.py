# Commentaire de la vidéo : une piste MP3 par scène (voix neuronale Microsoft, via edge-tts).
import asyncio, json, pathlib, sys
import edge_tts

dossier = pathlib.Path(sys.argv[1])
voix = sys.argv[2] if len(sys.argv) > 2 else "fr-FR-RemyMultilingualNeural"
scenes = json.loads((dossier / "scenario.json").read_text(encoding="utf-8"))
(dossier / "audio").mkdir(exist_ok=True)

async def main():
    for s in scenes:
        sortie = dossier / "audio" / f"{s['id']}.mp3"
        await edge_tts.Communicate(s["texte"], voix, rate="-4%").save(str(sortie))
        print(s["id"], sortie.stat().st_size, "octets")

asyncio.run(main())

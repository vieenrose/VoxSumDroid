<h1 align="center">VoxSum pour Linux — Démarrage rapide</h1>

<p align="center"><i>Transformez n'importe quel audio en une transcription avec locuteurs et un court résumé — entièrement sur votre machine, hors ligne.</i></p>

<p align="center"><b>Démarrage rapide en :</b> <a href="QUICKSTART.md">English</a> · <a href="QUICKSTART.zh-TW.md">繁體中文</a> · Français</p>
<p align="center"><a href="../README.md">← Retour au README</a></p>

---

Voici une visite de 5 minutes de tout ce que fait l'application de bureau. Rien ici ne nécessite de compte, et après le téléchargement unique des modèles, plus rien ne quitte votre ordinateur.

## Installation et lancement

```bash
sudo dpkg -i voxsum_<version>_amd64.deb    # depuis une release GitHub desktop-v*
```

L'application s'installe dans `/opt/voxsum` et ajoute une entrée **VoxSum** au menu des applications (catégorie AudioVideo). Elle a besoin de **`ffmpeg`** dans le `PATH` pour le décodage et la lecture audio — installez-le depuis votre distribution s'il manque (`sudo apt install ffmpeg`). Lancez-la depuis le menu, ou exécutez `/opt/voxsum/bin/VoxSum`.

> **Le premier lancement télécharge les modèles.** À la première transcription, VoxSum récupère les modèles de parole et de locuteurs ; au premier résumé, il récupère le modèle de résumé (depuis Hugging Face, vérifiés, dans `~/.local/share/VoxSum`). Une barre de progression s'affiche. Ensuite, tout fonctionne hors ligne. Si un téléchargement échoue, VoxSum le nettoie et vous laisse réessayer.

## 1. Importer de l'audio

Cliquez sur **➕ Ajouter de l'audio** (le bouton principal de l'écran vide, ou **Ouvrir** dans la barre du haut) et choisissez une source :

| Source | À quoi ça sert |
|---|---|
| **Fichier audio** | N'importe quel audio/vidéo déjà sur votre ordinateur — via la boîte de dialogue native. |
| **Enregistrer** | Capturez une réunion en direct et voyez la transcription s'afficher à mesure. |
| **En ligne → Podcast** | Cherchez une émission, choisissez un épisode, transcrivez-le. |
| **En ligne → YouTube** | Collez un lien ou cherchez par mot-clé. |
| **Ouvrir une session (.m4a / .ogg)** | Rouvrez une session enregistrée et continuez à l'éditer. |

**Enregistrer une réunion** — *Enregistrer* capture depuis le périphérique d'entrée par défaut du système ; les lignes apparaissent à mesure que vous parlez, de petites **barres de niveau** dans la barre d'état montrent que le micro vous entend, et **Arrêter** termine le résumé. Les sources trop faibles (micro d'ambiance lointain) reçoivent un gain automatique — pour la transcription *et* la lecture.

## 2. Lire et comprendre

- **Qui parle et quand** — chaque ligne est étiquetée et colorée par locuteur, et la barre de lecture affiche une frise chronologique colorée par locuteur. Le nombre de locuteurs est détecté **automatiquement** — un segmenteur neuronal trace les frontières de locuteurs et le nombre découle de la structure de similarité des voix ; aucun seuil à régler. Les passes longues affichent une **estimation du temps restant** (« Identification des locuteurs… ≈3 min restantes »), tout comme le résumé. VoxSum peut aussi **deviner le vrai nom des locuteurs** d'après ce qu'ils disent (barre du haut **↻ Relancer → Détecter les noms**).
- **Lecteur synchronisé** — ancré en bas comme une appli musicale : cliquez une ligne pour y sauter ; la ligne en cours se surligne pendant la lecture, et la transcription **défile automatiquement** pour la garder visible.
- **Recherche** — le 🔍 de la barre du haut trouve n'importe quel mot dans un long enregistrement ; les correspondances se surlignent et vous les parcourez.
- **Taille de texte confortable** — les boutons **A− / A+** agrandissent la transcription, le titre et le résumé (la barre d'outils et le lecteur ne bougent pas). Les écrans HiDPI sont détectés automatiquement ; forcez avec `VOXSUM_UI_SCALE=1.5` si la détection se trompe.
- **Le résumé à votre façon** — un titre court, plus un résumé concis (quelques points au plus, rendu en vrai **Markdown**, replié derrière « Afficher plus » s'il est long) en **puces, note de synthèse ou récit** (choisissez le style dans les Préférences), dans la langue de votre choix. La langue du résumé est indépendante de l'audio — p. ex. un résumé en anglais sur une transcription en chinois.
- **Actions à suivre** — barre du haut **↻ Relancer → Extraire les actions** : tire une liste de tâches (qui fait quoi) et les décisions clés d'une réunion.

## 3. Personnaliser

- **Tout éditer** — corrigez un mot, renommez un locuteur, ou ajustez le titre/résumé sur place.
- **Corriger les locuteurs** — sur n'importe quelle ligne, le menu ⇄ déplace une ligne mal attribuée vers la bonne personne, ou fusionne deux locuteurs en un.
- **Relancer** — le menu **↻** relance la transcription, le résumé, **la seule détection des locuteurs** (*Redétecter les locuteurs* — sans retranscrire), la détection des noms ou l'extraction des actions, et suit les dépendances : changez la langue ou le style du résumé, ou éditez la transcription, et il propose un résumé en un clic. Passer uniquement entre **繁體中文 ↔ 简体中文** convertit titre, résumé et transcription **instantanément** — sans relancer.

## 4. Enregistrer, partager, exporter

Ouvrez le menu **Exporter** (barre du haut). Pendant qu'une transcription tourne, l'export et les réglages sont brièvement verrouillés pour ne pas enregistrer une session à moitié faite — ils se déverrouillent à la fin.

- **Enregistrer la session (.m4a ou .ogg)** — regroupe toute la session (audio ＋ transcription ＋ résumé ＋ locuteurs ＋ une pochette) dans un seul fichier qui **se lit dans n'importe quel lecteur multimédia** (avec titre, pochette, et la transcription en paroles synchronisées) et **se rouvre dans VoxSum** intact. `.m4a` est le format par défaut et correspond à celui de l'appli Android, donc une session circule entre ordinateur et téléphone. C'est votre archive — rouvrez-la à tout moment via *Ajouter de l'audio → Ouvrir une session*.
- **Exporter la transcription** — texte brut (`.txt`), sous-titres (`.srt`, `.vtt`), Markdown (`.md`), ou un **PDF** imprimable (avec prise en charge CJK si une police Noto Sans CJK est installée).

Les sessions rouvertes et enregistrées apparaissent sous **Récent** dans la barre latérale, un clic pour continuer.

## 5. Réglages utiles

- **Apparence** — **Clair**, **Sombre** ou **E-ink** (thème plat très contrasté). **Auto** (par défaut) suit le réglage clair/sombre du système.
- **Langue du résumé** — garder la langue de la transcription, ou choisir English · Français · 繁體中文 · 简体中文 · 日本語 · 한국어.
- **Style de résumé** — Puces / Synthèse / Récit.
- **Moteur de transcription** — chinois ＋ anglais par défaut ; un moteur multilingue (chinois · anglais · japonais · coréen · cantonais) est à un clic.
- **Locuteurs** — activer/désactiver la séparation des locuteurs, ou indiquer un nombre fixe (sinon le nombre est automatique).
- **Modèles** — voir l'espace disque utilisé par chaque modèle téléchargé et en supprimer pour libérer de la place (retéléchargé à la prochaine utilisation).

---

<p align="center"><i>Tout ce qui précède s'exécute sur votre machine. Pas de compte, pas de cloud, pas d'abonnement.</i></p>

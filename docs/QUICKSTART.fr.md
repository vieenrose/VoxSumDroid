<p align="center">
  <img src="screenshots/app-icon.png" width="84" alt="VoxSum" />
</p>

<h1 align="center">VoxSum — Démarrage rapide</h1>

<p align="center"><i>Transformez n'importe quel audio en une transcription avec intervenants et un résumé concis — entièrement sur votre téléphone, hors ligne.</i></p>

<p align="center"><b>Démarrage rapide en :</b><a href="QUICKSTART.md">English</a> · <a href="QUICKSTART.zh-TW.md">繁體中文</a> · Français</p>
<p align="center"><a href="../README.fr.md">← Retour au README</a></p>

---

Voici un tour en 5 minutes de tout ce que VoxSum sait faire. Rien ici ne nécessite de compte, et après le téléchargement unique des modèles, plus rien ne quitte votre téléphone.

> **Le premier lancement télécharge des modèles.** À la première transcription, VoxSum récupère le modèle de reconnaissance vocale ; au premier résumé, le modèle de résumé (depuis Hugging Face, avec vérification d'intégrité). Une barre de progression suit le téléchargement. Ensuite, vous pouvez passer entièrement hors ligne. Si un téléchargement échoue ou qu'un modèle est corrompu, VoxSum le nettoie et affiche un **Réessayer** en un geste.

<p align="center"><img src="screenshots/qs-home-fr.png" width="260" alt="Écran d'accueil de VoxSum"></p>
<p align="center"><i>L'écran d'accueil : la promesse hors ligne en évidence, un seul bouton <b>Ajouter de l'audio</b>, et vos sessions <b>Récentes</b> à portée d'un geste.</i></p>

## 1. Importer de l'audio — cinq façons

Touchez **➕ Ajouter de l'audio** (le bouton de l'accueil, ou le **+** de la barre du haut). Choisissez une source :

| Source | À quoi ça sert |
|---|---|
| **Fichier audio** | N'importe quel audio/vidéo déjà sur votre téléphone — choisissez-le dans l'explorateur. |
| **Enregistrer** | Captez une réunion en direct et voyez la transcription apparaître pendant que vous parlez. |
| **Podcast** | Cherchez une émission, choisissez un épisode, et transcrivez-le. |
| **YouTube** | Collez un lien, ou cherchez par mot-clé. |
| **Ouvrir une session (.ogg / .m4a)** | Rouvrez une session enregistrée et continuez à l'éditer. |

Vous pouvez aussi **partager** une note vocale ou un fichier audio/vidéo *vers* VoxSum depuis une autre appli (LINE, WhatsApp, un dictaphone, votre navigateur) — VoxSum apparaît dans le menu de partage et démarre la transcription.

<p align="center"><img src="screenshots/qs-add-source-fr.png" width="260" alt="Feuille Ajouter de l'audio"></p>
<p align="center"><i>Les cinq sources. « Ouvrir une session » rouvre un <code>.ogg</code> ou <code>.m4a</code> enregistré.</i></p>

### Enregistrer une réunion
**Ajouter de l'audio → Enregistrer.** Accordez la permission micro la première fois. Les lignes apparaissent au fil de la parole ; touchez **Arrêter** quand vous avez fini et VoxSum termine le résumé. Vous pouvez lire (et écouter) avant la fin.

### Transcrire un podcast
**Ajouter de l'audio → Podcast.** Tapez le nom d'une émission, choisissez un épisode, et il se télécharge puis se transcrit.

### Transcrire une vidéo YouTube
**Ajouter de l'audio → YouTube.** Collez l'URL d'une vidéo (ou tapez des mots-clés pour chercher), choisissez le résultat, et l'audio est extrait puis transcrit.

## 2. Lire et comprendre

<p align="center">
  <img src="screenshots/qs-transcript-fr.png" width="260" alt="Transcription avec résumé, intervenants et lecteur">
  &nbsp;
  <img src="screenshots/qs-search-fr.png" width="260" alt="Rechercher dans la transcription">
</p>
<p align="center"><i>À gauche : le titre, un résumé en puces, la transcription étiquetée par intervenant et le lecteur synchronisé. À droite : touchez 🔍 pour chercher — les résultats se surlignent et vous les parcourez.</i></p>

- **Qui a parlé, et quand** — chaque ligne est étiquetée et colorée par intervenant, avec leur nombre. VoxSum peut **deviner le vrai nom des intervenants** d'après leurs propos (barre du haut, menu ↻ → *Détecter les noms*).
- **Lecteur synchronisé** — ancré en bas comme une appli musicale : touchez une ligne pour y sauter ; la ligne en cours se surligne pendant la lecture.
- **Recherche** — touchez le 🔍 de la barre du haut pour trouver n'importe quel mot dans un long enregistrement ; les résultats se surlignent et se parcourent avec les flèches haut/bas.
- **Un résumé à votre façon** — un titre court et un résumé en **puces, en synthèse ou en récit** (choisissez le style dans **Paramètres**), dans la langue de votre choix. (Remarquez la capture : un résumé en anglais sur une transcription en chinois — la langue du résumé est indépendante de l'audio.)
- **Actions à mener** — barre du haut, menu ↻ → *Extraire les actions* : tire d'une réunion une liste (brouillon) de qui-fait-quoi et des décisions clés.

## 3. Personnaliser

<p align="center"><img src="screenshots/qs-rerun-fr.png" width="260" alt="Menu Relancer"></p>
<p align="center"><i>Le menu ↻ de la barre du haut : re-transcrire, re-résumer, redétecter les noms, ou extraire les actions.</i></p>

- **Modifiez tout** — corrigez un mot, renommez un intervenant, ou ajustez le titre/résumé, sur place.
- **Corrigez les intervenants** — sur n'importe quelle ligne, le menu ⇄ déplace une ligne mal attribuée vers la bonne personne, ou fusionne deux intervenants.
- **Relancez** — le menu ↻ de la barre du haut relance la transcription, le résumé, la détection des noms, ou l'extraction des actions. VoxSum suit aussi les dépendances entre contenus : changez la langue ou le style du résumé, ou modifiez la transcription, et il propose un **re-résumé** en un geste (qui rafraîchit aussi le titre, sauf si vous l'avez écrit vous-même). Un simple passage entre **繁體中文 ↔ 简体中文** convertit le titre, le résumé et la transcription **instantanément**, sans relance.

## 4. Enregistrer, partager, exporter

<p align="center"><img src="screenshots/qs-export-menu-fr.png" width="260" alt="Menu Exporter"></p>
<p align="center"><i>Le menu Exporter : enregistrer/partager toute la session en <code>.ogg</code> ou <code>.m4a</code>, ou exporter la transcription en texte, sous-titres, Markdown ou PDF.</i></p>

Ouvrez le menu **⋮ (Exporter)** de la barre du haut. (Pendant une transcription, les exports et les réglages sont brièvement verrouillés pour éviter d'enregistrer une session à moitié finie — ils se déverrouillent dès la fin.)

- **Enregistrer / Partager la session (.ogg ou .m4a)** — réunit toute la session (audio + transcription + résumé + intervenants + une pochette) dans un seul `.ogg` *ou* `.m4a` qui **se lit dans n'importe quelle appli musicale** (en affichant le titre, la pochette et la transcription en paroles) et **se rouvre dans VoxSum** avec tout intact. C'est votre archive — rouvrez-la quand vous voulez via *Ajouter de l'audio → Ouvrir une session*. Choisissez `.m4a` pour la compatibilité maximale (iPhone, autoradios, tous les lecteurs).
- **Copier / Partager la transcription** — amenez le texte dans n'importe quelle autre appli.
- **Enregistrer en texte / sous-titres / Markdown / PDF** — `.txt`, `.srt`, `.vtt`, `.md`, ou un `.pdf` imprimable, pour documents, sous-titres ou notes.

Gérez les modèles téléchargés (et récupérez de l'espace) à tout moment dans **Paramètres → Stockage**.

Les sessions enregistrées et rouvertes apparaissent sous **Récent** sur l'écran d'accueil, à un toucher de la reprise.

## 5. Réglages à connaître

<p align="center">
  <img src="screenshots/qs-settings-summary-fr.png" width="260" alt="Réglages langue et style du résumé">
  &nbsp;
  <img src="screenshots/qs-storage-fr.png" width="260" alt="Réglages Stockage et À propos">
</p>
<p align="center"><i>À gauche : langue + style du résumé. À droite : <b>Stockage</b> (espace disque par modèle, avec bouton de suppression) et <b>À propos</b> (version, licence, composants open source).</i></p>

- **Apparence** — **Clair**, **Sombre**, ou **E-ink** (un thème plat à fort contraste pour les liseuses comme Boox). **Auto** — le réglage par défaut — suit le mode clair/sombre du système.
- **Langue du résumé** — gardez la langue de la transcription, ou choisissez English · Français · 繁體中文 · 简体中文 · 日本語 · 한국어.
- **Style du résumé** — Puces / Synthèse / Récit.
- **Moteur de transcription** — chinois + anglais par défaut ; un moteur multilingue (chinois · anglais · japonais · coréen · cantonais) est à un toucher.
- **Intervenants** — activez/désactivez la séparation des locuteurs, ou indiquez leur nombre.
- **Stockage** — voyez l'espace disque utilisé par chaque modèle et supprimez-en pour récupérer de la place (il se retéléchargera à la prochaine utilisation).

---

<p align="center"><i>Tout ce qui précède s'exécute sur l'appareil. Sans compte, sans cloud, sans abonnement.</i></p>

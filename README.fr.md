<p align="center">
  <img src="docs/screenshots/app-icon.png" width="96" alt="VoxSum" />
</p>

<h1 align="center">VoxSum pour Android</h1>

<p align="center">
  <b>Transcrire · diariser · résumer — entièrement sur l'appareil, entièrement hors ligne.</b>
</p>

<p align="center">
  <a href="https://github.com/vieenrose/VoxSumDroid/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/vieenrose/VoxSumDroid?sort=semver"></a>
  <img alt="Plateforme" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Licence" src="https://img.shields.io/badge/license-GPL--3.0-blue">
  <img alt="Hors ligne" src="https://img.shields.io/badge/r%C3%A9seau-non%20requis-success">
</p>

<p align="center"><a href="README.md">English →</a> · <a href="README.zh-TW.md">繁體中文說明 →</a></p>

---

VoxSum transforme de l'audio — un fichier, un épisode de podcast ou un lien YouTube — en une
transcription étiquetée par locuteur et un résumé concis, le tout s'exécutant **sur le téléphone**.
Reconnaissance vocale, séparation des locuteurs et résumé par LLM s'exécutent localement ; pas de
serveur, pas de compte, pas de cloud. C'est un portage sur appareil de
[VoxSum Studio](https://huggingface.co/spaces/Luigi/VoxSum-bak).

> Vérifié de bout en bout sur un Pixel 6 — les trois moteurs de reconnaissance (SenseVoice, x-asr
> Zipformer zh-en, Qwen3-ASR) et les deux modèles de résumé (Gemma 4 E2B, Gemma 4 E4B) s'exécutent sur
> l'appareil : reconnaissance segmentée par VAD → diarisation → résumé, avec un lecteur synchronisé à
> la transcription. Distribué sous forme d'**APK** via
> [Releases](https://github.com/vieenrose/VoxSumDroid/releases).

## Pourquoi VoxSum

Pas seulement une appli, mais une autre posture sur la transcription — **vos mots restent à vous.**

| 🛡️ Confidentiel par conception | ✈️ Fonctionne hors ligne | 💰 Sans abonnement |
| :-- | :-- | :-- |
| L'audio ne quitte jamais votre appareil ; chaque étape s'exécute localement, donc les enregistrements confidentiels ne peuvent pas fuiter vers un cloud. | Une fois les modèles présents, aucun réseau n'est nécessaire — en avion, en train ou loin de tout. | Acquis une bonne fois pour toutes. Pas de facturation à l'usage, pas de frais récurrents. |

## Captures d'écran

| Accueil | Transcription | Résumé | Langue du résumé |
| :--: | :--: | :--: | :--: |
| <img src="docs/screenshots/01-home.png" width="200" alt="Accueil"> | <img src="docs/screenshots/03-transcript.png" width="200" alt="Transcription"> | <img src="docs/screenshots/04-summary.png" width="200" alt="Résumé"> | <img src="docs/screenshots/05-summary-language.png" width="200" alt="Sélecteur de langue du résumé"> |

## Fonctionnalités

**Capturer**
- **Trois moteurs de reconnaissance**, sélectionnables à chaque exécution — SenseVoice (multilingue : zh / en / ja / ko / yue), Zipformer zh-en (ponctué, avec casse — par défaut), Qwen3-ASR (haute précision).
- **Enregistrement en direct** — enregistrez une réunion et transcrivez à mesure que vous parlez ; les énoncés apparaissent en flux, puis la diarisation et le résumé s'exécutent à l'arrêt.
- **Podcast et YouTube** — recherchez et téléchargez un épisode de podcast (iTunes + RSS), ou collez un lien YouTube (résolu via NewPipeExtractor) directement dans le pipeline.

**Comprendre**
- **Transcription en flux** — les énoncés apparaissent au fur et à mesure que la parole est détectée (Silero VAD).
- **Diarisation des locuteurs** — empreintes CAM++ (zh+en) par énoncé + regroupement adaptatif, avec une frise colorée, des pastilles par locuteur et un panneau de statistiques. L'empreinte fp16 a été choisie par étalonnage sur l'appareil — ~1,5× plus rapide et plus précise en mandarin/anglais que la référence précédente ([poids + étalonnage](https://huggingface.co/Luigi/campplus-zh-en-onnx)).
- **Résumé sur l'appareil** — un modèle GGUF local via llama.cpp produit un titre + un résumé. Deux modèles sélectionnables : **Gemma 4 E2B** (par défaut — multilingue + CJK QAT, ~2,2 Go) et **Gemma 4 E4B** (QAT, qualité supérieure, ~3,2 Go).

**Exploiter**
- **Lecteur synchronisé à la transcription**, ancré en bas comme un lecteur de musique — touchez une ligne pour vous y rendre, la ligne active se surligne automatiquement, et la lecture fonctionne pendant que la transcription est en cours.
- **Édition en ligne** — modifiez le texte des énoncés, le titre et le résumé, et renommez les locuteurs sur place.
- **Copie en un geste** — copiez tout le résumé dans le presse-papiers d'une seule touche.
- **Langue du résumé** — choisissez la langue du résumé + titre : *Comme la transcription*, ou English / Français / 繁體中文 / 简体中文 / 日本語 / 한국어. Par défaut la langue de votre appareil (« résumer dans votre langue ») ; le chinois traditionnel est affiné via OpenCC `s2tw`.
- **Session `.ogg` autodescriptive** — enregistrez/partagez toute la session dans un seul fichier OGG/Opus (avec une pochette générée) : il se lit dans n'importe quel lecteur, tandis que VoxSum récupère la transcription exacte intégrée pour la rouvrir et la modifier.
- **Trilingue (English / 繁體中文 / Français)** — interface entièrement localisée.

## Comment ça marche

```
audio ─► VAD (Silero) ─► ASR (sherpa-onnx) ─► diarisation (CAM++ + regroupement) ─► résumé (llama.cpp + Gemma)
```

Une fine couche de streaming transforme la sortie de chaque étape en mises à jour incrémentales de
l'interface ; rien ne bloque sur le pipeline complet. Voir [`ARCHITECTURE.md`](ARCHITECTURE.md) pour
la carte des modules.

## Modèles d'IA

Chaque modèle s'exécute **sur l'appareil**. Aucun n'est inclus dans l'APK — ils sont téléchargés à la
première utilisation (vérifiés par SHA-256) depuis les sources ci-dessous.

| Rôle | Modèle | Source |
| :-- | :-- | :-- |
| ASR — **par défaut** | Transducteur Zipformer zh-en, ponctué + casse mixte (`x-asr`) | [csukuangfj2/…zh-en-punct-int8-2026-06-03](https://huggingface.co/csukuangfj2/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03) · [k2-fsa/icefall](https://github.com/k2-fsa/icefall) · [sherpa-onnx asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| ASR — multilingue | SenseVoice (zh / en / ja / ko / yue) | [FunAudioLLM/SenseVoice](https://github.com/FunAudioLLM/SenseVoice) · [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| ASR — haute précision | Qwen3-ASR 0.6B | [QwenLM](https://huggingface.co/Qwen) · [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| Détection d'activité vocale | Silero VAD | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) |
| Empreinte de locuteur (diarisation) | CAM++ zh+en, fp16 | [Luigi/campplus-zh-en-onnx](https://huggingface.co/Luigi/campplus-zh-en-onnx) · en amont [modelscope/3D-Speaker](https://github.com/modelscope/3D-Speaker) |
| LLM de résumé | Gemma 4 E2B / E4B (GGUF) | [unsloth](https://huggingface.co/unsloth) · en amont [Google Gemma](https://huggingface.co/google) |

**LLM de résumé** (GGUF QAT — entraînés en tenant compte de la quantisation), sélectionnables dans les Paramètres — en amont [Google Gemma](https://huggingface.co/google) :
- **Gemma 4 E2B** *(par défaut)* — multilingue + CJK, ~2,2 Go — [unsloth/gemma-4-E2B-it-qat-mobile-GGUF](https://huggingface.co/unsloth/gemma-4-E2B-it-qat-mobile-GGUF)
- **Gemma 4 E4B** — qualité supérieure, ~3,2 Go — [unsloth/gemma-4-E4B-it-qat-mobile-GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-qat-mobile-GGUF)

**Moteurs d'inférence :** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ASR / VAD / empreinte de
locuteur, via ONNX Runtime) et [llama.cpp](https://github.com/ggml-org/llama.cpp) (LLM). La détection
des noms de locuteurs réutilise le LLM de résumé sélectionné.

## Pile technique

| Aspect | Implémentation | Licence |
| :-- | :-- | :-- |
| ASR | sherpa-onnx `OfflineRecognizer` (SenseVoice / Zipformer / Qwen3) | Apache-2.0 |
| VAD | sherpa-onnx `Vad` (Silero) | Apache-2.0 |
| Diarisation | sherpa-onnx `SpeakerEmbeddingExtractor` (CAM++ zh+en, fp16) + regroupement adaptatif | Apache-2.0 |
| Résumé | llama.cpp + Gemma 4 E2B / E4B (GGUF) | Gemma Terms |
| Conversion zh-TW | OpenCC (`s2tw`), inclus | Apache-2.0 |
| YouTube | NewPipeExtractor | GPL-3.0 |
| Décodage audio | Android MediaCodec | plateforme |
| Interface | Jetpack Compose (Material 3) | Apache-2.0 |

Tout le code natif est **compilé depuis les sources** (sous-modules sous `native/`) ; aucun
`.aar`/`.so` précompilé n'est commité.

## Installation

Téléchargez le dernier APK signé depuis la
[**page Releases**](https://github.com/vieenrose/VoxSumDroid/releases/latest) et installez-le par
chargement latéral (Android peut demander l'autorisation d'installer depuis votre navigateur ou
gestionnaire de fichiers). Les modèles ne sont **pas** inclus — ils sont téléchargés une seule fois,
vérifiés par SHA-256, à la première utilisation ; ensuite l'appli est entièrement hors ligne.

### Mises à jour

L'appli vérifie les Releases GitHub au plus une fois par jour et affiche une bannière « Mise à jour
disponible » ; toucher **Mettre à jour** télécharge l'APK signé et le confie à l'installateur système
(vous accordez une fois « installer des applis inconnues »). Vous pouvez aussi lancer une vérification
manuelle depuis Paramètres → À propos. Cette vérification est le seul appel réseau périodique,
uniquement vers GitHub, sans télémétrie, et ignorée silencieusement hors ligne.

## Compiler depuis les sources

Nécessite Android Studio (Ladybug+), SDK 35, NDK 27.2.

```bash
git clone --recurse-submodules https://github.com/vieenrose/VoxSumDroid.git
cd VoxSumDroid

# 1. Compiler onnxruntime pour Android (l'étape lente ; figée sur v1.24.3).
./scripts/build-onnxruntime-android.sh

# 2. Pointer la compilation de l'appli dessus, puis compiler.
export SHERPA_ONNXRUNTIME_LIB_DIR="$HOME/ort-build/Release"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$HOME/ort-headers"
./gradlew :app:assembleDebug          # arm64-v8a par défaut
```

Voir [`SPIKE.md`](SPIKE.md) pour la recette éprouvée et [`RELEASING.md`](RELEASING.md) pour la façon
dont l'étiquetage `v*` produit un APK de version signé via CI.

## Licence

[GPL-3.0-or-later](LICENSE). Les dépendances source incluses conservent leurs propres licences.

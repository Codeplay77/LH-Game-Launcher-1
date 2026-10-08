# LightHouse (lh)

## Idioma

- **Sempre responder em português do Brasil (pt-BR)**: respostas, resumos, atualizações durante o trabalho, perguntas e mensagens de commit.
- Código, identificadores e comentários de código seguem o padrão do repositório (inglês).
- Textos de interface novos (catálogo, emulador) em pt-BR.

## O projeto

Launcher Android estilo console (foco em portáteis com controle, ex.: Odin 2), para **uso pessoal** — o app não é distribuído, então dependências GPL são aceitáveis.

- Kotlin + Jetpack Compose, `minSdk 28`, `targetSdk 35`, pacote `org.lighthouse`.
- Navegação 100% por controle: o `MainActivity` intercepta as teclas (`dispatchKeyEvent`) e converte em `Nav` (`GamepadNav.kt`). Seleção é por índice, não pelo sistema de foco do Compose.
- Configuração do launcher em `lighthouse.conf` (`LauncherConfig`), perfis de console em JSON (`ProfileStore`), pastas via SAF (tree URIs).

## Funcionalidades principais

- **Biblioteca / tela inicial** (`HomeScreen.kt`): grade de capas por console, L1/R1 troca de console.
- **Catálogo retrô** (`RomgiCatalogScreen.kt`, `data/RomgiCatalog.kt`): banco no formato do Romgi (`version.json` + `romdb.db.gz`, schema v4), servido do repositório próprio `lh-romdb` (cópia filtrada para os consoles do LH, script `scripts/filter_romgi.py`), baixado em segundo plano ao abrir o app (a cada 24h). Abas: Instalados + um por console; X pesquisa; A instala/exclui.
  - Ids do Romgi diferem dos perfis (ex.: `nds`→`ds`, `ps1`→`psx`, `smd`→`genesis`, `dc`→`dreamcast`) — mapa em `RomgiCatalog.PROFILE_ALIASES`.
  - Instalações passam pela fila `data/DownloadQueue.kt` (uma por vez, independe da tela; capa mostra fila/%/falha).
  - Download: links diretos HTTPS primeiro, depois torrent (`data/TorrentFetcher.kt`, libtorrent4j, baixa só o arquivo do jogo). `.zip` é extraído na pasta do console, exceto arcade.
- **Emulador embutido** (`emu/`): LibretroDroid roda cores libretro dentro do app (`GameActivity`), sem abrir apps de terceiros. Cores baixados do buildbot do libretro (`EmulatorCores`), automaticamente para todo console com jogos (`prefetch`). Mapa: NES Mesen, SNES Snes9x, GB/GBC SameBoy, GBA mGBA, N64 Mupen64Plus-Next, DS melonDS, GC/Wii Dolphin, SMS/MD/SCD Genesis Plus GX, 32X PicoDrive, Saturn Beetle Saturn (sem BIOS: YabaSanshiro), DC Flycast, PS1 SwanStation (sem BIOS: PCSX ReARMed), PSP PPSSPP. 3DS, PS2 (NetherSX2/AetherSX2), Wii U, Switch, PS3, Vita, Xbox e 360 usam app externo. BIOS vão em `Android/data/<pacote>/files/emulation/system/`. Select+Start / Home abre o menu de pausa. Desligável com `builtin_emulator = false`.
  - Cuidado: `serializeSRAM`/`serializeState`/`unserializeState` esperam a thread GL; chamá-los com a view pausada trava o app inteiro. Salvar antes de pausar (ver `glPaused` em `GameActivity`).

## Padrão visual (reutilizar, não recriar)

- Ícones de botão do controle: `PadHint` (`ConsoleMenu.kt`), chips L1/R1: `BumperChip` (`HomeScreen.kt`).
- Barra inferior: fundo `Color.Black` 55% com `PadHint`s, como na `HomeScreen`.
- Modais/menus: `GameContextMenu`. Teclado de texto: `askText` (overlay próprio, navegável por controle).
- Cores sempre do tema (`LocalTheme.current`); seleção usa `theme.primary`.

## Build

O JDK do Android Studio é Java 25 e quebra o Gradle 8.11 ("Unsupported class file major version 69"). Usar o JDK 21 portátil:

```powershell
$env:JAVA_HOME = "C:\Users\Home\.jdks\jdk-21.0.12.1+1"; .\gradlew.bat :app:assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk` · instalar com `adb install -r app\build\outputs\apk\debug\app-debug.apk`.

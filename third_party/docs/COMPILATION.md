# 🛠️ Guia Técnico de Compilação & Arquitetura de Arquivos

Este documento descreve detalhadamente todos os arquivos e dependências necessários para compilar o projeto **Castlevania NES - HD Remaster Android**, tanto localmente via linha de comando/Android Studio quanto em nuvem via **GitHub Actions**.

---

## 📋 1. Requisitos de Ambiente

| Componente | Versão Recomendada | Finalidade |
| :--- | :--- | :--- |
| **JDK (Java Development Kit)** | 17 (Temurin / OpenJDK) | Compilação do código Kotlin e execução do Gradle |
| **Android SDK Platform** | API 34 (Android 14) | SDK alvo da aplicação |
| **Android Build-Tools** | 34.0.0 | Empacotamento do APK e geração do bytecode Dex |
| **Android NDK** | `27.0.12077973` (r27b) | Compilação do motor C++ nativo em `arm64-v8a` |
| **CMake** | `3.22.1` | Gerador de build para o código nativo C++ |
| **Gradle** | `8.13` (incluso via `gradlew`) | Sistema de automação de build do Android |

---

## 📂 2. Árvore de Arquivos Essenciais para Compilação

Para compilar o projeto sem erros, a seguinte estrutura de diretórios e arquivos **deve** estar presente:

```
CASTLEVANIA NES/
├── .github/
│   └── workflows/
│       └── build.yml               # Workflow de CI/CD para compilação automática no GitHub
│
├── android/                         # Projeto Android Gradle
│   ├── app/
│   │   ├── build.gradle.kts         # Configuração de build do módulo (SDK 34, NDK 27, ABI arm64-v8a)
│   │   ├── proguard-rules.pro       # Regras de ofuscação/otimização
│   │   └── src/main/
│   │       ├── AndroidManifest.xml  # Configuração de permissões, orientação de tela, tema
│   │       ├── assets/              # Dados embutidos no APK
│   │       │   ├── rom.nes          # ROM base do jogo
│   │       │   └── mods.zip         # Pacote completo do HD Remaster (texturas, sons, hires.txt)
│   │       ├── cpp/                 # Camada JNI e CMake nativo
│   │       │   ├── CMakeLists.txt   # Script de compilação da biblioteca libcastlevania.so
│   │       │   ├── jni_bridge.cpp   # Ponte JNI entre Kotlin e o núcleo C++ Mesen
│   │       │   └── embedded_rom.h   # Estruturas auxiliares do motor
│   │       ├── java/com/castlevania/nes/ # Código-fonte Kotlin
│   │       │   ├── MainActivity.kt           # Atividade principal (ciclo de vida, modo imersivo)
│   │       │   ├── NativeBridge.kt           # Declaração dos métodos JNI nativos
│   │       │   ├── NesRenderer.kt            # Renderizador OpenGL ES 2.0
│   │       │   ├── NesGLSurfaceView.kt       # View Surface OpenGL
│   │       │   ├── NesAudioPlayer.kt         # Streaming de áudio PCM de baixa latência
│   │       │   ├── HdPackManager.kt          # Descompactador automático do mods.zip
│   │       │   ├── VirtualControllerView.kt  # Controles de toque na tela (analógico, pulo, chicote)
│   │       │   ├── ControllerEditorBar.kt    # Editor de posição/tamanho do controle
│   │       │   └── SettingsOverlayView.kt    # Menu de opções in-game (Save State, filtros, etc.)
│   │       └── res/                 # Recursos visuais (ícones, botões, temas, strings)
│   ├── build.gradle.kts             # Configurações globais dos plugins Gradle
│   ├── settings.gradle.kts          # Inclusão de módulos e repositórios
│   ├── gradle.properties            # Propriedades da JVM e do Gradle
│   ├── gradlew                      # Script wrapper Gradle para Linux/macOS
│   ├── gradlew.bat                  # Script wrapper Gradle para Windows
│   └── gradle/wrapper/              # Wrapper JAR e propriedades do Gradle
│
├── third_party/                     # Módulos C++ do emulador e utilitários
│   ├── Core/                        # Motor C++ compartilhado do Mesen
│   │   ├── Debugger/
│   │   │   └── DebuggerStub.cpp     # Stubs sem debugger (economiza RAM e processamento)
│   │   ├── Netplay/                 # Definições de estruturas de rede usadas pelo Core
│   │   ├── Shared/                  # Emulador, configurações, áudio, vídeo, savestates
│   │   ├── SNES/Input/              # Hub de controle unificado usado pelo subsistema de input
│   │   └── pch.cpp / pch.h          # Cabeçalhos pré-compilados
│   │
│   ├── NES/                         # Emulação do Hardware NES
│   │   ├── APU/                     # Áudio nativo do NES
│   │   ├── CPU/                     # Processador Ricoh 2A03 (6502)
│   │   ├── PPU/                     # Processador Gráfico Ricoh 2C02
│   │   ├── Mappers/                 # Mapeadores de cartucho NES
│   │   ├── HdPacks.cpp / HdPacks.h  # Motor de carregamento de gráficos HD (sprites/tiles)
│   │   └── HdAudioDevice.cpp        # Motor de reprodução de trilha sonora orquestrada
│   │
│   ├── SevenZip/                    # Biblioteca C de descompressão LZMA / 7z
│   ├── Utilities/                   # Utilitários (filtros de áudio, PNG spng, miniz, HQX, NTSC)
│   └── docs/                        # Documentação do projeto e capturas de tela
│       ├── COMPILATION.md
│       └── screenshots/
└── .gitignore                       # Regras de exclusão de binários temporários do Git
```

---

## ⚡ 3. Como Compilar Localmente

### Pré-requisitos no Linux / Ubuntu:
```bash
# 1. Instalar JDK 17
sudo apt update && sudo apt install -y openjdk-17-jdk

# 2. Configurar variáveis de ambiente do Android SDK
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 3. Instalar o NDK 27 e CMake via sdkmanager
yes | sdkmanager --licenses
sdkmanager --install "ndk;27.0.12077973" "cmake;3.22.1"
```

### Compilar via Linha de Comando:
```bash
# Entrar no diretório android
cd android

# Dar permissão de execução ao wrapper
chmod +x gradlew

# Para compilar APK de Debug (pronto para instalar e testar imediatamente):
./gradlew assembleDebug

# Para compilar APK de Release:
./gradlew assembleRelease
```

O APK gerado estará localizado em:
- **Debug:** `android/app/build/outputs/apk/debug/app-debug.apk`
- **Release:** `android/app/build/outputs/apk/release/app-release-unsigned.apk`

---

## ☁️ 4. Compilação Automática no GitHub Actions

O repositório está configurado com o workflow `.github/workflows/build.yml`.

### Como Funciona:
1. **Gatilhos Automáticos:**
   - A cada `push` nas branches `main` ou `master`.
   - A cada `pull_request`.
   - Ao criar uma tag de versão (ex: `git tag v1.0.0 && git push origin v1.0.0`).
2. **Gatilho Manual (Workflow Dispatch):**
   - Acesse a aba **Actions** no seu repositório no GitHub.
   - Selecione **Build Castlevania NES Android**.
   - Clique em **Run workflow** e escolha se deseja compilar `debug`, `release` ou `both`.
3. **Download do APK:**
   - Quando o workflow finalizar com sucesso, o arquivo `.apk` fica disponível na seção **Artifacts** na página da execução, pronto para download!
   - Se disparado via tag de versão (`v*`), o GitHub criará automaticamente uma **Release** com o APK anexado.

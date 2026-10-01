#include <jni.h>
#include <android/log.h>
#include <GLES2/gl2.h>
#include <mutex>
#include <atomic>
#include <vector>
#include <string>
#include <cstring>
#include <algorithm>

#include "pch.h"
#include "Shared/Emulator.h"
#include "Shared/EmuSettings.h"
#include "Shared/Interfaces/IRenderingDevice.h"
#include "Shared/Interfaces/IAudioDevice.h"
#include "Shared/Interfaces/IInputProvider.h"
#include "Shared/Video/VideoRenderer.h"
#include "Shared/Audio/SoundMixer.h"
#include "Shared/BaseControlManager.h"
#include "Shared/BaseControlDevice.h"
#include "NES/Input/NesController.h"
#include "Shared/SaveStateManager.h"
#include "Utilities/FolderUtilities.h"
#include "Utilities/VirtualFile.h"

#include "embedded_rom.h"
#include "NES/NesDefaultVideoFilter.h"
#include "NES/NesConsole.h"
#include "NES/NesMemoryManager.h"
#include "NES/HdPacks/HdAudioDevice.h"

#include "rc_hash.h"
#include "rc_consoles.h"
#include "rc_client.h"

#define LOG_TAG "CastlevaniaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static rc_client_t* g_rcClient = nullptr;
static std::recursive_mutex g_rcMutex;
static char g_raRomHash[33] = {0};

static JavaVM* g_vm = nullptr;
static jclass g_nativeBridgeClass = nullptr;
static jmethodID g_methodPerformHttpRequest = nullptr;
static jmethodID g_methodOnAchievementUnlocked = nullptr;
static jmethodID g_methodOnLoginResult = nullptr;
static jmethodID g_methodOnGameLoaded = nullptr;

static std::atomic<bool> g_cheatInfiniteHealth{false};
static std::atomic<bool> g_cheatInfiniteHearts{false};
static std::atomic<bool> g_cheatOneHitBoss{false};
static std::atomic<int>  g_difficultyMode{0}; // 0 = Normal, 1 = 2x Damage, 2 = Nightmare (1-hit death)
static std::atomic<bool> g_featureDoubleJump{false};
static std::atomic<bool> g_cheatInfiniteLives{false};
static std::atomic<bool> g_cheatMaxWhip{false};
static std::atomic<bool> g_cheatTripleShot{false};

void ProcessGameplayFrame();

class AndroidRenderingDevice : public IRenderingDevice
{
private:
    std::mutex _mutex;
    uint32_t* _pixelBuffer = nullptr;
    uint32_t _width = 256;
    uint32_t _height = 240;
    bool _isDirty = false;

    bool _textureNeedsAlloc = true;
    uint32_t _lastAllocWidth = 0;
    uint32_t _lastAllocHeight = 0;

public:
    AndroidRenderingDevice()
    {
        _pixelBuffer = new uint32_t[256 * 240];
        memset(_pixelBuffer, 0, 256 * 240 * sizeof(uint32_t));
    }

    ~AndroidRenderingDevice() override
    {
        delete[] _pixelBuffer;
    }

    void UpdateFrame(RenderedFrame& frame) override
    {
        {
            std::lock_guard<std::mutex> lock(_mutex);
            if (_width != frame.Width || _height != frame.Height) {
                delete[] _pixelBuffer;
                _width = frame.Width;
                _height = frame.Height;
                _pixelBuffer = new uint32_t[_width * _height];
                _textureNeedsAlloc = true;
                LOGI("Renderer size changed to %ux%u", _width, _height);
            }
            memcpy(_pixelBuffer, frame.FrameBuffer, _width * _height * sizeof(uint32_t));
            _isDirty = true;
        }

        // Process RetroAchievements frame tick
        {
            std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
            if (g_rcClient) {
                rc_client_do_frame(g_rcClient);
            }
        }

        // Process gameplay features & cheats
        ProcessGameplayFrame();

        static int fCount = 0;
        if (++fCount % 120 == 1) {
            LOGI("UpdateFrame #%d: %ux%u, sample pixel[0]=0x%08X", fCount, _width, _height, _pixelBuffer[0]);
        }
    }

    void ClearFrame() override
    {
        std::lock_guard<std::mutex> lock(_mutex);
        if (_pixelBuffer) {
            memset(_pixelBuffer, 0, _width * _height * sizeof(uint32_t));
        }
        _isDirty = true;
    }

    void Render(RenderSurfaceInfo& emuHud, RenderSurfaceInfo& scriptHud) override {}
    void Reset() override {}
    void SetFullscreenMode(FullscreenSettings settings) override {}

    void UploadToTexture(GLuint textureId)
    {
        std::lock_guard<std::mutex> lock(_mutex);
        if (!_pixelBuffer) return;

        glBindTexture(GL_TEXTURE_2D, textureId);
        if (_textureNeedsAlloc || _lastAllocWidth != _width || _lastAllocHeight != _height) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, _width, _height, 0, GL_RGBA, GL_UNSIGNED_BYTE, _pixelBuffer);
            _lastAllocWidth = _width;
            _lastAllocHeight = _height;
            _textureNeedsAlloc = false;
            _isDirty = false;
            LOGI("Uploaded initial/resized texture %ux%u to texId %u", _width, _height, textureId);
        } else if (_isDirty) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, _width, _height, GL_RGBA, GL_UNSIGNED_BYTE, _pixelBuffer);
            _isDirty = false;
        }
    }

    uint32_t GetWidth()
    {
        std::lock_guard<std::mutex> lock(_mutex);
        return _width;
    }

    uint32_t GetHeight()
    {
        std::lock_guard<std::mutex> lock(_mutex);
        return _height;
    }
};

class AndroidAudioDevice : public IAudioDevice
{
private:
    static constexpr size_t RING_SIZE = 65536;
    int16_t _ringBuffer[RING_SIZE];
    size_t _writePos = 0;
    size_t _readPos = 0;
    std::mutex _mutex;

public:
    void PlayBuffer(int16_t* soundBuffer, uint32_t bufferSize, uint32_t sampleRate, bool isStereo) override
    {
        std::lock_guard<std::mutex> lock(_mutex);
        uint32_t totalSamples = bufferSize * (isStereo ? 2 : 1);
        for (uint32_t i = 0; i < totalSamples; i++) {
            _ringBuffer[_writePos] = soundBuffer[i];
            _writePos = (_writePos + 1) % RING_SIZE;
            if (_writePos == _readPos) {
                // Buffer overflow, drop oldest sample
                _readPos = (_readPos + 1) % RING_SIZE;
            }
        }

        static int playCount = 0;
        if (++playCount % 120 == 1) {
            LOGI("Audio PlayBuffer #%d: %u samples (%s), sample[0]=%d", 
                 playCount, totalSamples, isStereo ? "stereo" : "mono", soundBuffer ? soundBuffer[0] : 0);
        }
    }

    void Stop() override {}
    void Pause() override {}
    void ProcessEndOfFrame() override {}
    std::string GetAvailableDevices() override { return ""; }
    void SetAudioDevice(std::string deviceName) override {}
    AudioStatistics GetStatistics() override { return {}; }

    int ReadSamples(int16_t* outBuffer, int maxSamples)
    {
        std::lock_guard<std::mutex> lock(_mutex);
        int count = 0;
        while (_readPos != _writePos && count < maxSamples) {
            outBuffer[count++] = _ringBuffer[_readPos];
            _readPos = (_readPos + 1) % RING_SIZE;
        }
        return count;
    }
};

static std::unique_ptr<Emulator> g_emu;

class AndroidInputProvider : public IInputProvider
{
private:
    std::atomic<uint32_t> _buttonMask{0};
    bool _hasDoubleJumped = false;
    bool _prevBtnA = false;

public:
    void SetButtons(uint32_t buttons)
    {
        _buttonMask.store(buttons, std::memory_order_relaxed);
    }

    bool SetInput(BaseControlDevice* device) override
    {
        if (device && device->GetPort() == 0) {
            uint32_t b = _buttonMask.load(std::memory_order_relaxed);
            // Bitmask:
            // 0x01: A
            // 0x02: B (Whip Attack)
            // 0x04: Select
            // 0x08: Start
            // 0x10: Up
            // 0x20: Down
            // 0x40: Left
            // 0x80: Right
            // 0x100: Item (Sub-weapon)
            bool btnA = (b & 0x01) != 0;
            bool btnB = (b & 0x02) != 0;
            bool btnSel = (b & 0x04) != 0;
            bool btnStart = (b & 0x08) != 0;
            bool btnUp = (b & 0x10) != 0;
            bool btnDown = (b & 0x20) != 0;
            bool btnLeft = (b & 0x40) != 0;
            bool btnRight = (b & 0x80) != 0;
            bool btnItem = (b & 0x100) != 0;

            if (btnItem) {
                // Dedicated subweapon button: triggers subweapon in the game engine
                // Completely independent from UP and B (does NOT set UP, does NOT set B!)
                btnSel = true;
            }

            // Pulo Duplo no Ar (Double Jump)
            if (g_featureDoubleJump.load()) {
                bool isHardcore = false;
                {
                    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
                    if (g_rcClient && rc_client_get_hardcore_enabled(g_rcClient)) {
                        isHardcore = true;
                    }
                }
                if (!isHardcore && g_emu) {
                    shared_ptr<IConsole> console = g_emu->GetConsole();
                    if (console) {
                        NesConsole* nesConsole = static_cast<NesConsole*>(console.get());
                        if (nesConsole && nesConsole->GetMemoryManager()) {
                            uint8_t* ram = nesConsole->GetMemoryManager()->GetInternalRam();
                            if (ram) {
                                uint8_t simonState = ram[0x046C]; // 0 = no chão, >0 = no ar
                                uint8_t stunTimer = ram[0x0047];  // stun/dano

                                if (simonState == 0) {
                                    _hasDoubleJumped = false;
                                } else if (simonState > 0 && stunTimer == 0 && !_hasDoubleJumped) {
                                    if (btnA && !_prevBtnA) {
                                        // Executa o pulo duplo no ar!
                                        ram[0x04DC] = 0xFB; // -5 velocidade vertical para cima (pulo completo)
                                        ram[0x04F8] = 0x80;
                                        if (btnLeft) {
                                            ram[0x0450] = 0xFF; // Direciona para a esquerda
                                        } else if (btnRight) {
                                            ram[0x0450] = 0x01; // Direciona para a direita
                                        }
                                        _hasDoubleJumped = true;
                                        LOGI("Pulo Duplo executado com sucesso! simonState=%d", simonState);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            _prevBtnA = btnA;

            device->SetPressedState(NesController::Buttons::A, btnA);
            device->SetPressedState(NesController::Buttons::B, btnB);
            device->SetPressedState(NesController::Buttons::Select, btnSel);
            device->SetPressedState(NesController::Buttons::Start, btnStart);
            device->SetPressedState(NesController::Buttons::Up, btnUp);
            device->SetPressedState(NesController::Buttons::Down, btnDown);
            device->SetPressedState(NesController::Buttons::Left, btnLeft);
            device->SetPressedState(NesController::Buttons::Right, btnRight);

            static uint32_t lastLogged = 0xFFFFFFFF;
            if (b != lastLogged) {
                LOGI("Input provider applied mask: 0x%03X (A:%d B:%d Item:%d Sel:%d Start:%d Up:%d Down:%d Left:%d Right:%d)",
                     b, btnA, btnB, btnItem, btnSel, btnStart, btnUp, btnDown, btnLeft, btnRight);
                lastLogged = b;
            }
            return true;
        }
        return false;
    }
};

static std::unique_ptr<AndroidRenderingDevice> g_renderer;
static std::unique_ptr<AndroidAudioDevice> g_audio;
static std::unique_ptr<AndroidInputProvider> g_input;

static int g_userHdBgmVolume = 70;

static void ApplyHdBgmVolume(int volume)
{
    g_userHdBgmVolume = std::clamp(volume, 0, 100);
    if (g_emu) {
        shared_ptr<IConsole> console = g_emu->GetConsole();
        if (console) {
            NesConsole* nesConsole = static_cast<NesConsole*>(console.get());
            if (nesConsole && nesConsole->GetHdAudioDevice()) {
                nesConsole->GetHdAudioDevice()->SetUserBgmVolume(g_userHdBgmVolume);
                LOGI("Applied HD BGM volume: %d%%", g_userHdBgmVolume);
            }
        }
    }
}

void ProcessGameplayFrame()
{
    if (!g_emu) return;

    // Verificar se o modo Hardcore do RetroAchievements está ativo
    {
        std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
        if (g_rcClient && rc_client_get_hardcore_enabled(g_rcClient)) {
            return; // No modo hardcore oficial, trapaças permanecem desativadas
        }
    }

    shared_ptr<IConsole> console = g_emu->GetConsole();
    if (!console) return;

    NesConsole* nesConsole = static_cast<NesConsole*>(console.get());
    if (!nesConsole || !nesConsole->GetMemoryManager()) return;

    uint8_t* ram = nesConsole->GetMemoryManager()->GetInternalRam();
    if (!ram) return;

    // 1. Vida Infinita (God Mode)
    if (g_cheatInfiniteHealth.load()) {
        if (ram[0x0045] > 0 && ram[0x0045] < 0x40) {
            ram[0x0045] = 0x40; // 64 = 16 blocos de vida cheios
            ram[0x0044] = 0x40; // Barra de vida no HUD
        }
    }

    // 2. Corações Infinitos (99 Corações)
    if (g_cheatInfiniteHearts.load()) {
        if (ram[0x0071] < 99) {
            ram[0x0071] = 99;
        }
    }

    // 3. Matar Chefão com 1 Golpe (One-Hit Boss Kill)
    if (g_cheatOneHitBoss.load()) {
        uint8_t bossHp = ram[0x005B];
        static uint8_t s_lastBossHp = 0;
        static bool s_bossReady = false;

        if (bossHp >= 0x30) {
            // A barra de vida do boss carregou e a luta começou
            s_bossReady = true;
        } else if (bossHp == 0) {
            s_bossReady = false;
        }

        if (s_bossReady && bossHp > 0 && bossHp < s_lastBossHp) {
            LOGI("One-hit boss kill! Reduzindo vida do boss de %d para 0", bossHp);
            ram[0x005B] = 0;
            s_bossReady = false;
        }
        s_lastBossHp = bossHp;
    }

    // 4. Aumentar a Dificuldade do Jogo (Dano 2x ou Modo Pesadelo)
    int diff = g_difficultyMode.load();
    if (diff > 0 && !g_cheatInfiniteHealth.load()) {
        uint8_t currentHp = ram[0x0045];
        static uint8_t s_lastSimonHp = 0x40;

        if (currentHp > 0 && currentHp < s_lastSimonHp) {
            uint8_t damage = s_lastSimonHp - currentHp;
            if (diff == 1) {
                // Dano em Dobro (2x)
                if (currentHp > damage) {
                    ram[0x0045] = currentHp - damage;
                } else {
                    ram[0x0045] = 0;
                }
                ram[0x0044] = ram[0x0045];
                LOGI("Dificuldade 2x: Dano extra de %d aplicado. Vida atual: %d", damage, ram[0x0045]);
            } else if (diff == 2) {
                // Modo Pesadelo: Morte em 1 Golpe
                ram[0x0045] = 0;
                ram[0x0044] = 0;
                LOGI("Modo Pesadelo: Simon sofreu dano e foi eliminado!");
            }
        }
        s_lastSimonHp = ram[0x0045];
    }

    // 5. Chicote Nível Máximo (Morning Star)
    if (g_cheatMaxWhip.load()) {
        if (ram[0x0070] < 2) {
            ram[0x0070] = 2;
        }
    }

    // 6. Disparo Triplo Permanente (Triple Shot III)
    if (g_cheatTripleShot.load()) {
        if (ram[0x006C] < 2) {
            ram[0x006C] = 2;
        }
    }

    // 7. Vidas Infinitas (9 vidas)
    if (g_cheatInfiniteLives.load()) {
        if (ram[0x002A] < 9) {
            ram[0x002A] = 9;
        }
    }
}

static uint32_t NesReadMemory(uint32_t address, uint8_t* buffer, uint32_t num_bytes, rc_client_t* client)
{
    if (!g_emu) return 0;
    auto console = g_emu->GetConsole();
    if (!console) return 0;

    NesConsole* nesConsole = static_cast<NesConsole*>(console.get());
    if (!nesConsole || !nesConsole->GetMemoryManager()) return 0;

    auto mm = nesConsole->GetMemoryManager();
    uint8_t* ram = mm->GetInternalRam();

    uint32_t bytesRead = 0;
    for (uint32_t i = 0; i < num_bytes; i++) {
        uint32_t addr = address + i;
        if (addr < 0x2000 && ram) {
            buffer[i] = ram[addr & 0x07FF];
        } else if (addr < 0x10000) {
            buffer[i] = mm->DebugRead((uint16_t)addr);
        } else {
            buffer[i] = 0;
        }
        bytesRead++;
    }
    return bytesRead;
}

static void NesServerCall(const rc_api_request_t* request, rc_client_server_callback_t callback, void* callback_data, rc_client_t* client)
{
    LOGI("RetroAchievements NesServerCall: url=%s, postDataLen=%zu",
         request->url ? request->url : "(null)",
         request->post_data ? strlen(request->post_data) : 0);

    if (!g_vm || !g_nativeBridgeClass || !g_methodPerformHttpRequest) {
        LOGE("RetroAchievements server call failed: JNI not initialized");
        return;
    }

    JNIEnv* env = nullptr;
    bool needsDetach = false;
    jint getEnvRes = g_vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (getEnvRes == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            needsDetach = true;
        }
    }

    if (env) {
        jstring jUrl = env->NewStringUTF(request->url ? request->url : "");
        jstring jPost = request->post_data ? env->NewStringUTF(request->post_data) : nullptr;
        jstring jContentType = request->content_type ? env->NewStringUTF(request->content_type) : nullptr;
        jlong cbPtr = (jlong)(uintptr_t)callback;
        jlong cbDataPtr = (jlong)(uintptr_t)callback_data;

        env->CallStaticVoidMethod(g_nativeBridgeClass, g_methodPerformHttpRequest, jUrl, jPost, jContentType, cbPtr, cbDataPtr);

        env->DeleteLocalRef(jUrl);
        if (jPost) env->DeleteLocalRef(jPost);
        if (jContentType) env->DeleteLocalRef(jContentType);

        if (needsDetach) {
            g_vm->DetachCurrentThread();
        }
    }
}

static void OnRcClientEvent(const rc_client_event_t* event, rc_client_t* client)
{
    if (!event) return;
    if (event->type == RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED && event->achievement) {
        LOGI("RetroAchievements Achievement Triggered: %s (%d pts)", event->achievement->title, event->achievement->points);

        if (g_vm && g_nativeBridgeClass && g_methodOnAchievementUnlocked) {
            JNIEnv* env = nullptr;
            bool needsDetach = false;
            if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
                if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    needsDetach = true;
                }
            }
            if (env) {
                jint id = (jint)event->achievement->id;
                jstring title = env->NewStringUTF(event->achievement->title ? event->achievement->title : "");
                jstring desc = env->NewStringUTF(event->achievement->description ? event->achievement->description : "");
                jint pts = (jint)event->achievement->points;
                jstring badge = env->NewStringUTF(event->achievement->badge_name);

                env->CallStaticVoidMethod(g_nativeBridgeClass, g_methodOnAchievementUnlocked, id, title, desc, pts, badge);

                env->DeleteLocalRef(title);
                env->DeleteLocalRef(desc);
                env->DeleteLocalRef(badge);

                if (needsDetach) {
                    g_vm->DetachCurrentThread();
                }
            }
        }
    }
}

static void OnLoginCallback(int result, const char* error_message, rc_client_t* client, void* userdata)
{
    LOGI("RetroAchievements login result: %d, error: %s", result, error_message ? error_message : "none");
    if (g_vm && g_nativeBridgeClass && g_methodOnLoginResult) {
        JNIEnv* env = nullptr;
        bool needsDetach = false;
        if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                needsDetach = true;
            }
        }
        if (env) {
            jboolean success = (result == RC_OK) ? JNI_TRUE : JNI_FALSE;
            jstring err = env->NewStringUTF(error_message ? error_message : "");

            std::string tok = "";
            const auto* user = rc_client_get_user_info(client);
            if (user && user->token) tok = user->token;
            jstring jTok = env->NewStringUTF(tok.c_str());

            env->CallStaticVoidMethod(g_nativeBridgeClass, g_methodOnLoginResult, success, err, jTok);

            env->DeleteLocalRef(err);
            env->DeleteLocalRef(jTok);

            if (needsDetach) {
                g_vm->DetachCurrentThread();
            }
        }
    }
}

static void OnGameLoadedCallback(int result, const char* error_message, rc_client_t* client, void* userdata)
{
    LOGI("RetroAchievements game load result: %d, error: %s", result, error_message ? error_message : "none");
    if (g_vm && g_nativeBridgeClass && g_methodOnGameLoaded) {
        JNIEnv* env = nullptr;
        bool needsDetach = false;
        if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                needsDetach = true;
            }
        }
        if (env) {
            jboolean success = (result == RC_OK) ? JNI_TRUE : JNI_FALSE;
            jstring err = env->NewStringUTF(error_message ? error_message : "");

            env->CallStaticVoidMethod(g_nativeBridgeClass, g_methodOnGameLoaded, success, err);

            env->DeleteLocalRef(err);

            if (needsDetach) {
                g_vm->DetachCurrentThread();
            }
        }
    }
}

static std::string EscapeJson(const char* str)
{
    if (!str) return "";
    std::string out;
    for (const char* p = str; *p; p++) {
        if (*p == '"') out += "\\\"";
        else if (*p == '\\') out += "\\\\";
        else if (*p == '\n') out += "\\n";
        else if (*p == '\r') out += "\\r";
        else if (*p == '\t') out += "\\t";
        else out += *p;
    }
    return out;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        jclass clazz = env->FindClass("com/castlevania/nes/NativeBridge");
        if (clazz) {
            g_nativeBridgeClass = (jclass)env->NewGlobalRef(clazz);
            g_methodPerformHttpRequest = env->GetStaticMethodID(
                g_nativeBridgeClass,
                "onServerCall",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V"
            );
            g_methodOnAchievementUnlocked = env->GetStaticMethodID(
                g_nativeBridgeClass,
                "onAchievementUnlocked",
                "(ILjava/lang/String;Ljava/lang/String;ILjava/lang/String;)V"
            );
            g_methodOnLoginResult = env->GetStaticMethodID(
                g_nativeBridgeClass,
                "onLoginResult",
                "(ZLjava/lang/String;Ljava/lang/String;)V"
            );
            g_methodOnGameLoaded = env->GetStaticMethodID(
                g_nativeBridgeClass,
                "onGameLoaded",
                "(ZLjava/lang/String;)V"
            );
        }
    }
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_castlevania_nes_NativeBridge_nativeInit(JNIEnv* env, jclass clazz, jstring homeDir, jstring hdPackDir)
{
    const char* homeDirC = env->GetStringUTFChars(homeDir, nullptr);
    const char* hdPackDirC = env->GetStringUTFChars(hdPackDir, nullptr);

    LOGI("Initializing Mesen NES with embedded Castlevania ROM...");
    LOGI("Home directory: %s", homeDirC);
    LOGI("HD Pack directory: %s", hdPackDirC);

    try {
        FolderUtilities::SetHomeFolder(homeDirC);

        g_emu.reset(new Emulator());
        g_emu->Initialize();

        // Configure emulator settings
        EmuSettings* settings = g_emu->GetSettings();
        settings->GetEmulationConfig().EmulationSpeed = 100;
        
        // Configure NES & HD Pack
        NesConfig& nesCfg = settings->GetNesConfig();
        nesCfg.Port1.Type = ControllerType::NesController;
        nesCfg.Port2.Type = ControllerType::NesController;
        nesCfg.EnableHdPacks = true;
        nesCfg.SpritesEnabled = true;
        nesCfg.BackgroundEnabled = true;
        for (int i = 0; i < 11; i++) {
            nesCfg.ChannelVolumes[i] = 100;
        }
        nesCfg.EpsmVolume = 100;
        NesDefaultVideoFilter::GetFullPalette(nesCfg.UserPalette, nesCfg, PpuModel::Ppu2C02);

        // Configure Audio
        AudioConfig& audioCfg = settings->GetAudioConfig();
        audioCfg.EnableAudio = true;
        audioCfg.MasterVolume = 100;
        audioCfg.SampleRate = 48000;
        audioCfg.MuteSoundInBackground = false;
        audioCfg.ReduceSoundInBackground = false;

        // Register custom devices
        g_renderer.reset(new AndroidRenderingDevice());
        g_emu->GetVideoRenderer()->RegisterRenderingDevice(g_renderer.get());

        g_audio.reset(new AndroidAudioDevice());
        g_emu->GetSoundMixer()->RegisterAudioDevice(g_audio.get());

        g_input.reset(new AndroidInputProvider());

        // 1. Calculate RetroAchievements ROM Hash BEFORE in-memory patch:
        rc_hash_iterator_t hashIterator;
        rc_hash_initialize_iterator(&hashIterator, "rom.nes", assets_rom_nes, assets_rom_nes_len);
        if (rc_hash_generate(g_raRomHash, RC_CONSOLE_NINTENDO, &hashIterator)) {
            LOGI("RetroAchievements NES ROM Hash (pre-patch): %s", g_raRomHash);
        } else {
            LOGE("Failed to calculate RetroAchievements NES ROM Hash!");
        }
        rc_hash_destroy_iterator(&hashIterator);

        // Decouple subweapon completely from UP and B:
        // 1. Attack button B only attacks with whip (never uses subweapon, even if UP is held)
        // 2. Item button directly uses subweapon (without UP and without whip)
        // 3. If Simon has no subweapon/hearts, Item button does NOTHING (does not whip)
        static const uint8_t s_subweaponPatch[33] = {
            0xAD, 0x34, 0x04, // LDA $0434
            0xD0, 0xF9,       // BNE exit
            0xA9, 0x20,       // LDA #$20 (Item / Select)
            0x24, 0xF5,       // BIT $F5 (test B and Item bits)
            0x70, 0x11,       // BVS do_whip (if B pressed)
            0xF0, 0xF1,       // BEQ exit
            0x8A,             // TXA (save stance X)
            0x48,             // PHA
            0x20, 0x02, 0xFA, // JSR $FA02 (check subweapon & hearts)
            0x90, 0x05,       // BCC fail
            0x68,             // PLA (restore stance X)
            0x09, 0x80,       // ORA #$80 (subweapon flag)
            0xD0, 0x04,       // BNE store
            0x68,             // PLA (fail: clean stack)
            0x18,             // CLC
            0x60,             // RTS
            0x8A,             // TXA (do_whip: normal whip)
            0x8D, 0x34, 0x04, // STA $0434 (store)
            0xEA              // NOP
        };
        if (assets_rom_nes_len > 0x19a3a + 33) {
            memcpy(&assets_rom_nes[0x19a3a], s_subweaponPatch, 33);
            LOGI("Applied dedicated subweapon patch to embedded ROM at 0x19a3a");
        }

        // Load embedded Castlevania ROM directly from memory
        LOGI("Loading embedded ROM buffer (size: %u bytes)...", assets_rom_nes_len);
        VirtualFile romFile(assets_rom_nes, assets_rom_nes_len, "rom.nes");
        
        bool loaded = g_emu->LoadRom(romFile, VirtualFile());
        if (!loaded) {
            LOGE("Failed to load embedded ROM!");
            env->ReleaseStringUTFChars(homeDir, homeDirC);
            env->ReleaseStringUTFChars(hdPackDir, hdPackDirC);
            return JNI_FALSE;
        }

        // Register input provider with control manager and verify devices
        if (g_emu->GetConsole() && g_emu->GetConsole()->GetControlManager()) {
            auto cm = g_emu->GetConsole()->GetControlManager();
            cm->RegisterInputProvider(g_input.get());
            auto devices = cm->GetControlDevices();
            LOGI("ControlManager has %zu devices registered", devices.size());
            for (size_t i = 0; i < devices.size(); i++) {
                LOGI("ControlDevice[%zu]: port=%d, type=%d", i, devices[i]->GetPort(), (int)devices[i]->GetControllerType());
            }
        }

        // Apply background music volume setting for HD Pack
        ApplyHdBgmVolume(g_userHdBgmVolume);

        // Initialize RetroAchievements rc_client
        {
            std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
            if (g_rcClient) {
                rc_client_destroy(g_rcClient);
                g_rcClient = nullptr;
            }
            g_rcClient = rc_client_create(NesReadMemory, NesServerCall);
            rc_client_set_event_handler(g_rcClient, OnRcClientEvent);
            LOGI("RetroAchievements rc_client initialized successfully!");
        }

        LOGI("Castlevania ROM loaded and emulation started successfully!");

        env->ReleaseStringUTFChars(homeDir, homeDirC);
        env->ReleaseStringUTFChars(hdPackDir, hdPackDirC);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception in nativeInit: %s", e.what());
        env->ReleaseStringUTFChars(homeDir, homeDirC);
        env->ReleaseStringUTFChars(hdPackDir, hdPackDirC);
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativePause(JNIEnv* env, jclass clazz)
{
    if (g_emu) {
        g_emu->Pause();
    }
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeResume(JNIEnv* env, jclass clazz)
{
    if (g_emu) {
        g_emu->Resume();
    }
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetInput(JNIEnv* env, jclass clazz, jint buttons)
{
    if (g_input) {
        g_input->SetButtons((uint32_t)buttons);
    }
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRender(JNIEnv* env, jclass clazz, jint textureId)
{
    if (g_renderer) {
        g_renderer->UploadToTexture((GLuint)textureId);
    }
}

JNIEXPORT jint JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetFrameWidth(JNIEnv* env, jclass clazz)
{
    return g_renderer ? (jint)g_renderer->GetWidth() : 256;
}

JNIEXPORT jint JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetFrameHeight(JNIEnv* env, jclass clazz)
{
    return g_renderer ? (jint)g_renderer->GetHeight() : 240;
}

JNIEXPORT jint JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetAudioSamples(JNIEnv* env, jclass clazz, jshortArray outArray)
{
    if (!g_audio) return 0;

    jsize len = env->GetArrayLength(outArray);
    std::vector<int16_t> temp(len);
    int read = g_audio->ReadSamples(temp.data(), len);
    if (read > 0) {
        env->SetShortArrayRegion(outArray, 0, read, (jshort*)temp.data());
    }
    return (jint)read;
}

JNIEXPORT jboolean JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSaveState(JNIEnv* env, jclass clazz, jint slot)
{
    {
        std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
        if (g_rcClient && rc_client_get_hardcore_enabled(g_rcClient)) {
            LOGI("Save State blocked: RetroAchievements Hardcore mode is active!");
            return JNI_FALSE;
        }
    }
    if (!g_emu || !g_emu->GetSaveStateManager()) return JNI_FALSE;
    g_emu->GetSaveStateManager()->SaveState((int)slot, false);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_castlevania_nes_NativeBridge_nativeLoadState(JNIEnv* env, jclass clazz, jint slot)
{
    {
        std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
        if (g_rcClient && rc_client_get_hardcore_enabled(g_rcClient)) {
            LOGI("Load State blocked: RetroAchievements Hardcore mode is active!");
            return JNI_FALSE;
        }
    }
    if (!g_emu || !g_emu->GetSaveStateManager()) return JNI_FALSE;
    return g_emu->GetSaveStateManager()->LoadState((int)slot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeReset(JNIEnv* env, jclass clazz)
{
    LOGI("Resetting Castlevania NES emulator...");
    if (g_emu) {
        g_emu->Reset();
        ApplyHdBgmVolume(g_userHdBgmVolume);
    }
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetHdPack(JNIEnv* env, jclass clazz, jboolean enable)
{
    if (g_emu) {
        LOGI("Setting HD Pack enabled: %d", (int)enable);
        g_emu->GetSettings()->GetNesConfig().EnableHdPacks = (bool)enable;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetHdPack(JNIEnv* env, jclass clazz)
{
    if (!g_emu) return JNI_TRUE;
    return g_emu->GetSettings()->GetNesConfig().EnableHdPacks ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetNoSpriteLimit(JNIEnv* env, jclass clazz, jboolean enable)
{
    if (g_emu) {
        LOGI("Setting RemoveSpriteLimit: %d", (int)enable);
        g_emu->GetSettings()->GetNesConfig().RemoveSpriteLimit = (bool)enable;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetNoSpriteLimit(JNIEnv* env, jclass clazz)
{
    if (!g_emu) return JNI_FALSE;
    return g_emu->GetSettings()->GetNesConfig().RemoveSpriteLimit ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetMasterVolume(JNIEnv* env, jclass clazz, jint volume)
{
    if (g_emu) {
        int v = std::clamp((int)volume, 0, 100);
        g_emu->GetSettings()->GetAudioConfig().MasterVolume = (uint32_t)v;
    }
}

JNIEXPORT jint JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetMasterVolume(JNIEnv* env, jclass clazz)
{
    if (!g_emu) return 100;
    return (jint)g_emu->GetSettings()->GetAudioConfig().MasterVolume;
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetHdBgmVolume(JNIEnv* env, jclass clazz, jint volume)
{
    ApplyHdBgmVolume((int)volume);
}

JNIEXPORT jint JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetHdBgmVolume(JNIEnv* env, jclass clazz)
{
    return (jint)g_userHdBgmVolume;
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeDestroy(JNIEnv* env, jclass clazz)
{
    LOGI("Destroying Mesen emulator instance...");
    if (g_emu) {
        g_emu->Stop(false);
        g_emu.reset();
    }
    {
        std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
        if (g_rcClient) {
            rc_client_destroy(g_rcClient);
            g_rcClient = nullptr;
        }
    }
    g_renderer.reset();
    g_audio.reset();
    g_input.reset();
}

JNIEXPORT jint JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetCurrentSubweapon(JNIEnv* env, jclass clazz)
{
    if (g_emu) {
        shared_ptr<IConsole> console = g_emu->GetConsole();
        if (console) {
            NesConsole* nesConsole = static_cast<NesConsole*>(console.get());
            if (nesConsole && nesConsole->GetMemoryManager()) {
                uint8_t* ram = nesConsole->GetMemoryManager()->GetInternalRam();
                if (ram) {
                    uint8_t sw = ram[0x015B];
                    static uint8_t lastSw = 0xFF;
                    if (sw != lastSw) {
                        LOGI("Subweapon changed in RAM 0x015B to: 0x%02X", sw);
                        lastSw = sw;
                    }
                    return (jint)sw;
                }
            }
        }
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_castlevania_nes_NativeBridge_nativeGetRomHash(JNIEnv* env, jclass clazz)
{
    return env->NewStringUTF(g_raRomHash);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaLoginWithPassword(JNIEnv* env, jclass clazz, jstring username, jstring password)
{
    const char* userC = env->GetStringUTFChars(username, nullptr);
    const char* passC = env->GetStringUTFChars(password, nullptr);

    {
        std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
        if (g_rcClient) {
            LOGI("Starting RetroAchievements login for user: %s", userC);
            rc_client_begin_login_with_password(g_rcClient, userC, passC, OnLoginCallback, nullptr);
        }
    }

    env->ReleaseStringUTFChars(username, userC);
    env->ReleaseStringUTFChars(password, passC);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaLoginWithToken(JNIEnv* env, jclass clazz, jstring username, jstring token)
{
    const char* userC = env->GetStringUTFChars(username, nullptr);
    const char* tokenC = env->GetStringUTFChars(token, nullptr);

    {
        std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
        if (g_rcClient) {
            LOGI("Starting RetroAchievements login with token for user: %s", userC);
            rc_client_begin_login_with_token(g_rcClient, userC, tokenC, OnLoginCallback, nullptr);
        }
    }

    env->ReleaseStringUTFChars(username, userC);
    env->ReleaseStringUTFChars(token, tokenC);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaLogout(JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
    if (g_rcClient) {
        LOGI("RetroAchievements logout");
        rc_client_logout(g_rcClient);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaIsLoggedIn(JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
    if (!g_rcClient) return JNI_FALSE;
    return rc_client_get_user_info(g_rcClient) != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaGetGameTitle(JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
    if (!g_rcClient) return env->NewStringUTF("");
    const auto* game = rc_client_get_game_info(g_rcClient);
    if (game && game->title) {
        return env->NewStringUTF(game->title);
    }
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaLoadGame(JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
    if (!g_rcClient) return;

    if (g_raRomHash[0] == '\0') {
        LOGE("RetroAchievements cannot load game: ROM Hash is empty");
        return;
    }

    if (!rc_client_get_user_info(g_rcClient)) {
        LOGW("RetroAchievements cannot load game: user is not logged in yet");
        return;
    }

    LOGI("RetroAchievements loading game with pre-patch hash: %s", g_raRomHash);
    rc_client_begin_load_game(g_rcClient, g_raRomHash, OnGameLoadedCallback, nullptr);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaSetHardcoreEnabled(JNIEnv* env, jclass clazz, jboolean enabled)
{
    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
    if (g_rcClient) {
        rc_client_set_hardcore_enabled(g_rcClient, enabled ? 1 : 0);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaGetHardcoreEnabled(JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
    if (g_rcClient) {
        return rc_client_get_hardcore_enabled(g_rcClient) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaGetUserInfoJson(JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
    if (!g_rcClient) return env->NewStringUTF("{}");

    const auto* user = rc_client_get_user_info(g_rcClient);
    if (!user) return env->NewStringUTF("{}");

    std::string json = "{";
    json += "\"username\":\"" + EscapeJson(user->username) + "\",";
    json += "\"display_name\":\"" + EscapeJson(user->display_name) + "\",";
    json += "\"token\":\"" + EscapeJson(user->token) + "\",";
    json += "\"score\":" + std::to_string(user->score) + ",";
    json += "\"score_softcore\":" + std::to_string(user->score_softcore);
    json += "}";
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_castlevania_nes_NativeBridge_nativeRaGetAchievementsJson(JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
    if (!g_rcClient) return env->NewStringUTF("[]");

    auto* list = rc_client_create_achievement_list(g_rcClient, RC_CLIENT_ACHIEVEMENT_CATEGORY_PROMOTED_AND_UNPROMOTED, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
    std::string json = "[";
    bool first = true;
    if (list) {
        for (uint32_t b = 0; b < list->num_buckets; b++) {
            const auto& bucket = list->buckets[b];
            for (uint32_t a = 0; a < bucket.num_achievements; a++) {
                const auto* ach = bucket.achievements[a];
                if (!ach) continue;
                if (!first) json += ",";
                first = false;

                json += "{";
                json += "\"id\":" + std::to_string(ach->id) + ",";
                json += "\"title\":\"" + EscapeJson(ach->title) + "\",";
                json += "\"description\":\"" + EscapeJson(ach->description) + "\",";
                json += "\"points\":" + std::to_string(ach->points) + ",";
                json += "\"unlocked\":" + std::string(ach->unlocked ? "true" : "false") + ",";
                json += "\"badge\":\"" + std::string(ach->badge_name) + "\"";
                json += "}";
            }
        }
        rc_client_destroy_achievement_list(list);
    }
    json += "]";
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeProcessServerResponse(
    JNIEnv* env, jclass clazz, jlong callbackPtr, jlong callbackDataPtr, jint httpStatus, jstring body)
{
    auto callback = reinterpret_cast<rc_client_server_callback_t>((uintptr_t)callbackPtr);
    void* callback_data = reinterpret_cast<void*>((uintptr_t)callbackDataPtr);

    const char* bodyC = body ? env->GetStringUTFChars(body, nullptr) : "";

    LOGI("RetroAchievements nativeProcessServerResponse: status=%d, bodyLen=%zu, snippet=%.90s",
         (int)httpStatus, strlen(bodyC), bodyC);

    rc_api_server_response_t server_response;
    memset(&server_response, 0, sizeof(server_response));
    server_response.http_status_code = (int)httpStatus;
    server_response.body = bodyC;
    server_response.body_length = strlen(bodyC);

    if (callback) {
        std::lock_guard<std::recursive_mutex> lock(g_rcMutex);
        callback(&server_response, callback_data);
    }

    if (body) {
        env->ReleaseStringUTFChars(body, bodyC);
    }
}

// =========================================================================
// Gameplay Features & Cheats JNI API
// =========================================================================

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetInfiniteHealth(JNIEnv* env, jclass clazz, jboolean enable)
{
    g_cheatInfiniteHealth.store(enable);
    LOGI("Native cheat Infinite Health set to: %d", (int)enable);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetInfiniteHearts(JNIEnv* env, jclass clazz, jboolean enable)
{
    g_cheatInfiniteHearts.store(enable);
    LOGI("Native cheat Infinite Hearts set to: %d", (int)enable);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetOneHitBoss(JNIEnv* env, jclass clazz, jboolean enable)
{
    g_cheatOneHitBoss.store(enable);
    LOGI("Native cheat One-Hit Boss Kill set to: %d", (int)enable);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetDifficultyMode(JNIEnv* env, jclass clazz, jint mode)
{
    g_difficultyMode.store(mode);
    LOGI("Native Difficulty Mode set to: %d", (int)mode);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetDoubleJump(JNIEnv* env, jclass clazz, jboolean enable)
{
    g_featureDoubleJump.store(enable);
    LOGI("Native Double Jump feature set to: %d", (int)enable);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetInfiniteLives(JNIEnv* env, jclass clazz, jboolean enable)
{
    g_cheatInfiniteLives.store(enable);
    LOGI("Native cheat Infinite Lives set to: %d", (int)enable);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetMaxWhip(JNIEnv* env, jclass clazz, jboolean enable)
{
    g_cheatMaxWhip.store(enable);
    LOGI("Native cheat Max Whip set to: %d", (int)enable);
}

JNIEXPORT void JNICALL
Java_com_castlevania_nes_NativeBridge_nativeSetTripleShot(JNIEnv* env, jclass clazz, jboolean enable)
{
    g_cheatTripleShot.store(enable);
    LOGI("Native cheat Triple Shot set to: %d", (int)enable);
}

}



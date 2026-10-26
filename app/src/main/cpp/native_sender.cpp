// JNI bridge: AudioPlaybackCapture PCM -> patched airplay2-sender-cpp -> AirPlay receiver.
// Mirrors example/airplay_stream.cpp from the sender repo: the same ring,
// the same latency management, but fed by nativeWrite() instead of stdin.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <span>
#include <string>
#include <thread>
#include <vector>

#include "raop_loop.h"
#include "raop_sender.h"
#include "ring_buffer.h"

using namespace fxchain;

namespace {

JavaVM* gVm = nullptr;
jclass gCastLogClass = nullptr;
jmethodID gCastLogAppend = nullptr;

RaopLoop* gLoop = nullptr;
RaopSender* gSender = nullptr;
RingBuffer<int16_t>* gRing = nullptr;
std::thread* gThread = nullptr;
std::atomic<bool> gLive{false};
std::atomic<bool> gDone{false};
std::atomic<bool> gStopped{false};
std::atomic<int> gWriters{0};
std::atomic<int> gPendingVolume{-1};
uint32_t gRate = 44100;
double gLatencySec = 2.0;   // mirrored from the app setting, frames = gRate * gLatencySec

// Push a raop log line into the Java CastLog ring buffer from any thread.
// The pump/network threads are not attached to the JVM, so attach briefly.
void postToJava(RaopLogLevel level, const std::string& line) {
    if (gVm == nullptr || gCastLogClass == nullptr || gCastLogAppend == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint rs = gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rs == JNI_EDETACHED) {
        if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (rs != JNI_OK) {
        return;
    }
    jstring jline = env->NewStringUTF(line.c_str());
    env->CallStaticVoidMethod(gCastLogClass, gCastLogAppend,
                              static_cast<jint>(level), jline);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jline);
    if (attached) gVm->DetachCurrentThread();
}

void pumpThread() {
    std::vector<int16_t> scratch(1 << 17);
    while (!gDone.load()) {
        gLoop->pump(*gSender);
        // remote (receiver) volume changes arrive from the UI thread
        const int pv = gPendingVolume.exchange(-1);
        if (pv >= 0 && gSender) gSender->setVolume(double(pv));
        // Latency management, scaled to the configured end-to-end latency
        // (see docs/DEBUGGING.md for the fixed-constant ancestor).
        //
        // UNITS: the ring holds int16 samples (stereo, so 2 samples/frame),
        // while gRate is frames/sec. Everything below is converted to
        // samples so the comparisons are apples-to-apples:
        //  - before the stream is live: drain to at most a quarter of the
        //    latency (and never more than 0.25 s) so the connect phase
        //    never hands streaming a backlog;
        //  - when live: shave 10 ms splices only above 1.6x the latency
        //    (drift absorber). The ring is 1<<19 samples (~5.9 s stereo
        //    @44.1k), so the 5 s ceiling's 8 s threshold still fits.
        const size_t avail = gRing->availableRead();                       // samples
        const size_t latFrames = size_t(double(gRate) * gLatencySec + 0.5);
        const size_t steadyHi = (latFrames * 8 / 5) * 2;                   // samples
        const size_t slice = size_t(gRate) * 2 * 10 / 1000;                // 10 ms, samples
        if (!gLive.load()) {
            const size_t target = std::min(size_t(gRate) / 2, latFrames / 2) * 2;  // samples
            if (avail > target) {
                size_t drop = avail - target;
                while (drop > 0) {
                    const size_t n = std::min(drop, scratch.size());
                    if (!gRing->tryPop(std::span<int16_t>(scratch.data(), n))) break;
                    drop -= n;
                }
            }
        } else if (avail > steadyHi) {
            const size_t drop = std::min(avail - steadyHi, slice);
            gRing->tryPop(std::span<int16_t>(scratch.data(), drop));
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_aircast_app_CastService_nativeStart(JNIEnv* env, jobject, jstring jhost,
                                            jint rate, jint latencyMs) {
    if (gSender) return -1;  // also the second-session guard: no re-init while live
    // Reset session state from a previous stop so stop->start works in the
    // same process (the service is stopped and restarted, not killed).
    gStopped = false;
    gDone = false;
    gLive = false;
    gPendingVolume = -1;
    const char* h = env->GetStringUTFChars(jhost, nullptr);
    std::string host = h ? h : "";
    env->ReleaseStringUTFChars(jhost, h);
    if (host.empty()) return -2;
    // host may be "IP" or "IP:port" — the default AirPlay port is 7000, which
    // matches Apple's receivers (HomePod, Apple TV, Airport Express).
    uint16_t port = 7000;
    size_t colon = host.find(':');
    if (colon != std::string::npos && colon + 1 < host.size()) {
        try {
            port = uint16_t(std::stoi(host.substr(colon + 1)));
            host = host.substr(0, colon);
        } catch (...) {}   // bogus port → fall back to 7000
    }
    if (host.empty() || port == 0) return -2;
    gRate = uint32_t(rate);
    gLatencySec = latencyMs > 0 ? double(latencyMs) / 1000.0 : 2.0;

    RaopEvents events;
    events.launched = [](bool ok, const std::string& err) {
        if (ok) {
            gLive = true;
            __android_log_print(ANDROID_LOG_INFO, "raop", "streaming");
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "raop", "launch failed: %s", err.c_str());
            gDone = true;
        }
    };
    events.closed = [] {
        __android_log_print(ANDROID_LOG_WARN, "raop", "session closed by receiver");
        gDone = true;
    };
    RaopLogSink log = [](RaopLogLevel level, const std::string& line) {
        int prio = level == RaopLogLevel::Warn ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
        __android_log_print(prio, "raop", "%s", line.c_str());
        postToJava(level, line);   // mirror into the app's exportable log buffer
    };

    gLoop = new RaopLoop();
    gRing = new RingBuffer<int16_t>(1u << 19);
    gSender = new RaopSender(*gLoop, std::move(events), std::move(log));
    gSender->setInputFormat(gRate);
    gSender->setLatency(gLatencySec);
    gSender->attachRing(gRing);
    // The initial volume arrives from Java as nativeSetVolume() right after
    // this returns; the pump thread applies it on its first pass (and again
    // on RECORD, as a pending dB value), so there is nothing to seed here.
    RaopIdentity identity;
    identity.name = "Android cast";
    gSender->setIdentity(identity);
    // The app name shown on the receiver matches App.APP_NAME on the Java side;
    // there is no cheap way to pull a constant across JNI from here.
    gSender->setNowPlaying("Android", "AirCast", "system audio");
    // Always AirPlay 2 (HAP transient pairing). The vendored sender only
    // implements the AP2 path and it works for both HomePod v14.4 and v27,
    // so there is no AP1 fallback to expose.
    gSender->setAuth(RaopDeviceInfo::Auth::HapTransient, true, host,
                     std::string(), std::string());
    gSender->start(host, port, "Android cast");
    gThread = new std::thread(pumpThread);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aircast_app_CastService_nativeWrite(JNIEnv* env, jobject, jshortArray jpcm, jint samples) {
    if (samples <= 0) return;
    // Rank against nativeStop(): a writer that arrived first finishes its
    // copy; one that arrived after stop bails instead of touching a ring
    // stop() is about to delete. The count also bounds nativeStop's wait.
    if (gStopped.load() || !gRing) return;
    gWriters.fetch_add(1);
    if (gStopped.load() || !gRing) {           // re-check now that we count
        gWriters.fetch_sub(1);
        return;
    }
    jshort* p = env->GetShortArrayElements(jpcm, nullptr);
    if (!p) {
        gWriters.fetch_sub(1);
        return;
    }
    size_t off = 0;
    const size_t cnt = size_t(samples);
    while (off < cnt && !gDone.load() && !gStopped.load()) {
        const size_t a = gRing->availableWrite();
        if (a == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }
        const size_t t = std::min(a, cnt - off);
        gRing->tryPush(std::span<const int16_t>(p + off, t));
        off += t;
    }
    env->ReleaseShortArrayElements(jpcm, p, JNI_ABORT);
    gWriters.fetch_sub(1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aircast_app_CastService_nativeSetVolume(JNIEnv*, jobject, jint pct) {
    gPendingVolume.store(int(pct));
}

extern "C" JNIEXPORT void JNICALL
Java_com_aircast_app_CastService_nativeStop(JNIEnv*, jobject) {
    gStopped = true;                       // refuse new writers first
    gDone = true;
    if (gThread && gThread->joinable()) gThread->join();  // pump stops first
    // Let a write already in flight finish before the ring disappears.
    while (gWriters.load() > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    if (gSender) gSender->stop();
    delete gThread; gThread = nullptr;
    delete gSender; gSender = nullptr;
    delete gRing; gRing = nullptr;
    delete gLoop; gLoop = nullptr;
    gLive = false;
    gStopped = false;
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("com/aircast/app/CastLog");
    if (cls != nullptr) {
        gCastLogClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
        gCastLogAppend = env->GetStaticMethodID(cls, "nativeAppend",
                                                "(ILjava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }
    return JNI_VERSION_1_6;
}

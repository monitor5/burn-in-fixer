// 측정 앱의 무거운 픽셀 처리(프레임 디코드·평균, 화면 격자 샘플링)를 네이티브에서 한다.
//
// 목적: Java 힙(S25 Edge 512MB 한도) 밖의 네이티브 메모리를 쓰고, 행 단위로 코어에 나눠 빠르게 돌린다.
// 계산은 Kotlin 구현(RawDecode, ImageOps.decodeYuvLinearGray, Analyzer.lumaGrid)과 같은 식을 따른다.
// 프레임 파일 형식은 CaptureFrameCodec(빅엔디언 DataOutputStream)과 같다.

#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr int32_t kMagic = 0x42494631;
constexpr int kFormatYuv420 = 0x23;     // ImageFormat.YUV_420_888
constexpr int kFormatRawSensor = 0x20;  // ImageFormat.RAW_SENSOR

struct Plane {
    std::vector<uint8_t> bytes;
    int rowStride = 0;
    int pixelStride = 0;
};

struct RawInfo {
    bool present = false;
    int cfa = 0;
    int black[4] = {0, 0, 0, 0};
    int white = 0;
    float gain[3] = {1, 1, 1};
    bool hasCcm = false;
    float ccm[9] = {};
    // 렌즈 셰이딩 맵: rows×cols 칸마다 [R, G_even, G_odd, B]
    int shCols = 0, shRows = 0;
    std::vector<float> shading;
};

struct Frame {
    int format = 0;
    int width = 0;
    int height = 0;
    std::vector<Plane> planes;
    RawInfo raw;
};

class Reader {
public:
    explicit Reader(FILE* f) : f_(f) {}
    bool ok() const { return ok_; }
    int32_t i32() {
        uint8_t b[4];
        read(b, 4);
        return (int32_t) ((uint32_t) b[0] << 24 | (uint32_t) b[1] << 16 | (uint32_t) b[2] << 8 | b[3]);
    }
    int64_t i64() {
        int64_t hi = (uint32_t) i32();
        int64_t lo = (uint32_t) i32();
        return (hi << 32) | lo;
    }
    bool boolean() {
        uint8_t b = 0;
        read(&b, 1);
        return b != 0;
    }
    float f32() {
        int32_t v = i32();
        float out;
        std::memcpy(&out, &v, 4);
        return out;
    }
    void read(void* dst, size_t n) {
        if (!ok_) return;
        if (fread(dst, 1, n, f_) != n) ok_ = false;
    }

private:
    FILE* f_;
    bool ok_ = true;
};

bool readFrame(const char* path, Frame& out) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    Reader r(f);
    bool good = r.i32() == kMagic;
    if (good) {
        out.format = r.i32();
        out.width = r.i32();
        out.height = r.i32();
        r.i64();                       // timestamp
        if (r.boolean()) r.i32();      // iso
        if (r.boolean()) r.i64();      // exposure
        int planes = r.i32();
        good = r.ok() && out.width > 0 && out.height > 0 && planes >= 1 && planes <= 3;
        for (int p = 0; good && p < planes; p++) {
            Plane pl;
            pl.rowStride = r.i32();
            pl.pixelStride = r.i32();
            int size = r.i32();
            good = r.ok() && size >= 0 && size <= (1 << 28);
            if (!good) break;
            pl.bytes.resize((size_t) size);
            r.read(pl.bytes.data(), (size_t) size);
            good = r.ok();
            out.planes.push_back(std::move(pl));
        }
        if (good && r.boolean()) {
            out.raw.present = true;
            out.raw.cfa = r.i32();
            for (int& b : out.raw.black) b = r.i32();
            out.raw.white = r.i32();
            for (float& g : out.raw.gain) g = r.f32();
            out.raw.hasCcm = r.boolean();
            if (out.raw.hasCcm) for (float& c : out.raw.ccm) c = r.f32();
            if (r.ok() && r.boolean()) {
                out.raw.shCols = r.i32();
                out.raw.shRows = r.i32();
                good = r.ok() && out.raw.shCols >= 2 && out.raw.shCols <= 256 && out.raw.shRows >= 2 && out.raw.shRows <= 256;
                if (good) {
                    out.raw.shading.resize((size_t) out.raw.shCols * out.raw.shRows * 4);
                    for (float& g : out.raw.shading) g = r.f32();
                }
            }
            good = good && r.ok();
        }
    }
    fclose(f);
    return good;
}

template <typename F>
void parallelRows(int n, F body) {
    int threads = (int) std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    if (n < threads * 4) threads = 1;
    std::vector<std::thread> pool;
    for (int t = 0; t < threads; t++) {
        pool.emplace_back([=, &body] {
            for (int row = t; row < n; row += threads) body(row);
        });
    }
    for (auto& th : pool) th.join();
}

// 2×2 위치 → 셰이딩 맵 채널(0 R, 1 G_even, 2 G_odd, 3 B). RawDecode.shadingChannelsFor와 같다.
void shadingChannels(int cfa, int ch[4]) {
    static const int table[4][4] = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 3, 0, 1}, {3, 2, 1, 0}};
    std::memcpy(ch, table[cfa & 3], sizeof(int) * 4);
}

void cfaChannels(int cfa, int ch[4]) {
    static const int table[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    std::memcpy(ch, table[cfa & 3], sizeof(int) * 4);
}

// mode: -1 = 회색(사이트 WB 정규화 평균), 0/1/2 = 해당 채널(쿼드 평균 후 CCM)
bool decodeRaw(const Frame& fr, int sample, int mode, int outW, int outH, float* out) {
    if (!fr.raw.present || fr.planes.empty() || fr.raw.cfa < 0 || fr.raw.cfa > 3) return false;
    const Plane& pl = fr.planes[0];
    const int w = fr.width, h = fr.height;
    if (pl.pixelStride != 2 || pl.rowStride < w * 2) return false;
    if ((int64_t) (h - 1) * pl.rowStride + (int64_t) w * 2 > (int64_t) pl.bytes.size()) return false;
    int ch[4];
    cfaChannels(fr.raw.cfa, ch);
    float gains[3];
    float maxGain = 0;
    for (int c = 0; c < 3; c++) {
        gains[c] = (std::isfinite(fr.raw.gain[c]) && fr.raw.gain[c] > 0) ? fr.raw.gain[c] : 1.f;
        maxGain = std::max(maxGain, gains[c]);
    }
    float scale[4], black[4];
    for (int p = 0; p < 4; p++) {
        black[p] = (float) fr.raw.black[p];
        int range = std::max(1, fr.raw.white - fr.raw.black[p]);
        scale[p] = gains[ch[p]] / maxGain / (float) range;
    }
    const uint8_t* base = pl.bytes.data();
    const int rs = pl.rowStride;
    int shCh[4];
    shadingChannels(fr.raw.cfa, shCh);
    const bool hasShading = !fr.raw.shading.empty();
    const int sc = fr.raw.shCols, sr = fr.raw.shRows;
    const float* sh = fr.raw.shading.data();
    auto shadingGain = [&](int ch, int x, int y) -> float {
        float fx = w > 1 ? (float) x * (sc - 1) / (w - 1) : 0.f;
        float fy = h > 1 ? (float) y * (sr - 1) / (h - 1) : 0.f;
        int x0 = std::min(std::max((int) fx, 0), sc - 2);
        int y0 = std::min(std::max((int) fy, 0), sr - 2);
        float dx = fx - x0, dy = fy - y0;
        auto g = [&](int c, int r) { return sh[((size_t) r * sc + c) * 4 + ch]; };
        return (g(x0, y0) * (1 - dx) + g(x0 + 1, y0) * dx) * (1 - dy) +
               (g(x0, y0 + 1) * (1 - dx) + g(x0 + 1, y0 + 1) * dx) * dy;
    };
    auto value = [&](int x, int y) -> float {
        const uint8_t* q = base + (size_t) y * rs + (size_t) x * 2;
        int raw = q[0] | (q[1] << 8);
        int p = ((y & 1) << 1) | (x & 1);
        float v = ((float) raw - black[p]) * scale[p];
        if (v < 0) v = 0;
        return hasShading ? v * shadingGain(shCh[p], x, y) : v;
    };
    const bool useCcm = fr.raw.hasCcm;
    parallelRows(outH, [&](int oy) {
        float* row = out + (size_t) oy * outW;
        if (mode < 0) {
            int y0 = oy * sample, y1 = std::min(y0 + sample, h);
            for (int ox = 0; ox < outW; ox++) {
                int x0 = ox * sample, x1 = std::min(x0 + sample, w);
                float acc = 0;
                for (int y = y0; y < y1; y++)
                    for (int x = x0; x < x1; x++) acc += value(x, y);
                row[ox] = acc / (float) ((y1 - y0) * (x1 - x0));
            }
        } else {
            int y0 = (oy * sample) & ~1;
            int y1 = std::min(std::max(oy * sample + sample, y0 + 2), h);
            for (int ox = 0; ox < outW; ox++) {
                int x0 = (ox * sample) & ~1;
                int x1 = std::min(std::max(ox * sample + sample, x0 + 2), w);
                float sum[3] = {0, 0, 0};
                int cnt[3] = {0, 0, 0};
                for (int y = y0; y < y1; y++)
                    for (int x = x0; x < x1; x++) {
                        int c = ch[((y & 1) << 1) | (x & 1)];
                        sum[c] += value(x, y);
                        cnt[c]++;
                    }
                float cr = cnt[0] ? sum[0] / cnt[0] : 0, cg = cnt[1] ? sum[1] / cnt[1] : 0, cb = cnt[2] ? sum[2] / cnt[2] : 0;
                float v;
                if (useCcm) {
                    const float* m = fr.raw.ccm + mode * 3;
                    v = m[0] * cr + m[1] * cg + m[2] * cb;
                    if (v < 0) v = 0;
                } else {
                    v = mode == 0 ? cr : (mode == 1 ? cg : cb);
                }
                row[ox] = v;
            }
        }
    });
    return true;
}

bool decodeYuvGray(const Frame& fr, int sample, const float* yLut, int outW, int outH, float* out) {
    if (fr.planes.empty()) return false;
    const Plane& pl = fr.planes[0];
    const int w = fr.width, h = fr.height;
    if (pl.rowStride <= 0 || pl.pixelStride <= 0) return false;
    if ((int64_t) (h - 1) * pl.rowStride + (int64_t) (w - 1) * pl.pixelStride + 1 > (int64_t) pl.bytes.size()) return false;
    const uint8_t* base = pl.bytes.data();
    parallelRows(outH, [&](int oy) {
        float* row = out + (size_t) oy * outW;
        int y0 = oy * sample, y1 = std::min(y0 + sample, h);
        for (int ox = 0; ox < outW; ox++) {
            int x0 = ox * sample, x1 = std::min(x0 + sample, w);
            float acc = 0;
            for (int y = y0; y < y1; y++) {
                const uint8_t* r = base + (size_t) y * pl.rowStride;
                for (int x = x0; x < x1; x++) acc += yLut[r[(size_t) x * pl.pixelStride]];
            }
            row[ox] = acc / (float) ((y1 - y0) * (x1 - x0));
        }
    });
    return true;
}

int sampleFor(int w, int h, int maxLongEdge) {
    int s = 1;
    while (std::max(w, h) / (s * 2) >= maxLongEdge) s *= 2;
    return s;
}

// 한 프레임을 mode로 디코드. 지원 밖 형식이면 false.
bool decodeFrame(const Frame& fr, int mode, int maxLongEdge, const float* yLut, int& w, int& h, std::vector<float>& out) {
    int s = sampleFor(fr.width, fr.height, maxLongEdge);
    w = std::max(1, (fr.width + s - 1) / s);
    h = std::max(1, (fr.height + s - 1) / s);
    out.resize((size_t) w * h);
    if (fr.format == kFormatRawSensor) return decodeRaw(fr, s, mode, w, h, out.data());
    if (fr.format == kFormatYuv420 && mode < 0) return decodeYuvGray(fr, s, yLut, w, h, out.data());
    return false;
}

// Analyzer.lumaGrid와 같은 9탭 샘플링.
void sampleGrid(const float* gray, const float* black, int iw, int ih, const double* m,
                int screenW, int screenH, int gw, int gh, float margin, float* grid) {
    auto bilinear = [&](const float* img, float x, float y) -> float {
        x = std::min(std::max(x, 0.f), (float) (iw - 1));
        y = std::min(std::max(y, 0.f), (float) (ih - 1));
        int x0 = (int) x, y0 = (int) y;
        float dx = x - x0, dy = y - y0;
        int x1 = std::min(x0 + 1, iw - 1), y1 = std::min(y0 + 1, ih - 1);
        float a = img[y0 * iw + x0], b = img[y0 * iw + x1], c = img[y1 * iw + x0], d = img[y1 * iw + x1];
        return (a * (1 - dx) + b * dx) * (1 - dy) + (c * (1 - dx) + d * dx) * dy;
    };
    parallelRows(gh, [&](int gy) {
        for (int gx = 0; gx < gw; gx++) {
            float u = (margin + (1 - 2 * margin) * (gx + 0.5f) / gw) * screenW;
            float v = (margin + (1 - 2 * margin) * (gy + 0.5f) / gh) * screenH;
            float acc = 0, accB = 0;
            for (int sy = -1; sy <= 1; sy++)
                for (int sx = -1; sx <= 1; sx++) {
                    double uu = u + sx * screenW * 0.15 / gw;
                    double vv = v + sy * screenH * 0.15 / gh;
                    double ww = m[6] * uu + m[7] * vv + 1.0;
                    float px = (float) ((m[0] * uu + m[1] * vv + m[2]) / ww);
                    float py = (float) ((m[3] * uu + m[4] * vv + m[5]) / ww);
                    acc += bilinear(gray, px, py);
                    if (black) accB += bilinear(black, px, py);
                }
            float value = (acc - accB) / 9.f;
            grid[(size_t) gy * gw + gx] = value > 1e-5f ? value : 1e-5f;
        }
    });
}

jobject makeResult(JNIEnv* env, int w, int h, const std::vector<float>& data) {
    jfloatArray arr = env->NewFloatArray((jsize) data.size());
    if (!arr) return nullptr;  // Java OOM 예외가 대기 중
    env->SetFloatArrayRegion(arr, 0, (jsize) data.size(), data.data());
    jclass cls = env->FindClass("com/burnin/scanner/analysis/GrayImage");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(II[F)V");
    return env->NewObject(cls, ctor, w, h, arr);
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_burnin_scanner_analysis_NativePixel_averageNative(
        JNIEnv* env, jobject, jobjectArray paths, jint mode, jint maxLongEdge, jfloatArray yLutArr) {
    const jsize n = env->GetArrayLength(paths);
    if (n <= 0) return nullptr;
    float yLut[256];
    env->GetFloatArrayRegion(yLutArr, 0, 256, yLut);

    int outW = 0, outH = 0;
    std::vector<float> sum, mn, mx, cur;
    for (jsize i = 0; i < n; i++) {
        auto jpath = (jstring) env->GetObjectArrayElement(paths, i);
        const char* cpath = env->GetStringUTFChars(jpath, nullptr);
        Frame fr;
        bool ok = readFrame(cpath, fr);
        env->ReleaseStringUTFChars(jpath, cpath);
        env->DeleteLocalRef(jpath);
        if (!ok) return nullptr;
        int w = 0, h = 0;
        if (!decodeFrame(fr, mode, maxLongEdge, yLut, w, h, cur)) return nullptr;  // 지원 밖 형식: Kotlin 폴백
        if (i == 0) {
            outW = w; outH = h;
            size_t total = (size_t) w * h;
            sum.assign(total, 0.f);
            mn.assign(total, INFINITY);
            mx.assign(total, -INFINITY);
        } else if (w != outW || h != outH) {
            return nullptr;
        }
        fr.planes.clear();
        parallelRows(outH, [&](int y) {
            size_t o = (size_t) y * outW;
            for (int x = 0; x < outW; x++) {
                float v = cur[o + x];
                sum[o + x] += v;
                if (v < mn[o + x]) mn[o + x] = v;
                if (v > mx[o + x]) mx[o + x] = v;
            }
        });
    }
    // Kotlin averageGrayFromStore와 같은 규칙: 4장 이상이면 최솟값·최댓값을 빼고 평균
    if (n >= 4) {
        float div = (float) (n - 2);
        for (size_t i = 0; i < sum.size(); i++) sum[i] = (sum[i] - mn[i] - mx[i]) / div;
    } else {
        for (float& v : sum) v /= (float) n;
    }
    return makeResult(env, outW, outH, sum);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_burnin_scanner_analysis_NativePixel_lumaGridNative(
        JNIEnv* env, jobject, jfloatArray grayArr, jint iw, jint ih, jfloatArray blackArr,
        jdoubleArray hArr, jint screenW, jint screenH, jint gw, jint gh, jfloat margin) {
    double m[9];
    env->GetDoubleArrayRegion(hArr, 0, 9, m);
    const size_t total = (size_t) gw * gh;
    std::vector<float> grid(total);
    // 이미지 두 장을 네이티브로 복사하지 않고 고정해서 읽는다(GC는 잠시 멈춘다).
    auto* gray = (float*) env->GetPrimitiveArrayCritical(grayArr, nullptr);
    float* black = blackArr ? (float*) env->GetPrimitiveArrayCritical(blackArr, nullptr) : nullptr;
    sampleGrid(gray, black, iw, ih, m, screenW, screenH, gw, gh, margin, grid.data());
    if (black) env->ReleasePrimitiveArrayCritical(blackArr, black, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(grayArr, gray, JNI_ABORT);
    jfloatArray out = env->NewFloatArray((jsize) total);
    if (out) env->SetFloatArrayRegion(out, 0, (jsize) total, grid.data());
    return out;
}

// 프레임마다 디코드한 뒤 바로 작은 격자로 샘플링해 [n × gw × gh]를 돌려준다(프레임 안정도용).
// 원본 크기 디코드 버퍼는 네이티브에서만 쓰고, Java에는 작은 격자만 올라간다.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_burnin_scanner_analysis_NativePixel_frameGridsNative(
        JNIEnv* env, jobject, jobjectArray paths, jint mode, jint maxLongEdge, jfloatArray yLutArr,
        jfloatArray blackArr, jint bw, jint bh, jdoubleArray hArr, jint screenW, jint screenH,
        jint gw, jint gh, jfloat margin) {
    const jsize n = env->GetArrayLength(paths);
    if (n <= 0) return nullptr;
    float yLut[256];
    env->GetFloatArrayRegion(yLutArr, 0, 256, yLut);
    double m[9];
    env->GetDoubleArrayRegion(hArr, 0, 9, m);
    std::vector<float> black;
    if (blackArr) {
        black.resize((size_t) env->GetArrayLength(blackArr));
        env->GetFloatArrayRegion(blackArr, 0, (jsize) black.size(), black.data());
    }
    const size_t cell = (size_t) gw * gh;
    std::vector<float> out((size_t) n * cell);
    std::vector<float> cur;
    for (jsize i = 0; i < n; i++) {
        auto jpath = (jstring) env->GetObjectArrayElement(paths, i);
        const char* cpath = env->GetStringUTFChars(jpath, nullptr);
        Frame fr;
        bool ok = readFrame(cpath, fr);
        env->ReleaseStringUTFChars(jpath, cpath);
        env->DeleteLocalRef(jpath);
        int w = 0, h = 0;
        if (!ok || !decodeFrame(fr, mode, maxLongEdge, yLut, w, h, cur)) return nullptr;
        if (!black.empty() && (w != bw || h != bh)) return nullptr;
        fr.planes.clear();
        sampleGrid(cur.data(), black.empty() ? nullptr : black.data(), w, h, m, screenW, screenH, gw, gh, margin,
                   out.data() + (size_t) i * cell);
    }
    jfloatArray arr = env->NewFloatArray((jsize) out.size());
    if (arr) env->SetFloatArrayRegion(arr, 0, (jsize) out.size(), out.data());
    return arr;
}

// 희소 측정용 풀해상도 black(약 50MB)을 Java 힙 대신 네이티브에 둔다(측정 앱 힙 512MB 한도).
static std::vector<float> gStoredBlack;

extern "C" JNIEXPORT void JNICALL
Java_com_burnin_scanner_analysis_NativePixel_storeBlackNative(JNIEnv* env, jobject, jfloatArray blackArr) {
    if (!blackArr) {
        std::vector<float>().swap(gStoredBlack);
        return;
    }
    const jsize n = env->GetArrayLength(blackArr);
    gStoredBlack.resize((size_t) n);
    env->GetFloatArrayRegion(blackArr, 0, n, gStoredBlack.data());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_burnin_scanner_analysis_NativePixel_decodeIntoStoredNative(
        JNIEnv* env, jobject, jstring jpath, jint mode, jint maxLongEdge, jfloatArray yLutArr, jfloatArray outArr) {
    float yLut[256];
    env->GetFloatArrayRegion(yLutArr, 0, 256, yLut);
    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    Frame fr;
    bool ok = readFrame(cpath, fr);
    env->ReleaseStringUTFChars(jpath, cpath);
    if (!ok) return JNI_FALSE;
    int w = 0, h = 0;
    std::vector<float> cur;
    if (!decodeFrame(fr, mode, maxLongEdge, yLut, w, h, cur)) return JNI_FALSE;
    fr.planes.clear();
    const jsize total = (jsize) cur.size();
    if (env->GetArrayLength(outArr) != total || gStoredBlack.size() != (size_t) total) return JNI_FALSE;
    auto* out = (float*) env->GetPrimitiveArrayCritical(outArr, nullptr);
    const float* black = gStoredBlack.data();
    parallelRows(h, [&](int y) {
        size_t o = (size_t) y * w;
        for (int x = 0; x < w; x++) out[o + x] = cur[o + x] - black[o + x];
    });
    env->ReleasePrimitiveArrayCritical(outArr, out, 0);
    return JNI_TRUE;
}

// 프레임 하나를 디코드해 Java 배열 [out]에 쓴다(재사용 버퍼). [black]이 있으면 뺀다.
// 희소 점등 측광처럼 원본 해상도 프레임을 여러 장 차례로 처리할 때 Java 힙 할당 없이 쓴다.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_burnin_scanner_analysis_NativePixel_decodeIntoNative(
        JNIEnv* env, jobject, jstring jpath, jint mode, jint maxLongEdge, jfloatArray yLutArr,
        jfloatArray blackArr, jfloatArray outArr) {
    float yLut[256];
    env->GetFloatArrayRegion(yLutArr, 0, 256, yLut);
    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    Frame fr;
    bool ok = readFrame(cpath, fr);
    env->ReleaseStringUTFChars(jpath, cpath);
    if (!ok) return JNI_FALSE;
    int w = 0, h = 0;
    std::vector<float> cur;
    if (!decodeFrame(fr, mode, maxLongEdge, yLut, w, h, cur)) return JNI_FALSE;
    fr.planes.clear();
    const jsize total = (jsize) cur.size();
    if (env->GetArrayLength(outArr) != total) return JNI_FALSE;
    if (blackArr && env->GetArrayLength(blackArr) != total) return JNI_FALSE;
    auto* out = (float*) env->GetPrimitiveArrayCritical(outArr, nullptr);
    float* black = blackArr ? (float*) env->GetPrimitiveArrayCritical(blackArr, nullptr) : nullptr;
    parallelRows(h, [&](int y) {
        size_t o = (size_t) y * w;
        if (black) for (int x = 0; x < w; x++) out[o + x] = cur[o + x] - black[o + x];
        else std::memcpy(out + o, cur.data() + o, sizeof(float) * w);
    });
    if (black) env->ReleasePrimitiveArrayCritical(blackArr, black, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outArr, out, 0);
    return JNI_TRUE;
}

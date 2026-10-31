"""
문헌 조건으로 현재 main(274edab) 보정 파이프라인을 다시 돌려 보는 synthetic 평가.

기존 research/ 시뮬레이션(shape-burn-in, stroke-level, dual-view)은 모두
  (1) gain을 '휘도'에 곱한다고 가정했고  (2) 카메라 영상을 정확히 선형으로 복원한다고 가정했다.
실제 앱은
  - Analyzer.gainGrid/refineGain, SparseSpotPhotometry.detailGain이 선형 휘도 비율로 gain을 구하고
  - Target PatternView(solid)가 그 gain을 8비트 sRGB 코드값에 곱한 뒤 반올림하며(dither 없음)
  - Scanner는 Camera2 기본 톤매핑 YUV를 x^2.2로 역변환한다(TONEMAP_MODE 미설정).
이 평가는 그 세 가지를 넣었을 때 수치가 어떻게 바뀌는지, 그리고 문헌의 처방(선형 도메인 적용,
dither/고비트 출력, 선형 톤커브·RAW, 계조별 맵, 채널별 고주파 층)이 얼마나 되돌리는지 본다.

실험
  A  출력 전달함수·양자화 (카메라는 이상적 선형)
  B  카메라 톤커브 (출력은 A의 수정안)
  C  계조 의존 번인 (TFT Vth 이동형) : gray70 단일 맵 vs 2계조 보간 맵
  D  R/G/B 차등 열화 획 : 휘도 단일 고주파 층 vs 채널별 층 (ΔE00)
  E  PenTile(RGBG) 서브픽셀 렌더링에서 논리 픽셀 단위 고주파 층의 한계 (1D)

수치는 모델 가정 안의 경향이며 실기기(S25 Edge → Tab S2) 검증값이 아니다.
"""
import numpy as np

rng = np.random.default_rng(7)
H, W = 240, 160
MAX_ATT = 0.10            # MeasurementActivity.MAX_ATTENUATION
MAX_DETAIL_ATT = 0.10
ALPHA = 0.3               # DAMPING_ALPHA
MAX_ITER = 24
PASS_RMS, PASS_P95, PASS_BLOCK = 0.015, 0.035, 0.02
SMOOTH_R = 2              # mapSmoothRadius (네이티브 맵, 카메라 px당 셀 ~2)
GAMMA_D = 2.2             # 패널 코드→휘도 지수 (Tab S2 기본 모드 가정)
BAND_JND = 0.0093         # SEMU: Tab S2 상태바(약 196×4.8mm=943mm²) → Cjnd = 1.97/S^0.33 + 0.72 [%]


# ---------------------------------------------------------------- 장면
def scene():
    yy, xx = np.mgrid[0:H, 0:W]
    band = yy < 12                                     # 상태바 띠
    text = np.zeros((H, W), bool)                      # 2px 획 글씨
    for row in range(3):
        y0 = 90 + row * 30
        for ch in range(7):
            x0 = 10 + ch * 20
            text[y0:y0 + 2, x0:x0 + 12] = True
            text[y0:y0 + 18, x0:x0 + 2] = True
            text[y0 + 8:y0 + 10, x0:x0 + 9] = True
    blob = 0.02 * np.exp(-((xx - 110) ** 2 + (yy - 50) ** 2) / (2 * 22 ** 2))
    return band, text, blob


BAND, TEXT, BLOB = scene()
PIX = rng.normal(0, 0.002, (H, W))


def panel_eff(depth_band=0.04, depth_text=0.03):
    e = (1 + PIX) * (1 - BLOB)
    e[BAND] *= 1 - depth_band
    e[TEXT] *= 1 - depth_text
    return e


# ---------------------------------------------------------------- 앱 수식 이식 (Analyzer.kt)
def box(a, r):
    if r < 1:
        return a.copy()
    k = 2 * r + 1
    pad = np.pad(a, r, mode="edge")
    # 앱은 가장자리를 실제 포함 셀 수로 나눈다. edge pad와 차이는 가장자리 몇 셀뿐이라 무시.
    c = np.cumsum(np.cumsum(np.pad(pad, ((1, 0), (1, 0))), 0), 1)
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def blocks(a, bs=4):
    h, w = a.shape[0] // bs * bs, a.shape[1] // bs * bs
    return a[:h, :w].reshape(h // bs, bs, w // bs, bs).mean((1, 3))


def target(l):
    m = np.median(l)
    b = np.sort(blocks(l).ravel())
    return max(b[int(b.size * 0.01)], m * (1 - MAX_ATT))


def gain_grid(l):
    g = np.clip(target(l) / np.maximum(l, 1e-5), 1 - MAX_ATT, 1)
    return np.clip(box(box(g, SMOOTH_R), SMOOTH_R), 1 - MAX_ATT, 1)


def refine(g, l, t):
    out = np.clip(g * (t / np.maximum(l, 1e-5)) ** ALPHA, 1 - MAX_ATT, 1)
    return np.clip(box(out, SMOOTH_R), 1 - MAX_ATT, 1)


def score(l):
    m = np.median(l)
    d = np.abs(l / m - 1)
    rms = np.sqrt(np.mean((l / m - 1) ** 2))
    p95 = np.quantile(d, 0.95)
    bd = np.max(np.abs(blocks(l) / m - 1))
    return max(rms / PASS_RMS, p95 / PASS_P95, bd / PASS_BLOCK)


def detail_layer(eff, noise=0.001):
    """희소 점등 측광 결과를 이상적으로 가정 (stroke-level sim에서 정합·모아레 영향 ~0)."""
    r_lp = SMOOTH_R + 1
    meas = eff * (1 + rng.normal(0, noise, eff.shape))
    low = box(box(meas, r_lp), r_lp)
    d = meas / low - 1
    ratio = 1 + d * (d * d / (d * d + noise ** 2))          # Wiener 수축 (detailRatio)
    raw = np.sort((meas / low).ravel())
    q = np.clip(raw[int(raw.size * 0.005)] + 2.58 * noise, 1 - MAX_DETAIL_ATT, 1)
    L = np.clip(q / np.maximum(ratio, 1e-3), 1 - MAX_DETAIL_ATT, 1)
    comp = box(box(L * ratio, r_lp), r_lp)
    return L, comp


# ---------------------------------------------------------------- 출력(Target) 모델
def fs_dither(x):
    """Floyd–Steinberg 오차 확산 (코드값 float → 정수)."""
    x = x.astype(float).copy()
    out = np.zeros_like(x)
    h, w = x.shape
    for y in range(h):
        for xx in range(w):
            o = np.round(x[y, xx])
            out[y, xx] = o
            e = x[y, xx] - o
            if xx + 1 < w: x[y, xx + 1] += e * 7 / 16
            if y + 1 < h:
                if xx > 0: x[y + 1, xx - 1] += e * 3 / 16
                x[y + 1, xx] += e * 5 / 16
                if xx + 1 < w: x[y + 1, xx + 1] += e * 1 / 16
    return out


def display(eff, gain, base_code, mode):
    """mode: 'code'(현행: gain을 코드에 곱하고 반올림), 'code_dither', 'linear', 'linear_dither', 'ideal'"""
    if mode == "ideal":
        return eff * (base_code / 255) ** GAMMA_D * gain
    g = gain ** (1 / GAMMA_D) if mode.startswith("linear") else gain
    c = base_code * g
    c = fs_dither(c) if mode.endswith("dither") else np.round(c)
    return eff * (np.clip(c, 0, 255) / 255) ** GAMMA_D


# ---------------------------------------------------------------- 카메라 모델
def psf(a, s=1.0):
    p = int(4 * s) + 4                       # 가장자리 복제 패딩 (FFT 순환 방지)
    return _psf(np.pad(a, p, mode="edge"), s)[p:-p, p:-p]


def _psf(a, s):
    f = np.fft.fft2(a)
    fy = np.fft.fftfreq(a.shape[0])[:, None]
    fx = np.fft.fftfreq(a.shape[1])[None, :]
    return np.real(np.fft.ifft2(f * np.exp(-2 * (np.pi * s) ** 2 * (fx ** 2 + fy ** 2))))


def tone(x, kind):
    x = np.clip(x, 0, 1)
    if kind in ("linear", "raw"):
        return x
    if kind == "gamma22":
        return x ** (1 / 2.2)
    if kind == "srgb":
        return np.where(x <= 0.0031308, 12.92 * x, 1.055 * x ** (1 / 2.4) - 0.055)
    if kind == "scurve":                     # 기본 톤매핑 근사: sRGB + 중간톤 대비 강화
        v = tone(x, "srgb")
        c = 2.2
        return 0.5 * (1 + np.tanh(c * (v - 0.5)) / np.tanh(c * 0.5))
    raise ValueError(kind)


def camera(lum, kind, frames=8, expo=0.55):
    """화면 휘도 → (톤커브, 8비트, 프레임 평균) → 앱식 x^2.2 역변환 (linear/raw는 역변환 없음)."""
    x = psf(lum) / lum.mean() * expo
    acc = np.zeros_like(x)
    for _ in range(frames):
        n = x * (1 + rng.normal(0, 0.004, x.shape)) + rng.normal(0, 0.002, x.shape)
        v = tone(n, kind)
        if kind != "raw":
            v = np.round(np.clip(v, 0, 1) * 255) / 255
        else:
            v = np.round(np.clip(v, 0, 1) * 1023) / 1023
        acc += v if kind in ("linear", "raw") else v ** 2.2
    return acc / frames


# ---------------------------------------------------------------- 지표
YY, XX = np.mgrid[0:H, 0:W]
REF_BAND = (YY >= 14) & (YY < 26)
RING = (box(TEXT.astype(float), 2) > 0) & ~TEXT
INTERIOR = (YY >= 30) & (YY < H - 4) & (XX >= 4) & (XX < W - 4)


def metrics(lum):
    band = lum[BAND].mean() / lum[REF_BAND].mean() - 1
    stroke = lum[TEXT].mean() / lum[RING].mean() - 1
    lowp = psf(lum, 4)
    rms4 = np.std(lowp[INTERIOR]) / lowp[INTERIOR].mean()
    return band, stroke, rms4


def run_loop(eff, out_mode, cam_kind, base_code=179, use_detail=True):
    L, comp = detail_layer(eff) if use_detail else (np.ones_like(eff), np.ones_like(eff))
    meas0 = camera(display(eff, np.ones_like(eff), base_code, out_mode), cam_kind) / comp
    t = target(meas0)
    gain = gain_grid(meas0)
    s0 = score(meas0)
    best = (s0, np.ones_like(eff), 0)
    first = None
    stall = 0
    for it in range(1, MAX_ITER + 1):
        lum = display(eff, gain * L, base_code, out_mode)
        if first is None:
            first = lum
        meas = camera(lum, cam_kind) / comp
        sc = score(meas)
        if sc < best[0] - 1e-4:
            best = (sc, gain.copy(), it); stall = 0
        else:
            stall += 1
        if sc <= 1 or stall >= 4:
            break
        gain = refine(gain, meas, t)
    final_gain = best[1] * (L if best[2] > 0 else 1)
    return dict(first=first, final_gain=final_gain, iters=it, best_iter=best[2], score=best[0], s0=s0)


def fmt(v):
    return f"{v * 100:+.2f}"


def row(*c):
    print("| " + " | ".join(str(x) for x in c) + " |")


# ================================================================ A
def exp_a():
    print("## A. 출력 전달함수·양자화 (카메라 이상적 선형, 희소 고주파 층 포함)\n")
    print("보정 전: 띠 −4%% (=%.1f JND), 획 −3%%, 저주파 얼룩 −2%%. 반복 루프는 gray70(코드 179)에서 돌고,"
          " 같은 맵을 gray25(코드 64)에도 적용해 본다. 띠 JND는 SEMU(Tab S2 상태바 면적) 기준.\n" % (0.04 / BAND_JND))
    row("출력 방식", "반복(최선/종료)", "gray70 띠%", "띠 JND", "gray70 획%", "gray70 RMS(σ4)%", "gray25 띠%", "gray25 획%", "gray25 RMS(σ4)%")
    row(*["---"] * 9)
    eff = panel_eff()
    res = {}
    for mode, name in [("code", "현행: gain×코드, 반올림"),
                       ("code_dither", "현행 + FS dither"),
                       ("linear", "gain^(1/γ)×코드, 반올림"),
                       ("linear_dither", "gain^(1/γ)×코드 + FS dither"),
                       ("ideal", "이상적(연속 휘도 곱)")]:
        r = run_loop(eff, mode, "linear")
        g = r["final_gain"]
        b70, s70, rms70 = metrics(display(eff, g, 179, mode))
        b25, s25, rms25 = metrics(display(eff, g, 64, mode))
        res[mode] = r
        row(name, f"{r['best_iter']}/{r['iters']}", fmt(b70), f"{abs(b70) / BAND_JND:.1f}", fmt(s70), f"{rms70 * 100:.2f}",
            fmt(b25), fmt(s25), f"{rms25 * 100:.2f}")
    b, s, _ = metrics(res["code"]["first"])
    bl, sl, _ = metrics(res["linear"]["first"])
    print(f"\n첫 반복(감쇠 전량 적용) 직후 gray70: 현행 띠 {fmt(b)}%, 획 {fmt(s)}% / gain^(1/γ) 띠 {fmt(bl)}%, 획 {fmt(sl)}%")
    # 고주파 층 단독: 루프가 못 보는 overshoot
    L, _ = detail_layer(eff)
    for mode in ["code", "linear_dither"]:
        _, s, _ = metrics(display(eff, L, 179, mode))
        print(f"고주파 층만 적용(저주파 gain=1), {mode}: 획 {fmt(s)}%")
    print()


# ================================================================ B
def exp_b():
    print("## B. 카메라 톤커브 (출력은 gain^(1/γ) + dither)\n")
    print("앱은 YUV를 x^2.2로 되돌린다. Camera2 TONEMAP_MODE를 지정하지 않으면 기기 기본 곡선이 쓰인다.\n")
    row("카메라 처리", "첫 측정 띠 깊이%(참 −4)", "첫 반복 후 띠%", "첫 반복 후 획%", "반복(최선/종료)", "최종 띠%", "최종 획%", "최종 RMS(σ4)%")
    row(*["---"] * 8)
    eff = panel_eff()
    for kind, name in [("raw", "RAW(10bit 선형)"), ("linear", "선형 톤커브(CONTRAST_CURVE)"),
                       ("gamma22", "정확히 γ2.2"), ("srgb", "sRGB 곡선"), ("scurve", "sRGB+중간톤 대비(기본 톤맵 근사)")]:
        meas = camera(display(eff, np.ones_like(eff), 179, "linear_dither"), kind)
        depth = meas[BAND].mean() / meas[REF_BAND].mean() - 1
        r = run_loop(eff, "linear_dither", kind)
        b1, s1, _ = metrics(r["first"])
        b, s, rms = metrics(display(eff, r["final_gain"], 179, "linear_dither"))
        row(name, fmt(depth), fmt(b1), fmt(s1), f"{r['best_iter']}/{r['iters']}", fmt(b), fmt(s), f"{rms * 100:.2f}")
    print()


def exp_b2():
    print("## B2. 희소 점등 점 측광의 톤커브 민감도 (고주파 층은 루프가 다시 보지 못함)\n")
    print("검은 바탕의 점 하나를 카메라 PSF(σ 0.8 카메라px)로 찍고, 창 안 값을 앱처럼 x^2.2로 되돌려 합한다."
          " 참 깊이 −3%인 점과 정상 점의 비율을 잰다. 피크 노출은 점 중심 카메라 픽셀의 선형 신호.\n")
    yy, xx = np.mgrid[-4:5, -4:5]
    spot = np.exp(-(xx ** 2 + yy ** 2) / (2 * 0.8 ** 2))
    spot /= spot.max()
    row("카메라 처리", *[f"피크 {p:.2f}" for p in (0.15, 0.3, 0.6, 0.9)])
    row(*["---"] * 5)
    for kind, name in [("raw", "RAW/선형 톤커브"), ("gamma22", "정확히 γ2.2"), ("srgb", "sRGB 곡선"),
                       ("scurve", "sRGB+중간톤 대비")]:
        cells = []
        for peak in (0.15, 0.3, 0.6, 0.9):
            def meas(scale):
                v = tone(spot * peak * scale, kind)
                return (v if kind == "raw" else v ** 2.2).sum()
            cells.append(fmt(meas(0.97) / meas(1.0) - 1))
        row(name, *cells)
    print("\n(8비트 양자화·노이즈 없이 톤커브만의 계통 오차. 측정 깊이가 −3%에서 벗어난 만큼 고주파 층이 과·소보정된다.)\n")


# ================================================================ C
def exp_c():
    print("## C. 계조 의존 번인 (gray70에서 −4%가 되도록 맞춘 두 열화 모델)\n")
    print("효율 저하형: L = η·(c/255)^γ (모든 계조에서 같은 비율). Vth 이동형: L = ((c−δ)/(255−δ))^γ, "
          "어두운 계조일수록 상대 손실이 커진다. 실제는 혼합. 맵은 이상적 측정·연속 출력으로 만든다.\n")
    levels = [26, 64, 128, 179, 255]
    gam = GAMMA_D

    def lum_vth(c, d):
        return np.clip((c - d) / 255, 0, None) ** gam

    # δ: gray70에서 4%
    d = 0
    for _ in range(60):
        d += (0.04 - (1 - lum_vth(179, d) / lum_vth(179, 0))) * 30
    eff_rel = lambda c, model: (0.96 if model == "eff" else lum_vth(c, d) / lum_vth(c, 0))
    row("열화 모델", "맵", *[f"코드 {c} 잔차%" for c in levels])
    row(*["---"] * (2 + len(levels)))
    for model, mname in [("eff", "효율 저하형"), ("vth", "Vth 이동형")]:
        loss = {c: eff_rel(c, model) for c in levels}
        g70 = loss[179]              # 정상부를 g70만큼 낮춰야 함 (번인부 gain=1)
        g25 = loss[64]
        # 단일 gray70 맵: 정상부 gain = loss70 (선형 휘도)
        r1 = [loss[c] / g70 - 1 for c in levels]
        # 앱 현행: gray70/gray25 감쇠 혼합 (LOW_LIGHT_GAIN_WEIGHT 0.35)
        gmix = 1 - (0.65 * (1 - g70) + 0.35 * (1 - g25))
        r2 = [loss[c] / gmix - 1 for c in levels]
        # 2계조 측정 + 코드 축 선형 보간(LUT): 정상부 코드를 계조별로 낮춤
        def lut_gain(c):
            t = np.clip((c - 64) / (179 - 64), -0.5, 1.6)
            return g25 + (g70 - g25) * t
        r3 = [loss[c] / lut_gain(c) - 1 for c in levels]
        row(mname, "gray70 단일 감쇠", *[fmt(v) for v in r1])
        row(mname, "gray70·25 감쇠 혼합(0.35, 현행)", *[fmt(v) for v in r2])
        row(mname, "gray25·70 계조 LUT 보간", *[fmt(v) for v in r3])
    print(f"\n(Vth 이동형 δ = {d:.1f} 코드. 잔차 = 번인부/정상부 − 1, 음수면 번인이 아직 어두움)\n")


# ================================================================ D
def srgb_lin_to_lab(rgb):
    M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = M @ rgb
    wn = M @ np.ones(3)
    f = lambda t: np.where(t > (6 / 29) ** 3, np.cbrt(t), t / (3 * (6 / 29) ** 2) + 4 / 29)
    fx, fy, fz = f(xyz / wn)
    return np.array([116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)])


def de2000(l1, l2):
    L1, a1, b1 = l1; L2, a2, b2 = l2
    C1, C2 = np.hypot(a1, b1), np.hypot(a2, b2)
    Cb = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cb ** 7 / (Cb ** 7 + 25 ** 7)))
    a1p, a2p = a1 * (1 + G), a2 * (1 + G)
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1 = np.degrees(np.arctan2(b1, a1p)) % 360
    h2 = np.degrees(np.arctan2(b2, a2p)) % 360
    dL, dC = L2 - L1, C2p - C1p
    dh = h2 - h1
    dh = dh - 360 if dh > 180 else dh + 360 if dh < -180 else dh
    dH = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dh / 2))
    Lb, Cbp = (L1 + L2) / 2, (C1p + C2p) / 2
    hb = (h1 + h2) / 2 if abs(h1 - h2) <= 180 else (h1 + h2 + 360) / 2
    T = 1 - 0.17 * np.cos(np.radians(hb - 30)) + 0.24 * np.cos(np.radians(2 * hb)) + 0.32 * np.cos(np.radians(3 * hb + 6)) - 0.20 * np.cos(np.radians(4 * hb - 63))
    SL = 1 + 0.015 * (Lb - 50) ** 2 / np.sqrt(20 + (Lb - 50) ** 2)
    SC, SH = 1 + 0.045 * Cbp, 1 + 0.015 * Cbp * T
    RT = -2 * np.sqrt(Cbp ** 7 / (Cbp ** 7 + 25 ** 7)) * np.sin(np.radians(60 * np.exp(-((hb - 275) / 25) ** 2)))
    return np.sqrt((dL / SL) ** 2 + (dC / SC) ** 2 + (dH / SH) ** 2 + RT * dC / SC * dH / SH)


def exp_d():
    print("## D. R/G/B 차등 열화 획 (gray70, 이상 측정)\n")
    print("청색 수명이 가장 짧다는 일반적 경향을 반영한 예시 깊이. 정상부 대비 획의 ΔE00(정상부를 흰 기준으로).\n")
    row("채널 손실 R/G/B", "보정 방식", "획 휘도 잔차%", "획 ΔE00")
    row(*["---"] * 4)
    base = np.full(3, (179 / 255) ** GAMMA_D)
    for loss in [(0.02, 0.03, 0.06), (0.01, 0.03, 0.10)]:
        burned = base * (1 - np.array(loss))
        Yw = np.array([0.2126, 0.7152, 0.0722])
        for name, gain_normal in [
            ("없음", np.ones(3)),
            ("휘도 단일 층 (현행 detail)", np.full(3, (Yw @ burned) / (Yw @ base))),
            ("채널별 층 (RGB 희소 측광)", 1 - np.array(loss)),
        ]:
            normal = base * gain_normal
            dY = (Yw @ burned) / (Yw @ normal) - 1
            de = de2000(srgb_lin_to_lab(normal / (Yw @ normal) * (Yw @ base)), srgb_lin_to_lab(burned / (Yw @ normal) * (Yw @ base)))
            row("/".join(f"{x * 100:.0f}%" for x in loss), name, fmt(dY), f"{de:.2f}")
    print()


# ================================================================ E
def exp_e():
    print("## E. PenTile RGBG 서브픽셀 렌더링 (1D, 이상 측정)\n")
    print("논리 픽셀마다 G 1개, R/B는 2픽셀에 1개씩 번갈아 있고 SPR이 R/B를 인접 2픽셀 평균으로 구동한다고 단순화."
          " 번인은 물리 서브픽셀에 생긴다(획 2px, −3%). 희소 점등으로 논리 픽셀 하나를 켰을 때의 휘도를 그 픽셀 응답으로 쓴다.\n")
    n = 64
    stroke = np.zeros(n, bool); stroke[30:32] = True
    Yw = np.array([0.2126, 0.7152, 0.0722])
    # 물리 서브픽셀: G[i] (i=0..n-1), R[k] at logical 2k..2k+1, B[k] at 2k+1..2k+2
    def sub_burn(depth):
        G = np.where(stroke, 1 - depth, 1.0)
        R = np.array([1 - depth if stroke[2 * k] or stroke[min(2 * k + 1, n - 1)] else 1.0 for k in range(n // 2)])
        B = np.array([1 - depth if stroke[min(2 * k + 1, n - 1)] or stroke[min(2 * k + 2, n - 1)] else 1.0 for k in range(n // 2)])
        return R, G, B

    def emit(drive, R, G, B):
        """논리 픽셀 구동값(선형) → 논리 픽셀 위치별 R,G,B 발광 (SPR 단순화)."""
        r = np.zeros(n); g = drive * G; b = np.zeros(n)
        for k in range(n // 2):
            i0, i1 = 2 * k, min(2 * k + 1, n - 1)
            v = 0.5 * (drive[i0] + drive[i1]) * R[k]
            r[i0] += v; r[i1] += v
            j0, j1 = min(2 * k + 1, n - 1), min(2 * k + 2, n - 1)
            v = 0.5 * (drive[j0] + drive[j1]) * B[k]
            b[j0] += v; b[j1] += v
        return r, g, b

    R, G, B = sub_burn(0.03)
    # 희소 측광: 논리 픽셀 i 하나만 켰을 때 전체 휘도
    resp = np.zeros(n)
    for i in range(n):
        d = np.zeros(n); d[i] = 1
        r, g, b = emit(d, R, G, B)
        resp[i] = Yw @ np.array([r.sum(), g.sum(), b.sum()])
    gain = resp.min() / resp
    row("보정", "획 Y 잔차%", "획 R 잔차%", "획 G 잔차%", "획 B 잔차%", "주변 R 최대 오차%")
    row(*["---"] * 6)
    for name, drv in [("없음", np.ones(n)), ("논리 픽셀 휘도 gain", gain)]:
        r, g, b = emit(drv, R, G, B)
        y = Yw[0] * r + Yw[1] * g + Yw[2] * b
        ring = np.zeros(n, bool); ring[24:28] = True; ring[34:38] = True
        near = np.zeros(n, bool); near[27:30] = True; near[32:35] = True
        rel = lambda a: a[stroke].mean() / a[ring].mean() - 1
        row(name, fmt(rel(y)), fmt(rel(r)), fmt(rel(g)), fmt(rel(b)), fmt(np.max(np.abs(r[near] / r[ring].mean() - 1))))
    print()


if __name__ == "__main__":
    import sys
    which = sys.argv[1:] or ["a", "b", "b2", "c", "d", "e"]
    for w in which:
        globals()["exp_" + w]()

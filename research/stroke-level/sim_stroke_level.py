"""
글씨 획 단위(1~3px) 번인을 카메라로 측정·보정할 수 있는지 보는 synthetic 시뮬레이션.

비교:
  full-field r=R : 현재 방식. 전면 gray 촬영 → 화면 픽셀마다 호모그래피 샘플 → gain → box blur 반경 R
  sparse b=B p=P : 희소 점등. B×B 픽셀 블록을 P 간격으로만 켜서 (P/B)² 장 촬영.
                   블록마다 예측 위치 근처에서 무게중심으로 국소 정합한 뒤, 창 안 광량을 적분(aperture
                   photometry)한다. 서브픽셀 구조·센서 격자 간섭(모아레)은 적분으로 사라지고,
                   정합 오차는 무게중심 탐색 창 안이면 결과에 영향이 없다.
평가: 보정 후 실제 패널 휘도(진짜 휘도 × gain)의
  stroke 잔차  = 글씨 획 평균 - 주변 평균 (원래 -3%)
  RMS(1px)     = 픽셀 단위 불균일
  RMS(2px)     = 2px 블러 후 불균일 (정상 시청거리에서 눈에 보이는 수준)
수치는 모델 가정 안의 경향이다.
"""
import numpy as np

N = 96          # 화면 영역 (screen px)
F = 8           # screen px당 미세 격자
rng0 = np.random.default_rng(0)


def text_mask():
    m = np.zeros((N, N), bool)
    for row in range(3):                       # 2px 획 글자 3줄
        y0 = 12 + row * 26
        for ch in range(6):
            x0 = 8 + ch * 14
            m[y0:y0 + 2, x0:x0 + 10] = True    # 가로획
            m[y0:y0 + 16, x0:x0 + 2] = True    # 세로획
            m[y0 + 7:y0 + 9, x0:x0 + 8] = True
    m[60:62, 6:90] = False
    yy, xx = np.mgrid[0:N, 0:N]
    d = np.hypot(xx - 70, yy - 75)
    m |= np.abs(d - 12) < 1.5                  # 버튼 링 3px
    return m


def true_panel(depth=0.03, pix_sigma=0.004):
    t = np.ones((N, N))
    t *= 1 + rng0.normal(0, pix_sigma, (N, N))  # 픽셀별 고유 편차
    t[text_mask()] *= 1 - depth
    return t


def subpixel_mask():
    """한 screen px 내부 발광 분포 (F×F). OLED 서브픽셀처럼 면적 일부만 발광."""
    s = np.zeros((F, F))
    s[1:4, 1:7] = 1.0      # 큰 발광부
    s[5:7, 2:5] = 1.0      # 작은 발광부
    return s / s.mean()


def render_emission(lum):
    """screen 휘도(N×N) → 미세 격자 방출(NF×NF)"""
    return np.kron(lum, np.ones((F, F))) * np.tile(subpixel_mask(), (N, N))


def gauss_kernel_fft(shape, sigma):
    fy = np.fft.fftfreq(shape[0])[:, None]
    fx = np.fft.fftfreq(shape[1])[None, :]
    return np.exp(-2 * (np.pi * sigma)**2 * (fx**2 + fy**2))


def camera(emission, s, psf_cam, shift, warp_amp, noise, rng, frames):
    """
    s: camera px per screen px. psf_cam: PSF σ(카메라 px). shift: (dx,dy) screen px 실제 위치 오차.
    warp_amp: 렌즈 왜곡 잔차(screen px, 저주파 사인). 반환: 카메라 이미지 (평균), 카메라→screen 좌표 함수
    """
    sig_fine = psf_cam / s * F
    em = np.real(np.fft.ifft2(np.fft.fft2(emission) * gauss_kernel_fft(emission.shape, sig_fine)))
    M = int(np.floor(N * s))
    # 카메라 픽셀 footprint를 4×4 서브샘플로 적분
    sub = (np.arange(4) + 0.5) / 4
    cy, cx = np.mgrid[0:M, 0:M].astype(float)
    acc = np.zeros((M, M))
    for a in sub:
        for b in sub:
            u = (cx + b) / s
            v = (cy + a) / s
            u = u + shift[0] + warp_amp * np.sin(2 * np.pi * v / N)
            v = v + shift[1] + warp_amp * np.sin(2 * np.pi * u / N + 1)
            fu = np.clip(u * F - 0.5, 0, N * F - 1.001)
            fv = np.clip(v * F - 0.5, 0, N * F - 1.001)
            i0 = fv.astype(int); j0 = fu.astype(int)
            dv = fv - i0; du = fu - j0
            acc += (em[i0, j0] * (1 - dv) * (1 - du) + em[i0 + 1, j0] * dv * (1 - du)
                    + em[i0, j0 + 1] * (1 - dv) * du + em[i0 + 1, j0 + 1] * dv * du)
    img = acc / 16
    # 샷 노이즈: 전면 70% 회색 기준 픽셀당 상대 σ=noise (프레임 평균)
    sigma = noise * np.sqrt(np.clip(img, 0, None)) / np.sqrt(frames)
    return img + rng.normal(0, 1, img.shape) * sigma


def bilinear(img, x, y):
    H, W = img.shape
    x = np.clip(x, 0, W - 1.001); y = np.clip(y, 0, H - 1.001)
    x0 = x.astype(int); y0 = y.astype(int); dx = x - x0; dy = y - y0
    return (img[y0, x0] * (1 - dx) * (1 - dy) + img[y0, x0 + 1] * dx * (1 - dy)
            + img[y0 + 1, x0] * (1 - dx) * dy + img[y0 + 1, x0 + 1] * dx * dy)


def box(a, r):
    if r < 1:
        return a
    k = 2 * r + 1
    p = np.pad(a, r, mode="edge")
    c = np.cumsum(np.cumsum(p, 0), 1)
    c = np.pad(c, ((1, 0), (1, 0)))
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def full_field(true_lum, s, psf, shift, warp, noise, rng, frames, r):
    img = camera(render_emission(true_lum), s, psf, shift, warp, noise, rng, frames)
    # 앱은 shift/warp를 모른다: 명목 매핑 cam = screen*s 로 lumaGrid처럼 3×3 샘플
    yy, xx = np.mgrid[0:N, 0:N].astype(float)
    acc = 0
    for sy in (-1, 0, 1):
        for sx in (-1, 0, 1):
            acc = acc + bilinear(img, (xx + 0.5 + 0.15 * sx) * s - 0.5, (yy + 0.5 + 0.15 * sy) * s - 0.5)
    luma = acc / 9
    return luma


def sparse(true_lum, s, psf, shift, warp, noise, rng, frames, b, p):
    """희소 블록 점등 측광. 반환: screen px별 측정 휘도 (블록 단위)"""
    est = np.full((N, N), np.nan)
    recs = []   # (y0, x0, flux, phase_x, phase_y)
    k = p // b
    win = max(2, int(np.floor((p * s) / 2 - 0.5)))    # 이웃 스팟과 겹치지 않는 창
    for pi in range(k):
        for pj in range(k):
            lit = np.zeros((N, N))
            for y0 in range(pi * b, N, p):
                for x0 in range(pj * b, N, p):
                    lit[y0:y0 + b, x0:x0 + b] = 1
            img = camera(render_emission(true_lum * lit), s, psf, shift, warp, noise, rng, frames)
            H, W = img.shape
            for y0 in range(pi * b, N - b + 1, p):
                for x0 in range(pj * b, N - b + 1, p):
                    cx = (x0 + b / 2) * s - 0.5; cy = (y0 + b / 2) * s - 0.5
                    for _ in range(2):                  # 무게중심 국소 정합
                        xa = int(round(cx)); ya = int(round(cy))
                        ys, xs = slice(max(0, ya - win), min(H, ya + win + 1)), slice(max(0, xa - win), min(W, xa + win + 1))
                        patch = np.clip(img[ys, xs], 0, None)
                        if patch.sum() <= 0:
                            break
                        gy, gx = np.mgrid[ys, xs]
                        cx = (patch * gx).sum() / patch.sum(); cy = (patch * gy).sum() / patch.sum()
                    xa = int(round(cx)); ya = int(round(cy))
                    ys, xs = slice(max(0, ya - win), min(H, ya + win + 1)), slice(max(0, xa - win), min(W, xa + win + 1))
                    if xa - win < 0 or ya - win < 0 or xa + win >= W or ya + win >= H:
                        continue                        # 창이 이미지 밖 → 미측정
                    recs.append((y0, x0, img[ys, xs].sum() / (b * b * s * s), cx - xa, cy - ya))
    # 위상 보정: 정수 창과 스팟 중심의 소수부 위상에 따라 적분 비율이 달라지는 계통 오차를
    # 모든 스팟의 위상별 중앙값으로 정규화한다 (번인은 위상과 무관하므로 데이터 자체로 보정 가능).
    R = np.array(recs)
    nb = 4
    bx = np.clip(((R[:, 3] + 0.5) * nb).astype(int), 0, nb - 1)
    by = np.clip(((R[:, 4] + 0.5) * nb).astype(int), 0, nb - 1)
    med = np.median(R[:, 2])
    corr = np.ones((nb, nb))
    for i in range(nb):
        for j in range(nb):
            sel = (by == i) & (bx == j)
            if sel.sum() >= 8:
                corr[i, j] = np.median(R[sel, 2]) / med
    for (y0, x0, f, _, _), i, j in zip(recs, by, bx):
        est[int(y0):int(y0) + b, int(x0):int(x0) + b] = f / corr[i, j]
    est[np.isnan(est)] = np.nanmedian(est)      # 가장자리 미측정 픽셀
    return est


def evaluate(true_lum, luma, r, maxatt=0.10):
    target = np.percentile(luma, 1)
    gain = np.clip(target / np.maximum(luma, 1e-6), 1 - maxatt, 1)
    gain = box(box(gain, r), r)
    out = true_lum * gain
    m = text_mask()
    ring = (box(m.astype(float), 3) > 0) & ~m
    stroke = (out[m].mean() - out[ring].mean()) / out[ring].mean() * 100
    e = out / out.mean() - 1
    e2 = box(out, 1) / out.mean() - 1
    inner = (slice(4, N - 4), slice(4, N - 4))
    return stroke, np.sqrt((e[inner]**2).mean()) * 100, np.sqrt((e2[inner]**2).mean()) * 100


if __name__ == "__main__":
    true_lum = true_panel()
    m = text_mask()
    ring = (box(m.astype(float), 3) > 0) & ~m
    base = evaluate(true_lum, np.ones((N, N)), 0)
    print(f"보정 전: stroke {base[0]:+.2f}%  RMS1 {base[1]:.2f}%  RMS2 {base[2]:.2f}%\n")
    print("| 카메라 px/화면 px | 정합 오차 | 방법 | 촬영 장수 | 획 잔차% | RMS 1px% | RMS 2px% |")
    print("|---|---|---|---|---|---|---|")
    for s, label in ((0.53, "0.53 (현재 1600px 디코드)"), (1.33, "1.33 (풀해상도 디코드)"), (2.6, "2.6 (망원 타일)")):
        for shift, warp, rl in (((0.0, 0.0), 0.0, "없음"), ((0.4, -0.3), 0.6, "0.5px+왜곡0.6px")):
            rng = np.random.default_rng(1)
            for r in (0, 1, 2):
                luma = full_field(true_lum, s, 0.7, shift, warp, 0.01, rng, 5, r)
                st, e1, e2 = evaluate(true_lum, luma, r)
                print(f"| {label} | {rl} | 전면 gray, 스무딩 r={r} | 5 | {st:+.2f} | {e1:.2f} | {e2:.2f} |")
            for b, p, fr in ((1, 4, 8), (1, 6, 4), (2, 6, 8), (1, 3, 8)):
                if p * s < 4.5:            # 블록 간 간격이 카메라 ~5px 미만이면 이웃 스팟이 섞임
                    continue
                luma = sparse(true_lum, s, 0.7, shift, warp, 0.01, rng, fr, b, p)
                st, e1, e2 = evaluate(true_lum, luma, 0)
                n = (p // b)**2 * fr
                print(f"| {label} | {rl} | 희소 b={b} p={p}, 스무딩 0 | {n} | {st:+.2f} | {e1:.2f} | {e2:.2f} |")

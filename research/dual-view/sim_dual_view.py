"""
듀얼(이상) 후면 카메라 동시 촬영으로 패널 성분(화면 좌표 고정)과
카메라 성분(카메라 위치·광축 고정)을 분리할 수 있는지 보는 synthetic 시뮬레이션.

물리 모델 (log 휘도, 셀 = 화면 1mm):
  L_i(x) = p(x) + s_i(x) + o_i + n_i(x)
  p   : 패널 성분 (번인, 그라데이션, 얼룩) — 화면 좌표에 고정
  s_i : 카메라 i 성분 = ISP 렌즈 음영 잔차(광축 기준 반지름) + OLED 시야각 감쇠(시선각)
        → 카메라 위치가 바뀌면 화면 위에서 '다른 중심'을 갖는 방사형 함수가 된다
  o_i : 카메라별 노출/게인 (상수)

비교하는 추정기
  single-center : 현재 main 방식 — 화면 중심 기준 radial bin flat-field (2-pass robust, 앱과 같은 상수)
  single-axis   : 같은 방식이지만 중심을 실제 광축 교점으로 옮김 (중심 오차만 고친 경우)
  dual-joint    : 두 시점 차이 D = L_1 - L_2 만으로 s_1, s_2 (각자 광축 교점 기준 radial 스플라인)를
                  최소제곱 추정 → p = mean_i(L_i - s_i).  화면에 고정된 성분은 D에서 상쇄되므로
                  번인이 카메라 성분으로 흡수될 경로가 원리적으로 없다.
순수 numpy. 수치는 경향 확인용이며 실기기 검증이 아니다.
"""
import numpy as np

SCREEN_W, SCREEN_H = 68.0, 151.0      # mm (6.5" 9:20 폰)
GW, GH = 68, 151
xs = (np.arange(GW) + 0.5) - SCREEN_W / 2
ys = (np.arange(GH) + 0.5) - SCREEN_H / 2
X, Y = np.meshgrid(xs, ys)            # 화면 중심 원점, y는 아래로 +

OK, REJECT, MAXDEV = 0.04, 0.12, 0.18  # Analyzer.FLAT_FIELD_* (main)


# ───────────────────────── 장면 ─────────────────────────
def panel(kind, depth=0.06):
    p = np.zeros_like(X)
    if "bars" in kind:                                   # 상태바 4.5mm, 내비바 3.5mm
        p[Y < -SCREEN_H / 2 + 4.5] += np.log(1 - depth)
        p[Y > SCREEN_H / 2 - 3.5] += np.log(1 - depth * 0.8)
    if "grad" in kind:                                   # 패널 자체 세로 그라데이션 4%
        p += np.log(1 + 0.04 * (-Y / SCREEN_H))
    if "center" in kind:                                 # 화면 중앙 원형 열화 3% (방사형!)
        p += np.log(1 - 0.03 * np.exp(-(X**2 + Y**2) / (2 * 25.0**2)))
    if "edge" in kind:                                   # 가장자리 방사형 열화 5%
        r = np.sqrt((X / (SCREEN_W / 2))**2 + (Y / (SCREEN_H / 2))**2) / np.sqrt(2)
        p += np.log(1 - 0.05 * r**2)
    if "blob" in kind:                                   # 국소 얼룩 2개
        p += np.log(1 - 0.04 * np.exp(-((X - 15)**2 + (Y + 30)**2) / 30))
        p += np.log(1 - 0.03 * np.exp(-((X + 20)**2 + (Y - 40)**2) / 60))
    return p


class Cam:
    """핀홀 카메라. pos=(x,y,z) mm (z<0: 화면 앞), 광축은 화면 법선에서 tilt_deg만큼 x로 기울어짐."""
    def __init__(self, pos, half_fov_diag_deg, k1, k2, tilt_deg=0.0, pp_off=(0.0, 0.0),
                 blur=0.0, gain_exp=1.0):
        self.pos = np.array(pos, float)
        self.blur = blur            # 화면 셀 단위 PSF σ (초광각은 화면당 픽셀이 적어 더 흐림)
        self.gain_exp = gain_exp    # ISP 톤커브 선형화 잔차: log 신호에 곱해지는 배율
        t = np.radians(tilt_deg)
        self.axis = np.array([np.sin(t), 0.0, np.cos(t)])
        self.tan_max = np.tan(np.radians(half_fov_diag_deg))
        self.k1, self.k2 = k1, k2
        self.pp_off = np.array(pp_off)               # 광학 중심 편심 (센서 정규 좌표)

    def axis_hit(self):
        s = -self.pos[2] / self.axis[2]
        h = self.pos + s * self.axis
        return h[0], h[1]

    def shading(self, gamma_oled=0.6):
        d = np.stack([X - self.pos[0], Y - self.pos[1], -self.pos[2] * np.ones_like(X)], -1)
        dn = d / np.linalg.norm(d, axis=-1, keepdims=True)
        cos_a = dn @ self.axis
        tan_a = np.sqrt(np.clip(1 - cos_a**2, 0, None)) / cos_a
        rho = tan_a / self.tan_max                       # 센서 정규 반지름(근사)
        rho = np.sqrt(rho**2 + self.pp_off @ self.pp_off)  # 편심 근사
        lens = 1 - self.k1 * rho**2 - self.k2 * rho**4   # ISP LSC 후 잔차
        cos_b = dn[..., 2]                               # 화면 법선과 시선각
        oled = cos_b ** gamma_oled
        return np.log(lens) + np.log(oled)


def gblur(a, sigma):
    if sigma <= 0:
        return a
    r = int(3 * sigma) + 1
    k = np.exp(-np.arange(-r, r + 1)**2 / (2 * sigma**2)); k /= k.sum()
    pad = np.pad(a, r, mode="edge")
    pad = np.apply_along_axis(lambda v: np.convolve(v, k, "same"), 0, pad)
    pad = np.apply_along_axis(lambda v: np.convolve(v, k, "same"), 1, pad)
    return pad[r:-r, r:-r]


def observe(p, cam, noise, rng, offset):
    sig = gblur(p, cam.blur) + cam.shading()
    return cam.gain_exp * sig + offset + rng.normal(0, noise, p.shape)


# ───────────────────────── 추정기 ─────────────────────────
def smooth(v, passes):
    for _ in range(passes):
        c = v.copy()
        for i in range(len(v)):
            lo, hi = max(0, i - 2), min(len(v), i + 3)
            v[i] = c[lo:hi].mean()
    return v


def radial_ff(L, center, bins=64):
    """Analyzer.radialFlatField(main) 이식. 입력 log, 출력 log flat-field."""
    lum = np.exp(L)
    med = np.median(lum)
    r = np.sqrt((X - center[0])**2 + (Y - center[1])**2)
    b = np.clip((r / r.max() * (bins - 1)).astype(int), 0, bins - 1)
    norm = lum / med
    prof = np.array([norm[b == i].mean() if (b == i).any() else 1.0 for i in range(bins)])
    prof = smooth(prof, 3)
    res = np.abs(norm / prof[b] - 1)
    w = np.clip(1 - (res - OK) / (REJECT - OK), 0, 1)
    for i in range(bins):
        m = b == i
        if w[m].sum() > 0:
            prof[i] = (norm[m] * w[m]).sum() / w[m].sum()
    prof = smooth(prof, 4)
    f = np.clip(r / r.max() * (bins - 1), 0, bins - 1.001)
    i0 = f.astype(int); t = f - i0
    ff = prof[i0] * (1 - t) + prof[np.minimum(i0 + 1, bins - 1)] * t
    return np.log(np.clip(ff, 1 - MAXDEV, 1 + MAXDEV))


def radial_ff_fix(L, center=(0.0, 0.0), blocks_long=40):
    """fix/false-pass-burn-in 브랜치의 radialFlatField 이식: 블록 평균 + Tukey IRLS로
    c0 + c1·ρ² + c2·ρ⁴ (처음 3회는 ρ²만). 입력/출력 log."""
    lum = np.exp(L)
    med = np.median(lum)
    bs = max(1, int(np.ceil(GH / blocks_long)))
    bx, by, bm, bc = [], [], [], []
    for y0 in range(0, GH, bs):
        for x0 in range(0, GW, bs):
            blk = lum[y0:y0 + bs, x0:x0 + bs]
            bx.append(X[y0:y0 + bs, x0:x0 + bs].mean()); by.append(Y[y0:y0 + bs, x0:x0 + bs].mean())
            bm.append(blk.mean() / med); bc.append(blk.size)
    bx, by, bm, bc = map(np.array, (bx, by, bm, bc))
    maxR2 = ((X - center[0])**2 + (Y - center[1])**2).max()
    rho2 = ((bx - center[0])**2 + (by - center[1])**2) / maxR2
    w = np.ones_like(bm)
    coef = np.array([1.0, 0.0, 0.0])
    for it in range(8):
        quartic = it >= 3
        A = np.stack([np.ones_like(rho2), rho2] + ([rho2**2] if quartic else []), 1)
        ww = w * bc
        c = np.linalg.lstsq(A * np.sqrt(ww)[:, None], bm * np.sqrt(ww), rcond=None)[0]
        coef = np.array(list(c) + [0.0] * (3 - len(c)))
        res = bm - (coef[0] + coef[1] * rho2 + coef[2] * rho2**2)
        scale = max(1.4826 * np.median(np.abs(res)), 0.002) * 4.685
        u = res / scale
        w = np.where(np.abs(u) < 1, (1 - u * u)**2, 0.0)
    r2 = ((X - center[0])**2 + (Y - center[1])**2) / maxR2
    ff = coef[0] + coef[1] * r2 + coef[2] * r2**2
    return np.log(np.clip(ff, 1 - MAXDEV, 1 + MAXDEV))


def hat_basis(r, knots):
    """선형 B-spline(hat) 기저: r -> (N, K)"""
    K = len(knots)
    B = np.zeros(r.shape + (K,))
    for k in range(K):
        if k > 0:
            m = (r >= knots[k - 1]) & (r <= knots[k])
            B[m, k] = (r[m] - knots[k - 1]) / (knots[k] - knots[k - 1])
        if k < K - 1:
            m = (r >= knots[k]) & (r <= knots[k + 1])
            B[m, k] = (knots[k + 1] - r[m]) / (knots[k + 1] - knots[k])
    return B


def dual_joint(Ls, centers, K=16, lam=3.0, huber=0.01, iters=6, refine=0.0, return_cost=False, main_only=True):
    """두(이상) 시점 차이로 시점별 radial 성분 추정. 첫 시점 성분의 r=0 값을 0으로 gauge 고정.
    refine>0 이면 각 광축 교점을 ±refine mm 안에서 패턴 탐색으로 보정한다(잔차 최소)."""
    if refine > 0:
        cs = [list(c) for c in centers]
        best = dual_joint(Ls, cs, K, lam, huber, 2, 0.0, True, main_only)[1]
        step = refine / 2
        while step > 0.1:
            improved = False
            for j in range(len(cs)):
                for ax in (0, 1):
                    for sgn in (1, -1):
                        trial = [c[:] for c in cs]
                        trial[j][ax] += sgn * step
                        if abs(trial[j][ax] - centers[j][ax]) > refine:
                            continue
                        cost = dual_joint(Ls, trial, K, lam, huber, 2, 0.0, True, main_only)[1]
                        if cost < best:
                            best, cs, improved = cost, trial, True
            if not improved:
                step /= 2
        centers = cs
    n = len(Ls)
    rmax = max(np.sqrt((X - c[0])**2 + (Y - c[1])**2).max() for c in centers) + 1
    knots = np.linspace(0, rmax, K)
    Bs = [hat_basis(np.sqrt((X - c[0])**2 + (Y - c[1])**2).ravel(), knots) for c in centers]
    # 모든 쌍 (0,j) 차이: L0 - Lj = s0 - sj + (o0-oj) + noise
    rows, rhs = [], []
    for j in range(1, n):
        A = np.zeros((GW * GH, n * K + (n - 1)))
        A[:, 0:K] = Bs[0]
        A[:, j * K:(j + 1) * K] = -Bs[j]
        A[:, n * K + j - 1] = 1.0                       # 노출 차
        rows.append(A); rhs.append((Ls[0] - Ls[j]).ravel())
    A = np.vstack(rows); y = np.concatenate(rhs)
    # 2차 차분 smoothness
    Dm = np.zeros((K - 2, K))
    for i in range(K - 2):
        Dm[i, i:i + 3] = [1, -2, 1]
    R = np.zeros((n * (K - 2) + 1, A.shape[1]))
    for j in range(n):
        R[j * (K - 2):(j + 1) * (K - 2), j * K:(j + 1) * K] = Dm
    R[-1, 0] = 1e3                                      # gauge: s0(0)=0
    w = np.ones(len(y))
    for _ in range(iters):                              # Huber IRLS (정합 오차·모아레 대비)
        Aw = A * (w * w)[:, None]
        N = A.T @ Aw + (lam * len(y) / 1e4) * (R.T @ R) + 1e-9 * np.eye(A.shape[1])
        sol = np.linalg.solve(N, Aw.T @ y)
        res = np.abs(A @ sol - y)
        w = np.sqrt(np.where(res <= huber, 1.0, huber / np.maximum(res, 1e-12)))
    if return_cost:
        rr = A @ sol - y
        return None, float(np.mean(np.minimum(rr * rr, huber * huber)))
    s = [(Bs[j] @ sol[j * K:(j + 1) * K]).reshape(GH, GW) for j in range(n)]
    offs = [0.0] + list(sol[n * K:])
    if main_only:
        return Ls[0] - s[0]                 # 보조 시점은 카메라 성분 추정에만 사용
    return np.mean([Ls[j] - s[j] + (offs[j] if j else 0) for j in range(n)], axis=0)


# ───────────────────────── 평가 ─────────────────────────
def demean(a):
    return a - np.median(a)


def metrics(p_est, p_true):
    """rms/p95: 패널 맵 추정 오차(%). keep: 실제 결함 패턴을 맵이 얼마나 보존했는지
    (실제 패턴 방향으로의 사영 계수, 100%=완전 보존, 0%=전부 카메라 성분으로 흡수)."""
    a = p_est - p_est.mean()
    t = p_true - p_true.mean()
    e = a - t
    keep = (a * t).sum() / (t * t).sum() if (t * t).sum() > 1e-12 else np.nan
    return dict(rms=np.sqrt((e**2).mean()) * 100, p95=np.percentile(np.abs(e), 95) * 100,
                keep=keep * 100)


def run(name, kind, cams, noise=0.003, seeds=6, depth=0.06, center_err=2.0, rel_err=0.5, refine=0.0):
    """center_err: 광축 교점의 공통 오차 σ(mm, 홈그래피+principal point 근사).
    rel_err: 카메라 간 상대 오차 σ(mm). 동시 촬영은 공장 보정된 LENS_POSE_TRANSLATION으로
    상대 위치를 알 수 있어 작게, 수동 이동은 각 장의 홈그래피로 따로 구하므로 center_err와 같게 둔다."""
    out = {k: [] for k in ("main@b1fa38a", "fix-branch", "fix-branch+광축", "dual-joint")}
    for sd in range(seeds):
        rng = np.random.default_rng(sd)
        p = panel(kind, depth)
        Ls = [observe(p, c, noise, rng, rng.normal(0, 0.05)) for c in cams]
        hits = [c.axis_hit() for c in cams]
        common = rng.normal(0, center_err, 2)
        est_hits = [(h[0] + common[0] + rng.normal(0, rel_err), h[1] + common[1] + rng.normal(0, rel_err))
                    for h in hits]
        out["main@b1fa38a"].append(metrics(Ls[0] - radial_ff(Ls[0], (0, 0)), p))
        out["fix-branch"].append(metrics(Ls[0] - radial_ff_fix(Ls[0]), p))
        out["fix-branch+광축"].append(metrics(Ls[0] - radial_ff_fix(Ls[0], est_hits[0]), p))
        out["dual-joint"].append(metrics(dual_joint(Ls, est_hits, refine=refine), p))
    rows = []
    for k, v in out.items():
        rows.append((name, k, *(np.nanmean([m[x] for m in v]) for x in ("rms", "p95", "keep"))))
    return rows


# 카메라 구성 (D = 화면까지 거리 150mm: 메인 화각에 화면이 ~80% 차는 거리)
D = 150.0
def main_cam(dx=0.0, dy=0.0, tilt=0.0):
    return Cam((dx, dy, -D), 40, 0.10, 0.04, tilt, pp_off=(0.01, -0.01))
def uw_cam(dx=12.0, dy=0.0, tilt=0.0):
    return Cam((dx, dy, -D), 60, 0.16, 0.06, tilt, pp_off=(-0.015, 0.01), blur=1.5, gain_exp=1.03)

CONFIGS = {
    "동시 main+UW 12mm":            [main_cam(), uw_cam(12)],
    "동시 main+UW 12mm, 기울기5°":  [main_cam(tilt=5), uw_cam(12, tilt=5)],
    "수동 main ±20mm (2장)":        [main_cam(-20), main_cam(20)],
    "동시 main+UW × 수동 ±20mm (4장)": [main_cam(-20), uw_cam(-8), main_cam(20), uw_cam(32)],
}

SCENES = {
    "번인 없음 (거짓 결함 확인)": "",
    "상태바+내비바 6% + 얼룩": "bars blob",
    "상태바+내비바 6% + 그라데이션 4%": "bars grad",
    "중앙 원형 열화 3%": "center",
    "가장자리 방사형 열화 5%": "edge",
}

if __name__ == "__main__":
    import sys
    # 광축 교점 오차 2mm(σ): 홈그래피+principal point 근사로 얻는다고 가정
    print("| 장면 | 촬영 | 방법 | 맵 오차 RMS% | 맵 오차 P95% | 결함 보존% |")
    print("|---|---|---|---|---|---|")
    for sname, kind in SCENES.items():
        for cname, cams in CONFIGS.items():
            rel = 2.0 if "수동" in cname else 0.5
            for r in run(cname, kind, cams, rel_err=rel):
                keep = "—" if not kind else f"{r[4]:.0f}"
                print(f"| {sname} | {r[0]} | {r[1]} | {r[2]:.2f} | {r[3]:.2f} | {keep} |")
        sys.stdout.flush()

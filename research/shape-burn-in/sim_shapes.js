// Text / game-button burn-in at native resolution (1080x2400), old (b1fa38a) vs new (main) Scanner pipeline.
// Ports Analyzer.radialFlatField / correctionTarget / gainGrid / refineGain / stats / blockMaxDev and the
// iteration-selection loop of MeasurementActivity (gray70 only, no confidence, no RGB). Camera = optical blur +
// noise + vignetting; registration is assumed perfect (see sim_registration.py for that error).
'use strict';
const W = 1080, H = 2400, N = W * H;
const MAX_ATT = 0.10, PASS_RMS = 0.015, PASS_P95 = 0.035, PASS_BLOCK = 0.02;
const args = Object.fromEntries(process.argv.slice(2).map(a => a.split('=')));
const NOISE = +(args.noise ?? 0.005), PSF = +(args.psf ?? 0.8), VIG = +(args.vig ?? 0.15), DEPTH = +(args.depth ?? 0.04);
const SMOOTH = +(args.smooth ?? 1), ITERS = +(args.iters ?? 24), ALPHA = +(args.alpha ?? 0.3);

// ---------- deterministic RNG ----------
let seed = 12345;
const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) / 4294967296);
const gauss = () => { const u = rnd() || 1e-9, v = rnd(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); };

// ---------- panel: burn-in features (true emission factor) ----------
const panel = new Float32Array(N).fill(1);
const features = [];   // {name, mask:Uint8Array}
function feature(name, paint) {
  const mask = new Uint8Array(N); paint(mask); features.push({ name, mask });
  for (let i = 0; i < N; i++) if (mask[i]) panel[i] = 1 - DEPTH;
}
const set = (m, x, y) => { x |= 0; y |= 0; if (x >= 0 && x < W && y >= 0 && y < H) m[y * W + x] = 1; };
function rect(m, x0, y0, x1, y1) { for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) set(m, x, y); }
function disk(m, cx, cy, r) { for (let y = cy - r; y <= cy + r; y++) for (let x = cx - r; x <= cx + r; x++) if ((x - cx) ** 2 + (y - cy) ** 2 <= r * r) set(m, x, y); }
function ring(m, cx, cy, r, t) { for (let y = cy - r; y <= cy + r; y++) for (let x = cx - r; x <= cx + r; x++) { const d = Math.hypot(x - cx, y - cy); if (d <= r && d >= r - t) set(m, x, y); } }
function line(m, x0, y0, x1, y1, t) {
  const L = Math.hypot(x1 - x0, y1 - y0), n = Math.ceil(L * 2);
  for (let k = 0; k <= n; k++) { const x = x0 + (x1 - x0) * k / n, y = y0 + (y1 - y0) * k / n; for (let dy = -t / 2; dy < t / 2; dy++) for (let dx = -t / 2; dx < t / 2; dx++) set(m, x + dx, y + dy); }
}
// 7-segment glyphs: stroke-based text, height h, stroke t
const SEG = { 0: 'abcdef', 1: 'bc', 2: 'abged', 3: 'abgcd', 4: 'fgbc', 5: 'afgcd', 6: 'afgedc', 7: 'abc', 8: 'abcdefg', 9: 'abcdfg' };
function glyph(m, ch, x, y, h, t) {
  const w = h * 0.55, s = { a: [0, 0, w, 0], b: [w, 0, w, h / 2], c: [w, h / 2, w, h], d: [0, h, w, h], e: [0, h / 2, 0, h], f: [0, 0, 0, h / 2], g: [0, h / 2, w, h / 2] };
  if (ch === ':') { rect(m, x + w * 0.3, y + h * 0.25, x + w * 0.3 + t, y + h * 0.25 + t); rect(m, x + w * 0.3, y + h * 0.7, x + w * 0.3 + t, y + h * 0.7 + t); return w * 0.7; }
  for (const k of SEG[ch]) { const [a, b, c, d] = s[k]; line(m, x + a, y + b, x + c, y + d, t); }
  return w + h * 0.25;
}
function text(m, str, x, y, h, t) { for (const ch of str) x += glyph(m, ch, x, y, h, t); }

feature('상태바 시계 "12:45" (높이 36px, 획 4px)', m => text(m, '12:45', 40, 22, 36, 4));
feature('상태바 아이콘 (배터리·와이파이, 획 3px)', m => { rect(m, 960, 26, 1030, 29); rect(m, 960, 54, 1030, 57); rect(m, 960, 26, 963, 57); rect(m, 1027, 26, 1030, 57); rect(m, 966, 32, 1010, 51); for (let r = 14; r <= 34; r += 10) ring(m, 905, 62, r, 3); });
feature('내비바 버튼 ◁ ○ □ (획 5px)', m => { line(m, 260, 2330, 300, 2305, 5); line(m, 260, 2330, 300, 2355, 5); line(m, 300, 2305, 300, 2355, 5); ring(m, 540, 2330, 26, 5); rect(m, 760, 2305, 810, 2310); rect(m, 760, 2350, 810, 2355); rect(m, 760, 2305, 765, 2355); rect(m, 805, 2305, 810, 2355); });
feature('본문 작은 글씨 (높이 24px, 획 2px)', m => { for (let row = 0; row < 3; row++) text(m, '0123456789', 120, 1100 + row * 40, 24, 2); });
feature('게임 조이스틱 링 (r=160, 두께 10px)', m => ring(m, 260, 1950, 160, 10));
feature('게임 액션 버튼 채움 원 (r=70 ×3)', m => { disk(m, 860, 1850, 70); disk(m, 720, 1990, 70); disk(m, 860, 2130, 70); });
feature('게임 HUD 점수 "9870" (높이 60px, 획 7px)', m => text(m, '9870', 650, 380, 60, 7));

// ---------- helpers ----------
function boxBlur(src, w, h, r) {
  if (r < 1) return Float32Array.from(src);
  const tmp = new Float32Array(src.length), dst = new Float32Array(src.length);
  for (let y = 0; y < h; y++) { const row = y * w; let acc = 0, n = 0; for (let x = 0; x < Math.min(r, w); x++) { acc += src[row + x]; n++; }
    for (let x = 0; x < w; x++) { const add = x + r; if (add < w) { acc += src[row + add]; n++; } tmp[row + x] = acc / n; const drop = x - r; if (drop >= 0) { acc -= src[row + drop]; n--; } } }
  for (let x = 0; x < w; x++) { let acc = 0, n = 0; for (let y = 0; y < Math.min(r, h); y++) { acc += tmp[y * w + x]; n++; }
    for (let y = 0; y < h; y++) { const add = y + r; if (add < h) { acc += tmp[add * w + x]; n++; } dst[y * w + x] = acc / n; const drop = y - r; if (drop >= 0) { acc -= tmp[drop * w + x]; n--; } } }
  return dst;
}
function gaussBlur(src, s) { // separable Gaussian (screen px)
  if (s <= 0) return Float32Array.from(src);
  const R = Math.ceil(3 * s), k = []; let sum = 0; for (let i = -R; i <= R; i++) { const v = Math.exp(-i * i / (2 * s * s)); k.push(v); sum += v; } for (let i = 0; i < k.length; i++) k[i] /= sum;
  const tmp = new Float32Array(N), dst = new Float32Array(N);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { let a = 0; for (let i = -R; i <= R; i++) { const xx = Math.min(W - 1, Math.max(0, x + i)); a += k[i + R] * src[y * W + xx]; } tmp[y * W + x] = a; }
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { let a = 0; for (let i = -R; i <= R; i++) { const yy = Math.min(H - 1, Math.max(0, y + i)); a += k[i + R] * tmp[yy * W + x]; } dst[y * W + x] = a; }
  return dst;
}
function sortedCopy(a) { return Float32Array.from(a).sort(); }
function stats(g) {
  const s = sortedCopy(g), med = s[s.length >> 1], p10 = s[Math.min(s.length - 1, Math.floor(s.length * 0.1))];
  let sq = 0; const dev = new Float32Array(g.length);
  for (let i = 0; i < g.length; i++) { const d = g[i] / med - 1; dev[i] = Math.abs(d); sq += d * d; }
  dev.sort();
  return { median: med, p10, rms: Math.sqrt(sq / g.length), p95: dev[Math.min(dev.length - 1, Math.floor(dev.length * 0.95))] };
}
function blockGrid(g, w, h, blocks = 60) {
  const bs = Math.max(1, Math.round(Math.max(w, h) / blocks)), bw = Math.ceil(w / bs), bh = Math.ceil(h / bs);
  const sums = new Float64Array(bw * bh), counts = new Int32Array(bw * bh);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const b = ((y / bs) | 0) * bw + ((x / bs) | 0); sums[b] += g[y * w + x]; counts[b]++; }
  const means = new Float32Array(bw * bh), cx = new Float64Array(bw * bh), cy = new Float64Array(bw * bh);
  for (let b = 0; b < bw * bh; b++) { means[b] = sums[b] / counts[b]; const bx = b % bw, by = (b / bw) | 0; cx[b] = (bx * bs + Math.min((bx + 1) * bs, w) - 1) / 2; cy[b] = (by * bs + Math.min((by + 1) * bs, h) - 1) / 2; }
  return { bs, means, counts, cx, cy };
}
function blockMaxDev(g, w = W, h = H, blocks = 60) { const med = stats(g).median; let m = 0; for (const v of blockGrid(g, w, h, blocks).means) m = Math.max(m, Math.abs(v / med - 1)); return m; }

// ---------- flat-field: old (binned radial) and new (block Tukey IRLS) ----------
function flatOld(l) {
  const bins = 64, med = stats(l).median, cx = (W - 1) / 2, cy = (H - 1) / 2, maxR = Math.hypot(cx, cy);
  const bin = new Int32Array(N); for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) bin[y * W + x] = Math.min(bins - 1, Math.max(0, Math.floor(Math.hypot(x - cx, y - cy) / maxR * (bins - 1))));
  const smooth = (p, passes) => { for (let k = 0; k < passes; k++) { const c = p.slice(); for (let i = 0; i < p.length; i++) { let a = 0, n = 0; for (let d = -2; d <= 2; d++) { const j = i + d; if (j >= 0 && j < p.length) { a += c[j]; n++; } } p[i] = a / n; } } };
  const sum = new Float64Array(bins), cnt = new Float64Array(bins); for (let i = 0; i < N; i++) { sum[bin[i]] += l[i] / med; cnt[bin[i]]++; }
  const prof = Array.from(sum, (s, i) => cnt[i] ? s / cnt[i] : 1); smooth(prof, 3);
  const rs = new Float64Array(bins), rw = new Float64Array(bins);
  for (let i = 0; i < N; i++) { const n = l[i] / med, a = Math.abs(n / Math.max(prof[bin[i]], 1e-5) - 1), w = a <= 0.04 ? 1 : a >= 0.12 ? 0 : 1 - (a - 0.04) / 0.08; if (w > 0) { rs[bin[i]] += n * w; rw[bin[i]] += w; } }
  for (let i = 0; i < bins; i++) if (rw[i] > 0) prof[i] = rs[i] / rw[i]; smooth(prof, 4);
  const out = new Float32Array(N);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const f = Math.min(bins - 1.001, Math.max(0, Math.hypot(x - cx, y - cy) / maxR * (bins - 1))), i0 = f | 0, t = f - i0; out[y * W + x] = Math.min(1.18, Math.max(0.82, prof[i0] * (1 - t) + prof[Math.min(i0 + 1, bins - 1)] * t)); }
  return out;
}
function solve(A, b) { const k = b.length, M = A.map((r, i) => [...r, b[i]]);
  for (let c = 0; c < k; c++) { let p = c; for (let r = c; r < k; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r; if (Math.abs(M[p][c]) < 1e-12) return null; [M[c], M[p]] = [M[p], M[c]];
    for (let r = 0; r < k; r++) if (r !== c) { const f = M[r][c] / M[c][c]; for (let j = c; j <= k; j++) M[r][j] -= f * M[c][j]; } }
  return Array.from({ length: 3 }, (_, i) => i < k ? M[i][k] / M[i][i] : 0); }
function flatNew(l) {
  const med = stats(l).median, cx = (W - 1) / 2, cy = (H - 1) / 2, R2 = cx * cx + cy * cy, B = blockGrid(l, W, H), n = B.means.length;
  const r2 = Float64Array.from({ length: n }, (_, i) => ((B.cx[i] - cx) ** 2 + (B.cy[i] - cy) ** 2) / R2), yv = Float64Array.from(B.means, v => v / med);
  const w = new Float64Array(n).fill(1); let c = [1, 0, 0];
  for (let it = 0; it < 8; it++) { const k = it >= 3 ? 3 : 2, A = Array.from({ length: k }, () => new Array(k).fill(0)), b = new Array(k).fill(0);
    for (let i = 0; i < n; i++) { const ww = w[i] * B.counts[i]; if (ww <= 0) continue; const f = [1, r2[i], r2[i] * r2[i]]; for (let a = 0; a < k; a++) { b[a] += ww * f[a] * yv[i]; for (let d = 0; d < k; d++) A[a][d] += ww * f[a] * f[d]; } }
    const s = solve(A, b); if (!s) break; c = s;
    const res = Float64Array.from({ length: n }, (_, i) => yv[i] - (c[0] + c[1] * r2[i] + c[2] * r2[i] * r2[i])), abs = Float64Array.from(res, Math.abs).sort();
    const sc = Math.max(1.4826 * abs[n >> 1], 0.002) * 4.685; for (let i = 0; i < n; i++) { const u = res[i] / sc; w[i] = Math.abs(u) < 1 ? (1 - u * u) ** 2 : 0; } }
  const out = new Float32Array(N);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const q = ((x - cx) ** 2 + (y - cy) ** 2) / R2; out[y * W + x] = Math.min(1.18, Math.max(0.82, c[0] + c[1] * q + c[2] * q * q)); }
  return out;
}
function targetOld(l) { return stats(l).p10; }
function targetNew(l) { const med = stats(l).median, m = blockGrid(l, W, H).means.sort(); return Math.max(m[Math.min(m.length - 1, Math.floor(m.length * 0.01))], med * (1 - MAX_ATT)); }
const SHRINK = +(args.shrink ?? 0);
// Edge-preserving option: keep high-frequency detail that stands clear of the noise (hard threshold at SHRINK*sigma_MAD).
function keepDetail(raw, smooth) {
  if (!SHRINK) return smooth;
  const hf = new Float32Array(N); for (let i = 0; i < N; i++) hf[i] = raw[i] - smooth[i];
  const a = Float32Array.from(hf, Math.abs).sort(), t = SHRINK * 1.4826 * a[N >> 1];
  const out = new Float32Array(N); for (let i = 0; i < N; i++) out[i] = Math.abs(hf[i]) > t ? raw[i] : smooth[i];
  return out;
}
function gainGrid(l, target) {
  const g = new Float32Array(N); for (let i = 0; i < N; i++) g[i] = Math.min(1, Math.max(1 - MAX_ATT, target / Math.max(l[i], 1e-5)));
  let b = keepDetail(g, boxBlur(boxBlur(g, W, H, SMOOTH), W, H, SMOOTH)); for (let i = 0; i < N; i++) b[i] = Math.min(1, Math.max(1 - MAX_ATT, b[i])); return b;
}
function refine(gain, after, target) {
  const o = new Float32Array(N); for (let i = 0; i < N; i++) o[i] = Math.min(1, Math.max(1 - MAX_ATT, gain[i] * Math.pow(target / Math.max(after[i], 1e-5), ALPHA)));
  const b = keepDetail(o, boxBlur(o, W, H, SMOOTH)); for (let i = 0; i < N; i++) b[i] = Math.min(1, Math.max(1 - MAX_ATT, b[i])); return b;
}

// ---------- camera ----------
const cx0 = (W - 1) / 2, cy0 = (H - 1) / 2, R20 = cx0 * cx0 + cy0 * cy0;
const vig = Float32Array.from({ length: N }, (_, i) => 1 - VIG * (((i % W) - cx0) ** 2 + (((i / W) | 0) - cy0) ** 2) / R20);
function quantize(g) { const step = MAX_ATT / 255; return Float32Array.from(g, v => 1 - Math.round((1 - v) / step) * step); }
function capture(gain) {
  const emitted = new Float32Array(N); for (let i = 0; i < N; i++) emitted[i] = panel[i] * (gain ? gain[i] : 1);
  const img = gaussBlur(emitted, PSF); for (let i = 0; i < N; i++) img[i] = img[i] * vig[i] * (1 + NOISE * gauss());
  return img;
}
const applyFF = (m, ff) => Float32Array.from(m, (v, i) => Math.max(1e-5, v / Math.max(ff[i], 0.5)));

// ---------- feature-level ground truth ----------
function featureReport(truth) {
  // contrast = mean over feature / median of a 24px surrounding ring (excluding all features) - 1
  const all = new Uint8Array(N); for (const f of features) for (let i = 0; i < N; i++) if (f.mask[i]) all[i] = 1;
  return features.map(f => {
    let x0 = W, y0 = H, x1 = 0, y1 = 0, s = 0, n = 0;
    for (let i = 0; i < N; i++) if (f.mask[i]) { const x = i % W, y = (i / W) | 0; x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); s += truth[i]; n++; }
    const bg = []; let haloMax = 0;
    for (let y = Math.max(0, y0 - 24); y <= Math.min(H - 1, y1 + 24); y++) for (let x = Math.max(0, x0 - 24); x <= Math.min(W - 1, x1 + 24); x++) if (!all[y * W + x]) bg.push(truth[y * W + x]);
    bg.sort((a, b) => a - b); const med = bg[bg.length >> 1];
    for (const v of bg) haloMax = Math.max(haloMax, Math.abs(v / med - 1));
    return { name: f.name, contrast: s / n / med - 1, haloP99: Math.abs(bg[Math.floor(bg.length * 0.01)] / med - 1) };
  });
}

function run(label, ffFn, targetFn, newScore) {
  seed = 12345;
  const m0 = capture(null), ff = ffFn(m0), l0 = applyFF(m0, ff), target = targetFn(l0);
  const score = l => { const s = stats(l); let sc = Math.max(s.rms / PASS_RMS, s.p95 / PASS_P95); if (newScore) sc = Math.max(sc, blockMaxDev(l) / PASS_BLOCK); return sc; };
  const s0 = score(l0); let best = { score: s0, gain: new Float32Array(N).fill(1), passed: s0 <= 1 }, gain = quantize(gainGrid(l0, target)), it = 0, prev = null, stall = 0, div = 0;
  for (; it < ITERS; it++) {
    const after = applyFF(capture(gain), ff), sc = score(after);
    if (sc < best.score - 1e-4) best = { score: sc, gain: Float32Array.from(gain), passed: sc <= 1 };
    if (sc <= 1 && !args.noearly) break;
    if (prev !== null) { const d = 0.01 + sc * 0.01; div = sc > prev + d ? div + 1 : 0; stall = prev - sc < d ? stall + 1 : 0; if (!args.nostall && (div >= 3 || stall >= 4)) break; }
    prev = sc; gain = quantize(refine(gain, after, target));
  }
  const truthBefore = featureReport(panel), truthAfter = featureReport(Float32Array.from(panel, (v, i) => v * best.gain[i]));
  const loss = 1 - best.gain.reduce((a, b) => a + b, 0) / N;
  console.log(`\n[${label}] 보정 전 앱 점수 ${s0.toFixed(2)} → 최적 ${best.score.toFixed(2)} (${best.passed ? '합격' : '미달'}), 반복 ${it + 1}회, 밝기 손실 ${(loss * 100).toFixed(1)}%, 목표 ${(target / stats(l0).median).toFixed(4)}×median`);
  console.log(`  실제 패널 블록(40px) 최대 편차 ${(blockMaxDev(panel) * 100).toFixed(2)}% → ${(blockMaxDev(Float32Array.from(panel, (v, i) => v * best.gain[i])) * 100).toFixed(2)}%, ` +
    `8px 블록 ${(blockMaxDev(panel, W, H, 300) * 100).toFixed(2)}% → ${(blockMaxDev(Float32Array.from(panel, (v, i) => v * best.gain[i]), W, H, 300) * 100).toFixed(2)}%`);
  for (let k = 0; k < features.length; k++) {
    const b = truthBefore[k], a = truthAfter[k];
    console.log(`  ${b.name.padEnd(36)} 대비 ${(b.contrast * 100).toFixed(2)}% → ${(a.contrast * 100).toFixed(2)}%  (보정률 ${((1 - a.contrast / b.contrast) * 100).toFixed(0).padStart(4)}%, 주변 halo p99 ${(a.haloP99 * 100).toFixed(2)}%)`);
  }
}

console.log(`screen ${W}x${H}, burn-in depth ${DEPTH * 100}%, camera PSF σ=${PSF}px, noise ${NOISE * 100}%/cell, vignetting ${VIG}, smoothRadius ${SMOOTH}`);
if (args.only !== 'new') run('현행 b1fa38a', flatOld, targetOld, false);
if (args.only !== 'old') run('수정본 main', flatNew, targetNew, true);

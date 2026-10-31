# 문헌·공개 구현 조사와 현재 main 평가 (2026-10-31)

> 대상: main@274edab (희소 점등 측광 cf4bc42, 색감 맞추기 ff5c596/274edab).
> 수치는 모두 synthetic이며 **실기기(S25 Edge 측정 → Tab S2 대상) 미검증**이다.
> 이전에 검토한 자료(woled-banding-compensation, BaSiCPy, SPECTACLE, Android camera-samples, OpenCV, SEMU/SEMI D31, Tianma SID P-75)는 반복하지 않았다.

## 한 줄 결론

문헌 기준으로 측정 쪽(희소 점등, flat-field, 반복 측정)은 산업 de-mura와 같은 방향이다. 하지만 **출력 쪽에 큰 구멍이 하나 있다.** gain을 선형 휘도 비율로 계산한 뒤 sRGB 코드값에 그대로 곱하기 때문에, 실제 감쇠가 약 2.2배가 된다. 저주파는 반복 루프가 되잡지만, 희소 측광 고주파 층은 루프가 다시 보지 못한다. 그래서 −3% 글씨 번인이 **+2.6% 밝은 역상 글씨**로 바뀐다(실험 A). 이 수정이 지금 가장 값싸고 효과가 크다.

## 1. 새로 본 연구·실험 결과

| 자료 | 내용 | 우리에게 주는 기준 |
| --- | --- | --- |
| Heo et al. (Samsung System LSI), *Frequency Decomposition-Based Demura*, SID 2025 Digest 28-3 | mura를 3×3 box low-pass로 고주파(sandy)와 저주파(cloudy)로 나눈다. 고주파는 1×1 맵 하나를 모든 계조에 공유하고, 저주파는 1/32 맵 약 10장을 계조별로 두고 보간한다. 계조 32와 255 사이 상관이 고주파는 R≈0.97~0.99, 저주파는 R≈0.19~0.22. 12bit/채널 출력, 균일도 98.5%. | 우리 구조(저주파 gain + 고주파 detail 층 곱)와 같은 분해다. 단, **저주파는 계조마다 다르다**는 실측이 있다. 지금은 gray70·gray25 감쇠를 0.35로 섞는다. 로드맵의 "회색 계조 LUT"를 저주파 층에 먼저 적용하라는 근거다. 고주파 층은 단일 계조로도 된다는 근거이기도 하다. |
| Radiant Vision Systems, *Fractional Pixel Method* (Display Week 2020 발표, 백서) | 29MP 카메라, 화면 1px당 센서 약 2.5~2.7px. 픽셀마다 무게중심을 찾고, 소수 면적 가중으로 ROI를 적분한다. 고해상도 기준 측정과 더 잘 맞는다(정량 수치는 공개 페이지에 없음). 생산 라인은 takt time 때문에 "N픽셀마다 점등" 다중 촬영을 피하고 단일 촬영을 쓴다. | `SparseSpotPhotometry`의 무게중심 정합과 소수 경계 창 적분은 같은 방식이다. 우리는 시간 제약이 약하므로 희소 다중 촬영이 정당하다. 산업계는 비용 때문에 피할 뿐, 정확도 면에서 피하는 것은 아니다. |
| Cai, Wanat(LG), Mantiuk, *CameraVDP*, SIGGRAPH Asia 2025 ([코드](https://github.com/gfxdisp/CameraVDP), MIT) | 카메라로 화면을 측정하는 파이프라인: RAW(pixel-shift)와 HDR 병합, 노이즈 모델, MTF 역보정(Wiener), 비네팅, 왜곡 보정, ArUco 호모그래피, RGB→XYZ. 비네팅은 적분구 없이 **균일 보정된 디스플레이를 defocus해서** 4위치 × 3노출로 찍는다. 색 보정 평균 ΔE00 0.31, 재투영 오차 0.315→0.018px. 2px 이상 결함은 PR-AUC≈1. 디스플레이 1대로만 검증했다. | (1) RAW 기반이 기준이다. 우리는 YUV에 x^2.2를 쓴다. (2) flat-field를 "**A급 기준 기기 화면을 defocus 촬영**"해서 얻는 방법은 세 번째 기기가 있는 우리 구성에 그대로 맞는다. 단일 촬영 방사형 근사의 한계(dual-view 결론)를 우회한다. (3) 측정값에 불확실성을 붙여 VDP로 "보이는가"를 판정하는 구조는 합격 지표 개선의 참고가 된다. |
| Mantiuk et al., ColorVideoVDP ([코드](https://github.com/gfxdisp/ColorVideoVDP), MIT, `pip install cvvdp`) | 화면 크기, 밝기, 시청거리를 넣으면 test/ref 영상의 가시 차이를 JOD로 낸다. PyTorch 기반. | PC 평가 하네스에서 보정 후 측정 영상과 균일 기준을 비교해 "보이는 잔차"를 수치화할 수 있다. 앱 내부 판정용은 아니다. |
| Nixon et al., *Device specific calibration for smartphone colorimetry*, CIC 27 (2019) | 같은 모델 폰끼리도 기기별 보정이 필요하다. 메타데이터 기반(dcraw 포함) 보정보다 컬러 카드 보정이 낫다. | 색감 맞추기는 같은 카메라로 기준·대상을 모두 재므로 카메라 절대색 오차가 상쇄된다. 이 설계가 옳다. 단, metamerism은 남는다(코드 주석에 이미 명시). |
| Kirchner et al., *Improving Accuracy of Color Reproduction on Mobile Displays*, LIM 2024 | iPhone XS Max OLED에 디스플레이 특성 + 주변광 모델(MDCIM)을 적용했다. 심리물리 일치율이 3.1%→85.9%로 올랐다. | 색 매칭 평가에 **주변광 조건**이 크게 작용한다. 암실 측정값과 실사용 사이의 간극을 기록해야 한다. |
| CCMX ([Youfffeng/CCMX](https://github.com/Youfffeng/CCMX), CC0) | 카메라(고정 렌즈) × 디스플레이 조합별 3×3 RGB→XYZ 행렬 커뮤니티 DB. 기준 측정기가 필요하다. 가법이 아닌 패널(WOLED/QD-OLED)은 근사로 표시한다. | 기준 측정기가 없으면 쓸 수 없다. 측색계를 빌릴 수 있다면 S25 Edge × Tab S2 행렬을 만들어 절대 ΔE를 낼 수 있다. |
| Qualcomm US12046192B2, *Adaptive subsampling for demura* (2024) | 서브픽셀별 오프셋은 엔트로피가 높아 DSC 압축에 깨진다. 2×2 블록을 기본으로 블록마다 분할을 적응적으로 고른다. | 우리 출력은 앱 비트맵이라 DSC와 무관하다. 다만 "1×1 고주파 층"이 패널 압축 경로에서 깨질 수 있다는 점은 기록해 둔다. |
| HP 방어 공개 TDCommons 6124 (2023), IGNIS AOIS (2021) | HP: 픽셀 사용 이력을 누적하고 감쇠 곡선으로 예측해 보상한다(카메라 없음). IGNIS: 수리점용 광학 검사기와 MaxLife 보상(정확도 비공개). | 사용 이력 기반 예측은 구형 기기에서 이력이 없어 쓸 수 없다. 카메라 측정이 맞는 선택이다. IGNIS가 상용 선례지만 공개된 비교 수치는 없다. |

### 공개 데이터셋

- 카메라로 잰 **휘도 보정된(radiometric) 번인 맵 공개 데이터셋은 찾지 못했다.** 공개된 mura 데이터(Roboflow "LCD display defect detection" 등)는 결함 분류·검출용 일반 사진이다. 정답 휘도가 없어 보정 알고리즘 평가에 쓸 수 없다.
- CameraVDP 저자도 RAW 100GB 이상을 썼다고 밝혔지만 데이터는 공개하지 않았다.
- 그래서 문헌의 측정 조건(계조 상관, 출력 비트 깊이, RAW 대 처리 영상)을 synthetic으로 재현했다(아래 2절).

### GitHub 구현과 라이선스

| 저장소 | 라이선스 | 쓸모 |
| --- | --- | --- |
| gfxdisp/CameraVDP | MIT | 비네팅(defocus 디스플레이), MTF, HDR/불확실성 파이프라인 참고. 코드 재사용 가능(고지 필요). |
| gfxdisp/ColorVideoVDP (`cvvdp`) | MIT | PC 평가 하네스의 지각 지표. |
| Youfffeng/CCMX | CC0 (파일별 metadata.license 확인) | 카메라→XYZ 행렬 포맷. |
| vinser/burnfix | 미확인(저장소 직접 확인 필요) | 화면 결함 확인과 잔상 완화 앱. 카메라 측정 근거는 보이지 않는다. 참고 가치가 낮다. |
| Samsung SID 2025, Radiant 백서, Qualcomm 특허 | 논문/특허 | 코드 없음. 특허 범위는 docs/research-and-rights.md 원칙대로 다룬다. |

GPL 코드는 이번에 새로 찾지 못했다. 특허 문헌(Qualcomm, HP, 각종 aging compensation)은 아이디어만 참고하고 구현을 베끼지 않는다.

## 2. 현재 main 평가 (`sim_pipeline_eval.py`, 전체 표는 `results.md`)

기존 research/ 시뮬레이션은 모두 두 가지를 가정했다. ① gain을 휘도에 바로 곱한다. ② 카메라 영상을 정확히 선형으로 되돌린다. 실제 코드는 다르다.

- `Analyzer.gainGrid`/`refineGain`과 `SparseSpotPhotometry.detailGain`은 **선형 휘도 비율**로 gain을 만든다.
- Target `PatternView`(solid)는 그 gain을 **8비트 sRGB 코드값에 곱하고 반올림**한다. 오버레이는 알파 블렌딩, 즉 코드 공간 곱이다. dither는 없다. `docs/validation.md`에 한계로 적혀 있지만, 수치화한 적은 없다.
- Scanner는 Camera2 `TONEMAP_MODE`를 지정하지 않고 YUV를 x^2.2로 되돌린다.

이번 평가는 이 세 가지를 앱 수식(gainGrid, refineGain α=0.3, 24회, 합격 RMS 1.5%/P95 3.5%/블록 2%, 반경 2 스무딩, detailRatio의 Wiener 수축, detailReference, compensateDetail) 그대로 넣어 돌렸다.

### A. 출력 전달함수와 양자화가 가장 크다

보정 전: 상태바 띠 −4%(4.3 JND), 2px 글씨 −3%, 저주파 얼룩 −2%. 카메라는 이상적 선형으로 둔다.

| 출력 방식 | gray70 띠 | 띠 JND | gray70 획 | gray25 띠 | gray25 획 |
| --- | --- | --- | --- | --- | --- |
| **현행**: gain × 코드, 반올림 | +0.64% | 0.7 | **+2.57%** | −0.04% | **+1.75%** |
| gain^(1/γ) × 코드, 반올림 | −0.11% | 0.1 | −0.31% | −0.35% | +0.48% |
| gain^(1/γ) × 코드 + Floyd–Steinberg dither | +0.07% | 0.1 | −0.07% | +0.07% | −0.05% |
| 이상적(연속 휘도 곱) | +0.08% | 0.1 | +0.02% | +0.08% | +0.02% |

- 첫 반복 직후 현행 방식은 띠가 +5.0%, 획이 +3.8%다(둘 다 역상). 저주파 띠는 루프가 2~3회 만에 되잡는다.
- **획은 되잡지 못한다.** 고주파 층은 한 번 계산해 고정하고, 루프 재측정은 `compensateDetail`로 그 *의도한* 효과를 나눠 지우기 때문이다. 고주파 층만 적용해도 획이 −3%에서 +2.9%가 된다. 즉 희소 측광 연구(cf4bc42)에서 얻은 "획 잔차 −0.01%"는 실제 출력 경로에서는 **역상 +2.6~2.9%**가 된다.
- 고칠 방법: 감쇠를 선형 휘도로 계산하고, 코드에는 `gain^(1/γ)`를 곱한다. γ는 상수 2.2 대신 색감 모듈이 이미 재는 회색 램프(`ColorMatchModel`의 채널 곡선 f_k)의 역함수를 쓰는 것이 더 낫다. 오버레이 알파도 같은 변환을 거쳐야 한다(alpha = 1 − gain^(1/γ)).
- dither는 gray25(코드 64)에서 효과가 크다. 코드 1단계가 휘도로 약 3.4%(gray70에서는 약 1.2%)라서, 반올림만으로는 2~4% 보정이 0 아니면 3.4%로 갈린다. 산업 de-mura가 10~12bit와 dither를 쓰는 이유가 이것이다(Samsung SID 2025는 12bit/채널).
- 덧붙여, "현행 + dither"가 띠 +1.7%(1.8 JND)에서 멈춘 것은 합격 기준 블록 2%가 SEMU 기준 약 2 JND까지 통과시키기 때문이다. 블록 기준을 JND 단위(상태바 기준 약 0.9%)로 바꾸는 것은 이미 제안된 방향이며, 이번 결과도 그 근거가 된다.

### B. 카메라 톤커브: 저주파는 무해하지만 고주파 층은 민감하다

- gray70 반복 루프에서는 RAW, 선형 톤커브, γ2.2, sRGB, S-curve 근사 모두 1회 만에 합격했다(최종 띠 −0.24~+0.07%). closed loop가 톤커브 오차를 흡수한다.
- **희소 점 측광은 다르다(B2).** 검은 바탕의 작은 점은 톤커브의 어두운 쪽(toe)에 걸린다. −3% 점의 측정 깊이는 다음과 같다(피크 노출 0.15~0.9).
  - sRGB 곡선이면 −3.0~−3.35%다.
  - 중간톤 대비가 강한 기본 톤맵 근사면 **−3.1~−4.5%**다.
- 이 오차는 루프가 보정하지 못한다(A와 같은 이유). Camera2에서 `TONEMAP_MODE_CONTRAST_CURVE` 선형 곡선이나 `GAMMA_VALUE`를 지정하는 것이 우선이고, 가능하면 RAW_SENSOR를 쓴다. S25 Edge 지원 여부는 앱 진단 로그로 확인해야 한다.
- 실제 Samsung ISP의 local tone mapping은 이 모델에 없다. 장면 의존적이라 오차가 더 클 수 있다.
- 참고로 Stack Overflow 사례(Sony XA1)에서는 선형 곡선을 지정해도 YUV Y가 203/255 이상에서 꺾였고, RGB 변환 후에는 선형이었다. 그러니 노출을 Y 상한 아래로 둔다.

### C. 계조 의존 번인

두 열화 모델을 각각 gray70에서 −4%가 되도록 맞췄다.

- **효율 저하형**(모든 계조에서 같은 비율): 어떤 맵이든 잔차 0이다.
- **TFT Vth 이동형**(어두운 계조일수록 손실이 큼): 계조별 잔차는 아래와 같다.

| 맵 | 코드 26 | 64 | 128 | 179 | 255 |
| --- | --- | --- | --- | --- | --- |
| gray70 단일 | −22.7% | −7.3% | −1.6% | 0 | +1.2% |
| gray70·25 감쇠 혼합 0.35(현행) | −20.6% | −4.8% | +0.9% | **+2.6%** | +3.9% |
| gray25·70 계조 LUT 보간 | −14.4% | 0 | +1.6% | 0 | −3.0% |

- 현행 혼합은 양쪽 계조 모두에서 틀린다. 특히 밝은 화면에서 역상(+2.6~3.9%)이 된다. 계조별 저주파 맵(Samsung 방식)이 방향이 맞다.
- 다만 2개 계조로는 어두운 쪽(코드 26)을 못 덮는다. gray10을 측정하거나, 어두운 계조는 보정을 끄고 한계로 명시해야 한다.
- 실제 앱은 두 계조를 모두 재측정하며 반복하므로, 이 정적 계산보다는 완화된다.
- 어느 모델이 Tab S2에 맞는지는 **실측으로만** 알 수 있다. 같은 위치를 gray10/25/50/70/100으로 재서 상대 깊이가 일정한지 보면 된다.

### D. R/G/B 차등 열화 (글씨 획)

- 청색이 더 빨리 열화하면(R/G/B −2/−3/−6%), 휘도만 맞추는 현행 고주파 층은 휘도 잔차를 0으로 만든다. 그래도 **획 ΔE00 1.37**이 남는다. 청색 −10%면 3.10이다.
- 채널별 층이면 0이다. 저주파에는 RGB30 채널 gain이 있지만, 고주파(희소) 층은 gray뿐이다.
- red/green/blue 희소 패턴을 추가하면 측정 시간이 3배가 된다. 우선 gray 희소 측광 뒤에 획 위치에서만 RGB 비율을 재는 절충안이 가능하다.

### E. PenTile RGBG (Tab S2)

- Tab S2는 다이아몬드 PenTile이다. R/B 서브픽셀은 논리 픽셀 2개에 하나꼴이고, 서브픽셀 렌더링(SPR)이 구동한다.
- 1D 단순 모델에서 논리 픽셀 단위 휘도 gain은 G와 R을 맞추고 휘도 잔차를 −0.1%로 줄였다. 하지만 B 채널은 획에서 −1.4%가 남는다. 2px 획과 R/B 격자의 위상이 맞지 않기 때문이다.
- 즉 휘도는 거의 맞아도 획 가장자리에 색 줄무늬가 남을 수 있다.
- 실제 SPR 필터는 비공개이고 패턴마다 다르다(Korea Univ.의 patch 기반 adaptive SPR 연구 참고). 실기기에서 단일 픽셀 점등의 서브픽셀 발광을 S25 Edge 메인 카메라 근접 촬영으로 먼저 확인할 필요가 있다.

## 3. 권장 순서

1. **출력 선형화**(A): Target에서 코드 = 기준 코드 × gain^(1/γ). 오버레이 알파 = 1 − gain^(1/γ). γ는 회색 램프 실측 곡선에서 가져온다. 작고 확실한 수정이며, 희소 측광 결과를 살리는 전제 조건이다.
2. **출력 dither**(A): solid 패턴과 오버레이 비트맵에 오차 확산이나 blue-noise dither를 넣는다. gray25 이하에서 필수다.
3. **카메라 선형화**(B2): `TONEMAP_MODE_CONTRAST_CURVE`(선형)를 지정하고 실제 적용 여부를 CaptureResult로 확인한다. 그다음 RAW 경로를 쓴다. 고주파 층에 먼저 적용한다.
4. **고주파 층 closed-loop 검증**: 희소 측광을 보정 후 한 번 더 돌려 획 잔차를 직접 잰다. A와 B2 같은 개방 루프 오차를 잡는 유일한 방법이다(+1~2분).
5. **계조별 저주파 맵**(C): gray10/25/50/70/100 실측으로 열화 모델을 먼저 확인한 뒤 LUT로 간다.
6. **flat-field**: A급 기준 기기 화면을 defocus 촬영하는 CameraVDP 방식을 시험한다.
7. **합격 지표**: 블록 2%를 SEMU JND 단위로 바꾼다. 오프라인 평가에는 ColorVideoVDP를 쓴다.

## 재현

```bash
cd research/literature-eval
python3 sim_pipeline_eval.py > results.md      # 약 6초, numpy만 필요
python3 sim_pipeline_eval.py a b2              # 실험 일부만
```

## 출처

- Heo, Lee, Na, Lim, "Frequency Decomposition-Based High-Performance Demura Processing with Low Memory Cost", SID 2025 Digest — https://sid.org/Portals/sid/Files/SID-Digest/docs/28-3.pdf
- Radiant Vision Systems, "Inspecting and Correcting Displays as Resolution Goes Up" — https://www.radiantvisionsystems.com/blog/inspecting-and-correcting-displays-resolution-goes-method-improved-pixel-level-measurement
- Cai, Wanat, Mantiuk, "CameraVDP", arXiv 2509.08947 — https://arxiv.org/abs/2509.08947v1 , https://www.cl.cam.ac.uk/research/rainbow/projects/cameravdp/ , https://github.com/gfxdisp/CameraVDP
- ColorVideoVDP — https://github.com/gfxdisp/ColorVideoVDP
- Nixon et al., CIC 2019 — https://library.imaging.org/cic/articles/27/1/art00010
- Kirchner et al., LIM 2024 — https://library.imaging.org/lim/articles/5/1/25
- CCMX — https://github.com/Youfffeng/CCMX
- Qualcomm US12046192B2 — https://patents.google.com/patent/US12046192
- HP TDCommons 6124 — https://www.tdcommons.org/dpubs_series/6124
- IGNIS AOIS — https://www.oled-info.com/ignis-launches-automated-inspection-machine-and-software-fix-smartphone-burn
- Camera2 YUV 선형성 사례 — https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/63154956/camera2-pixel-values-not-linear-to-sensor-exposure-time
- Radiant JND/mura — https://cet.mydigitalpublication.co.uk/articles/spot-the-difference
- Diamond PenTile SPR — https://scholar.korea.ac.kr/handle/2021.sw.korea/127810
- Roboflow LCD defect dataset — https://universe.roboflow.com/cp-uwsgu/lcd-display-defect-detection

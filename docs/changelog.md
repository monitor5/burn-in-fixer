# Changelog

## Unreleased

### Fixed (노이즈·줄무늬·무아레·플리커링)
- 측정 노출을 대상 화면 주사 주기의 정수배로 양자화(flicker-sync)하고 ISO로 밝기를 보상.
  롤링셔터 각 행이 완전한 리프레시/PWM 사이클을 적분해 가로 밴딩·플리커가 원천 상쇄된다.
  대상 앱 HELLO 응답에 `screen.refreshRate`를 추가해 실제 주사율을 사용한다.
- YUV 다운샘플 디코드를 N픽셀 건너뛰기 점 샘플링에서 N×N 블록 평균(저역 통과)으로 교체.
  센서-서브픽셀 간섭 무아레의 앨리어싱 증폭을 제거하고 노이즈도 1/N로 감소한다.
  gray 경로는 선형 도메인에서 평균한다.
- 프레임 간 촬영 딜레이(1200ms)가 60Hz 주기의 정확히 72배라 잔여 롤링 밴드가 매 프레임
  같은 위치에 반복되던 문제를 프레임별 위상 지터(0/7/23/41/11ms)로 해소.
- 프레임 스택을 4장 이상일 때 픽셀별 최소/최대 제외 절사 평균으로 변경 — 밴드가 남은
  프레임이 평균을 오염시키지 않는다.
- gain 맵 스무딩 반경을 '카메라 1픽셀이 차지하는 네이티브 그리드 셀 수'에 맞춰 적응 조정.
  고정 3×3 블러로는 네이티브 1:1 맵에서 카메라 픽셀 스케일의 노이즈·무아레가 보정맵에
  그대로 새겨져 화면에 줄무늬/얼룩으로 표시됐다. 박스 블러는 분리형 슬라이딩 윈도로 O(w·h) 유지.
- AE 수렴 단계 프리뷰에 안티밴딩 AUTO 적용.

### Added
- Development log started for the reference-device color calibration work.
- Added target-device roles: `adjustment` for the device being corrected and `reference` for the uncorrected comparison display.
- Added a separate target-side white-balance layer so RGB white balance is applied on top of the existing burn-in correction instead of replacing it.
- Added scanner dual-device connection UI for adjustment/reference targets.
- Added a scanner white-balance calibration activity that compares the uncorrected reference display against the adjustment display with burn-in correction enabled, then uploads a separate RGB white-balance layer.

### Changed
- Reference-role target devices reject correction-map, white-balance, and overlay commands and force correction off when showing patterns.
- Reference-role pattern screens also force correction off for manual pattern previews and tap toggles.

### Verified
- Built `burn-in-fixed` with `:app:assembleDebug`.
- Built and tested `burn-in-camera` with `:app:testDebugUnitTest :app:assembleDebug`.

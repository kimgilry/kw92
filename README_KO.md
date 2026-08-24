# CoinBot Second Engine 데모

실제 주문을 전혀 하지 않는 모의투자 전용 Android 데모입니다.

## 동작
- Upbit 공개 WebSocket `trade` 스트림에서 KRW-BTC / KRW-ETH 체결을 수신
- 매 1초 가격/체결량 샘플 저장
- 5초마다 초단기 + 1분 + 5분 흐름을 조합해 엔진 점수 재계산
- 10초마다 목표 자금 비중에 맞춰 모의 매수/매도
- 1초 급변 또는 거래량 급증 시 5초/10초를 기다리지 않고 즉시 재평가
- BTC/ETH 둘 다 양호하면 점수 비례 분산
- 한쪽만 양호하면 해당 코인에 집중
- 둘 다 약하면 현금 비중 확대
- 수수료 0.05%/편도, 가상 슬리피지 0.02%/편도 반영
- 5초 이내 재거래 금지 + 최소 재배분 금액으로 과매매 완화

## 백그라운드
Android Foreground Service로 실행되므로 화면을 끄거나 홈 화면으로 나가도 일반 HTML보다 훨씬 안정적으로 실행됩니다.
상태바 알림에 LIVE/RECONNECT와 BTC·ETH 가격 및 배분 상태가 표시됩니다.

단, 휴대폰 제조사 절전 정책, 강제 종료, 네트워크 절전/Doze에 의해 WebSocket이 끊길 수 있습니다.
앱은 연결 종료 시 지수 백오프로 자동 재연결합니다. "강제 종료"한 앱을 임의로 다시 살릴 수는 없습니다.

이 데모는 targetSdk 34로 구성했습니다. Android 15+ target에서 `dataSync` foreground service는
백그라운드 누적 6시간/24시간 제한이 있으므로 장시간 상시 서비스로 발전시키려면 서버 기반 스트리밍/푸시 구조를 검토해야 합니다.

## APK 만들기
GitHub 저장소에 이 프로젝트 전체를 업로드하면 Actions의
`Build CoinBot Demo APK`가 자동 실행됩니다.

Actions → 해당 실행 → Artifacts → `CoinBot-SecondEngine-Demo-APK` 다운로드.

## 주의
- 모의투자 연구용입니다.
- API 키가 없으며 실제 주문 코드도 없습니다.
- 초단위 판단이 수익을 보장하지 않습니다.

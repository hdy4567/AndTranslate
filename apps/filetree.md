apps/android/
├── src/main/
│   ├── kotlin/com/viitor/
│   │   ├── service/
│   │   │   ├── SubtitleCaptureService.kt          # ★ Foreground Service 메인 컨트롤러
│   │   │   │                                      # - startForeground(NOTIFICATION_ID=1001)
│   │   │   │                                      # - MediaProjectionCapturer 생명주기 관리
│   │   │   │                                      # - SilenceEvent 수신 → WorkManager 트리거
│   │   │   └── AudioProcessingWorker.kt           # ★ STT 백그라운드 처리 (WorkManager)
│   │   │                                          # - CoroutineScope(Dispatchers.Default)
│   │   │                                          # - ProvisionalUseCase (단일 청크)
│   │   │                                          # - FinalUseCase (배치 4개)
│   │   ├── ui/
│   │   │   ├── MainActivity.kt                    # ★ 진입점: FAB 토글 + 권한 체크
│   │   │   │                                      # - checkPermissions() → onboarding 분기
│   │   │   │                                      # - FAB onClick → startService()
│   │   │   ├── SubtitleOverlayManager.kt          # ★ WindowManager 오케스트레이터
│   │   │   │                                      # - addView(TYPE_APPLICATION_OVERLAY)
│   │   │   │                                      # - showProvisional() / replaceWithFinal()
│   │   │   ├── SubtitleOverlayView.kt             # ★ 최종 자막 View (alpha=0.9, 검은 배경)
│   │   │   ├── ProvisionalSubtitleView.kt         # ★ 임시 자막 View (회색, 점선 테두리)
│   │   │   └── PiPController.kt                   # ★ PIP 진입/종료 관리
│   │   │                                          # - enterPiP(aspectRatio=Rational(16,9))
│   │   └── permission/
│   │       └── PermissionOnboardingActivity.kt    # ★ 3단계 권한 가이드 (마이크→오버레이→PIP)
│   │
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml                  # FAB + Material Design 3
│   │   │   ├── overlay_subtitle.xml               # TextView (Roboto 18sp, maxLines=3)
│   │   │   └── provisional_subtitle_overlay.xml   # TextView (이탤릭, 회색, 점선 테두리)
│   │   ├── values/
│   │   │   ├── themes.xml                         # DayNight 테마
│   │   │   └── strings.xml
│   │   └── drawable/
│   │       └── bg_provisional_border.xml          # 점선 테두리 drawable
│   │
│   ├── assets/
│   │   ├── whisper-tiny-ko.tflite                 # 한국어 모델 (40MB)
│   │   ├── whisper-tiny-ja.tflite                 # 일본어 모델 (40MB)
│   │   ├── vosk-model-small-ko-0.22.zip           # Fallback 모델 (45MB)
│   │   └── argos-translate-ko-ja.argosmodel       # 번역 모델
│   │
│   └── AndroidManifest.xml                        # ★ 권한 선언
│       # <uses-permission android:name="android.permission.RECORD_AUDIO" />
│       # <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
│       # <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
│       # <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
│       # <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
│       # <service android:foregroundServiceType="microphone|mediaProjection" />
│       # <activity android:supportsPictureInPicture="true" />
│
├── src/androidTest/kotlin/
│   └── SubtitleCaptureServiceTest.kt              # Instrumented Test (실제 AudioRecord 모킹)
│
└── build.gradle.kts                               # ★ dependencies
    # implementation("androidx.work:work-runtime-ktx:2.9.0")
    # implementation("org.tensorflow:tensorflow-lite:2.14.0")
    # implementation("com.alphacephei:vosk-android:0.3.47")
    # implementation("com.google.android.material:material:1.11.0")


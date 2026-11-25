package com.viitor.util

import com.viitor.R

/**
 * [ResourceBroker] - Central Resource Authority (Aura Capsule V5)
 * 리소스 참조 시 발생하는 오타 및 런타임 에러를 방지하고 
 * 파일 간의 하드코딩된 R 참조 의존성을 중앙 집중화합니다.
 */
object ResourceBroker {

    // --- Layouts ---
    val layoutMain = R.layout.activity_main
    val layoutBrowser = R.layout.fragment_browser
    val layoutScript = R.layout.fragment_script
    val layoutWords = R.layout.fragment_words
    val layoutLens = R.layout.fragment_lens
    val layoutPremiumQuiz = R.layout.dialog_premium_quiz

    // --- Core Navigation IDs ---
    val idBottomNav = R.id.bottom_nav_view
    val idMainContainer = R.id.main_nav_container
    
    val navBrowser = R.id.nav_browser
    val navScript = R.id.nav_script
    val navWords = R.id.nav_words
    val navLens = R.id.nav_lens

    // --- Browser/WebView IDs ---
    val idWebView = R.id.youtube_web_view
    val idOverlayToggle = R.id.btn_overlay_toggle

    // --- Script/Vault IDs ---
    val idScriptList = R.id.rv_script_list
    val idScriptLoading = R.id.pb_script_loading
    val idScriptEmpty = R.id.tv_script_empty_placeholder
    val idScriptItemTime = R.id.tv_item_time
    val idScriptItemFlex = R.id.flex_item_jp
    val idScriptItemKo = R.id.tv_item_ko

    // --- Words/Vault IDs ---
    val idWordsList = R.id.rv_words
    val idQuizBtn = R.id.btn_start_quiz
    val idWordCount = R.id.tv_word_count
    val idWordJp = R.id.tv_word_jp
    val idWordKo = R.id.tv_word_ko

    // --- Quiz IDs ---
    val idQuizWord = R.id.tv_quiz_word
    val idQuizContext = R.id.tv_quiz_context
    val idQuizMeaning = R.id.tv_quiz_meaning
    val idQuizCanvas = R.id.quiz_canvas
    val idQuizNext = R.id.btn_quiz_next
    val idQuizRoot = R.id.quiz_card_root
    val idQuizClearCanvas = R.id.btn_quiz_clear_canvas
    val idQuizTts = R.id.btn_quiz_tts
    val idQuizGradeLayout = R.id.layout_quiz_grade
    val idQuizKnow = R.id.btn_quiz_know
    val idQuizRetry = R.id.btn_quiz_retry

    // --- Lens IDs ---
    val idLensPreview = R.id.preview_view
    val idLensCapture = R.id.btn_capture_ocr

    // --- Styles ---
    val stylePremiumDialog = com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert
}

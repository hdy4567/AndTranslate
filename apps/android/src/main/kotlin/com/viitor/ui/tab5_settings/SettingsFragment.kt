package com.viitor.ui.tab5_settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.viitor.R
import com.viitor.pipeline.core.GlobalSubtitleRelay
import com.viitor.util.ResourceBroker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * [SettingsFragment] - 독립 격리된 5번째 설정 탭 (기본 코드 오염 방지)
 * 앱의 전역 설정(스크롤 동기화, 언어 모드, 발음 레이어, 섀도잉 정지, 단어장 하이라이트)을 중앙 집중 관리합니다.
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(ResourceBroker.layoutSettings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("viitor_settings_prefs", Context.MODE_PRIVATE)

        // 1. 대본(2탭) 자동 스크롤 동기화 스위치
        val switchAutoScroll = view.findViewById<MaterialSwitch>(R.id.switch_script_autoscroll)
        switchAutoScroll?.isChecked = GlobalSubtitleRelay.isScriptAutoScrollEnabled
        switchAutoScroll?.setOnCheckedChangeListener { _, isChecked ->
            GlobalSubtitleRelay.isScriptAutoScrollEnabled = isChecked
            val scriptPrefs = requireContext().getSharedPreferences("viitor_script_prefs", Context.MODE_PRIVATE)
            scriptPrefs.edit().putBoolean("script_auto_scroll", isChecked).apply()
            val msg = if (isChecked) "대본 자동 스크롤 동기화 켜짐" else "대본 자동 스크롤 동기화 꺼짐 (스크롤 고정)"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 2. 번역 언어 모드 라디오 그룹
        val rgMode = view.findViewById<RadioGroup>(R.id.rg_translation_mode)
        val rbJaKo = view.findViewById<RadioButton>(R.id.rb_mode_ja_ko)
        val rbEnKo = view.findViewById<RadioButton>(R.id.rb_mode_en_ko)

        if (GlobalSubtitleRelay.translationMode == GlobalSubtitleRelay.TranslationMode.EN_TO_KO) {
            rbEnKo?.isChecked = true
        } else {
            rbJaKo?.isChecked = true
        }

        rgMode?.setOnCheckedChangeListener { _, checkedId ->
            val overlayPrefs = requireContext().getSharedPreferences("viitor_overlay_prefs", Context.MODE_PRIVATE)
            if (checkedId == R.id.rb_mode_en_ko) {
                GlobalSubtitleRelay.translationMode = GlobalSubtitleRelay.TranslationMode.EN_TO_KO
                overlayPrefs.edit().putString("translation_mode_code", "EN_KO").apply()
                Toast.makeText(requireContext(), "🇺🇸 영어 ➔ 🇰🇷 한국어 번역 적용", Toast.LENGTH_SHORT).show()
            } else {
                GlobalSubtitleRelay.translationMode = GlobalSubtitleRelay.TranslationMode.JA_TO_KO
                overlayPrefs.edit().putString("translation_mode_code", "JA_KO").apply()
                Toast.makeText(requireContext(), "🇯🇵 일본어 ➔ 🇰🇷 한국어 번역 적용", Toast.LENGTH_SHORT).show()
            }
            GlobalSubtitleRelay.requestRepair()
        }

        // 3. 발음(3레이어) 표시 스위치
        val switchPron = view.findViewById<MaterialSwitch>(R.id.switch_pron3layer)
        switchPron?.isChecked = GlobalSubtitleRelay.isPron3LayerEnabled
        switchPron?.setOnCheckedChangeListener { _, isChecked ->
            GlobalSubtitleRelay.isPron3LayerEnabled = isChecked
            prefs.edit().putBoolean("pron3layer_enabled", isChecked).apply()
            val msg = if (isChecked) "3레이어 발음 표시 켜짐" else "3레이어 발음 표시 꺼짐"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 4. 내 단어장 형광펜 하이라이트 스위치
        val switchVocab = view.findViewById<MaterialSwitch>(R.id.switch_vocab_highlight)
        switchVocab?.isChecked = GlobalSubtitleRelay.isVocabHighlightEnabled
        switchVocab?.setOnCheckedChangeListener { _, isChecked ->
            GlobalSubtitleRelay.isVocabHighlightEnabled = isChecked
            prefs.edit().putBoolean("vocab_highlight_enabled", isChecked).apply()
            GlobalSubtitleRelay.onVocabUpdated?.invoke()
            val msg = if (isChecked) "단어장 형광펜 강조 켜짐" else "단어장 형광펜 강조 꺼짐"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 5. 섀도잉 1.5초 따라 말하기 일시정지 스위치
        val switchShadow = view.findViewById<MaterialSwitch>(R.id.switch_shadow_pause)
        switchShadow?.isChecked = GlobalSubtitleRelay.loopConfig.value.shadowPauseEnabled
        switchShadow?.setOnCheckedChangeListener { _, isChecked ->
            val cur = GlobalSubtitleRelay.loopConfig.value
            if (cur.shadowPauseEnabled != isChecked) {
                GlobalSubtitleRelay.toggleShadowPause()
            }
            prefs.edit().putBoolean("shadow_pause_enabled", isChecked).apply()
            val msg = if (isChecked) "섀도잉 따라말하기 일시정지 (1.5초) 켜짐" else "섀도잉 따라말하기 일시정지 꺼짐"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 6. A-B 구간 반복 상태 및 초기화 버튼
        val tvLoopStatus = view.findViewById<TextView>(R.id.tv_settings_loop_status)
        val btnClearLoop = view.findViewById<MaterialButton>(R.id.btn_settings_clear_loop)

        btnClearLoop?.setOnClickListener {
            GlobalSubtitleRelay.clearAbLoop()
            Toast.makeText(requireContext(), "구간 반복이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            GlobalSubtitleRelay.loopConfig.collectLatest { loop ->
                switchShadow?.isChecked = loop.shadowPauseEnabled
                if (loop.enabled) {
                    val sMin = (loop.startMs / 1000) / 60
                    val sSec = (loop.startMs / 1000) % 60
                    val eMin = (loop.endMs / 1000) / 60
                    val eSec = (loop.endMs / 1000) % 60
                    tvLoopStatus?.text = String.format("🔁 반복 중: %02d:%02d ~ %02d:%02d", sMin, sSec, eMin, eSec)
                    tvLoopStatus?.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    btnClearLoop?.isEnabled = true
                } else {
                    tvLoopStatus?.text = "설정된 반복 구간 없음"
                    tvLoopStatus?.setTextColor(android.graphics.Color.parseColor("#888888"))
                    btnClearLoop?.isEnabled = false
                }
            }
        }
    }
}

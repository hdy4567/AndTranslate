package com.viitor.ui.view.tab2

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

/**
 * @file Tab2ArchiveFragment.kt
 * @description AndTranslate 2번 탭 (보관함/아카이브) 코틀린 프래그먼트
 */

class Tab2ArchiveFragment : Fragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 1. 패킷 리스트 초기화 
        initArchiveList()
    }

    private fun initArchiveList() {
        // [비즈니스 조건] AtArchive (Tab 2) 데이터 영속성 계층 연동
        println("[Tab2ArchiveFragment] Archive List Initialized")
    }
}

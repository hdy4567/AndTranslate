#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <unistd.h>

// @ysig_audit(ticket_id="TASK-901", risk="ERR_NATIVE_LINK_FAILED")
/* 
 * 실제 CTranslate2 및 SentencePiece 엔진 연동
 */
#include "include/ctranslate2/translator.h"
#include "include/sentencepiece_processor.h"

#define LOG_TAG "TranslateDBNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * TranslatorSession: 네이티브 메모리에 유지되는 실제 엔진 인스턴스
 */
struct TranslatorSession {
    ctranslate2::Translator* translator;
    sentencepiece::SentencePieceProcessor* sp;

    // Argos 모델 구조: model_path = .../ko_en/model/
    // sentencepiece.model은 .../ko_en/ 에 위치 (model_path의 부모)
    static std::string findSpmPath(const std::string& model_path) {
        // 후보 경로 우선순위:
        // 1. model_path/sentencepiece.model  (일부 버전)
        // 2. model_path/../sentencepiece.model  (Argos 표준)
        // 3. model_path/source.spm
        // 4. model_path/../source.spm
        std::vector<std::string> candidates = {
            model_path + "/sentencepiece.model",
            model_path + "/../sentencepiece.model",
            model_path + "/source.spm",
            model_path + "/../source.spm",
        };
        for (const auto& c : candidates) {
            if (access(c.c_str(), F_OK) == 0) {
                LOGD("Found SentencePiece model at: %s", c.c_str());
                return c;
            }
        }
        return "";
    }

    TranslatorSession(const std::string& model_path) {
        // ComputeType::AUTO → ARM64: FLOAT16/INT8 자동 선택
        translator = new ctranslate2::Translator(model_path, ctranslate2::Device::CPU, ctranslate2::ComputeType::AUTO);
        sp = new sentencepiece::SentencePieceProcessor();

        const std::string spm_path = findSpmPath(model_path);
        if (spm_path.empty() || !sp->Load(spm_path).ok()) {
            LOGE("Failed to load SentencePiece model. Tried paths near: %s", model_path.c_str());
        } else {
            LOGD("SentencePiece ready.");
        }
    }

    ~TranslatorSession() {
        delete translator;
        delete sp;
    }
};


/**
 * [Native DB Connect]
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_viitor_translate_LocalTranslator_initNativeModel(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, 0);
    LOGD(">>> [DB_CONNECT] Realizing Engine at: %s", path);

    try {
        TranslatorSession* session = new TranslatorSession(path);
        env->ReleaseStringUTFChars(model_path, path);
        return reinterpret_cast<jlong>(session); 
    } catch (const std::exception& e) {
        LOGE("Failed to init translator session: %s", e.what());
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }
}

/**
 * [Native DB Query]
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_viitor_translate_LocalTranslator_translateNative(JNIEnv *env, jobject thiz, jlong model_ptr, jstring text, jstring target_lang) {
    if (model_ptr == 0) return text;
    
    const char *c_text = env->GetStringUTFChars(text, 0);
    const char *c_lang = env->GetStringUTFChars(target_lang, 0);
    
    TranslatorSession* session = reinterpret_cast<TranslatorSession*>(model_ptr);
    
    try {
        // 1. Tokenizing
        std::vector<std::string> tokens;
        session->sp->Encode(c_text, &tokens);
        
        // Argos 모델은 종종 타겟 언어 토큰을 접두사로 요구함
        std::vector<std::vector<std::string>> batch = { tokens };
        
        // 2. Inference (CTranslate2)
        // target_prefix가 없는 경우 일반 번역
        auto results = session->translator->translate_batch(batch);
        
        // 3. Detokenizing
        std::string output;
        session->sp->Decode(results[0].hypotheses[0], &output);
        
        env->ReleaseStringUTFChars(text, c_text);
        env->ReleaseStringUTFChars(target_lang, c_lang);
        
        return env->NewStringUTF(output.c_str());
    } catch (const std::exception& e) {
        LOGE("Translation failed: %s", e.what());
        env->ReleaseStringUTFChars(text, c_text);
        env->ReleaseStringUTFChars(target_lang, c_lang);
        return text; // 실패 시 원본 반환
    }
}

/**
 * [Native DB Disconnect]
 */
extern "C" JNIEXPORT void JNICALL
Java_com_viitor_translate_LocalTranslator_releaseNativeModel(JNIEnv *env, jobject thiz, jlong model_ptr) {
    if (model_ptr == 0) return;
    LOGD(">>> [DB_DISCONNECT] Releasing Engine Session");
    TranslatorSession* session = reinterpret_cast<TranslatorSession*>(model_ptr);
    delete session;
}

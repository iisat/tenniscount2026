package com.tenniscount.app.util

import android.util.Log
import com.tenniscount.app.BuildConfig

/**
 * Логирование приложения. Распознанная речь и текущий счёт — чувствительные
 * данные, поэтому [d]/[i] пишутся только в debug-сборках (release собирается
 * без минификации, поэтому фильтрация здесь, а не ProGuard-правилами).
 * [w]/[e] идут в logcat всегда — это диагностика ошибок без пользовательского
 * текста, не вызывать их с распознанными фразами/счётом.
 */
object AppLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}

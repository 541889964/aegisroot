package com.example.kernelsustyleuikit

import android.app.Application
import android.os.Build
import android.os.UserManager
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale

lateinit var templateApp: TemplateApplication

class TemplateApplication : Application(), ViewModelStoreOwner {

    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        super.onCreate()
        templateApp = this
        if (!isUserUnlocked()) return
        okhttpClient =
            OkHttpClient.Builder()
                .cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .build()
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}

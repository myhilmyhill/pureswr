package myhilmyhill.pureswr

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 必要であれば、ここに追加の初期化コードを記述します
    }
}

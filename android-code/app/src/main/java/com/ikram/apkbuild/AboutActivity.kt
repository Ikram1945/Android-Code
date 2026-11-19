package com.ikram.apkbuild

import android.os.Bundle
import com.ikram.apkbuild.databinding.ActivityAboutBinding

/**
 * Menu "Tentang" (About) — aktivitas pertama yang ditulis Kotlin (awal migrasi
 * Java -> Kotlin). Mewarisi ThemedActivity (Java) untuk palet + mode terang/gelap.
 */
class AboutActivity : ThemedActivity() {

    private var binding: ActivityAboutBinding? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val bind = ActivityAboutBinding.inflate(layoutInflater)
        binding = bind
        setContentView(bind.root)

        bind.btnBack.setOnClickListener { finish() }
        bind.txtVersion.text = "Versi " + appVersion()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding = null
    }

    /** versionName dari PackageInfo; fallback "?" bila tak terbaca. */
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}

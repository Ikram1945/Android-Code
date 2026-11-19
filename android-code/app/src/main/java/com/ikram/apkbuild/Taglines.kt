package com.ikram.apkbuild

import kotlin.random.Random

/**
 * Kumpulan tagline penyemangat untuk layar utama. Dipilih acak.
 * Ditulis Kotlin (lanjutan migrasi Java -> Kotlin).
 */
object Taglines {

    private val ALL = arrayOf(
        "Dibuat dengan cinta by Ikram",
        "Kode kecil hari ini, karya besar nanti.",
        "Build dulu, sempurna kemudian.",
        "Setiap error adalah guru.",
        "Ngoding itu seni, sabar itu kuncinya.",
        "Dari ide jadi APK. Ayo mulai!",
        "Jangan takut error, takutlah berhenti.",
        "Satu baris sehari, proyek pun jadi.",
        "Belajar, bangun, ulangi.",
        "Karyamu, aturanmu."
    )

    /** Pilih satu tagline acak. */
    fun random(): String = ALL[Random.nextInt(ALL.size)]
}

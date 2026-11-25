package com.bosmuda.translatelayar

/** Jenis pengenal teks (OCR) yang dipakai untuk bahasa asal. */
enum class Script { LATIN, CHINESE, JAPANESE, KOREAN }

data class Lang(val label: String, val code: String, val script: Script = Script.LATIN)

object Languages {

    /** Bahasa asal. "auto" memakai pengenal huruf Latin. */
    val SOURCE = listOf(
        Lang("Otomatis (huruf Latin)", "auto"),
        Lang("Inggris", "en"),
        Lang("Indonesia", "id"),
        Lang("Prancis", "fr"),
        Lang("Jerman", "de"),
        Lang("Spanyol", "es"),
        Lang("Mandarin", "zh", Script.CHINESE),
        Lang("Jepang", "ja", Script.JAPANESE),
        Lang("Korea", "ko", Script.KOREAN),
        Lang("Vietnam", "vi")
    )

    /** Bahasa tujuan. */
    val TARGET = listOf(
        Lang("Indonesia", "id"),
        Lang("Inggris", "en"),
        Lang("Prancis", "fr"),
        Lang("Jerman", "de"),
        Lang("Spanyol", "es"),
        Lang("Mandarin", "zh"),
        Lang("Jepang", "ja"),
        Lang("Korea", "ko"),
        Lang("Arab", "ar"),
        Lang("Thailand", "th"),
        Lang("Vietnam", "vi")
    )

    fun byCode(code: String, list: List<Lang>): Lang =
        list.firstOrNull { it.code == code } ?: list.first()
}

package com.danieldk.splatevpn.data

enum class AppUiMode(val id: String) {
    SIMPLE("simple"),
    PRO("pro");

    val isSimple: Boolean get() = this == SIMPLE
    val isPro: Boolean get() = this == PRO

    companion object {
        fun fromId(id: String?): AppUiMode =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: SIMPLE
    }
}

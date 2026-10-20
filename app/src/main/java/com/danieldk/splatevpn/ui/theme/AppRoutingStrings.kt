package com.danieldk.splatevpn.ui.theme

import com.danieldk.splatevpn.data.AppRoutingMode

internal val AppStrings.appRouting get() = AppRoutingStrings(this is RussianAppStrings)

internal class AppRoutingStrings(private val ru: Boolean) {
    val title get() = if (ru) "Приложения" else "Applications"
    val subtitle get() = if (ru) "Укажите, каким приложениям нужен VPN" else "Choose which applications use VPN"
    val modeHeading get() = if (ru) "1. Выберите режим" else "1. Choose a mode"
    val changeMode get() = if (ru) "Изменить режим" else "Change mode"
    fun title(mode: AppRoutingMode) = when (mode) {
        AppRoutingMode.ALL -> if (ru) "Все приложения" else "All applications"
        AppRoutingMode.INCLUDE -> if (ru) "Только выбранные" else "Only selected applications"
        AppRoutingMode.EXCLUDE -> if (ru) "Все, кроме выбранных" else "All except selected applications"
    }
    fun description(mode: AppRoutingMode) = when (mode) {
        AppRoutingMode.ALL -> if (ru) "Для всех приложений действуют правила VPN." else "VPN routing rules apply to every application."
        AppRoutingMode.INCLUDE -> if (ru) "Отмеченные — через VPN. Остальные — напрямую." else "Selected apps use VPN. All others connect directly."
        AppRoutingMode.EXCLUDE -> if (ru) "Отмеченные — напрямую. Остальные — через VPN." else "Selected apps connect directly. All others use VPN."
    }
    fun pickerHeading(mode: AppRoutingMode) = if (mode == AppRoutingMode.INCLUDE) {
        if (ru) "2. Отметьте приложения для VPN" else "2. Select applications for VPN"
    } else if (ru) "2. Отметьте приложения без VPN" else "2. Select applications to bypass VPN"
    fun selected(count: Int) = if (ru) "Отмечено: $count" else "Selected: $count"
    fun destination(vpn: Boolean) = if (vpn) {
        if (ru) "По правилам VPN" else "VPN routing rules"
    } else if (ru) "Без VPN · напрямую" else "Without VPN · direct"
    val rulesNote get() = if (ru) "«Через VPN» учитывает режим на главной: в умном режиме часть сайтов идёт напрямую." else "VPN apps follow the mode on Home. Smart routing can send some sites directly."
    val allNote get() = if (ru) "Выбирать приложения по одному не нужно. Для исключений выберите «Все, кроме выбранных»." else "No need to select apps individually. To add exceptions, choose “All except selected applications”."
    val emptyInclude get() = if (ru) "Отметьте хотя бы одно приложение или выберите «Все приложения»." else "Select at least one application or choose “All applications”."
    val emptyExclusions get() = if (ru) "Исключений нет — VPN будет доступен всем приложениям." else "No exceptions: VPN will be available to every application."
    val pending get() = if (ru) "Есть неприменённые изменения" else "Changes not applied yet"
    val saved get() = if (ru) "Настройки сохранены" else "Settings saved"
    val connectedHint get() = if (ru) "При применении VPN переподключится один раз." else "Applying changes reconnects VPN once."
    val disconnectedHint get() = if (ru) "Правила начнут действовать после подключения VPN." else "Rules take effect when you connect VPN."
    val apply get() = if (ru) "Применить" else "Apply changes"
    val discard get() = if (ru) "Отменить изменения" else "Discard changes"
    val selectedOnly get() = if (ru) "Только отмеченные" else "Selected only"
    val selectVisible get() = if (ru) "Отметить найденные" else "Select search results"
    val selectAll get() = if (ru) "Отметить все" else "Select all"
    val clearSelection get() = if (ru) "Снять отметки" else "Clear selection"
    val clearSearch get() = if (ru) "Очистить поиск" else "Clear search"
    val noMatches get() = if (ru) "Приложения не найдены. Измените поиск." else "No applications found. Change your search."
    val noSelected get() = if (ru) "Пока ничего не отмечено. Покажите все приложения и выберите нужные." else "Nothing selected yet. Show all applications to choose some."
    val showAll get() = if (ru) "Показать все приложения" else "Show all applications"
    val noApps get() = if (ru) "Список приложений пуст. Попробуйте обновить его." else "Application list is empty. Try refreshing it."
}

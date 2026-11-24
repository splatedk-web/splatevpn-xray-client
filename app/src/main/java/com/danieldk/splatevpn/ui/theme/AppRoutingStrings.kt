package com.danieldk.splatevpn.ui.theme

import com.danieldk.splatevpn.data.AppRoutingMode

internal val AppStrings.appRouting get() = AppRoutingStrings(this)

internal class AppRoutingStrings(private val strings: AppStrings) {
    val title get() = strings.localized("Приложения", "Applications")
    val subtitle get() = strings.localized("Укажите, каким приложениям нужен VPN", "Choose which applications use VPN")
    val modeHeading get() = strings.localized("1. Выберите режим", "1. Choose a mode")
    val changeMode get() = strings.localized("Изменить режим", "Change mode")
    fun title(mode: AppRoutingMode) = when (mode) {
        AppRoutingMode.ALL -> strings.localized("Все приложения", "All applications")
        AppRoutingMode.INCLUDE -> strings.localized("Только выбранные", "Only selected applications")
        AppRoutingMode.EXCLUDE -> strings.localized("Все, кроме выбранных", "All except selected applications")
    }
    fun description(mode: AppRoutingMode) = when (mode) {
        AppRoutingMode.ALL -> strings.localized("Для всех приложений действуют правила VPN.", "VPN routing rules apply to every application.")
        AppRoutingMode.INCLUDE -> strings.localized("Отмеченные — через VPN. Остальные — напрямую.", "Selected apps use VPN. All others connect directly.")
        AppRoutingMode.EXCLUDE -> strings.localized("Отмеченные — напрямую. Остальные — через VPN.", "Selected apps connect directly. All others use VPN.")
    }
    fun pickerHeading(mode: AppRoutingMode) = if (mode == AppRoutingMode.INCLUDE) {
        strings.localized("2. Отметьте приложения для VPN", "2. Select applications for VPN")
    } else strings.localized("2. Отметьте приложения без VPN", "2. Select applications to bypass VPN")
    fun selected(count: Int) = String.format(strings.localized("Отмечено: %d", "Selected: %d"), count)
    fun destination(vpn: Boolean) = if (vpn) {
        strings.localized("По правилам VPN", "VPN routing rules")
    } else strings.localized("Без VPN · напрямую", "Without VPN · direct")
    val rulesNote get() = strings.localized("«Через VPN» учитывает режим на главной: в умном режиме часть сайтов идёт напрямую.", "VPN apps follow the mode on Home. Smart routing can send some sites directly.")
    val allNote get() = strings.localized("Выбирать приложения по одному не нужно. Для исключений выберите «Все, кроме выбранных».", "No need to select apps individually. To add exceptions, choose “All except selected applications”.")
    val emptyInclude get() = strings.localized("Отметьте хотя бы одно приложение или выберите «Все приложения».", "Select at least one application or choose “All applications”.")
    val emptyExclusions get() = strings.localized("Исключений нет — VPN будет доступен всем приложениям.", "No exceptions: VPN will be available to every application.")
    val pending get() = strings.localized("Есть неприменённые изменения", "Changes not applied yet")
    val saved get() = strings.localized("Настройки сохранены", "Settings saved")
    val connectedHint get() = strings.localized("При применении VPN переподключится один раз.", "Applying changes reconnects VPN once.")
    val disconnectedHint get() = strings.localized("Правила начнут действовать после подключения VPN.", "Rules take effect when you connect VPN.")
    val apply get() = strings.localized("Применить", "Apply changes")
    val discard get() = strings.localized("Отменить изменения", "Discard changes")
    val selectedOnly get() = strings.localized("Только отмеченные", "Selected only")
    val selectVisible get() = strings.localized("Отметить найденные", "Select search results")
    val selectAll get() = strings.localized("Отметить все", "Select all")
    val clearSelection get() = strings.localized("Снять отметки", "Clear selection")
    val clearSearch get() = strings.localized("Очистить поиск", "Clear search")
    val noMatches get() = strings.localized("Приложения не найдены. Измените поиск.", "No applications found. Change your search.")
    val noSelected get() = strings.localized("Пока ничего не отмечено. Покажите все приложения и выберите нужные.", "Nothing selected yet. Show all applications to choose some.")
    val showAll get() = strings.localized("Показать все приложения", "Show all applications")
    val noApps get() = strings.localized("Список приложений пуст. Попробуйте обновить его.", "Application list is empty. Try refreshing it.")
}

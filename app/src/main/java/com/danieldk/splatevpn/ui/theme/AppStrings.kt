package com.danieldk.splatevpn.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale


interface AppStrings {
    val serverToolsFolders: String
    val serverToolsList: String
    val serverToolsGrouping: String
    val serverToolsGrouped: String
    val serverToolsUngrouped: String
    val serverToolsSort: String
    val serverToolsSortDescription: String
    val serverToolsAuto: String
    val serverToolsTest: String
    val serversPageTitle: String
    val presetsPageTitle: String
    val serversPageSubtitle: String
    val subscriptionsPageSubtitle: String
    val personalPageSubtitle: String
    val subscriptionsTitle: String
    val subscriptionsSearch: String
    val subscriptionsNoResults: String
    val subscriptionDetails: String
    val subscriptionName: String
    val subscriptionUrl: String
    val subscriptionUrlInvalid: String
    val subscriptionEditHint: String
    val subscriptionAutoUpdate: String
    val subscriptionManual: String
    val subscriptionScheduleOff: String
    val subscriptionSchedule: String
    val subscriptionUpdateAll: String
    val subscriptionRefreshSummary: String
    val subscriptionNoUpdates: String
    val subscriptionBuiltIn: String
    val subscriptionDisabled: String
    val subscriptionNodes: String
    val subscriptionDeleteTitle: String
    val subscriptionDeleteMessage: String

    val subscriptionNever: String
    val subscriptionJustNow: String
    val subscriptionMinutesAgo: String
    val subscriptionHoursAgo: String
    val subscriptionsTab: String
    val personalNodesTab: String
    val subscriptionEnabledMessage: String
    val subscriptionDisabledMessage: String
    val subscriptionAutoEnabledMessage: String
    val subscriptionAutoDisabledMessage: String

    val subscriptionNodesLabel: String
    val subscriptionsLabel: String
    val personalNodesLabel: String

    val uiPause: String
    val uiResume: String
    val uiClear: String
    val uiMonitoring: String
    val uiPaused: String
    val uiNoMatches: String
    val uiNoPersonal: String
    val uiPersonalHint: String
    val uiAddConfig: String
    val uiNoSubscriptions: String
    val uiSubscriptionHint: String
    val uiAddSubscription: String
    val uiRestorePresets: String
    val uiPresetsEmpty: String
    val uiPersonalSearch: String
    val uiPresetSearch: String
    val uiAllActive: String
    val uiPersonalServers: String
    val uiSubscriptionPrefix: String
    val uiDisabledNodesHidden: String
    val uiExpand: String
    val uiCollapse: String
    val uiPresetTag: String
    val uiSubscriptionTag: String
    val uiPersonalTag: String
    val uiActions: String
    val uiAutoSelect: String
    val uiFastest: String
    val uiOff: String
    val uiPingSummary: String
    val uiCamera: String
    val uiAutoImport: String
    val uiLinkHint: String

    val uiPresetHarvester: String
    val uiPresetCidr: String
    val uiPresetUniversal: String

    val appRoutingConnected: String
    val appRoutingDisconnected: String
    val fieldAddressError: String
    val fieldPortError: String
    val fieldPassword: String
    val fieldSniHint: String
    val fieldHostHint: String
    val tlsPresetStandard: String
    val tlsPresetAggressive: String
    val tlsPresetCustom: String
    val tlsCustomTitle: String
    val tlsParametersSummary: String
    val shareLanNotReady: String
    val shareLanCopyEnable: String
    val shareLanDisable: String

    val uiCopied: String
    val uiCopy: String

    val lanEndpointUsb: String
    val lanEndpointHotspot: String
    val lanEndpointWifi: String
    val lanEndpointOther: String

    // Common
    val appName: String
    val cancel: String
    val save: String
    val delete: String
    val close: String
    val add: String
    val refresh: String
    val edit: String

    // Navigation Tabs
    val tabControl: String
    val tabNodes: String
    val tabRouting: String
    val tabConfig: String

    // Home Screen
    val standbyUnprotected: String
    val encryptedSession: String
    val resolvingLocation: String
    val protectedGateway: String
    val egressIp: String
    val ping: String
    val download: String
    val upload: String
    val routingProfile: String
    val smartRoute: String
    val smartRouteDesc: String
    val globalTunnel: String
    val globalTunnelDesc: String
    val activeGateway: String
    val autoFastest: String
    val pingNoResponse: String
    val pingUntested: String
    val pinging: String
    val noServerSelected: String
    val tapToChooseNode: String
    val coreConnected: String
    val coreConnect: String
    val latencyLabel: String
    val downlinkLabel: String
    val uplinkLabel: String
    val coreSubtitle: String
    val stateSelectingServer: String
    val coreDiscSelect: String
    val stateStartingCore: String
    val stateConnecting: String
    val stateHealthChecking: String
    val stateReconnecting: String
    val stateFailed: String
    val coreDiscBoot: String
    val coreDiscLink: String
    val coreDiscVerify: String
    val coreDiscRetry: String
    val coreDiscError: String
    val tagSplitTunnel: String
    val tagFullEncap: String
    val searchServers: String
    val noServersMatchingQuery: String
    val linkCopied: String
    // Session traffic labels
    val sessionDownloaded: String
    val sessionUploaded: String

    // Servers Screen
    val networkNodes: String
    val presetServersTitle: String
    val availableNodes: String
    val pingAll: String
    val pasteLink: String
    val subs: String
    val allChip: String
    val builtinChip: String
    val noServersFound: String
    val noServersInFilter: String
    val addServerTitle: String
    val addServerPrompt: String
    val serverAddedSuccess: String
    val invalidLinkFormat: String
    val editServerTitle: String
    val serverUpdatedSuccess: String
    val manageSubsTitle: String
    val activeSubsHeader: String
    val serversCountSub: String
    val subBuiltinTag: String
    val subUpdatedSuccess: String
    val subDeletedSuccess: String
    val addNewSubHeader: String
    val subNamePlaceholder: String
    val subUrlPlaceholder: String
    val subLoading: String
    val splashSubtitle: String
    val splashInitializing: String
    val subAddedSuccess: String
    val errorPrefix: String
    val autoSelectSelected: String

    // Server Fields
    val fieldName: String
    val fieldProtocol: String
    val fieldAddress: String
    val fieldPort: String
    val fieldNetwork: String
    val fieldSecurity: String
    val fieldSni: String
    val fieldPath: String
    val fieldHost: String
    val fieldUuid: String
    val fieldPublicKey: String
    val fieldShortId: String
    val fieldSpiderX: String
    val fieldFingerprint: String

    // Apps Screen
    val appRoutingTitle: String
    val appRoutingSubtitle: String
    val proxiedAppsCount: String
    val selectedAppsCount: String
    val proxyModeAll: String
    val proxyModeAllDesc: String
    val proxyModeSelected: String
    val proxyModeSelectedDesc: String
    val globalModeActive: String
    val allAppsProtected: String
    val searchApps: String
    val statusProxied: String
    val statusDirect: String
    val statusBypassed: String
    val perAppModeInclude: String
    val perAppModeIncludeDesc: String
    val perAppModeExclude: String
    val perAppModeExcludeDesc: String
    val perAppModeHeader: String

    // Settings Screen
    val settingsTitle: String
    val settingsSubtitle: String
    val sectionAppearance: String
    val themeTitle: String
    val themeSubtitle: String
    val themeDark: String
    val themeAmoled: String
    val themeLight: String
    val themeSystem: String
    val themeCyberpunk: String
    val themeOnePlus: String
    val themeAurora: String
    val themeOcean: String
    val themeSakura: String
    val themePony: String
    val themeWinx: String
    val glassTransparencyTitle: String
    val glassTransparencySubtitle: String
    val glassBlurTitle: String
    val glassBlurSubtitle: String
    val glassDefault: String
    val glassMoreSolid: String
    val glassMoreClear: String
    val glassLegacyHint: String
    val themeShowAll: String
    val themeShowLess: String
    val themeCurrent: String
    val themeSunset: String
    val languageTitle: String
    val languageSubtitle: String
    val langRu: String
    val langEn: String
    val langSystem: String

    val sectionAutomation: String
    val autoSelectTitle: String
    val autoSelectSubtitle: String
    val autoUpdateTitle: String
    val autoUpdateSubtitle: String
    val intervalOff: String
    val interval6h: String
    val interval12h: String
    val interval24h: String
    val updateAllSubsNow: String
    val updating: String
    val updatedCount: String

    val sectionRouting: String
    val dnsProviderTitle: String
    val dnsProviderSubtitle: String
    val xhttpModeTitle: String
    val xhttpModeSubtitle: String
    val fakeDnsTitle: String
    val fakeDnsSubtitle: String
    val blockQuicTitle: String
    val blockQuicSubtitle: String
    val sniffingTitle: String
    val sniffingSubtitle: String
    val blockIpv6Title: String
    val blockIpv6Subtitle: String
    val bypassAiTitle: String
    val bypassAiSubtitle: String
    val aiDnsProviderTitle: String
    val aiDnsProviderHint: String
    val aiTlsFragmentTitle: String
    val aiTlsFragmentHint: String
    val adBlockerTitle: String
    val adBlockerSubtitle: String

    val sectionCore: String
    val coreVersion: String
    val coreArch: String
    val coreTunnel: String
    val coreProtocols: String
    val coreFakeDns: String

    val sectionDiagnostics: String
    val diagnosticsDesc: String
    val openLogsButton: String
    val logsDialogTitle: String
    val copyReport: String
    val shareReport: String
    val clearLogs: String
    val reportCopied: String
    val recordsCount: String
    val logsCleared: String
    val logsEmpty: String
    val logsFilterAll: String
    val logsFilterErrors: String
    val logsFilterXray: String
    val logsFilterVpn: String
    val logsSearchHint: String
    val logsAutoscroll: String
    val logsClose: String
    val logsCopiedLine: String
    val logsStatsTotal: String
    val logsStatsErrors: String
    val logsStatsWarnings: String

    // Custom Domain Routes
    val sectionCustomRoutes: String
    val customRoutesDomainHint: String
    val customRoutesAddBtn: String
    val customRoutesEmpty: String
    val customRoutesSaved: String
    val customRoutesRemoved: String
    val customRouteDirect: String
    val customRouteProxy: String
    val customRouteBlock: String
    val customRoutesSubtitle: String

    // Updates
    val updateAvailableTitle: String
    val updateAvailableDesc: String
    val updateDownload: String
    val updateDownloadAndInstall: String
    val updateInstall: String
    val updateDownloading: String
    val updateDownloaded: String
    val updateTapToInstall: String
    val updateChangelog: String
    val updateScrollHint: String
    val updateExpandNotes: String
    val updateCollapseNotes: String
    val updatePermissionRequired: String
    val updateFailed: String
    val updateRetry: String
    val updateCheck: String
    val updateChecking: String
    val updateLatest: String

    // Real-Time Traffic & Live Connections Monitor
    val liveConnectionsTitle: String
    val liveConnectionsSubtitle: String
    val liveConnectionsHudBadge: String
    val liveConnectionsSearchPlaceholder: String
    val filterAll: String
    val filterProxy: String
    val filterDirect: String
    val filterBlock: String
    val filterDns: String
    val clearConnections: String
    val noConnectionsYet: String
    val noConnectionsDesc: String
    val liveConnectionsCount: String
    val liveConnectionsSummary: String
    val connectionOutboundLabel: String
    val connectionDestLabel: String
    val connectionProtocolLabel: String

    // Auto Connect on Boot & Battery
    val autoConnectOnBootTitle: String
    val autoConnectOnBootSubtitle: String
    val batteryOptimizationTitle: String
    val batteryOptimizationDisabled: String
    val batteryOptimizationPrompt: String
    val batterySettingsOpenError: String

    // Ping & Protocol Extras
    val pingModeTitle: String
    val pingModeSubtitle: String
    val pingModeRealDelay: String
    val pingModeTcp: String
    val pingModeIcmp: String
    val pingModeRealDelayDesc: String
    val pingModeTcpDesc: String
    val pingModeIcmpDesc: String
    val tlsFragmentTitle: String
    val tlsFragmentSubtitle: String
    val killSwitchActive: String
    val killSwitchAlwaysOnly: String
    val killSwitchOff: String
    val killSwitchUnknown: String
    val killSwitchUnknownHint: String
    val killSwitchBody: String
    val killSwitchStepOne: String
    val killSwitchStepTwo: String
    val killSwitchStepThree: String
    val killSwitchConfigure: String
    val killSwitchManage: String
    val killSwitchExcludedApps: String
    val killSwitchTitle: String

    // App Updates Section
    val sectionAppUpdates: String

    // Quick Settings
    val sectionQuickSettings: String
    val quickSettingsDesc: String
    val quickSettingsAddTile: String
    val quickSettingsTileAdded: String
    val quickSettingsTileAlreadyAdded: String
    val quickSettingsManualPrompt: String

    // Backup & Restore
    val sectionBackupRestore: String
    val backupRestoreDesc: String
    val exportBtn: String
    val importBtn: String
    val backupExportSuccess: String
    val backupExportError: String
    val backupImportSuccess: String
    val backupImportError: String
    val autoRestoreBtn: String
    val autoRestoreSuccess: String
    val autoRestoreNotFound: String

    // Speedtest & Status Badges
    val autoSelectOn: String
    val speedtestTitle: String
    val speedtestDesc: String
    val speedtestRunning: String
    val speedtestSpeedUnit: String
    val speedtestResult: String
    val speedtestLatency: String
    val speedtestError: String

    // Additional Node & App labels
    val serversAddedCount: String
    val qrScanError: String
    val scanQr: String
    val copyLink: String
    val autoSelectBalancer: String
    val activeNodePrefix: String
    val autoSelectBalancerDesc: String
    val onUpper: String
    val offUpper: String
    val filterSelected: String
    val selectAll: String
    val resetAll: String

    // Unified Add & Empty State
    val unifiedAddTitle: String
    val unifiedAddSubtitle: String
    val pasteFromClipboard: String
    val scanQrCode: String
    val clipboardEmpty: String
    val detectedSubscription: String
    val detectedServerNode: String
    val detectedMultipleNodes: String
    val emptyServersCta: String
    val emptyServersHint: String
    val importAction: String
    val subNameOptional: String
    val addServerOrSub: String

    // Subscription Quota & Userinfo
    val subQuotaUsed: String
    val subQuotaRemaining: String
    val subQuotaUnlimited: String
    val subExpireDate: String
    val subExpired: String

    // Server Share & QR Dialog
    val shareServerTitle: String
    val shareQrPrompt: String
    val shareCopyLink: String
    val shareSend: String
    val shareAction: String
    val sharePayloadTooLarge: String
    val shareSubscriptionTitle: String
    val shareSubQrPrompt: String

    // LAN / Hotspot / USB Proxy Sharing
    val sectionShareLan: String
    val shareLanTitle: String
    val shareLanSubtitle: String
    val shareLanActiveEndpoints: String
    val shareLanNoEndpoints: String
    val shareLanInstructionsBtn: String
    val shareLanHttpProxy: String
    val shareLanSocksProxy: String
    val shareLanCopied: String
    val shareLanDialogTitle: String
    val shareLanStep1: String
    val shareLanStep2: String
    val shareLanStep3Windows: String
    val shareLanStep3Mac: String
    val shareLanStep3Telegram: String
    val shareLanWindowsTitle: String
    val shareLanWindowsSettingsGuide: String
    val shareLanWindowsInetcplGuide: String
    val shareLanWindowsProxyString: String
    val shareLanWindowsExceptions: String
    val shareLanWindowsExceptionsValue: String
    val shareLanWindowsCmdEnable: String
    val shareLanWindowsCmdDisable: String
    val shareLanExceptionsCopied: String
    val shareLanCommandCopied: String

    // TLS Fragmentation Tuning
    val tlsFragmentPresetTitle: String
    val tlsFragmentPresetStandard: String
    val tlsFragmentPresetAggressive: String
    val tlsFragmentPresetCustom: String
    val tlsFragmentLengthLabel: String
    val tlsFragmentIntervalLabel: String
    val tlsFragmentPacketsLabel: String

    // Custom DoH DNS
    val dnsCustom: String
    val dnsCustomUrlHint: String
    val dnsCustomUrlDialogTitle: String

    // Multiplexing (Mux)
    val muxTitle: String
    val muxSubtitle: String
    val muxConcurrencyLabel: String
}

class RussianAppStrings : AppStrings {
    override val appName: String = "SplateVPN"
    override val cancel: String = "Отмена"
    override val save: String = "Сохранить"
    override val delete: String = "Удалить"
    override val close: String = "Закрыть"
    override val add: String = "Добавить"
    override val refresh: String = "Обновить"
    override val edit: String = "Редактировать"

    override val tabControl: String = "ГЛАВНАЯ"
    override val tabNodes: String = "СЕРВЕРЫ"
    override val tabRouting: String = "ПРИЛОЖЕНИЯ"
    override val tabConfig: String = "НАСТРОЙКИ"

    override val standbyUnprotected: String = "ОЖИДАНИЕ • НЕ ЗАЩИЩЕНО"
    override val encryptedSession: String = "ПОДКЛЮЧЕНО • %s"
    override val resolvingLocation: String = "ОПРЕДЕЛЕНИЕ ЛОКАЦИИ..."
    override val protectedGateway: String = "ЗАЩИЩЁННЫЙ ТУННЕЛЬ"
    override val egressIp: String = "ВНЕШНИЙ IP: %s"
    override val ping: String = "ПИНГ"
    override val download: String = "СКАЧИВАНИЕ"
    override val upload: String = "ОТПРАВКА"
    override val routingProfile: String = "ПРОФИЛЬ МАРШРУТИЗАЦИИ"
    override val smartRoute: String = "УМНЫЙ МАРШРУТ"
    override val smartRouteDesc: String = "Обход блокировок (RU напрямую)"
    override val globalTunnel: String = "ВЕСЬ ТРАФИК"
    override val globalTunnelDesc: String = "Все назначения через VPN"
    override val activeGateway: String = "АКТИВНЫЙ СЕРВЕР"
    override val autoFastest: String = "БЫСТРЫЙ"
    override val pingNoResponse = "Нет ответа"
    override val pingUntested = "Не проверен"
    override val pinging: String = "ПИНГ..."
    override val noServerSelected: String = "Сервер не выбран"
    override val tapToChooseNode: String = "Нажмите для выбора сервера"
    override val coreConnected: String = "ПОДКЛЮЧЕНО"
    override val coreConnect: String = "ПОДКЛЮЧИТЬ"
    override val latencyLabel: String = "ПИНГ"
    override val downlinkLabel: String = "ПРИЁМ"
    override val uplinkLabel: String = "ОТДАЧА"
    override val coreSubtitle: String = "ЯДРО ${com.danieldk.splatevpn.SplateConstants.XRAY_VERSION} // XRAY-CORE"
    override val stateSelectingServer: String = "ПОИСК СЕРВЕРА..."
    override val coreDiscSelect: String = "ПОИСК..."
    override val stateStartingCore: String = "ЗАПУСК ЯДРА..."
    override val stateConnecting: String = "ПОДКЛЮЧЕНИЕ..."
    override val stateHealthChecking: String = "ПРОВЕРКА СВЯЗИ..."
    override val stateReconnecting: String = "ПЕРЕПОДКЛЮЧЕНИЕ..."
    override val stateFailed: String = "СБОЙ ПОДКЛЮЧЕНИЯ"
    override val coreDiscBoot: String = "ЗАПУСК..."
    override val coreDiscLink: String = "СОЕДИНЕНИЕ..."
    override val coreDiscVerify: String = "ПРОВЕРКА..."
    override val coreDiscRetry: String = "ПОВТОР..."
    override val coreDiscError: String = "ОШИБКА"
    override val tagSplitTunnel: String = "РАЗДЕЛЬНЫЙ"
    override val tagFullEncap: String = "ГЛОБАЛЬНЫЙ"
    override val searchServers: String = "Поиск по имени, IP, протоколу..."
    override val noServersMatchingQuery: String = "Серверы по запросу не найдены"
    override val linkCopied: String = "Ссылка скопирована в буфер"
    override val sessionDownloaded: String = "ПОЛУЧЕНО"
    override val sessionUploaded: String = "ОТПРАВЛЕНО"

    override val networkNodes: String = "СЕРВЕРЫ"
    override val presetServersTitle: String = "ПРЕДУСТАНОВЛЕННЫЕ"
    override val availableNodes: String = "ДОСТУПНО: %d / %d"
    override val pingAll: String = "ПРОВЕРИТЬ ВСЕ"
    override val pasteLink: String = "ВСТАВИТЬ ССЫЛКУ"
    override val subs: String = "ПОДПИСКИ (%d)"
    override val allChip: String = "Все (%d)"
    override val builtinChip: String = "⚡ Предустановленные"
    override val noServersFound: String = "Нет доступных серверов.\nДобавьте ссылку или подписку."
    override val noServersInFilter: String = "В данной категории нет серверов."
    override val addServerTitle: String = "Добавить сервер"
    override val addServerPrompt: String = "Вставьте vless://, trojan://, vmess:// или ss:// ссылку:"
    override val serverAddedSuccess: String = "Сервер успешно добавлен!"
    override val invalidLinkFormat: String = "Неверный формат ссылки"
    override val editServerTitle: String = "Настройки сервера"
    override val serverUpdatedSuccess: String = "Параметры сервера сохранены!"
    override val manageSubsTitle: String = "Управление подписками"
    override val activeSubsHeader: String = "Активные подписки:"
    override val serversCountSub: String = "%d серверов"
    override val subBuiltinTag: String = "Предустановленная"
    override val subUpdatedSuccess: String = "Обновлено: %d серверов"
    override val subDeletedSuccess: String = "Подписка удалена"
    override val addNewSubHeader: String = "Добавить новую подписку:"
    override val serverToolsFolders = "Папки"
    override val serverToolsList = "Список"
    override val serverToolsGrouping = "Группировка серверов"
    override val serverToolsGrouped = "Группировка по источникам"
    override val serverToolsUngrouped = "Общий список узлов"
    override val serverToolsSort = "Порядок"
    override val serverToolsSortDescription = "Сортировка по задержке"
    override val serverToolsAuto = "Авто"
    override val serverToolsTest = "Проверить"
    override val serversPageTitle = "Серверы"
    override val presetsPageTitle = "Предустановки"
    override val serversPageSubtitle = "Выбор сервера и проверка задержки"
    override val subscriptionsPageSubtitle = "Источники и обновление списков серверов"
    override val personalPageSubtitle = "Ваши собственные конфигурации VPN"
    override val subscriptionsTitle: String = "Подписки"
    override val subscriptionsSearch: String = "Поиск по имени или URL…"
    override val subscriptionsNoResults: String = "Подписки не найдены"
    override val subscriptionDetails: String = "Настройки подписки"
    override val subscriptionName: String = "Название"
    override val subscriptionUrl: String = "URL подписки"
    override val subscriptionUrlInvalid: String = "Введите корректную ссылку на подписку"
    override val subscriptionEditHint: String = "Изменение ссылки применяется при следующем обновлении. Существующие узлы сохраняются."
    override val subscriptionAutoUpdate: String = "Автообновление"
    override val subscriptionManual: String = "Вручную"
    override val subscriptionScheduleOff: String = "Общее расписание отключено в настройках"
    override val subscriptionSchedule: String = "Обновление каждые %s"
    override val subscriptionUpdateAll: String = "Обновить все"
    override val subscriptionRefreshSummary: String = "Обновлено подписок: %d. Ошибок: %d."
    override val subscriptionNoUpdates: String = "Нет включённых подписок для обновления"
    override val subscriptionBuiltIn: String = "Встроенная"
    override val subscriptionDisabled: String = "Отключена"
    override val subscriptionNodes: String = "Узлы (%d)"
    override val subscriptionDeleteTitle: String = "Удалить подписку?"
    override val subscriptionDeleteMessage: String = "Удалить «%s» и все её узлы (%d)?"
    override val subscriptionNever: String = "Никогда"
    override val subscriptionJustNow: String = "Только что"
    override val subscriptionMinutesAgo: String = "%d мин назад"
    override val subscriptionHoursAgo: String = "%d ч назад"
    override val subscriptionsTab: String = "Подписки (%d/%d)"
    override val personalNodesTab: String = "Личные (%d)"
    override val subscriptionEnabledMessage: String = "«%s»: включена"
    override val subscriptionDisabledMessage: String = "«%s»: отключена, узлы скрыты"
    override val subscriptionAutoEnabledMessage: String = "Автообновление включено"
    override val subscriptionAutoDisabledMessage: String = "Автообновление выключено"
    override val subscriptionNodesLabel: String = "Узлы"
    override val subscriptionsLabel: String = "Подписки"
    override val personalNodesLabel: String = "Личные"
    override val uiPause: String = "Пауза"
    override val uiResume: String = "Продолжить"
    override val uiClear: String = "Очистить"
    override val uiMonitoring: String = "Мониторинг"
    override val uiPaused: String = "Приостановлено"
    override val uiNoMatches: String = "Ничего не найдено по запросу"
    override val uiNoPersonal: String = "Нет личных конфигураций"
    override val uiPersonalHint: String = "Вставьте ссылку vless://, vmess://, trojan:// или shadowsocks:// из буфера обмена или QR-кода."
    override val uiAddConfig: String = "Добавить конфигурацию"
    override val uiNoSubscriptions: String = "Нет подписок"
    override val uiSubscriptionHint: String = "Подписка позволяет автоматически обновлять десятки узлов по одной ссылке."
    override val uiAddSubscription: String = "Добавить подписку"
    override val uiRestorePresets: String = "Восстановить встроенные подписки"
    override val uiPresetsEmpty: String = "Встроенные подписки ещё не загружены или были удалены."
    override val uiPersonalSearch: String = "Поиск по личным…"
    override val uiPresetSearch: String = "Поиск по предустановленным…"
    override val uiAllActive: String = "Все активные (%d)"
    override val uiPersonalServers: String = "Личные серверы"
    override val uiSubscriptionPrefix: String = "Подписка: %s"
    override val uiDisabledNodesHidden: String = "Отключена — узлы скрыты"
    override val uiExpand: String = "Развернуть"
    override val uiCollapse: String = "Свернуть"
    override val uiPresetTag: String = "Предустановленный • %s"
    override val uiSubscriptionTag: String = "Подписка • %s"
    override val uiPersonalTag: String = "Личный конфиг"
    override val uiActions: String = "Действия"
    override val uiAutoSelect: String = "Автовыбор"
    override val uiFastest: String = "быстрейший"
    override val uiOff: String = "выключен"
    override val uiPingSummary: String = "«%s»: проверено %d узлов"
    override val uiCamera: String = "Камера"
    override val uiAutoImport: String = "Автоимпорт"
    override val uiLinkHint: String = "vless://…, vmess://… или https://…"
    override val uiPresetHarvester: String = "Harvester (Рабочие узлы)"
    override val uiPresetCidr: String = "Белый список (CIDR RU)"
    override val uiPresetUniversal: String = "Белый список (Universal)"
    override val appRoutingConnected: String = "VPN подключён. Для приложений действуют выбранные правила маршрутизации."
    override val appRoutingDisconnected: String = "VPN отключён. Подключитесь на главной вкладке, чтобы применить маршрутизацию."
    override val fieldAddressError: String = "Введите домен или IP-адрес без протокола, пути и пробелов"
    override val fieldPortError: String = "Введите целый порт от 1 до 65535"
    override val fieldPassword: String = "Пароль"
    override val fieldSniHint: String = "domain.com (для Cloudflare/TLS)"
    override val fieldHostHint: String = "HTTP-заголовок Host"
    override val tlsPresetStandard: String = "Стандарт"
    override val tlsPresetAggressive: String = "ТСПУ (50–100)"
    override val tlsPresetCustom: String = "Свой"
    override val tlsCustomTitle: String = "Настройка TLS Fragmentation"
    override val tlsParametersSummary: String = "Параметры: length=%s, interval=%s ms, packets=%s"
    override val shareLanNotReady: String = "Раздача ещё не готова. Когда появится реальный адрес точки доступа или USB-модема, здесь будут доступны адреса и команды подключения."
    override val shareLanCopyEnable: String = "Скопировать команду включения"
    override val shareLanDisable: String = "Выключить прокси"
    override val uiCopied: String = "Скопировано"
    override val uiCopy: String = "Копировать"
    override val lanEndpointUsb: String = "USB-модем (%s)"
    override val lanEndpointHotspot: String = "Точка доступа Wi-Fi (%s)"
    override val lanEndpointWifi: String = "Сеть Wi-Fi (%s)"
    override val lanEndpointOther: String = "Интерфейс %s"
    override val subNamePlaceholder: String = "Название (например: Мой VPN)"
    override val subUrlPlaceholder: String = "https://example.com/sub/..."
    override val subLoading: String = "Загрузка серверов..."
    override val splashSubtitle: String = "Безопасный прокси-туннель"
    override val splashInitializing: String = "Инициализация серверов..."
    override val subAddedSuccess: String = "Добавлено серверов: %d"
    override val errorPrefix: String = "Ошибка: %s"
    override val autoSelectSelected: String = "Выбран: %s"

    override val fieldName: String = "Название сервера"
    override val fieldProtocol: String = "Протокол"
    override val fieldAddress: String = "Адрес / IP"
    override val fieldPort: String = "Порт"
    override val fieldNetwork: String = "Транспорт / Сеть (type)"
    override val fieldSecurity: String = "Безопасность (security)"
    override val fieldSni: String = "SNI домен"
    override val fieldPath: String = "Путь (path / serviceName)"
    override val fieldHost: String = "Host заголовок"
    override val fieldUuid: String = "UUID / Пароль"
    override val fieldPublicKey: String = "Публичный ключ (pbk / Reality)"
    override val fieldShortId: String = "Short ID (sid)"
    override val fieldSpiderX: String = "SpiderX (spx)"
    override val fieldFingerprint: String = "Fingerprint (fp)"

    override val appRoutingTitle: String = "Приложения"
    override val appRoutingSubtitle: String = "Выберите приложения, для которых действуют правила VPN"
    override val proxiedAppsCount: String = "ПРИЛОЖЕНИЯ В VPN: %d / %d"
    override val selectedAppsCount: String = "Выбрано: %d"
    override val proxyModeAll: String = "Все приложения"
    override val proxyModeAllDesc: String = "При подключении VPN для всех приложений действуют выбранные правила маршрутизации"
    override val proxyModeSelected: String = "Выборочный режим (Per-App)"
    override val proxyModeSelectedDesc: String = "Только отмеченные приложения идут через VPN"
    override val globalModeActive: String = "Выбраны все приложения"
    override val allAppsProtected: String = "Все установленные приложения защищены"
    override val searchApps: String = "Поиск приложений..."
    override val statusProxied: String = "В VPN"
    override val statusDirect: String = "НАПРЯМУЮ"
    override val statusBypassed: String = "В ОБХОД"
    override val perAppModeInclude: String = "Только выбранные"
    override val perAppModeIncludeDesc: String = "Через VPN идут только отмеченные приложения"
    override val perAppModeExclude: String = "Все, кроме выбранных (Обход)"
    override val perAppModeExcludeDesc: String = "Отмеченные приложения (банки, Госуслуги) пойдут в обход VPN"
    override val perAppModeHeader: String = "РЕЖИМ РАЗДЕЛЬНОГО ТУННЕЛИРОВАНИЯ"

    override val settingsTitle: String = "Настройки"
    override val settingsSubtitle: String = "Конфигурация обхода блокировок, оформления и ядра"
    override val sectionAppearance: String = "ОФОРМЛЕНИЕ И ИНТЕРФЕЙС"
    override val themeTitle: String = "Тема оформления"
    override val themeSubtitle: String = "Цветовая палитра и визуальный стиль"
    override val themeDark: String = "Тёмная"
    override val themeAmoled: String = "AMOLED"
    override val themeLight: String = "Светлая"
    override val themeSystem: String = "Системная"
    override val themeCyberpunk: String = "Киберпанк"
    override val themeOnePlus: String = "OnePlus Red"
    override val themeAurora: String = "Северное сияние"
    override val themeOcean = "Океан"
    override val themeSakura = "Сакура"
    override val themePony = "Пони"
    override val themeWinx = "Винкс · Блум"
    override val glassTransparencyTitle = "Прозрачность карточек"
    override val glassTransparencySubtitle = "Прозрачность карточек и нижнего дока. Текст и кнопки остаются чёткими."
    override val glassBlurTitle = "Размытие фона"
    override val glassBlurSubtitle = "Блюр за карточками и нижним доком. Прозрачность регулируется отдельно."
    override val glassDefault = "По умолчанию"
    override val glassMoreSolid = "Плотнее"
    override val glassMoreClear = "Прозрачнее"
    override val glassLegacyHint = "Размытие фона доступно на Android 12 и новее."
    override val themeShowAll = "Показать все темы (%d)"
    override val themeShowLess = "Свернуть темы"
    override val themeCurrent = "Выбрана: %s"
    override val themeSunset: String = "Закат"
    override val languageTitle: String = "Язык приложения"
    override val languageSubtitle: String = "Выбор языка интерфейса"
    override val langRu: String = "Русский"
    override val langEn: String = "English"
    override val langSystem: String = "Системный"

    override val sectionAutomation: String = "АВТОМАТИЗАЦИЯ И ПОДПИСКИ"
    override val autoSelectTitle: String = "Автовыбор сервера"
    override val autoSelectSubtitle: String = "Автоматически проверять пинг и подключаться к самому быстрому серверу."
    override val autoUpdateTitle: String = "Автообновление подписок"
    override val autoUpdateSubtitle: String = "Периодичность фонового обновления серверов"
    override val intervalOff: String = "Выкл"
    override val interval6h: String = "6 ч"
    override val interval12h: String = "12 ч"
    override val interval24h: String = "24 ч"
    override val updateAllSubsNow: String = "Обновить все подписки сейчас"
    override val updating: String = "Обновление..."
    override val updatedCount: String = "Обновлено подписок: %d"

    override val sectionRouting: String = "МАРШРУТИЗАЦИЯ И ОБХОД БЛОКИРОВОК"
    override val dnsProviderTitle: String = "DNS Провайдер (DoH + UDP)"
    override val dnsProviderSubtitle: String = "Защита от подмены и отслеживания DNS-запросов"
    override val xhttpModeTitle: String = "Режим XHTTP (SplitHTTP)"
    override val xhttpModeSubtitle: String = "stream-one оптимизирован для голосовых в Telegram"
    override val fakeDnsTitle: String = "Strict FakeDNS (198.18.0.0/15)"
    override val fakeDnsSubtitle: String = "Локальный пул Fake-IP. Защита от подмены DNS со стороны провайдера."
    override val blockQuicTitle: String = "Блокировка QUIC (UDP 443)"
    override val blockQuicSubtitle: String = "Принудительный fallback на TLS. Защищает YouTube, Discord и браузеры."
    override val sniffingTitle: String = "Сниффинг доменов (RouteOnly)"
    override val sniffingSubtitle: String = "Определяет реальный домен (SNI/HTTP) из трафика для маршрутизации по правилам без подмены адреса назначения."
    override val blockIpv6Title: String = "Защита от утечек IPv6"
    override val blockIpv6Subtitle: String = "Блокирует нешифрованный трафик IPv6 для исключения раскрытия адреса."
    override val bypassAiTitle: String = "Разблокировка нейросетей (AI)"
    override val bypassAiSubtitle: String = "SmartDNS: выбранный DNS и прямой маршрут. VPN: через VPN-сервер."
    override val aiDnsProviderTitle = "SmartDNS для нейросетей"
    override val aiDnsProviderHint = "Выбирается отдельно от общего DNS. Доступность Gemini и ChatGPT зависит от провайдера SmartDNS, сети и аккаунта."
    override val aiTlsFragmentTitle = "TLS-фрагментация SmartDNS"
    override val aiTlsFragmentHint = "Дополнительный режим для сетей с фильтрацией TLS. По умолчанию выключен: некоторые прокси Gemini с ним несовместимы."
    override val adBlockerTitle: String = "Блокировщик рекламы"
    override val adBlockerSubtitle: String = "Блокирует рекламные домены и трекеры в трафике через VPN: Яндекс, VK / Mail.ru и другие сети. Встроенная реклама в ленте VK и видео YouTube может оставаться."

    override val sectionCore: String = "ЯДРО XRAY-CORE"
    override val coreVersion: String = "Версия ядра"
    override val coreArch: String = "Архитектура"
    override val coreTunnel: String = "Туннель"
    override val coreProtocols: String = "Протоколы"
    override val coreFakeDns: String = "FakeDNS Pool"

    override val sectionDiagnostics: String = "ДИАГНОСТИКА И ОТЛАДКА"
    override val diagnosticsDesc: String = "Журнал работы ядра Xray, событий VPN и сетевых ошибок. Позволяет скопировать или отправить диагностический отчёт."
    override val openLogsButton: String = "Открыть журнал и логи ошибок"
    override val logsDialogTitle: String = "Журнал событий и логи ядра"
    override val copyReport: String = "Скопировать отчёт"
    override val shareReport: String = "Поделиться"
    override val clearLogs: String = "Очистить логи"
    override val reportCopied: String = "Отчёт скопирован в буфер обмена!"
    override val recordsCount: String = "Записей"
    override val logsCleared: String = "Логи очищены"
    override val logsEmpty: String = "Логи отсутствуют"
    override val logsFilterAll: String = "Все"
    override val logsFilterErrors: String = "Ошибки"
    override val logsFilterXray: String = "Xray"
    override val logsFilterVpn: String = "VPN Служба"
    override val logsSearchHint: String = "Поиск по логам и ключевым словам..."
    override val logsAutoscroll: String = "Автопрокрутка"
    override val logsClose: String = "Закрыть журнал"
    override val logsCopiedLine: String = "Строка лога скопирована"
    override val logsStatsTotal: String = "Всего"
    override val logsStatsErrors: String = "Ошибки"
    override val logsStatsWarnings: String = "Внимание"

    override val sectionCustomRoutes: String = "КАСТОМНЫЕ МАРШРУТЫ ПО ДОМЕНУ"
    override val customRoutesSubtitle: String = "Введите домен и выберите режим: напрямую, через VPN или заблокировать"
    override val customRoutesDomainHint: String = "example.com или domain:example.com"
    override val customRoutesAddBtn: String = "ДОБАВИТЬ МАРШРУТ"
    override val customRoutesEmpty: String = "Кастомных маршрутов нет"
    override val customRoutesSaved: String = "Маршрут добавлен"
    override val customRoutesRemoved: String = "Маршрут удален"
    override val customRouteDirect: String = "Напрямую"
    override val customRouteProxy: String = "Через VPN"
    override val customRouteBlock: String = "Блокировать"

    override val updateAvailableTitle: String = "Доступно обновление"
    override val updateAvailableDesc: String = "Версия %s готова к загрузке"
    override val updateDownload: String = "Скачать"
    override val updateDownloadAndInstall: String = "Скачать и установить"
    override val updateInstall: String = "Установить"
    override val updateDownloading: String = "Загрузка обновления..."
    override val updateDownloaded: String = "Обновление готово к установке"
    override val updateTapToInstall: String = "Нажмите для установки"
    override val updateChangelog: String = "Что нового"
    override val updateScrollHint: String = "Листайте вниз для подробностей ↓"
    override val updateExpandNotes: String = "Развернуть"
    override val updateCollapseNotes: String = "Свернуть"
    override val updatePermissionRequired: String = "Разрешите установку приложений для SplateVPN"
    override val updateFailed: String = "Ошибка скачивания обновления"
    override val updateRetry: String = "Повторить"
    override val updateCheck: String = "Проверить обновления"
    override val updateChecking: String = "Проверка..."
    override val updateLatest: String = "У вас установлена последняя версия"

    // Real-Time Traffic & Live Connections Monitor
    override val liveConnectionsTitle: String = "Монитор соединений"
    override val liveConnectionsSubtitle: String = "Маршрутизация трафика по доменам и IP в реальном времени"
    override val liveConnectionsHudBadge: String = "Соединения"
    override val liveConnectionsSearchPlaceholder: String = "Поиск домена или IP адреса..."
    override val filterAll: String = "Все"
    override val filterProxy: String = "Прокси"
    override val filterDirect: String = "Прямые"
    override val filterBlock: String = "Блок"
    override val filterDns: String = "DNS"
    override val clearConnections: String = "Очистить список"
    override val noConnectionsYet: String = "Нет активных соединений"
    override val noConnectionsDesc: String = "Подключите VPN для отслеживания входящих и исходящих потоков трафика в реальном времени"
    override val liveConnectionsCount: String = "%d активных узлов"
    override val liveConnectionsSummary: String = "%d узлов • %d запросов"
    override val connectionOutboundLabel: String = "Маршрут"
    override val connectionDestLabel: String = "Назначение"
    override val connectionProtocolLabel: String = "Протокол"

    // Auto Connect on Boot & Battery
    override val autoConnectOnBootTitle: String = "Автозапуск при старте"
    override val autoConnectOnBootSubtitle: String = "Автоматически подключать VPN при включении устройства"
    override val batteryOptimizationTitle: String = "Работа в фоне (Батарея)"
    override val batteryOptimizationDisabled: String = "Оптимизация отключена — служба защищена от закрытия"
    override val batteryOptimizationPrompt: String = "Нажмите для отключения ограничений батареи"
    override val batterySettingsOpenError: String = "Не удалось открыть настройки батареи"

    // Ping & Protocol Extras
    override val pingModeTitle: String = "Режим пинга (Ping Mode)"
    override val pingModeSubtitle: String = "Метод проверки доступности серверов"
    override val pingModeRealDelay: String = "Real Delay"
    override val pingModeTcp: String = "TCP Ping"
    override val pingModeIcmp: String = "ICMP"
    override val pingModeRealDelayDesc: String = "HTTP 204 через туннель — проверяет реальную проходимость данных и выявляет блокировки ТСПУ"
    override val pingModeTcpDesc: String = "Быстрое измерение сетевой задержки подключения к IP и порту сервера"
    override val pingModeIcmpDesc: String = "Прямой ICMP эхо-запрос к хосту сервера (может блокироваться фаерволом)"
    override val tlsFragmentTitle: String = "Фрагментация пакетов TLS (Fragment)"
    override val tlsFragmentSubtitle: String = "Дробит пакеты рукопожатия TLS на фрагменты 100-200 байт с микрозадержкой. Защищает от блокировок ТСПУ/DPI по SNI даже на незамаскированных TLS-серверах."
    override val killSwitchActive = "Защита включена"
    override val killSwitchAlwaysOnly = "Только постоянная VPN"
    override val killSwitchOff = "Блокировка выключена"
    override val killSwitchUnknown = "Статус не подтверждён"
    override val killSwitchUnknownHint = "Android не предоставил статус защиты. Он может быть недоступен без активного туннеля. Проверьте параметры VPN в настройках Android."
    override val killSwitchBody = "При включении Android блокирует подключения без VPN, в том числе при остановке приложения."
    override val killSwitchStepOne = "Откройте параметры %s в списке VPN Android."
    override val killSwitchStepTwo = "Включите «Постоянная VPN»."
    override val killSwitchStepThree = "Включите «Блокировать соединения без VPN»."
    override val killSwitchConfigure = "Настроить защиту"
    override val killSwitchManage = "Управление защитой"
    override val killSwitchExcludedApps = "Приложения, исключённые из VPN, потеряют доступ к интернету при включённой блокировке."
    override val killSwitchTitle: String = "Kill Switch"

    // App Updates Section
    override val sectionAppUpdates: String = "ОБНОВЛЕНИЯ ПРИЛОЖЕНИЯ"

    // Quick Settings
    override val sectionQuickSettings: String = "БЫСТРЫЕ НАСТРОЙКИ (ШТОРКА)"
    override val quickSettingsDesc: String = "Плитка быстрого переключения VPN прямо из верхней шторки уведомлений Android без необходимости открывать приложение."
    override val quickSettingsAddTile: String = "Добавить плитку в шторку"
    override val quickSettingsTileAdded: String = "Плитка SplateVPN успешно добавлена в шторку!"
    override val quickSettingsTileAlreadyAdded: String = "Плитка SplateVPN уже есть в шторке"
    override val quickSettingsManualPrompt: String = "Опустите шторку уведомлений дважды, нажмите значок карандаша (Редактировать) и перетащите плитку SplateVPN на панель."

    // Backup & Restore
    override val sectionBackupRestore: String = "РЕЗЕРВНОЕ КОПИРОВАНИЕ И ВОССТАНОВЛЕНИЕ"
    override val backupRestoreDesc: String = "Экспорт всех сохранённых серверов, подписок, кастомных правил маршрутизации и настроек в JSON-файл или восстановление из резервной копии."
    override val exportBtn: String = "ЭКСПОРТ"
    override val importBtn: String = "ИМПОРТ"
    override val backupExportSuccess: String = "Конфигурация успешно экспортирована!"
    override val backupExportError: String = "Ошибка экспорта: %s"
    override val backupImportSuccess: String = "Успешно импортировано серверов: %d"
    override val backupImportError: String = "Ошибка импорта: %s"
    override val autoRestoreBtn: String = "ВОССТАНОВИТЬ АВТОБЭКАП"
    override val autoRestoreSuccess: String = "Успешно восстановлено серверов: %d"
    override val autoRestoreNotFound: String = "Автоматический бэкап не найден или пуст"

    // Speedtest & Status Badges
    override val autoSelectOn: String = "⚡ АВТО: ВКЛ"
    override val speedtestTitle: String = "Тест скорости"
    override val speedtestDesc: String = "Замер реальной скорости загрузки через VPN"
    override val speedtestRunning: String = "ТЕСТИРОВАНИЕ: %d%%"
    override val speedtestSpeedUnit: String = "Мбит/с"
    override val speedtestResult: String = "СКОРОСТЬ: %s Мбит/с"
    override val speedtestLatency: String = "Отклик HTTPS: %s мс"
    override val speedtestError: String = "Ошибка теста: %s"

    // Additional Node & App labels
    override val serversAddedCount: String = "Добавлено серверов: %d"
    override val qrScanError: String = "Ошибка сканирования: %s"
    override val scanQr: String = "Сканировать QR"
    override val copyLink: String = "Копировать ссылку"
    override val autoSelectBalancer: String = "Автовыбор сервера"
    override val activeNodePrefix: String = "Активен: %s"
    override val autoSelectBalancerDesc: String = "Автоматический выбор лучшего сервера и защита от сбоев"
    override val onUpper: String = "ВКЛ"
    override val offUpper: String = "ВЫКЛ"
    override val filterSelected: String = "Выбранные"
    override val selectAll: String = "Все"
    override val resetAll: String = "Сброс"

    // Unified Add & Empty State
    override val unifiedAddTitle: String = "Добавить сервер или подписку"
    override val unifiedAddSubtitle: String = "Вставьте ссылку VLESS/VMess/SS/Trojan или URL подписки"
    override val pasteFromClipboard: String = "Вставить из буфера"
    override val scanQrCode: String = "Сканировать QR"
    override val clipboardEmpty: String = "Буфер обмена пуст"
    override val detectedSubscription: String = "🔗 Ссылка на подписку"
    override val detectedServerNode: String = "⚡ Конфигурация сервера"
    override val detectedMultipleNodes: String = "📦 Найдено серверов: %d"
    override val emptyServersCta: String = "Добавить первый сервер или подписку"
    override val emptyServersHint: String = "Вставьте ссылку из буфера обмена или сканируйте QR-код для начала работы"
    override val importAction: String = "Импортировать"
    override val subNameOptional: String = "Название подписки (необязательно)"
    override val addServerOrSub: String = "Добавить сервер или подписку"

    // Subscription Quota & Userinfo
    override val subQuotaUsed: String = "Использовано: %s из %s"
    override val subQuotaRemaining: String = "Осталось %s из %s"
    override val subQuotaUnlimited: String = "Без лимита трафика"
    override val subExpireDate: String = "Истекает: %s"
    override val subExpired: String = "Срок подписки истёк"

    // Server Share & QR Dialog
    override val shareServerTitle: String = "Экспорт сервера"
    override val shareQrPrompt: String = "Отсканируйте камерой другого устройства"
    override val shareCopyLink: String = "Копировать ссылку"
    override val shareSend: String = "Поделиться"
    override val shareAction: String = "Поделиться / QR"
    override val sharePayloadTooLarge: String = "Конфигурация слишком велика для QR-кода (%d байт). Скопируйте ссылку или поделитесь текстом."
    override val shareSubscriptionTitle: String = "Экспорт подписки"
    override val shareSubQrPrompt: String = "Отсканируйте камерой другого устройства для импорта подписки"

    // LAN / Hotspot / USB Proxy Sharing
    override val sectionShareLan: String = "Раздача VPN (Точка доступа / USB)"
    override val shareLanTitle: String = "Раздача VPN в локальную сеть"
    override val shareLanSubtitle: String = "Запускает HTTP и SOCKS5 прокси для подключений по Wi-Fi точке доступа и USB-модему"
    override val shareLanActiveEndpoints: String = "Обнаруженные адреса раздачи"
    override val shareLanNoEndpoints: String = "Точка доступа или USB-модем не обнаружены. Включите раздачу в настройках телефона."
    override val shareLanInstructionsBtn: String = "Инструкция по настройке ПК / ТВ"
    override val shareLanHttpProxy: String = "HTTP Прокси (Windows / Mac / ТВ)"
    override val shareLanSocksProxy: String = "SOCKS5 Прокси"
    override val shareLanCopied: String = "Адрес прокси скопирован: %s"
    override val shareLanDialogTitle: String = "Подключение ПК и устройств через телефон"
    override val shareLanStep1: String = "1. Включите на телефоне Точку доступа Wi-Fi или USB-модем и подключите к нему компьютер/ноутбук."
    override val shareLanStep2: String = "2. Убедитесь, что VPN в SplateVPN подключен."
    override val shareLanStep3Windows: String = "3. На Windows: Параметры ➔ Сеть и Интернет ➔ Прокси-сервер ➔ Настройка вручную ➔ введите IP-адрес и порт 10809."
    override val shareLanStep3Mac: String = "4. На macOS / Linux: Настройки сети ➔ Прокси ➔ включите Веб-прокси (HTTP/HTTPS) с указанным IP и портом 10809."
    override val shareLanStep3Telegram: String = "5. В Telegram / отдельных программах: Настройки ➔ Данные и память ➔ Прокси ➔ Добавить SOCKS5 (порт 10808) или HTTP (порт 10809)."
    override val shareLanWindowsTitle: String = "Настройка на Windows (Wi-Fi или USB)"
    override val shareLanWindowsSettingsGuide: String = "В Windows 10/11: Параметры ➔ Сеть и Интернет ➔ Прокси-сервер ➔ Настройка вручную ➔ включите «Использовать прокси-сервер»."
    override val shareLanWindowsInetcplGuide: String = "Либо нажмите Win+R ➔ введите inetcpl.cpl ➔ вкладка «Подключения» ➔ «Настройка сети»."
    override val shareLanWindowsProxyString: String = "Строка прокси (HTTP / HTTPS):"
    override val shareLanWindowsExceptions: String = "Исключения для локальных адресов (не проксировать):"
    override val shareLanWindowsExceptionsValue: String = "localhost;127.*;10.*;192.168.*;172.16.*"
    override val shareLanWindowsCmdEnable: String = "Команда включения в 1 клик (PowerShell / CMD):"
    override val shareLanWindowsCmdDisable: String = "Команда выключения прокси (PowerShell / CMD):"
    override val shareLanExceptionsCopied: String = "Исключения скопированы"
    override val shareLanCommandCopied: String = "Команда для Windows скопирована"

    // TLS Fragmentation Tuning
    override val tlsFragmentPresetTitle: String = "Профиль фрагментации"
    override val tlsFragmentPresetStandard: String = "Стандарт (100-200, 10-20)"
    override val tlsFragmentPresetAggressive: String = "Агрессивный (50-100, 20-40, 1-3)"
    override val tlsFragmentPresetCustom: String = "Пользовательский"
    override val tlsFragmentLengthLabel: String = "Размер чанков (байт)"
    override val tlsFragmentIntervalLabel: String = "Интервал (мс)"
    override val tlsFragmentPacketsLabel: String = "Пакеты"

    // Custom DoH DNS
    override val dnsCustom: String = "Свой DoH DNS"
    override val dnsCustomUrlHint: String = "https://dns.nextdns.io/..."
    override val dnsCustomUrlDialogTitle: String = "Пользовательский DNS-over-HTTPS"

    // Multiplexing (Mux)
    override val muxTitle: String = "Мультиплексирование (Mux)"
    override val muxSubtitle: String = "Объединение TCP-потоков в один туннель (ускоряет открытие страниц, несовместимо с Vision)"
    override val muxConcurrencyLabel: String = "Параллельных сессий"
}
val RussianStrings: AppStrings = RussianAppStrings()

class EnglishAppStrings : AppStrings {
    override val appName: String = "SplateVPN"
    override val cancel: String = "Cancel"
    override val save: String = "Save"
    override val delete: String = "Delete"
    override val close: String = "Close"
    override val add: String = "Add"
    override val refresh: String = "Refresh"
    override val edit: String = "Edit"

    override val tabControl: String = "HOME"
    override val tabNodes: String = "SERVERS"
    override val tabRouting: String = "APPS"
    override val tabConfig: String = "SETTINGS"

    override val standbyUnprotected: String = "STANDBY • UNPROTECTED"
    override val encryptedSession: String = "CONNECTED • %s"
    override val resolvingLocation: String = "RESOLVING LOCATION..."
    override val protectedGateway: String = "PROTECTED TUNNEL"
    override val egressIp: String = "SERVER IP: %s"
    override val ping: String = "PING"
    override val download: String = "DOWNLOAD"
    override val upload: String = "UPLOAD"
    override val routingProfile: String = "ROUTING PROFILE"
    override val smartRoute: String = "SMART ROUTE"
    override val smartRouteDesc: String = "Bypass local RU services"
    override val globalTunnel: String = "ALL TRAFFIC"
    override val globalTunnelDesc: String = "All destinations through VPN"
    override val activeGateway: String = "ACTIVE SERVER"
    override val autoFastest: String = "FASTEST"
    override val pingNoResponse = "No response"
    override val pingUntested = "Untested"
    override val pinging: String = "PINGING..."
    override val noServerSelected: String = "No server selected"
    override val tapToChooseNode: String = "Tap to choose a server"
    override val coreConnected: String = "CONNECTED"
    override val coreConnect: String = "CONNECT"
    override val latencyLabel: String = "LATENCY"
    override val downlinkLabel: String = "DOWNLINK"
    override val uplinkLabel: String = "UPLINK"
    override val coreSubtitle: String = "CORE ${com.danieldk.splatevpn.SplateConstants.XRAY_VERSION} // XRAY-ENGINE"
    override val stateSelectingServer: String = "FINDING SERVER..."
    override val coreDiscSelect: String = "SEARCH..."
    override val stateStartingCore: String = "STARTING CORE..."
    override val stateConnecting: String = "CONNECTING..."
    override val stateHealthChecking: String = "VERIFYING LINK..."
    override val stateReconnecting: String = "RECONNECTING..."
    override val stateFailed: String = "CONNECTION FAILED"
    override val coreDiscBoot: String = "BOOT..."
    override val coreDiscLink: String = "LINK..."
    override val coreDiscVerify: String = "VERIFY..."
    override val coreDiscRetry: String = "RETRY..."
    override val coreDiscError: String = "ERROR"
    override val tagSplitTunnel: String = "SPLIT-TUNNEL"
    override val tagFullEncap: String = "GLOBAL"
    override val searchServers: String = "Search by name, IP, protocol..."
    override val noServersMatchingQuery: String = "No servers match your search"
    override val linkCopied: String = "Link copied to clipboard"
    override val sessionDownloaded: String = "RECEIVED"
    override val sessionUploaded: String = "SENT"

    override val networkNodes: String = "SERVERS"
    override val presetServersTitle: String = "PRESETS"
    override val availableNodes: String = "AVAILABLE: %d / %d"
    override val pingAll: String = "PING ALL"
    override val pasteLink: String = "PASTE LINK"
    override val subs: String = "SUBS (%d)"
    override val allChip: String = "All (%d)"
    override val builtinChip: String = "⚡ Presets"
    override val noServersFound: String = "No servers available.\nAdd a link or subscription."
    override val noServersInFilter: String = "No servers in this category."
    override val addServerTitle: String = "Add Server"
    override val addServerPrompt: String = "Paste vless://, trojan://, vmess://, or ss:// link:"
    override val serverAddedSuccess: String = "Server added successfully!"
    override val invalidLinkFormat: String = "Invalid link format"
    override val editServerTitle: String = "Edit Server Parameters"
    override val serverUpdatedSuccess: String = "Server parameters updated!"
    override val manageSubsTitle: String = "Subscription Management"
    override val activeSubsHeader: String = "Active Subscriptions:"
    override val serversCountSub: String = "%d nodes"
    override val subBuiltinTag: String = "Preset"
    override val subUpdatedSuccess: String = "Updated: %d nodes"
    override val subDeletedSuccess: String = "Subscription removed"
    override val addNewSubHeader: String = "Add New Subscription:"
    override val serverToolsFolders = "Folders"
    override val serverToolsList = "List"
    override val serverToolsGrouping = "Server grouping"
    override val serverToolsGrouped = "Grouped by source"
    override val serverToolsUngrouped = "All nodes in one list"
    override val serverToolsSort = "Sort"
    override val serverToolsSortDescription = "Sort by latency"
    override val serverToolsAuto = "Auto"
    override val serverToolsTest = "Test"
    override val serversPageTitle = "Servers"
    override val presetsPageTitle = "Presets"
    override val serversPageSubtitle = "Choose a server and check latency"
    override val subscriptionsPageSubtitle = "Manage sources and update server lists"
    override val personalPageSubtitle = "Your own VPN configurations"
    override val subscriptionsTitle: String = "Subscriptions"
    override val subscriptionsSearch: String = "Search by name or URL…"
    override val subscriptionsNoResults: String = "No matching subscriptions"
    override val subscriptionDetails: String = "Subscription details"
    override val subscriptionName: String = "Name"
    override val subscriptionUrl: String = "Subscription URL"
    override val subscriptionUrlInvalid: String = "Enter a valid subscription URL"
    override val subscriptionEditHint: String = "URL changes apply on the next update. Existing nodes are retained."
    override val subscriptionAutoUpdate: String = "Auto-update"
    override val subscriptionManual: String = "Manual"
    override val subscriptionScheduleOff: String = "The global schedule is disabled in Settings"
    override val subscriptionSchedule: String = "Update every %s"
    override val subscriptionUpdateAll: String = "Update all"
    override val subscriptionRefreshSummary: String = "Updated subscriptions: %d. Failed: %d."
    override val subscriptionNoUpdates: String = "No enabled subscriptions to update"
    override val subscriptionBuiltIn: String = "Built-in"
    override val subscriptionDisabled: String = "Disabled"
    override val subscriptionNodes: String = "Nodes (%d)"
    override val subscriptionDeleteTitle: String = "Delete subscription?"
    override val subscriptionDeleteMessage: String = "Delete “%s” and all its nodes (%d)?"
    override val subscriptionNever: String = "Never"
    override val subscriptionJustNow: String = "Just now"
    override val subscriptionMinutesAgo: String = "%d min ago"
    override val subscriptionHoursAgo: String = "%d h ago"
    override val subscriptionsTab: String = "Subscriptions (%d/%d)"
    override val personalNodesTab: String = "Personal (%d)"
    override val subscriptionEnabledMessage: String = "“%s”: enabled"
    override val subscriptionDisabledMessage: String = "“%s”: disabled, nodes hidden"
    override val subscriptionAutoEnabledMessage: String = "Auto-update enabled"
    override val subscriptionAutoDisabledMessage: String = "Auto-update disabled"
    override val subscriptionNodesLabel: String = "Nodes"
    override val subscriptionsLabel: String = "Subscriptions"
    override val personalNodesLabel: String = "Personal"
    override val uiPause: String = "Pause"
    override val uiResume: String = "Resume"
    override val uiClear: String = "Clear"
    override val uiMonitoring: String = "Monitoring"
    override val uiPaused: String = "Paused"
    override val uiNoMatches: String = "No results for this search"
    override val uiNoPersonal: String = "No personal configurations"
    override val uiPersonalHint: String = "Paste a vless://, vmess://, trojan:// or shadowsocks:// link from the clipboard or scan a QR code."
    override val uiAddConfig: String = "Add configuration"
    override val uiNoSubscriptions: String = "No subscriptions"
    override val uiSubscriptionHint: String = "A subscription keeps multiple nodes up to date using a single link."
    override val uiAddSubscription: String = "Add subscription"
    override val uiRestorePresets: String = "Restore built-in subscriptions"
    override val uiPresetsEmpty: String = "Built-in subscriptions have not been loaded or were deleted."
    override val uiPersonalSearch: String = "Search personal configurations…"
    override val uiPresetSearch: String = "Search preset nodes…"
    override val uiAllActive: String = "All enabled (%d)"
    override val uiPersonalServers: String = "Personal servers"
    override val uiSubscriptionPrefix: String = "Subscription: %s"
    override val uiDisabledNodesHidden: String = "Disabled — nodes hidden"
    override val uiExpand: String = "Expand"
    override val uiCollapse: String = "Collapse"
    override val uiPresetTag: String = "Preset • %s"
    override val uiSubscriptionTag: String = "Subscription • %s"
    override val uiPersonalTag: String = "Personal configuration"
    override val uiActions: String = "Actions"
    override val uiAutoSelect: String = "Auto-select"
    override val uiFastest: String = "fastest"
    override val uiOff: String = "off"
    override val uiPingSummary: String = "“%s”: tested %d nodes"
    override val uiCamera: String = "Camera"
    override val uiAutoImport: String = "Auto-import"
    override val uiLinkHint: String = "vless://…, vmess://… or https://…"
    override val uiPresetHarvester: String = "Harvester (Working nodes)"
    override val uiPresetCidr: String = "Allowlist (CIDR RU)"
    override val uiPresetUniversal: String = "Allowlist (Universal)"
    override val appRoutingConnected: String = "VPN is connected. Apps follow the selected routing rules."
    override val appRoutingDisconnected: String = "VPN is disconnected. Connect on the Home tab to apply routing."
    override val fieldAddressError: String = "Enter a hostname or IP without a scheme, path or spaces"
    override val fieldPortError: String = "Enter a whole port number from 1 to 65535"
    override val fieldPassword: String = "Password"
    override val fieldSniHint: String = "domain.com (for Cloudflare/TLS)"
    override val fieldHostHint: String = "HTTP Host header"
    override val tlsPresetStandard: String = "Standard"
    override val tlsPresetAggressive: String = "DPI (50–100)"
    override val tlsPresetCustom: String = "Custom"
    override val tlsCustomTitle: String = "TLS fragmentation settings"
    override val tlsParametersSummary: String = "Parameters: length=%s, interval=%s ms, packets=%s"
    override val shareLanNotReady: String = "Sharing is not ready. Proxy addresses and setup commands will appear when a hotspot or USB tethering address is available."
    override val shareLanCopyEnable: String = "Copy enable command"
    override val shareLanDisable: String = "Disable proxy"
    override val uiCopied: String = "Copied"
    override val uiCopy: String = "Copy"
    override val lanEndpointUsb: String = "USB tethering (%s)"
    override val lanEndpointHotspot: String = "Wi-Fi hotspot (%s)"
    override val lanEndpointWifi: String = "Wi-Fi network (%s)"
    override val lanEndpointOther: String = "Interface %s"
    override val subNamePlaceholder: String = "Name (e.g. My VPN)"
    override val subUrlPlaceholder: String = "https://example.com/sub/..."
    override val subLoading: String = "Syncing nodes..."
    override val splashSubtitle: String = "Secure Proxy Tunnel"
    override val splashInitializing: String = "Initializing nodes..."
    override val subAddedSuccess: String = "Added nodes: %d"
    override val errorPrefix: String = "Error: %s"
    override val autoSelectSelected: String = "Selected: %s"

    override val fieldName: String = "Node Name"
    override val fieldProtocol: String = "Protocol"
    override val fieldAddress: String = "Address / IP"
    override val fieldPort: String = "Port"
    override val fieldNetwork: String = "Network Transport (type)"
    override val fieldSecurity: String = "Security Mode"
    override val fieldSni: String = "SNI Domain"
    override val fieldPath: String = "Path (path / serviceName)"
    override val fieldHost: String = "Host Header"
    override val fieldUuid: String = "UUID / Password"
    override val fieldPublicKey: String = "Public Key (pbk / Reality)"
    override val fieldShortId: String = "Short ID (sid)"
    override val fieldSpiderX: String = "SpiderX (spx)"
    override val fieldFingerprint: String = "Fingerprint (fp)"

    override val appRoutingTitle: String = "App routing"
    override val appRoutingSubtitle: String = "Choose which apps follow VPN routing rules"
    override val proxiedAppsCount: String = "PROXIED APPS: %d / %d"
    override val selectedAppsCount: String = "Selected: %d"
    override val proxyModeAll: String = "All applications"
    override val proxyModeAllDesc: String = "When VPN is connected, all apps follow the selected routing rules"
    override val proxyModeSelected: String = "Selective Mode (Per-App)"
    override val proxyModeSelectedDesc: String = "Only selected applications are routed through VPN"
    override val globalModeActive: String = "All apps selected"
    override val allAppsProtected: String = "All installed applications are protected"
    override val searchApps: String = "Search applications..."
    override val statusProxied: String = "PROXIED"
    override val statusDirect: String = "DIRECT"
    override val statusBypassed: String = "BYPASS"
    override val perAppModeInclude: String = "Only Selected"
    override val perAppModeIncludeDesc: String = "Only selected applications are routed via VPN"
    override val perAppModeExclude: String = "Bypass Selected"
    override val perAppModeExcludeDesc: String = "Selected applications (banking, local apps) will bypass VPN"
    override val perAppModeHeader: String = "SPLIT TUNNELING MODE"

    override val settingsTitle: String = "Settings"
    override val settingsSubtitle: String = "Censorship bypass, UI appearance and core engine config"
    override val sectionAppearance: String = "INTERFACE & APPEARANCE"
    override val themeTitle: String = "App Theme"
    override val themeSubtitle: String = "Visual style and color palette"
    override val themeDark: String = "Dark"
    override val themeAmoled: String = "AMOLED"
    override val themeLight: String = "Light"
    override val themeSystem: String = "System"
    override val themeCyberpunk: String = "Cyberpunk"
    override val themeOnePlus: String = "OnePlus Red"
    override val themeAurora: String = "Aurora"
    override val themeOcean = "Ocean Depths"
    override val themeSakura = "Moonlit Sakura"
    override val themePony = "Pony"
    override val themeWinx = "Winx · Bloom"
    override val glassTransparencyTitle = "Card transparency"
    override val glassTransparencySubtitle = "Card and bottom dock transparency. Text and controls stay sharp."
    override val glassBlurTitle = "Background blur"
    override val glassBlurSubtitle = "Blur behind cards and the bottom dock. Transparency stays independent."
    override val glassDefault = "Default"
    override val glassMoreSolid = "More solid"
    override val glassMoreClear = "More transparent"
    override val glassLegacyHint = "Background blur is available on Android 12 and newer."
    override val themeShowAll = "Show all themes (%d)"
    override val themeShowLess = "Show fewer themes"
    override val themeCurrent = "Selected: %s"
    override val themeSunset: String = "Sunset Mirage"
    override val languageTitle: String = "Language"
    override val languageSubtitle: String = "Interface language selection"
    override val langRu: String = "Русский"
    override val langEn: String = "English"
    override val langSystem: String = "System"

    override val sectionAutomation: String = "AUTOMATION & SUBSCRIPTIONS"
    override val autoSelectTitle: String = "Auto-select Server"
    override val autoSelectSubtitle: String = "Automatically test latency and select the fastest node."
    override val autoUpdateTitle: String = "Auto-update Subscriptions"
    override val autoUpdateSubtitle: String = "Frequency of background server list synchronization"
    override val intervalOff: String = "Off"
    override val interval6h: String = "6 h"
    override val interval12h: String = "12 h"
    override val interval24h: String = "24 h"
    override val updateAllSubsNow: String = "Update all subscriptions now"
    override val updating: String = "Updating..."
    override val updatedCount: String = "Updated subscriptions: %d"

    override val sectionRouting: String = "ROUTING & ANTI-CENSORSHIP"
    override val dnsProviderTitle: String = "DNS Provider (DoH + UDP)"
    override val dnsProviderSubtitle: String = "Protection against DNS spoofing and ISP tracking"
    override val xhttpModeTitle: String = "XHTTP (SplitHTTP) Mode"
    override val xhttpModeSubtitle: String = "stream-one is optimized for voice and streaming traffic"
    override val fakeDnsTitle: String = "Strict FakeDNS (198.18.0.0/15)"
    override val fakeDnsSubtitle: String = "Local Fake-IP pool. Prevents censorship DNS poisoning."
    override val blockQuicTitle: String = "Block QUIC (UDP 443)"
    override val blockQuicSubtitle: String = "Forces fallback to TLS. Unblocks YouTube, Discord, and browsers."
    override val sniffingTitle: String = "Domain Sniffing (RouteOnly)"
    override val sniffingSubtitle: String = "Extracts target domain (SNI/HTTP) from traffic for routing rules without rewriting destination address."
    override val blockIpv6Title: String = "IPv6 Leak Protection"
    override val blockIpv6Subtitle: String = "Blocks unencrypted IPv6 to prevent real address leaks."
    override val bypassAiTitle: String = "AI Services (ChatGPT, Claude & Gemini)"
    override val bypassAiSubtitle: String = "SmartDNS: selected DNS and direct routing. VPN: via the VPN server."
    override val aiDnsProviderTitle = "SmartDNS for AI services"
    override val aiDnsProviderHint = "Independent of general DNS. Gemini and ChatGPT availability depends on the SmartDNS provider, network and account."
    override val aiTlsFragmentTitle = "SmartDNS TLS fragmentation"
    override val aiTlsFragmentHint = "Optional mode for networks filtering TLS. Off by default: some Gemini proxies are incompatible with it."
    override val adBlockerTitle: String = "Ad Blocker"
    override val adBlockerSubtitle: String = "Blocks advertising and tracking domains in VPN traffic: Yandex, VK / Mail.ru and other networks. Embedded VK feed and YouTube video ads may remain."

    override val sectionCore: String = "XRAY CORE ENGINE"
    override val coreVersion: String = "Core Version"
    override val coreArch: String = "Architecture"
    override val coreTunnel: String = "Tunnel Engine"
    override val coreProtocols: String = "Protocols"
    override val coreFakeDns: String = "FakeDNS Pool"

    override val sectionDiagnostics: String = "DIAGNOSTICS & DEBUGGING"
    override val diagnosticsDesc: String = "Logs of Xray core execution, VPN events and connection errors."
    override val openLogsButton: String = "Open Logs & Error Journal"
    override val logsDialogTitle: String = "System Events & Core Logs"
    override val copyReport: String = "Copy Report"
    override val shareReport: String = "Share"
    override val clearLogs: String = "Clear Logs"
    override val reportCopied: String = "Report copied to clipboard!"
    override val recordsCount: String = "Records"
    override val logsCleared: String = "Logs cleared"
    override val logsEmpty: String = "No logs recorded"
    override val logsFilterAll: String = "All"
    override val logsFilterErrors: String = "Errors"
    override val logsFilterXray: String = "Xray"
    override val logsFilterVpn: String = "VPN Service"
    override val logsSearchHint: String = "Search logs and keywords..."
    override val logsAutoscroll: String = "Autoscroll"
    override val logsClose: String = "Close Logs"
    override val logsCopiedLine: String = "Log line copied"
    override val logsStatsTotal: String = "Total"
    override val logsStatsErrors: String = "Errors"
    override val logsStatsWarnings: String = "Warnings"

    override val sectionCustomRoutes: String = "CUSTOM DOMAIN ROUTES"
    override val customRoutesSubtitle: String = "Enter a domain and pick a routing mode: direct, proxy, or block"
    override val customRoutesDomainHint: String = "example.com or domain:example.com"
    override val customRoutesAddBtn: String = "ADD ROUTE"
    override val customRoutesEmpty: String = "No custom routes defined"
    override val customRoutesSaved: String = "Route added"
    override val customRoutesRemoved: String = "Route removed"
    override val customRouteDirect: String = "Direct"
    override val customRouteProxy: String = "Via VPN"
    override val customRouteBlock: String = "Block"

    override val updateAvailableTitle: String = "Update Available"
    override val updateAvailableDesc: String = "Version %s is ready to download"
    override val updateDownload: String = "Download"
    override val updateDownloadAndInstall: String = "Download & Install"
    override val updateInstall: String = "Install"
    override val updateDownloading: String = "Downloading update..."
    override val updateDownloaded: String = "Update ready to install"
    override val updateTapToInstall: String = "Tap to install"
    override val updateChangelog: String = "What's new"
    override val updateScrollHint: String = "Scroll down for details ↓"
    override val updateExpandNotes: String = "Expand"
    override val updateCollapseNotes: String = "Collapse"
    override val updatePermissionRequired: String = "Please allow installing unknown apps for SplateVPN"
    override val updateFailed: String = "Failed to download update"
    override val updateRetry: String = "Retry"
    override val updateCheck: String = "Check for updates"
    override val updateChecking: String = "Checking..."
    override val updateLatest: String = "You are on the latest version"

    // Real-Time Traffic & Live Connections Monitor
    override val liveConnectionsTitle: String = "Live Connections Monitor"
    override val liveConnectionsSubtitle: String = "Real-time traffic routing by domain and IP"
    override val liveConnectionsHudBadge: String = "Connections"
    override val liveConnectionsSearchPlaceholder: String = "Search domain or IP address..."
    override val filterAll: String = "All"
    override val filterProxy: String = "Proxy"
    override val filterDirect: String = "Direct"
    override val filterBlock: String = "Block"
    override val filterDns: String = "DNS"
    override val clearConnections: String = "Clear list"
    override val noConnectionsYet: String = "No active connections"
    override val noConnectionsDesc: String = "Connect to VPN to monitor real-time inbound & outbound traffic streams"
    override val liveConnectionsCount: String = "%d active endpoints"
    override val liveConnectionsSummary: String = "%d endpoints • %d requests"
    override val connectionOutboundLabel: String = "Outbound"
    override val connectionDestLabel: String = "Destination"
    override val connectionProtocolLabel: String = "Protocol"

    // Auto Connect on Boot & Battery
    override val autoConnectOnBootTitle: String = "Auto-connect on Boot"
    override val autoConnectOnBootSubtitle: String = "Automatically connect VPN when device powers on"
    override val batteryOptimizationTitle: String = "Background Execution (Battery)"
    override val batteryOptimizationDisabled: String = "Optimization disabled — service protected from kill"
    override val batteryOptimizationPrompt: String = "Tap to disable battery restrictions"
    override val batterySettingsOpenError: String = "Failed to open battery settings"

    // Ping & Protocol Extras
    override val pingModeTitle: String = "Ping Mode"
    override val pingModeSubtitle: String = "Method to check server reachability and latency"
    override val pingModeRealDelay: String = "Real Delay"
    override val pingModeTcp: String = "TCP Ping"
    override val pingModeIcmp: String = "ICMP"
    override val pingModeRealDelayDesc: String = "HTTP 204 via proxy — tests actual data delivery and detects DPI blocking"
    override val pingModeTcpDesc: String = "Fast measurement of TCP socket connection latency to server IP:port"
    override val pingModeIcmpDesc: String = "Direct ICMP echo request to server host (may be blocked by firewalls)"
    override val tlsFragmentTitle: String = "TLS Packet Fragmentation (Fragment)"
    override val tlsFragmentSubtitle: String = "Splits TLS handshake packets into 100-200 byte chunks with micro-delays. Bypasses SNI-based DPI blocking."
    override val killSwitchActive = "Protection enabled"
    override val killSwitchAlwaysOnly = "Always-on only"
    override val killSwitchOff = "Blocking disabled"
    override val killSwitchUnknown = "Status not confirmed"
    override val killSwitchUnknownHint = "Android has not provided a protection status. It may be unavailable without an active tunnel. Verify the VPN options in Android settings."
    override val killSwitchBody = "When enabled, Android blocks connections without VPN, including when the app stops."
    override val killSwitchStepOne = "Open %s options in the Android VPN list."
    override val killSwitchStepTwo = "Enable “Always-on VPN”."
    override val killSwitchStepThree = "Enable “Block connections without VPN”."
    override val killSwitchConfigure = "Set up protection"
    override val killSwitchManage = "Manage protection"
    override val killSwitchExcludedApps = "Apps excluded from the VPN will lose internet access while blocking is enabled."
    override val killSwitchTitle: String = "Kill Switch"

    // App Updates Section
    override val sectionAppUpdates: String = "APP UPDATES"

    // Quick Settings
    override val sectionQuickSettings: String = "QUICK SETTINGS TILE"
    override val quickSettingsDesc: String = "Toggle VPN directly from the Android notification shade without opening the app."
    override val quickSettingsAddTile: String = "Add tile to quick settings"
    override val quickSettingsTileAdded: String = "SplateVPN tile added to quick settings!"
    override val quickSettingsTileAlreadyAdded: String = "SplateVPN tile is already in quick settings"
    override val quickSettingsManualPrompt: String = "Pull down notification shade twice, tap edit icon, and drag SplateVPN tile onto panel."

    // Backup & Restore
    override val sectionBackupRestore: String = "BACKUP & RESTORE"
    override val backupRestoreDesc: String = "Export all saved servers, subscriptions, custom routes, and settings to a JSON file or restore from a backup."
    override val exportBtn: String = "EXPORT"
    override val importBtn: String = "IMPORT"
    override val backupExportSuccess: String = "Configuration exported successfully!"
    override val backupExportError: String = "Export error: %s"
    override val backupImportSuccess: String = "Successfully imported %d servers"
    override val backupImportError: String = "Import error: %s"
    override val autoRestoreBtn: String = "RESTORE AUTO-BACKUP"
    override val autoRestoreSuccess: String = "Successfully restored %d servers"
    override val autoRestoreNotFound: String = "Auto-backup not found or empty"

    // Speedtest & Status Badges
    override val autoSelectOn: String = "⚡ AUTO: ON"
    override val speedtestTitle: String = "Speed test"
    override val speedtestDesc: String = "Measure real download speed through VPN"
    override val speedtestRunning: String = "TESTING: %d%%"
    override val speedtestSpeedUnit: String = "Mbps"
    override val speedtestResult: String = "SPEED: %s Mbps"
    override val speedtestLatency: String = "HTTPS response: %s ms"
    override val speedtestError: String = "Test error: %s"

    // Additional Node & App labels
    override val serversAddedCount: String = "Added servers: %d"
    override val qrScanError: String = "QR scan error: %s"
    override val scanQr: String = "Scan QR"
    override val copyLink: String = "Copy link"
    override val autoSelectBalancer: String = "Auto-select Server"
    override val activeNodePrefix: String = "Active: %s"
    override val autoSelectBalancerDesc: String = "Automatic selection of fastest server & failover"
    override val onUpper: String = "ON"
    override val offUpper: String = "OFF"
    override val filterSelected: String = "Selected"
    override val selectAll: String = "All"
    override val resetAll: String = "Reset"

    // Unified Add & Empty State
    override val unifiedAddTitle: String = "Add Server or Subscription"
    override val unifiedAddSubtitle: String = "Paste VLESS/VMess/SS/Trojan link or subscription URL"
    override val pasteFromClipboard: String = "Paste from Clipboard"
    override val scanQrCode: String = "Scan QR Code"
    override val clipboardEmpty: String = "Clipboard is empty"
    override val detectedSubscription: String = "🔗 Subscription link detected"
    override val detectedServerNode: String = "⚡ Server configuration detected"
    override val detectedMultipleNodes: String = "📦 Servers found: %d"
    override val emptyServersCta: String = "Add first server or subscription"
    override val emptyServersHint: String = "Paste link from clipboard or scan a QR code to get started"
    override val importAction: String = "Import"
    override val subNameOptional: String = "Subscription name (optional)"
    override val addServerOrSub: String = "Add server or subscription"

    // Subscription Quota & Userinfo
    override val subQuotaUsed: String = "Used: %s of %s"
    override val subQuotaRemaining: String = "%s remaining of %s"
    override val subQuotaUnlimited: String = "Unlimited bandwidth"
    override val subExpireDate: String = "Expires: %s"
    override val subExpired: String = "Subscription expired"

    // Server Share & QR Dialog
    override val shareServerTitle: String = "Export Node"
    override val shareQrPrompt: String = "Scan with another device's camera"
    override val shareCopyLink: String = "Copy Link"
    override val shareSend: String = "Share"
    override val shareAction: String = "Share / QR"
    override val sharePayloadTooLarge: String = "Configuration is too large for QR code (%d bytes). Copy link or share via text."
    override val shareSubscriptionTitle: String = "Export Subscription"
    override val shareSubQrPrompt: String = "Scan with another device's camera to import subscription"

    // LAN / Hotspot / USB Proxy Sharing
    override val sectionShareLan: String = "VPN Sharing (Hotspot & USB)"
    override val shareLanTitle: String = "Share VPN over LAN / Tethering"
    override val shareLanSubtitle: String = "Enables local HTTP and SOCKS5 proxy on 0.0.0.0 for Wi-Fi Hotspot & USB tethering"
    override val shareLanActiveEndpoints: String = "Detected Tethering Endpoints"
    override val shareLanNoEndpoints: String = "Hotspot or USB tethering not detected yet. Enable tethering in phone settings."
    override val shareLanInstructionsBtn: String = "PC & Device Setup Guide"
    override val shareLanHttpProxy: String = "HTTP Proxy (Windows / Mac / TV)"
    override val shareLanSocksProxy: String = "SOCKS5 Proxy"
    override val shareLanCopied: String = "Proxy address copied: %s"
    override val shareLanDialogTitle: String = "Connect PC & Devices via Phone"
    override val shareLanStep1: String = "1. Enable Wi-Fi Hotspot or USB tethering on your phone and connect your PC/laptop."
    override val shareLanStep2: String = "2. Make sure VPN is connected in SplateVPN."
    override val shareLanStep3Windows: String = "3. On Windows: Settings ➔ Network & Internet ➔ Proxy ➔ Manual setup ➔ enter the IP and port 10809."
    override val shareLanStep3Mac: String = "4. On macOS / Linux: Network Settings ➔ Proxies ➔ Web Proxy (HTTP/HTTPS) with port 10809."
    override val shareLanStep3Telegram: String = "5. In Telegram / apps: Settings ➔ Data and Storage ➔ Proxy ➔ Add SOCKS5 (port 10808) or HTTP (port 10809)."
    override val shareLanWindowsTitle: String = "Windows Setup (Wi-Fi or USB)"
    override val shareLanWindowsSettingsGuide: String = "In Windows 10/11: Settings ➔ Network & Internet ➔ Proxy ➔ Manual setup ➔ turn on \"Use a proxy server\"."
    override val shareLanWindowsInetcplGuide: String = "Or press Win+R ➔ type inetcpl.cpl ➔ \"Connections\" tab ➔ \"LAN settings\"."
    override val shareLanWindowsProxyString: String = "Proxy string (HTTP / HTTPS):"
    override val shareLanWindowsExceptions: String = "Bypass proxy for local addresses:"
    override val shareLanWindowsExceptionsValue: String = "localhost;127.*;10.*;192.168.*;172.16.*"
    override val shareLanWindowsCmdEnable: String = "1-Click enable command (PowerShell / CMD):"
    override val shareLanWindowsCmdDisable: String = "Disable proxy command (PowerShell / CMD):"
    override val shareLanExceptionsCopied: String = "Exceptions copied"
    override val shareLanCommandCopied: String = "Windows command copied"

    // TLS Fragmentation Tuning
    override val tlsFragmentPresetTitle: String = "Fragmentation Profile"
    override val tlsFragmentPresetStandard: String = "Standard (100-200, 10-20)"
    override val tlsFragmentPresetAggressive: String = "Aggressive (50-100, 20-40, 1-3)"
    override val tlsFragmentPresetCustom: String = "Custom"
    override val tlsFragmentLengthLabel: String = "Packet length (bytes)"
    override val tlsFragmentIntervalLabel: String = "Interval (ms)"
    override val tlsFragmentPacketsLabel: String = "Packets"

    // Custom DoH DNS
    override val dnsCustom: String = "Custom DoH DNS"
    override val dnsCustomUrlHint: String = "https://dns.nextdns.io/..."
    override val dnsCustomUrlDialogTitle: String = "Custom DNS-over-HTTPS"

    // Multiplexing (Mux)
    override val muxTitle: String = "Multiplexing (Mux)"
    override val muxSubtitle: String = "Multiplex multiple TCP connections over a single tunnel (incompatible with Vision)"
    override val muxConcurrencyLabel: String = "Concurrency limit"
}
val EnglishStrings: AppStrings = EnglishAppStrings()

val LocalAppStrings = compositionLocalOf<AppStrings> { RussianStrings }

@Composable
fun getStringsForLanguage(languageSetting: String): AppStrings {
    return when (languageSetting.lowercase()) {
        "ru" -> RussianStrings
        "en" -> EnglishStrings
        else -> {
            val systemLocale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()
            if (systemLocale.language.startsWith("ru", ignoreCase = true)) {
                RussianStrings
            } else {
                EnglishStrings
            }
        }
    }
}

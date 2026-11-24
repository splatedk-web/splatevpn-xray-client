package com.danieldk.splatevpn.ui.theme

import com.danieldk.splatevpn.ui.components.LanGuidePlatform
import com.danieldk.splatevpn.ui.components.LanGuideStep
import com.danieldk.splatevpn.ui.components.LanLinuxMode

internal val AppStrings.lanGuide get() = LanGuideStrings(this)

internal class LanGuideStrings(private val strings: AppStrings) {
    private fun t(ruText: String, enText: String) = strings.localized(ruText, enText)
    val title get() = t("Раздача VPN", "Share your VPN")
    val subtitle get() = t("Подключите другое устройство через телефон", "Connect another device through your phone")
    val choose get() = t("Что подключаем?", "Which device are you connecting?")
    val apps get() = t("В приложении", "In an app")
    val stages get() = listOf(t("Телефон", "Phone"), t("Устройство", "Device"), t("Проверка", "Check"))
    val back get() = t("Назад", "Back")
    val next get() = t("Далее", "Next")
    val done get() = t("Готово", "Done")
    val via get() = t("Wi-Fi / USB / LAN", "Wi-Fi / USB / LAN")
    val phone get() = t("Телефон", "Phone")
    val recipient get() = t("Ваше устройство", "Your device")
    val prepare get() = t("Сначала на телефоне с SplateVPN", "First, on the phone running SplateVPN")
    val prepareSteps get() = listOf(
        LanGuideStep(t("Подключите VPN", "Connect the VPN"), t("Выберите рабочий сервер в SplateVPN и подключитесь. Для проверки раздачи удобно выбрать маршрут «Весь трафик».", "Choose a working server in SplateVPN and connect. For testing, the All traffic route is convenient.")),
        LanGuideStep(t("Включите раздачу в SplateVPN", "Enable sharing in SplateVPN"), t("Настройки → Раздача VPN в локальную сеть. Выберите интерфейс, затем дождитесь переподключения VPN. На втором устройстве включите авторизацию прокси: логин splate, пароль из настроек раздачи.", "Settings → Share VPN over LAN. Choose an interface and wait for the VPN to reconnect. On the other device, enable proxy authentication: username splate and the password shown in sharing settings.")),
        LanGuideStep(t("Соедините устройства", "Connect the devices"), t("Включите точку доступа телефона и подключите второе устройство по Wi-Fi. Для ПК с поддержкой USB-модема можно использовать USB. Также подойдёт общая локальная сеть без изоляции клиентов.", "Enable the phone's hotspot and connect the other device over Wi-Fi. A computer supporting Android USB tethering can use USB instead. A shared LAN also works if client isolation is disabled."))
    )
    val mobileConnection get() = t("Для Android и iPhone/iPad проще подключиться к точке доступа телефона по Wi-Fi. Прокси настраивается именно у этой сети.", "For Android and iPhone/iPad, connect to the phone's hotspot over Wi-Fi. Configure the proxy for that network.")
    val macConnection get() = t("Для Mac используйте Wi-Fi или общую локальную сеть. Поддержка USB-модема Android зависит от телефона и драйвера; Wi-Fi — основной вариант.", "For a Mac, use Wi-Fi or a shared LAN. Android USB tethering support depends on the phone and driver; Wi-Fi is the main option.")
    val scope get() = t("Это раздача через прокси. Она работает в браузерах и приложениях, которые используют системный прокси или поддерживают собственную настройку. Игры, звонки и другие приложения могут обходить его; весь трафик устройства автоматически в VPN не попадает.", "This shares a proxy connection. It works in browsers and apps that honor the system proxy or provide their own proxy settings. Games, calls and other apps may bypass it; the device's entire traffic does not automatically enter the VPN.")
    val disconnected get() = t("VPN отключён. Подключите его на главной странице.", "VPN is disconnected. Connect it on the home screen.")
    val sharingOff get() = t("Раздача ещё не запущена. Включите её и дождитесь переподключения VPN.", "Sharing is not running yet. Enable it and wait for the VPN to reconnect.")
    val noAddress get() = t("Адрес пока не обнаружен", "No address detected yet")
    val noAddressHint get() = t("Включите точку доступа или USB-модем. Инструкцию можно читать сейчас, но копирование параметров появится после обнаружения адреса. Адрес может измениться при следующем подключении.", "Enable the hotspot or USB tethering. You can read the guide now; connection parameters become copyable after an address is detected. The address may change next time.")
    val available get() = t("Параметры для подключения", "Connection parameters")
    val endpoint get() = t("Адрес телефона в вашей сети", "Phone address on your network")
    val chooseEndpoint get() = t("Выберите сеть, к которой подключено второе устройство. Адрес Wi-Fi и адрес USB могут отличаться.", "Choose the network used by the other device. Wi-Fi and USB addresses can differ.")
    val host get() = t("IP-адрес телефона", "Phone IP address")
    val hostPlaceholder get() = t("IP телефона", "Phone IP")
    val port get() = t("Порт", "Port")
    val auth get() = t("Авторизация", "Authentication")
    val off get() = t("Выключена", "Off")
    val manual get() = t("Вручную", "Manual")
    val server get() = t("Сервер", "Server")
    val proxyHost get() = t("Имя хоста прокси", "Proxy hostname")
    val proxyPort get() = t("Порт прокси", "Proxy port")
    val save get() = t("Сохранить", "Save")
    val apply get() = t("Применить", "Apply")
    val proxy get() = t("Прокси", "Proxy")
    val webHttp get() = t("Веб-прокси (HTTP)", "Web proxy (HTTP)")
    val webHttps get() = t("Защищённый веб-прокси (HTTPS)", "Secure web proxy (HTTPS)")
    val diagram get() = t("Наглядная схема настроек", "Settings illustration")
    val diagramHint get() = t("Это схема: названия пунктов и внешний вид зависят от версии системы.", "This is an illustration; labels and appearance vary by OS version.")
    val valuesHint get() = t("В поле адреса укажите только IP, без http:// и порта. Для авторизации используйте splate и пароль из настроек раздачи. HTTP и HTTPS используют один HTTP-порт; SOCKS5 — отдельный.", "Enter only the IP, without http:// or a port. Authenticate with splate and the password from sharing settings. HTTP and HTTPS use the HTTP port; SOCKS5 has its own port.")
    val advanced get() = t("Команды и дополнительные способы", "Commands and other methods")
    val enableCommand get() = t("Включить в PowerShell", "Enable in PowerShell")
    val disableCommand get() = t("Выключить в PowerShell", "Disable in PowerShell")
    val shellEnable get() = t("Включить в текущем терминале", "Enable in this terminal")
    val shellDisable get() = t("Выключить в текущем терминале", "Disable in this terminal")
    val shellNote get() = t("Для bash / zsh и программ, которые читают эти переменные. Действует только в текущем терминале и запущенных из него программах; графические приложения и sudo могут их не использовать.", "For bash / zsh and programs honoring these variables. Applies only to this terminal and programs launched from it; GUI apps and sudo may not use them.")
    val powershellNote get() = t("Только PowerShell, не CMD. Меняет прокси текущего пользователя для приложений, которые используют настройки Windows. Предыдущий адрес прокси будет заменён.", "Use PowerShell, not CMD. Changes the current user's proxy for apps honoring Windows settings. Replaces the previously configured proxy address.")
    val curlCommand get() = t("Проверить HTTP-прокси через curl", "Test the HTTP proxy with curl")
    val curlHint get() = t("В ответ должен прийти HTML example.com. Это проверяет соединение через прокси, но не подтверждает маршрут всего устройства.", "The response should contain example.com HTML. This tests a proxy connection, not the route of the entire device.")
    val checkTitle get() = t("Проверим соединение", "Check the connection")
    val checkSteps get() = listOf(
        LanGuideStep(t("Откройте сайт на втором устройстве", "Open a website on the other device"), t("Используйте браузер с системным прокси. Проверьте сайт, который должен открываться через VPN. Трафик следует выбранному маршруту SplateVPN.", "Use a browser honoring the system proxy. Try a site that should open through the VPN. Traffic follows the route selected in SplateVPN.")),
        LanGuideStep(t("Если приложение не работает", "If an app does not work"), t("Оно может игнорировать системный прокси. Если внутри приложения есть пункт «Прокси», выберите SOCKS5 и укажите адрес телефона и SOCKS5-порт. При отсутствии такой настройки этот способ может не подойти.", "It may ignore the system proxy. If the app has proxy settings, choose SOCKS5 and enter the phone address and SOCKS5 port. Without such settings, this method may not work for that app."))
    )
    val troubleshoot get() = t("Не подключается?", "Cannot connect?")
    val troubles get() = listOf(
        LanGuideStep(t("Сверьте адрес и порт", "Check the address and port"), t("После перезапуска точки доступа IP мог измениться. Возьмите текущий адрес из шага «Устройство». HTTP и HTTPS используют HTTP-порт, а не SOCKS5-порт.", "The IP may change after restarting the hotspot. Use the current address from the Device step. HTTP and HTTPS use the HTTP port, not the SOCKS5 port.")),
        LanGuideStep(t("Проверьте сеть", "Check the network"), t("Оба устройства должны видеть друг друга в одной сети. Гостевой Wi-Fi, изоляция клиентов, сон телефона или другой VPN на получателе могут мешать подключению.", "Both devices must be able to reach each other on the same network. Guest Wi-Fi, client isolation, phone sleep or another VPN on the receiving device can interfere.")),
        LanGuideStep(t("Проверьте телефон", "Check the phone"), t("VPN и раздача должны быть включены. Если сайт не работает на телефоне, сначала исправьте VPN. Используйте пароль прокси и подключайте доверенные устройства.", "VPN and sharing must be enabled. If the site fails on the phone, fix VPN first. Use the proxy password and share with trusted devices.")),
        LanGuideStep(t("Если импортирован полный JSON-профиль", "If you imported a full JSON profile"), t("Локальные порты прокси задаются приложением. Используйте адреса HTTP/SOCKS5 из этой инструкции.", "Local proxy ports are managed by the app. Use the HTTP/SOCKS5 addresses from this guide."))
    )
    val disconnect get() = t("После использования выключите прокси", "Turn the proxy off when finished")
    val source get() = t("Официальная справка", "Official documentation")

    fun setup(platform: LanGuidePlatform, linux: LanLinuxMode): List<LanGuideStep> = when (platform) {
        LanGuidePlatform.ANDROID -> listOf(
            LanGuideStep(t("Откройте подключённую сеть Wi-Fi", "Open the connected Wi-Fi network"), t("Настройки → Wi-Fi / Интернет → сеть точки доступа → шестерёнка или значок редактирования.", "Settings → Wi-Fi / Internet → the hotspot network → its settings or edit icon.")),
            LanGuideStep(t("Выберите прокси вручную", "Choose a manual proxy"), t("Откройте «Изменить сеть» или «Дополнительно» → Прокси → Вручную. На Samsung, Xiaomi и других устройствах названия могут отличаться.", "Open Modify network or Advanced options → Proxy → Manual. Labels vary on Samsung, Xiaomi and other devices.")),
            LanGuideStep(t("Заполните поля и сохраните", "Fill in the fields and save"), t("Имя хоста — IP телефона с SplateVPN, порт — HTTP-порт ниже. IP-настройки сети оставьте DHCP / автоматически.", "Proxy hostname is the SplateVPN phone's IP; port is the HTTP port below. Leave the network's IP settings on DHCP / automatic."))
        )
        LanGuidePlatform.IOS -> listOf(
            LanGuideStep(t("Откройте сведения о сети", "Open network details"), t("Настройки → Wi-Fi → ⓘ рядом с сетью точки доступа телефона.", "Settings → Wi-Fi → ⓘ next to the phone's hotspot network.")),
            LanGuideStep(t("Настройте HTTP-прокси", "Configure the HTTP proxy"), t("Прокрутите вниз → Настройка прокси → Вручную.", "Scroll down → Configure Proxy → Manual.")),
            LanGuideStep(t("Введите сервер и порт", "Enter the server and port"), t("Сервер — IP телефона, порт — HTTP-порт ниже. Включите аутентификацию, введите splate и пароль из настроек раздачи, затем сохраните.", "Server is the phone IP; port is the HTTP port below. Enable authentication with splate and the sharing password, then save."))
        )
        LanGuidePlatform.WINDOWS -> listOf(
            LanGuideStep(t("Откройте настройки прокси", "Open proxy settings"), t("Windows 10/11: Параметры → Сеть и Интернет → Прокси-сервер.", "Windows 10/11: Settings → Network & internet → Proxy.")),
            LanGuideStep(t("Включите ручную настройку", "Enable manual setup"), t("Настройка прокси вручную → Настроить / Использовать прокси-сервер → Вкл.", "Manual proxy setup → Set up / Use a proxy server → On.")),
            LanGuideStep(t("Введите IP и HTTP-порт", "Enter the IP and HTTP port"), t("Заполните поля по схеме ниже и сохраните. Это прокси текущего пользователя, а не отдельного VPN-подключения Windows.", "Fill in the illustrated fields below and save. These are the current user's proxy settings, not a Windows VPN connection."))
        )
        LanGuidePlatform.MACOS -> listOf(
            LanGuideStep(t("Выберите активное подключение", "Choose the active connection"), t("Системные настройки → Сеть → Wi-Fi или Ethernet → Подробнее → Прокси.", "System Settings → Network → Wi-Fi or Ethernet → Details → Proxies.")),
            LanGuideStep(t("Включите HTTP и HTTPS", "Enable HTTP and HTTPS"), t("Включите «Веб-прокси (HTTP)» и «Защищённый веб-прокси (HTTPS)». Для обоих укажите одинаковые IP и HTTP-порт.", "Enable Web proxy (HTTP) and Secure web proxy (HTTPS). Enter the same IP and HTTP port for both.")),
            LanGuideStep(t("Сохраните настройки", "Save the settings"), t("Включите авторизацию прокси: splate и пароль раздачи. Нажмите OK / Готово, затем Применить. SOCKS одновременно включать не требуется.", "Enable proxy authentication with splate and the sharing password. Click OK / Done, then Apply. SOCKS need not be enabled at the same time."))
        )
        LanGuidePlatform.LINUX -> when (linux) {
            LanLinuxMode.GNOME -> listOf(
                LanGuideStep(t("Откройте сетевой прокси", "Open Network Proxy"), t("Ubuntu / GNOME: Настройки → Сеть → Сетевой прокси. Выберите режим «Вручную».", "Ubuntu / GNOME: Settings → Network → Network Proxy. Choose Manual.")),
                LanGuideStep(t("Заполните HTTP и HTTPS", "Fill in HTTP and HTTPS"), t("В обе строки введите один IP телефона и HTTP-порт. Сохраните настройки; некоторые приложения нужно перезапустить.", "Enter the same phone IP and HTTP port in both rows. Save; some apps need to be restarted."))
            )
            LanLinuxMode.KDE -> listOf(
                LanGuideStep(t("Откройте модуль прокси", "Open the Proxy module"), t("KDE Plasma: найдите «Прокси» в параметрах системы → выберите ручную настройку.", "KDE Plasma: search for Proxy in System Settings → choose manual configuration.")),
                LanGuideStep(t("Настройте HTTP и HTTPS", "Configure HTTP and HTTPS"), t("Укажите IP телефона и HTTP-порт для HTTP и HTTPS, затем «Применить». Приложения могут иметь собственные настройки прокси.", "Enter the phone IP and HTTP port for HTTP and HTTPS, then Apply. Apps may have their own proxy settings."))
            )
            LanLinuxMode.TERMINAL -> listOf(LanGuideStep(t("Откройте bash или zsh", "Open bash or zsh"), t("Скопируйте команды ниже и выполните их в терминале. Затем запускайте нужные команды из этого же окна.", "Copy and run the commands below in your terminal. Then launch the commands you need from that same terminal.")))
        }
        LanGuidePlatform.APPS -> listOf(
            LanGuideStep(t("Найдите настройки прокси", "Find the proxy settings"), t("Откройте раздел «Прокси» в нужном приложении. Он часто находится в настройках сети или данных.", "Open the app's Proxy section, often found under network or data settings.")),
            LanGuideStep(t("Выберите SOCKS5", "Choose SOCKS5"), t("Введите IP телефона и SOCKS5-порт ниже. Логин splate, пароль — из настроек раздачи. Если поддерживается только HTTP, используйте HTTP-порт.", "Enter the phone IP and SOCKS5 port below. Username is splate; use the sharing password. For HTTP-only apps, use the HTTP port."))
        )
    }

    fun disable(platform: LanGuidePlatform, linux: LanLinuxMode) = when (platform) {
        LanGuidePlatform.ANDROID -> t("В настройках этой Wi-Fi-сети выберите Прокси → Нет и сохраните.", "In this Wi-Fi network's settings, set Proxy → None and save.")
        LanGuidePlatform.IOS -> t("Настройки → Wi-Fi → ⓘ → Настройка прокси → Выкл. → Сохранить.", "Settings → Wi-Fi → ⓘ → Configure Proxy → Off → Save.")
        LanGuidePlatform.WINDOWS -> t("Параметры → Сеть и Интернет → Прокси-сервер → Использовать прокси-сервер → Выкл.", "Settings → Network & internet → Proxy → Use a proxy server → Off.")
        LanGuidePlatform.MACOS -> t("Сеть → ваше подключение → Подробнее → Прокси: выключите HTTP и HTTPS и сохраните.", "Network → your connection → Details → Proxies: turn HTTP and HTTPS off and save.")
        LanGuidePlatform.LINUX -> if (linux == LanLinuxMode.TERMINAL) t("Выполните команду unset ниже или закройте этот терминал.", "Run the unset command below or close this terminal.") else t("Верните режим «Нет / Отключено» в настройках сетевого прокси.", "Set Network Proxy back to None / Disabled.")
        LanGuidePlatform.APPS -> t("Выключите прокси в настройках самого приложения.", "Turn the proxy off in the app's own settings.")
    } + " " + t("Иначе после отключения телефона интернет в этих приложениях может перестать работать.", "Otherwise those apps may lose internet access after the phone disconnects.")

    fun sourceUrl(platform: LanGuidePlatform, linux: LanLinuxMode) = when (platform) {
        LanGuidePlatform.ANDROID -> "https://support.google.com/pixelphone/answer/9655181"
        LanGuidePlatform.IOS -> "https://support.apple.com/guide/iphone/iphw5gjwl8k2/ios"
        LanGuidePlatform.WINDOWS -> "https://support.microsoft.com/en-us/windows/experience/connectivity-networking/use-a-proxy-server-in-windows"
        LanGuidePlatform.MACOS -> "https://support.apple.com/guide/mac-help/mchlp2591/mac"
        LanGuidePlatform.LINUX -> when (linux) {
            LanLinuxMode.GNOME -> "https://help.gnome.org/gnome-help/net-proxy.html"
            LanLinuxMode.KDE -> "https://docs.kde.org/stable_kf6/en/kio-extras/kcontrol6/proxy/"
            LanLinuxMode.TERMINAL -> "https://curl.se/docs/manpage.html#--proxy"
        }
        LanGuidePlatform.APPS -> "https://curl.se/docs/manpage.html#--proxy"
    }
}

package com.danieldk.splatevpn.data

internal fun dnsFailureAdvice(dns: DnsHealthIssue, ru: Boolean): TunnelHealthIssue =
    dnsFailureAdvice(dns) { russian, english -> if (ru) russian else english }

internal fun dnsFailureAdvice(dns: DnsHealthIssue, text: (String, String) -> String): TunnelHealthIssue {
    val candidate = dns.candidate
    return TunnelHealthIssue(
        kind = HealthIssueKind.DNS_BLOCKED,
        title = if (candidate != null) {
            String.format(text("Рабочий DNS: %s", "Available DNS: %s"), candidate.label)
        } else text("Проверки DNS не прошли", "DNS checks failed"),
        description = if (candidate != null) {
            String.format(text("%s не ответил на повторную проверку. %s отвечает. Причина отказа не установлена.",
                "%s failed repeated checks; %s responded. The cause is unknown."), dns.failedDnsLabel, candidate.label)
        } else if (dns.isFakeDnsOff) {
            text("Проверенные DNS-серверы не ответили. Причина не установлена. Можно попробовать FakeDNS.",
                "The tested DNS resolvers did not respond. The cause is unknown. You can try FakeDNS.")
        } else {
            text("Проверенные DNS-серверы не ответили. FakeDNS уже включён. Проверьте соединение с выбранным узлом.",
                "The tested DNS resolvers did not respond. FakeDNS is already enabled. Check the selected server connection.")
        },
        primaryActionLabel = candidate?.let { String.format(text("Выбрать %s", "Switch to %s"), it.label) }
            ?: if (dns.isFakeDnsOff) text("Включить FakeDNS", "Enable FakeDNS") else null,
        primaryActionId = if (candidate != null) "APPLY_DNS" else if (dns.isFakeDnsOff) "ENABLE_FAKEDNS" else null,
        primaryActionExtra = candidate?.key,
        dnsIssue = dns
    )
}

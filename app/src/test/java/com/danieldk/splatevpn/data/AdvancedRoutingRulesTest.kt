package com.danieldk.splatevpn.data

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.net.IDN

class AdvancedRoutingRulesTest {
    private fun rule(operation: String = "and", domains: List<String> = emptyList(), suffixes: List<String> = emptyList(),
        keywords: List<String> = emptyList()) = AdvancedRouteRule("test", "Test", operation = operation,
        domains = domains, suffixes = suffixes, keywords = keywords)

    private fun domainPredicates(compiled: List<JsonObject>): List<(String) -> Boolean> = compiled.flatMap { native ->
        native.getAsJsonArray("domain")?.map { item ->
            val value = item.asString
            when {
                value.startsWith("regexp:") -> Regex(value.removePrefix("regexp:")).let { regex -> { host: String -> regex.containsMatchIn(host) } }
                value.startsWith("domain:") -> value.removePrefix("domain:").let { suffix -> { host: String -> host == suffix || host.endsWith(".$suffix") } }
                value.startsWith("full:") -> value.removePrefix("full:").let { exact -> { host: String -> host == exact } }
                else -> { host: String -> host.contains(value) }
            }
        }.orEmpty()
    }
    private fun matches(rule: AdvancedRouteRule, host: String): Boolean = domainPredicates(AdvancedRoutingRules.compile(listOf(rule)))
        .any { it(IDN.toASCII(host).lowercase()) }

    @Test fun andRequiresEachPopulatedDomainSectionWhileListsRemainAlternatives() {
        val value = rule(domains = listOf("www.example.com", "ads.example.com", "ads.other.net"),
            suffixes = listOf(".example.com", "example.org"), keywords = listOf("ads", "shop"))
        val compiled = AdvancedRoutingRules.compile(listOf(value)).single()
        assertEquals(listOf("full:ads.example.com"), compiled.getAsJsonArray("domain").map { it.asString })
        assertTrue(matches(value, "ads.example.com"))
        assertFalse(matches(value, "www.example.com"))
        assertFalse(matches(value, "ads.other.net"))
        assertFalse(matches(value, "ads.example.org"))
    }

    @Test fun orAcceptsAnyPopulatedDomainSection() {
        val value = rule("or", domains = listOf("only.exact.test"), suffixes = listOf("example.com"), keywords = listOf("advert"))
        assertTrue(matches(value, "only.exact.test"))
        assertTrue(matches(value, "sub.example.com"))
        assertTrue(matches(value, "advert.other.test"))
        assertFalse(matches(value, "neutral.other.test"))
    }

    @Test fun suffixBoundaryIncludesApexButCannotMatchAnUnrelatedEnding() {
        for (suffix in listOf("example.com", ".example.com")) {
            val value = rule(suffixes = listOf(suffix))
            assertTrue(matches(value, "example.com"))
            assertTrue(matches(value, "a.example.com"))
            assertFalse(matches(value, "notexample.com"))
            assertFalse(matches(value, "example.com.evil.test"))
        }
    }

    @Test fun suffixKeywordIntersectionHandlesDotBoundaryOverlapAndApexCorrectly() {
        val cases = listOf(
            Triple(".exam", "a.example.com", true), Triple(".exam", "example.com", false),
            Triple("a.exam", "a.example.com", true), Triple("a.exam", "b.example.com", false),
            Triple(".example.com", "a.example.com", true), Triple(".example.com", "example.com", false),
            Triple("example", "example.com", true), Triple("example", "a.example.com", true),
            Triple("com.", "com.example.com", true), Triple("com.", "example.com", false),
            Triple("foo.example.com", "foo.example.com", true), Triple("foo.example.com", "foo.example.com.example.com", true)
        )
        for ((keyword, host, expected) in cases) {
            assertEquals("keyword=$keyword host=$host", expected, matches(rule(suffixes = listOf("example.com"), keywords = listOf(keyword)), host))
        }
    }

    @Test fun compiledIntersectionsAgreeWithReferenceAcrossGeneratedValidDomains() {
        val suffixes = listOf("example.com", "b.c", "y.com", "xy.z", "z")
        val prefixes = listOf("a", "b", "x", "ab", "foo", "com", "a.b", "foo-bar", "x-y", "abc.def")
        val hosts = (suffixes + suffixes.flatMap { suffix -> prefixes.map { "$it.$suffix" } } +
            listOf("notexample.com", "example.com.evil", "x.y.com", "xyz", "foo.com"))
        val keywords = (listOf("a", "b", "x", "com", ".exam", "com.", ".example.com", "x.y", "c.def", "-bar") +
            hosts.flatMap { host -> (1..minOf(host.length, 6)).flatMap { length ->
                (0..host.length - length).map { start -> host.substring(start, start + length) }
            } }).distinct()
        for (suffix in suffixes) for (keyword in keywords) {
            val predicates = domainPredicates(AdvancedRoutingRules.compile(listOf(rule(suffixes = listOf(suffix), keywords = listOf(keyword)))))
            for (host in hosts) {
                val expected = (host == suffix || host.endsWith(".$suffix")) && host.contains(keyword)
                assertEquals("suffix=$suffix keyword=$keyword host=$host", expected, predicates.any { it(host) })
            }
        }
    }

    @Test fun combinedListsUseAnyValueInsideEachAndSection() {
        val value = rule(suffixes = listOf("example.com", "example.org"), keywords = listOf("shop", "ads"))
        assertTrue(matches(value, "shop.example.org")); assertTrue(matches(value, "ads.example.com"))
        assertFalse(matches(value, "neutral.example.com")); assertFalse(matches(value, "ads.other.org"))
    }

    @Test fun andUsesOneNativeRuleForAllOtherSectionsWhileOrUsesIndependentRules() {
        val value = rule(domains = listOf("example.com")).copy(ips = listOf("192.0.2.0/24"), ports = "443,8443-8444",
            networks = setOf("tcp"), protocols = setOf("tls"), outbound = "direct")
        val and = AdvancedRoutingRules.compile(listOf(value)).single()
        assertTrue(listOf("domain", "ip", "port", "network", "protocol").all(and::has))
        assertEquals("direct", and["outboundTag"].asString)
        val or = AdvancedRoutingRules.compile(listOf(value.copy(operation = "or")))
        assertEquals(5, or.size)
        assertTrue(or.all { native -> listOf("domain", "ip", "port", "network", "protocol").count(native::has) == 1 })
        assertTrue(or.all { it["outboundTag"].asString == "direct" && it["ruleTag"].asString.startsWith("adv-rule-test") })
    }

    @Test fun encodingIsCanonicalAndUnicodeDomainsBecomeIdnWithoutChangingNames() {
        val value = rule(suffixes = listOf(".ПРИМЕР.РФ.", "пример.рф"), keywords = listOf("Ads", "ads")).copy(
            name = "  Русское правило  ", ports = "8444, 443, 8443-8445,8444", networks = setOf("UDP", "tcp"))
        val encoded = AdvancedRoutingRules.encode(listOf(value))
        val restored = AdvancedRoutingRules.decode(encoded).single()
        assertEquals("Русское правило", restored.name)
        assertEquals(listOf(IDN.toASCII("пример.рф")), restored.suffixes)
        assertEquals(listOf("ads"), restored.keywords)
        assertEquals("443,8443-8445", restored.ports)
        assertEquals(setOf("tcp", "udp"), restored.networks)
        assertEquals(encoded, AdvancedRoutingRules.validateEncoded(encoded))
        assertEquals("[]", AdvancedRoutingRules.validateEncoded(""))
    }

    @Test fun rejectsImpossibleAndButPreservesTheSameValuesInOr() {
        val impossible = rule(domains = listOf("example.net"), suffixes = listOf("example.com"), keywords = listOf("shop"))
        assertTrue(runCatching { AdvancedRoutingRules.validate(impossible) }.isFailure)
        assertEquals(1, AdvancedRoutingRules.compile(listOf(impossible.copy(operation = "or"))).size)
        assertTrue(runCatching { AdvancedRoutingRules.validate(rule()) }.isFailure)
    }

    @Test fun rejectsMalformedPortsIpsNamesAndUnknownNativeFields() {
        val good = rule(suffixes = listOf("example.com"))
        for (port in listOf("0", "65536", "9000-8000", "443,", "443-", "-1", "443;444", "999999999999999999")) {
            assertTrue(port, runCatching { AdvancedRoutingRules.validate(good.copy(ports = port)) }.isFailure)
        }
        for (ip in listOf("host.example", "256.0.0.1", "1.2.3.4/33", "1.2.3.4/", "2001:db8::/129", "2001:db8::%eth0")) {
            assertTrue(ip, runCatching { AdvancedRoutingRules.validate(good.copy(ips = listOf(ip))) }.isFailure)
        }
        for (field in listOf("package_name", "process", "script", "domain_regex")) {
            assertTrue(field, runCatching { AdvancedRoutingRules.decode("""[{"id":"a","name":"Rule","suffixes":["example.com"],"$field":["ignored"]}]""") }.isFailure)
        }
        assertTrue(runCatching { AdvancedRoutingRules.decode("""[{"id":"a","name":"Rule","enabled":"true","domains":["example.com"]}]""") }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.decode("""[{"id":"a","name":"Rule","domains":[null]}]""") }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.validate(good.copy(name = "bad\nname")) }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.validate(good.copy(domains = listOf("http://example.com"))) }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.validate(good.copy(networks = setOf("icmp"))) }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.validate(good.copy(protocols = setOf("unknown"))) }.isFailure)
    }

    @Test fun ipv6AndNativeOnlyFlagsAreCanonicalAndDisabledRulesDoNotExecute() {
        val value = AdvancedRouteRule("ip", "Numeric IP", ips = listOf("2001:db8::/32", "192.000.002.000/24"))
        val normalized = AdvancedRoutingRules.validate(value)
        assertTrue(normalized.ips.any { it.endsWith("/32") }); assertTrue(normalized.ips.contains("192.0.2.0/24"))
        assertFalse(AdvancedRoutingRules.requiresDomainSniffing(listOf(value)))
        assertTrue(AdvancedRoutingRules.requiresIpResolution(listOf(value)))
        assertTrue(AdvancedRoutingRules.requiresDomainSniffing(listOf(rule(keywords = listOf("example")))))
        assertFalse(AdvancedRoutingRules.requiresIpResolution(listOf(value.copy(enabled = false))))
        assertTrue(AdvancedRoutingRules.compile(listOf(value.copy(enabled = false))).isEmpty())
    }

    @Test fun limitsRejectLargeExpansionsAndDuplicateIdentitiesRatherThanDroppingConditions() {
        val value = rule(suffixes = listOf("example.com"))
        assertTrue(runCatching { AdvancedRoutingRules.encode(List(33) { value.copy(id = "id$it") }) }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.encode(listOf(value, value)) }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.validate(value.copy(keywords = List(65) { "key$it" })) }.isFailure)
        val complex = value.copy(suffixes = List(64) { "long-host-$it.very-long-example-domain.test" }, keywords = List(64) { "unmatched-keyword-$it" })
        assertTrue(runCatching { AdvancedRoutingRules.validate(complex) }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.decode(" ".repeat(AdvancedRoutingRules.MAX_ENCODED_CHARS + 1)) }.isFailure)
    }

    @Test fun appAndDomainCompilesBothConditionsOnTheSameNativeRule() {
        val value = rule(suffixes = listOf("ozon.ru"), keywords = listOf("ozon"))
            .copy(packages = listOf("com.brave.browser", "org.mozilla.firefox"), outbound = "direct")
        val native = AdvancedRoutingRules.compile(listOf(value)).single()
        assertEquals(listOf("domain:ozon.ru"), native.getAsJsonArray("domain").map { it.asString })
        assertEquals(value.packages.map(AdvancedRoutingRules::appIdentity).sorted(), native.getAsJsonArray("user").map { it.asString })
        assertEquals("direct", native["outboundTag"].asString)
        assertFalse(native.has("process"))
        assertFalse(native.has("package_name"))
        assertTrue(AdvancedRoutingRules.requiresAppIdentity(listOf(value)))
        assertTrue(AdvancedRoutingRules.requiresDomainSniffing(listOf(value)))
    }

    @Test fun appOrDomainKeepsIndependentNativeRulesAndNeverTurnsUserIntoAnAccountEmailField() {
        val value = rule(operation = "or", suffixes = listOf("example.test"))
            .copy(packages = listOf("com.brave.browser"), ports = "443")
        val native = AdvancedRoutingRules.compile(listOf(value))
        assertEquals(3, native.size)
        assertEquals(1, native.count { it.has("user") })
        assertTrue(native.single { it.has("user") }.let { !it.has("domain") && !it.has("port") })
        assertTrue(native.single { it.has("domain") }.let { !it.has("user") && !it.has("port") })
        assertEquals(AdvancedRoutingRules.appIdentity("com.brave.browser"), native.single { it.has("user") }.getAsJsonArray("user").single().asString)
    }

    @Test fun packagesRoundTripWithExactCaseAndMissingPackagesRemainConstrained() {
        val value = AdvancedRouteRule("app-only", "Application", packages = listOf("org.example.FutureApp", " com.brave.browser ", "com.brave.browser"))
        val restored = AdvancedRoutingRules.decode(AdvancedRoutingRules.encode(listOf(value))).single()
        assertEquals(listOf("com.brave.browser", "org.example.FutureApp"), restored.packages)
        assertEquals(restored.packages, AdvancedRoutingRules.packageNames(listOf(restored)))
        assertFalse(AdvancedRoutingRules.requiresDomainSniffing(listOf(restored)))
        assertTrue(AdvancedRoutingRules.compile(listOf(restored)).single().has("user"))
        assertFalse(AdvancedRoutingRules.requiresAppIdentity(listOf(restored.copy(enabled = false))))
        assertTrue(AdvancedRoutingRules.packageNames(listOf(restored.copy(enabled = false))).isEmpty())
        assertNotEquals(AdvancedRoutingRules.appIdentity("org.example.FutureApp"), AdvancedRoutingRules.appIdentity("org.example.futureapp"))
        assertTrue(AdvancedRoutingRules.appIdentity("org.example.FutureApp").matches(Regex("splate-app-[0-9a-f]{64}")))
    }

    @Test fun oldRulesRemainCompatibleAndPackagesCannotCarryArbitraryRoutingUsers() {
        val legacy = """[{"id":"old","name":"Old","suffixes":["example.test"]}]"""
        assertTrue(AdvancedRoutingRules.decode(legacy).single().packages.isEmpty())
        assertFalse(AdvancedRoutingRules.encode(AdvancedRoutingRules.decode(legacy)).contains("packages"))
        for (value in listOf("brave", "com..brave", ".com.brave", "com.brave.", "1com.brave", "com.brave-browser", "com:brave.browser", "com.brave/Browser", "com.brave browser", "com.brave\nother", "com." + "x".repeat(252))) {
            val candidate = AdvancedRouteRule("invalid", "Invalid", packages = listOf(value))
            assertTrue(value, runCatching { AdvancedRoutingRules.validate(candidate) }.isFailure)
            assertTrue(value, runCatching { AdvancedRoutingRules.appIdentity(value) }.isFailure)
        }
        assertTrue(runCatching { AdvancedRoutingRules.decode("""[{"id":"bad","name":"Bad","packages":[null]}]""") }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.decode("""[{"id":"bad","name":"Bad","packages":"com.brave.browser"}]""") }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.validate(AdvancedRouteRule("many", "Many", packages = List(65) { "org.example.app$it" })) }.isFailure)
    }

    @Test fun enabledPackageLimitIsGlobalAndDisabledRulesDoNotConsumeRuntimeIdentities() {
        val groups = List(5) { group -> AdvancedRouteRule("group$group", "Group $group",
            packages = List(64) { index -> "org.example.app${group * 64 + index}" }) }
        assertEquals(AdvancedRoutingRules.MAX_PACKAGES, AdvancedRoutingRules.packageNames(groups.take(4)).size)
        assertTrue(runCatching { AdvancedRoutingRules.encode(groups) }.isFailure)
        assertTrue(runCatching { AdvancedRoutingRules.decode(com.google.gson.Gson().toJson(groups)) }.isFailure)
        val disabledLast = groups.mapIndexed { index, rule -> rule.copy(enabled = index < 4) }
        assertEquals(5, AdvancedRoutingRules.decode(AdvancedRoutingRules.encode(disabledLast)).size)
        assertEquals(256, AdvancedRoutingRules.packageNames(disabledLast).size)
        val repeated = List(5) { groups.first().copy(id = "repeat$it") }
        assertEquals(64, AdvancedRoutingRules.packageNames(repeated).size)
    }
}

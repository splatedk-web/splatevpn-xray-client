package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.data.AdvancedRouteRule
import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AppIdentityRoutingIntegrationTest {
    private val packages = setOf("com.brave.browser")
    private val rule = AdvancedRouteRule("app-and-site", "App and site", packages = packages.toList(), suffixes = listOf("example.test"))
    private val access = AppIdentityAccess(32189, packages, LocalProxyCredentials("private-fallback", "private-test-secret"))
    private val node = ServerNode("test", "Test", "vless", "203.0.113.1", 443, uuid = "key")

    @Test fun cannotCompileAppRuleWithoutItsPrivateIdentityChannel() {
        assertTrue(runCatching { XrayConfigGenerator().generateConfig(node, advancedRules = listOf(rule)) }.isFailure)
        assertTrue(runCatching { XrayConfigGenerator().generateConfig(node, advancedRules = listOf(rule),
            appIdentityAccess = AppIdentityAccess(32189, setOf("com.other.browser"), access.fallback)) }.isFailure)
        assertTrue(runCatching { XrayConfigGenerator().generateConfig(node, advancedRules = listOf(rule),
            appIdentityAccess = AppIdentityAccess(SplateConstants.XRAY_SOCKS_PORT, packages, access.fallback)) }.isFailure)
    }

    @Test fun appRulesAreScopedToPrivateInboundEvenIfMixedUserImpersonatesApp() {
        val identity = AdvancedRoutingRules.appIdentity(packages.single())
        val root = JsonParser.parseString(XrayConfigGenerator().generateConfig(node, advancedRules = listOf(rule),
            appIdentityAccess = access, localProxyAccess = LocalProxyCredentials(identity, "public-secret"),
            dnsProbePort = SplateConstants.DNS_CHECK_SOCKS_PORT,
            shareLan = true, lanAccess = LanAccess("192.168.1.5", "lan-user", "lan-test-secret", true))).asJsonObject
        val inbounds = root.getAsJsonArray("inbounds").map { it.asJsonObject }
        val privateInbound = inbounds.single { it["tag"].asString == AppIdentityAccess.INBOUND_TAG }
        assertEquals("127.0.0.1", privateInbound["listen"].asString)
        assertEquals(access.port, privateInbound["port"].asInt)
        assertEquals("password", privateInbound.getAsJsonObject("settings")["auth"].asString)
        val accounts = privateInbound.getAsJsonObject("settings").getAsJsonArray("accounts")
        assertEquals(setOf(identity, access.fallback.username), accounts.map { it.asJsonObject["user"].asString }.toSet())
        assertTrue(accounts.all { it.asJsonObject["pass"].asString == access.fallback.password })
        inbounds.filter { it != privateInbound }.forEach {
            assertFalse("Private password leaked into shared/probe listener", it.toString().contains(access.fallback.password))
        }
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val compiled = rules.single { it["ruleTag"]?.asString == "adv-rule-app-and-site" }
        assertEquals(listOf(AppIdentityAccess.INBOUND_TAG), compiled.getAsJsonArray("inboundTag").map { it.asString })
        assertEquals(listOf(identity), compiled.getAsJsonArray("user").map { it.asString })
        assertTrue(compiled.has("domain"))
        assertTrue(rules.any { it["outboundTag"]?.asString == "dns-out" &&
            it.getAsJsonArray("inboundTag")?.any { tag -> tag.asString == AppIdentityAccess.INBOUND_TAG } == true })
    }

    @Test fun orScopesOnlyItsAppBranchAndKeepsDomainAlternative() {
        val root = JsonParser.parseString(XrayConfigGenerator().generateConfig(node,
            advancedRules = listOf(rule.copy(operation = "or")), appIdentityAccess = access)).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
            .filter { it["ruleTag"]?.asString?.startsWith("adv-rule-app-and-site") == true }
        assertEquals(2, rules.size)
        assertTrue(rules.single { it.has("user") }.has("inboundTag"))
        assertFalse(rules.single { it.has("domain") }.has("inboundTag"))
    }

    @Test fun disabledAppRuleDoesNotNeedNativeChannelAndLegacyRulesKeepWorking() {
        XrayConfigGenerator().generateConfig(node, advancedRules = listOf(rule.copy(enabled = false)))
        XrayConfigGenerator().generateConfig(node, advancedRules = listOf(rule.copy(packages = emptyList())))
        assertFalse(access.toString().contains("private-test-secret"))
    }

    @Test fun uidClassifierNeverAttributesSharedUidToOnePackage() {
        assertEquals(AdvancedRoutingRules.appIdentity(packages.single()),
            AppFlowIdentityResolver.classify(packages, packages, "other"))
        assertEquals("other", AppFlowIdentityResolver.classify(setOf("com.other.browser"), packages, "other"))
        assertNull(AppFlowIdentityResolver.classify(emptySet(), packages, "other"))
        assertNull(AppFlowIdentityResolver.classify(packages + "com.shared.browser", packages, "other"))
        assertTrue(runCatching { AppIdentityAccess(32189, packages,
            LocalProxyCredentials(AdvancedRoutingRules.appIdentity(packages.single()), "secret")) }.isFailure)
    }
}

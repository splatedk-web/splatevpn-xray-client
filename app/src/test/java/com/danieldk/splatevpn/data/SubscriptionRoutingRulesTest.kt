package com.danieldk.splatevpn.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRoutingRulesTest {
    private val xray = """{
      "inbounds":[{"listen":"0.0.0.0","port":1234,"protocol":"socks"}],
      "dns":{"servers":["http://attacker.invalid/dns-query"]},
      "outbounds":[{"tag":"remote","protocol":"vless"},{"tag":"bypass","protocol":"freedom"},{"tag":"deny","protocol":"blackhole"}],
      "routing":{"rules":[
        {"type":"field","domain":["domain:example.com"],"outboundTag":"bypass"},
        {"type":"field","ip":["198.51.100.0/24"],"port":"443","outboundTag":"remote"},
        {"type":"field","domain":["ads.example"],"outboundTag":"deny"}
      ]}
    }"""
    private fun subscription(json: String, enabled: Boolean = true, skipped: Int = 0) =
        SubscriptionEntity("sub", "Test", "https://example.com/sub", useSubscriptionRules = enabled, importedRulesJson = json, importedRulesSkipped = skipped)
    private fun node(subId: String = "sub") = ServerNode("node", "Node", "vless", "vpn.example", 443, subId = subId)

    @Test fun xrayImportsOnlyValidatedRoutingWithManagedTargetsAndPreservesOrder() {
        val result = SubscriptionRoutingRules.parse(xray)
        assertEquals(0, result.skipped)
        val rules = SubscriptionRoutingRules.rules(result.json)
        assertEquals(listOf("direct", "proxy", "block", "proxy"), rules.map { it["outboundTag"].asString })
        assertEquals("domain:example.com", rules[0].getAsJsonArray("domain")[0].asString)
        assertEquals("tcp,udp", rules.last()["network"].asString)
        assertFalse(result.json.contains("attacker"))
        assertFalse(result.json.contains("inbounds"))
        assertEquals(result.json, SubscriptionRoutingRules.validateStored(result.json))
    }

    @Test fun onlySelectedNodesEnabledSubscriptionCanApplyItsApprovedCompleteSet() {
        val parsed = SubscriptionRoutingRules.parse(xray)
        assertEquals(4, SubscriptionRoutingRules.activeFor(node(), listOf(subscription(parsed.json))).size)
        assertTrue(SubscriptionRoutingRules.activeFor(node("other"), listOf(subscription(parsed.json))).isEmpty())
        assertTrue(SubscriptionRoutingRules.activeFor(node(), listOf(subscription(parsed.json, enabled = false))).isEmpty())
        assertTrue(SubscriptionRoutingRules.activeFor(node(), listOf(subscription(parsed.json).copy(enabled = false))).isEmpty())
        assertTrue(SubscriptionRoutingRules.activeFor(node(), listOf(subscription(parsed.json, skipped = 1))).isEmpty())
        assertTrue(SubscriptionRoutingRules.activeFor(null, listOf(subscription(parsed.json))).isEmpty())
        assertTrue(SubscriptionRoutingRules.activeFor(node(), listOf(subscription("not-json"))).isEmpty())
    }

    @Test fun unknownConditionsOrUnresolvedTargetsCannotBecomeBroaderDirectRules() {
        val inputs = listOf(
            xray.replace("\"domain\":[\"domain:example.com\"]", "\"domain\":[\"domain:example.com\"],\"source\":[\"10.0.0.1\"]"),
            xray.replace("\"outboundTag\":\"bypass\"", "\"outboundTag\":\"missing\""),
            xray.replace("\"type\":\"field\",\"domain\":[\"domain:example.com\"]", "\"type\":\"field\",\"balancerTag\":\"dynamic\""),
            xray.replace("domain:example.com", "ext:/private/file:all"),
            xray.replace("domain:example.com", "geosite:not-a-bundled-category")
        )
        for (input in inputs) {
            val result = SubscriptionRoutingRules.parse(input)
            assertTrue(input, result.skipped > 0)
            assertTrue(SubscriptionRoutingRules.activeFor(node(), listOf(subscription(result.json, skipped = result.skipped))).isEmpty())
        }
    }

    @Test fun mixedPoliciesInArrayOfProfilesRequirePerProfileSupportInsteadOfGuessing() {
        val same = SubscriptionRoutingRules.parse("[$xray,$xray]")
        assertEquals(SubscriptionRoutingRules.parse(xray), same)
        val different = SubscriptionRoutingRules.parse("[$xray,${xray.replace("domain:example.com", "domain:other.example")}]")
        assertTrue(different.skipped > 0)
        assertEquals("", different.json)
    }

    @Test fun singBoxConvertsSimpleConditionsAndFinalRouteWithoutImportingRemoteDns() {
        val result = SubscriptionRoutingRules.parse("""{
          "outbounds":[{"tag":"vpn","type":"vless"},{"tag":"local","type":"direct"}],
          "route":{"rules":[
            {"domain":["exact.example"],"domain_suffix":[".example.org"],"outbound":"local"},
            {"ip_cidr":["2001:db8::/32"],"port":[443],"network":"tcp","outbound":"vpn"},
            {"domain_keyword":["advert"],"action":"reject"}
          ],"final":"vpn"}
        }""")
        assertEquals(0, result.skipped)
        val rules = SubscriptionRoutingRules.rules(result.json)
        assertEquals(4, rules.size)
        assertEquals(listOf("full:exact.example", "regexp:^.+\\.example\\.org$"), rules[0].getAsJsonArray("domain").map { it.asString })
        assertEquals("443", rules[1]["port"].asString)
        assertEquals("block", rules[2]["outboundTag"].asString)
        assertEquals("proxy", rules.last()["outboundTag"].asString)
    }

    @Test fun singBoxLogicalRulesAndRuleProvidersAreReportedUnsupported() {
        for (extra in listOf("\"rule_set\":[\"remote\"]", "\"invert\":true", "\"source_ip_cidr\":[\"10.0.0.0/8\"]")) {
            val result = SubscriptionRoutingRules.parse("""{"outbounds":[{"tag":"vpn","type":"vless"}],"route":{"rules":[{"domain":"example.com","outbound":"vpn",$extra}]}}""")
            assertEquals(1, result.skipped)
        }
    }

    @Test fun clashSimpleRulesResolveProxyOnlyGroupsAndShowAllActions() {
        val result = SubscriptionRoutingRules.parse("""
            proxies:
              - name: "Node A"
                type: vless
                server: vpn.example
              - {name: 'Node B', type: trojan, server: other.example}
            proxy-groups:
              - name: Auto
                type: url-test
                proxies: [Node A, Node B]
              - name: Proxy
                type: select
                proxies:
                  - Auto
                  - Node B
            rules:
              - DOMAIN,exact.example,DIRECT
              - 'DOMAIN-SUFFIX,example.org,Proxy'
              - DOMAIN-KEYWORD,advert,REJECT
              - IP-CIDR,198.51.100.0/24,Auto
              - GEOIP,RU,DIRECT
              - MATCH,Proxy
        """.trimIndent())
        assertEquals(result.toString(), 0, result.skipped)
        val preview = SubscriptionRoutingRules.preview(result.json)
        assertEquals(listOf("direct", "proxy", "block", "proxy", "direct", "proxy"), preview.map { it.target })
        assertTrue(preview[0].conditions.contains("full:exact.example"))
    }

    @Test fun clashMixedDirectProxyGroupsAndDynamicProvidersCannotBeGuessed() {
        for (group in listOf("proxies: [Node A, DIRECT]", "use: [remote-provider]")) {
            val result = SubscriptionRoutingRules.parse("""
                proxies:
                  - name: Node A
                    type: vless
                    server: vpn.example
                proxy-groups:
                  - name: Choice
                    type: select
                    $group
                rules:
                  - DOMAIN,example.com,Choice
                  - MATCH,DIRECT
            """.trimIndent())
            assertTrue(result.toString(), result.skipped > 0)
            assertTrue(SubscriptionRoutingRules.activeFor(node(), listOf(subscription(result.json, skipped = result.skipped))).isEmpty())
        }
    }

    @Test fun sourceMatchingSemanticsAreNeverBroadenedOrNarrowedSilently() {
        val mixed = SubscriptionRoutingRules.parse("""{"outbounds":[{"tag":"vpn","type":"vless"}],"route":{"rules":[{"domain":"example.com","ip_cidr":"198.51.100.0/24","outbound":"vpn"}],"final":"vpn"}}""")
        assertEquals(1, mixed.skipped)
        val unresolved = SubscriptionRoutingRules.parse("rules:\n  - IP-CIDR,198.51.100.0/24,DIRECT,no-resolve\n  - MATCH,DIRECT")
        assertEquals(1, unresolved.skipped)
        val suffix = SubscriptionRoutingRules.parse("""{"outbounds":[{"tag":"local","type":"direct"}],"route":{"rules":[{"domain_suffix":".example.com","outbound":"local"}]}}""")
        assertEquals(0, suffix.skipped)
        val value = SubscriptionRoutingRules.rules(suffix.json)[0].getAsJsonArray("domain")[0].asString
        assertEquals("regexp:^.+\\.example\\.com$", value)
        val regex = Regex(value.removePrefix("regexp:"))
        assertFalse(regex.matches("example.com"))
        assertTrue(regex.matches("www.example.com"))
        assertTrue(regex.matches("one.two.example.com"))
    }

    @Test fun plainAndBase64NodeListsDoNotInventRoutingRules() {
        val raw = "vless://password@vpn.example:443#Node"
        assertEquals(SubscriptionRoutingRules.Parsed(), SubscriptionRoutingRules.parse(raw))
        assertEquals(SubscriptionRoutingRules.Parsed(), SubscriptionRoutingRules.parse(java.util.Base64.getEncoder().encodeToString(raw.toByteArray())))
    }

    @Test fun malformedOrOversizedStoredRulesAreRejectedInsteadOfPartiallyRestored() {
        val invalid = listOf(
            "{}", "bad", "[{\"outboundTag\":\"direct\"}]",
            "[{\"type\":\"field\",\"outboundTag\":\"direct\",\"port\":\"0\"}]",
            "[{\"type\":\"field\",\"outboundTag\":\"direct\",\"network\":\"icmp\"}]",
            "[{\"type\":\"field\",\"outboundTag\":\"direct\",\"domain\":[\"example.com\"],\"source\":[\"10.0.0.1\"]}]",
            "[{\"type\":\"field\",\"outboundTag\":\"other-proxy\",\"network\":\"tcp\"}]",
            " ".repeat(SubscriptionRoutingRules.MAX_STORED_CHARS) + "[]"
        )
        invalid.forEach { input -> assertThrows(input.take(120), Exception::class.java) { SubscriptionRoutingRules.validateStored(input) } }
        assertEquals("", SubscriptionRoutingRules.validateStored("[]"))
    }

    @Test fun ruleCountAndFieldValuesAreBoundedWithoutActivatingTruncatedPolicies() {
        val content = "rules:\n" + (0..SubscriptionRoutingRules.MAX_RULES).joinToString("\n") { "  - DOMAIN,test$it.example,DIRECT" }
        val result = SubscriptionRoutingRules.parse(content)
        assertTrue(result.skipped >= 1)
        assertEquals(SubscriptionRoutingRules.MAX_RULES, SubscriptionRoutingRules.preview(result.json).size)
        assertTrue(SubscriptionRoutingRules.activeFor(node(), listOf(subscription(result.json, skipped = result.skipped))).isEmpty())
        val many = (0..128).joinToString(",") { "\"example$it.com\"" }
        val stored = "[{\"type\":\"field\",\"outboundTag\":\"direct\",\"domain\":[$many]}]"
        assertThrows(Exception::class.java) { SubscriptionRoutingRules.validateStored(stored) }
    }

    @Test fun implicitSourceDefaultsAppearExplicitlyInTheConsentPreview() {
        val clash = SubscriptionRoutingRules.parse("rules:\n  - DOMAIN,example.com,DIRECT")
        assertEquals(0, clash.skipped)
        val clashRules = SubscriptionRoutingRules.rules(clash.json)
        assertEquals("direct", clashRules.last()["outboundTag"].asString)
        assertEquals("tcp,udp", clashRules.last()["network"].asString)
        val singBox = SubscriptionRoutingRules.parse("""{"outbounds":[{"tag":"local","type":"direct"},{"tag":"vpn","type":"vless"}],"route":{"rules":[{"domain":"example.com","outbound":"vpn"}]}}""")
        assertEquals(0, singBox.skipped)
        assertEquals("direct", SubscriptionRoutingRules.rules(singBox.json).last()["outboundTag"].asString)
    }

    @Test fun clashNamedDirectEntriesAreNotMistakenForVpnProxies() {
        val parsed = SubscriptionRoutingRules.parse("""
            proxies:
              - {name: Local, type: direct}
              - name: Remote
                type: ss
                server: vpn.example
            rules:
              - DOMAIN,example.com,Local
              - MATCH,Remote
        """.trimIndent())
        assertEquals(0, parsed.skipped)
        assertEquals(listOf("direct", "proxy"), SubscriptionRoutingRules.preview(parsed.json).map { it.target })
    }

    @Test fun previewUsesCanonicalValuesAndRoundTripDoesNotChangeOrder() {
        val raw = """[{"outboundTag":"direct","network":["udp","tcp"],"port":"443,80-81","type":"field"}]"""
        val normalized = SubscriptionRoutingRules.validateStored(raw)
        val rule = JsonParser.parseString(normalized).asJsonArray[0].asJsonObject
        assertEquals("tcp,udp", rule["network"].asString)
        assertEquals("443,80-81", rule["port"].asString)
        assertEquals(normalized, SubscriptionRoutingRules.validateStored(normalized))
    }
}

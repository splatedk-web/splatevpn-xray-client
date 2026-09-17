package com.danieldk.splatevpn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerNode(
    @PrimaryKey
    val id: String,
    val name: String,
    val protocol: String, // vless, vmess, trojan, shadowsocks
    val address: String,
    val port: Int,
    val uuid: String = "",
    val flow: String = "",
    val encryption: String = "none",
    val network: String = "tcp", // tcp, xhttp, ws, grpc, splithttp
    val security: String = "none", // reality, tls, none
    val sni: String = "",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val path: String = "",
    val host: String = "",
    val mode: String = "auto", // for xhttp
    val padding: String = "100-1000",
    val ping: Long = 0L,
    val rawLink: String = "",
    val isSelected: Boolean = false,
    val subId: String = "",
    val subName: String = ""
)

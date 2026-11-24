package com.danieldk.splatevpn.data

import java.io.File

internal object UpdateFilePolicy {
    fun stagedApk(destination: File): File = File(destination.parentFile, ".${destination.name}.download.apk")

    fun sha256FromAsset(digest: String?): String? =
        digest?.removePrefix("sha256:")?.takeIf {
            digest.startsWith("sha256:") && it.matches(Regex("[0-9a-fA-F]{64}"))
        }?.lowercase()

    fun hasMatchingCertificate(installed: List<ByteArray>, downloaded: List<ByteArray>): Boolean =
        installed.any { current -> downloaded.any { it.isNotEmpty() && current.contentEquals(it) } }

    fun matchesApk(expectedPackage: String, expectedVersion: String, actualPackage: String?, actualVersion: String?): Boolean {
        fun normalized(version: String?): String? = version?.trim()?.removePrefix("v")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")) }
        val version = normalized(expectedVersion) ?: return false
        return expectedPackage.isNotBlank() && actualPackage == expectedPackage && normalized(actualVersion) == version
    }

    fun cachedApk(directory: File, version: String, assetName: String?): File {
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}"))) { "Invalid update version" }
        val asset = assetName?.takeIf(String::isNotBlank) ?: "app.apk"
        require(asset.length <= 160 && asset.endsWith(".apk", ignoreCase = true) &&
            asset.none { it.isISOControl() || it in "/\\:" }) { "Invalid update filename" }
        // Release assets often keep the same basename across versions.
        val file = File(directory, "splatevpn_${version}_$asset")
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "Update file is outside its cache directory" }
        return file
    }

    fun isNewerVersion(current: String, remote: String): Boolean {
        fun parse(v: String): List<Long> {
            val clean = v.trim().let {
                if (it.startsWith("v", ignoreCase = true)) it.substring(1) else it
            }.substringBefore("-").substringBefore("+")
            return clean.split(".")
                .map { segment -> segment.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L }
        }

        val currParts = parse(current)
        val remoteParts = parse(remote)
        val maxLen = maxOf(currParts.size, remoteParts.size)

        for (i in 0 until maxLen) {
            val c = currParts.getOrElse(i) { 0L }
            val r = remoteParts.getOrElse(i) { 0L }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}

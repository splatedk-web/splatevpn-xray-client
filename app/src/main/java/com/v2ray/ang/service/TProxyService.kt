package com.v2ray.ang.service

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep

class TProxyService(private val vpnInterface: ParcelFileDescriptor) {

    /** Called once for each original TUN flow, on a dedicated native thread. */
    @Keep
    fun interface FlowIdentityResolver {
        fun resolve(protocol: Int, localAddress: String, localPort: Int,
                    remoteAddress: String, remotePort: Int): String?
    }

    companion object {
        private const val TAG = "TProxyService"

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                Log.d(TAG, "libhev-socks5-tunnel.so loaded successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load libhev-socks5-tunnel.so", t)
            }
        }

        @JvmStatic
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        private external fun TProxyStopService()

        @JvmStatic
        private external fun TProxyStartServiceWithIdentity(configPath: String, fd: Int, resolver: Any?): Boolean

        @JvmStatic
        private external fun TProxyStopServiceWithIdentity(): Boolean

        @JvmStatic
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        private external fun TProxyIdentityApiVersion(): Int

        @JvmStatic
        private external fun TProxyGetStats(): LongArray

        fun identityApiVersion(): Int = runCatching { TProxyIdentityApiVersion() }.getOrDefault(0)
    }

    fun start(configPath: String, resolver: FlowIdentityResolver? = null) {
        try {
            if (resolver != null) check(identityApiVersion() == 1) {
                "Native TUN does not support authenticated app identities"
            }
            Log.d(TAG, "Starting TProxy, fd: ${vpnInterface.fd}")
            if (identityApiVersion() == 1) {
                check(TProxyStartServiceWithIdentity(configPath, vpnInterface.fd, resolver)) {
                    "Native TUN rejected startup"
                }
            } else {
                TProxyStartService(configPath, vpnInterface.fd)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting TProxy", t)
            throw IllegalStateException("Failed to start native TUN tunnel", t)
        }
    }

    fun isRunning(): Boolean = runCatching { TProxyIsRunning() }.getOrDefault(false)

    fun stop() {
        try {
            Log.d(TAG, "Stopping TProxy...")
            if (identityApiVersion() == 1) TProxyStopServiceWithIdentity() else TProxyStopService()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping TProxy", t)
        }
    }

    fun getStats(): LongArray {
        return try {
            TProxyGetStats()
        } catch (t: Throwable) {
            LongArray(0)
        }
    }
}

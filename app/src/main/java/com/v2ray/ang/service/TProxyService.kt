package com.v2ray.ang.service

import android.os.ParcelFileDescriptor
import android.util.Log

class TProxyService(private val vpnInterface: ParcelFileDescriptor) {

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
        private external fun TProxyGetStats(): LongArray
    }

    fun start(configPath: String) {
        try {
            Log.d(TAG, "Starting TProxy with config: $configPath, fd: ${vpnInterface.fd}")
            TProxyStartService(configPath, vpnInterface.fd)
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting TProxy", t)
        }
    }

    fun stop() {
        try {
            Log.d(TAG, "Stopping TProxy...")
            TProxyStopService()
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

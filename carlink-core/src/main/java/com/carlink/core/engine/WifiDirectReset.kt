package com.carlink.core.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Removes the current Wi-Fi Direct group, if any, and waits (bounded) for the result. */
object WifiDirectReset {
    private const val TAG = "CarLink-P2P"

    @SuppressLint("MissingPermission") // Nearby devices / location are granted from Home's permission card.
    fun removeGroup(context: Context, timeoutMillis: Long = 3_000) {
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: return
        val channel = runCatching { manager.initialize(context, Looper.getMainLooper(), null) }.getOrNull() ?: return
        val done = CountDownLatch(1)
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    done.countDown()
                } else {
                    Log.i(TAG, "removing leftover group ${group.networkName}")
                    manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = done.countDown()
                        override fun onFailure(reason: Int) {
                            Log.w(TAG, "removeGroup failed: $reason")
                            done.countDown()
                        }
                    })
                }
            }
        }.onFailure { done.countDown() }
        done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        runCatching { channel.close() }
    }
}

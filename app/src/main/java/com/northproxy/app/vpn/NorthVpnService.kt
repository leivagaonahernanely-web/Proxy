package com.northproxy.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.northproxy.app.MainActivity
import com.northproxy.app.R

class NorthVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var engine: Tun2Socks? = null

    companion object {
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DNS = "dns"
        const val CHANNEL = "north_vpn"

        @Volatile var active = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                val host = intent?.getStringExtra(EXTRA_HOST)
                val port = intent?.getIntExtra(EXTRA_PORT, 1080) ?: 1080
                val dns = intent?.getStringExtra(EXTRA_DNS) ?: "1.1.1.1"
                if (host != null) startTunnel(host, port, dns)
            }
        }
        return START_STICKY
    }

    private fun startTunnel(host: String, port: Int, dns: String) {
        stopTunnel()
        val builder = Builder()
            .setSession("North Proxy")
            .addAddress("10.111.222.1", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("10.111.222.2")
            .setMtu(1500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        val fd = try { builder.establish() } catch (e: Exception) { null } ?: return
        tun = fd
        active = true
        startForegroundNotif()
        engine = Tun2Socks(this, fd, host, port, dns).also { it.start() }
    }

    private fun stopTunnel() {
        active = false
        engine?.stop()
        engine = null
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "North Proxy", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("North Proxy activo")
            .setContentText("Tráfico protegido")
            .setSmallIcon(R.drawable.north_logo)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}

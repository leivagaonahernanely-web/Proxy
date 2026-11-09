package com.northproxy.app.data

import android.content.Context

data class ProxyServer(
    val name: String,
    val host: String,
    val port: Int,
    val region: String,
    val dns: String = "1.1.1.1"
)

object Settings {
    private const val PREFS = "north_prefs"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_NAME = "name"
    private const val K_DNS = "dns"

    fun saveCustom(ctx: Context, s: ProxyServer) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_HOST, s.host)
            .putInt(K_PORT, s.port)
            .putString(K_NAME, s.name)
            .putString(K_DNS, s.dns)
            .apply()
    }

    fun loadCustom(ctx: Context): ProxyServer? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString(K_HOST, null) ?: return null
        return ProxyServer(
            name = p.getString(K_NAME, "Custom") ?: "Custom",
            host = host,
            port = p.getInt(K_PORT, 1080),
            region = "CUSTOM",
            dns = p.getString(K_DNS, "1.1.1.1") ?: "1.1.1.1"
        )
    }
}

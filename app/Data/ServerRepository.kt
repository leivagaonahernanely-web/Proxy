package com.northproxy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object ServerRepository {

    private const val REMOTE_URL =
        "https://github.com/leivagaonahernanely-web/Proxy.git"

    val fallback = listOf(
        ProxyServer("North · Santiago", "cl1.northmods.net", 1080, "CL"),
        ProxyServer("North · Miami", "us1.northmods.net", 1080, "US"),
        ProxyServer("North · São Paulo", "br1.northmods.net", 1080, "BR"),
        ProxyServer("North · Frankfurt", "de1.northmods.net", 1080, "DE"),
        ProxyServer("North · Singapur", "sg1.northmods.net", 1080, "SG")
    )

    suspend fun load(): List<ProxyServer> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            val list = ArrayList<ProxyServer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ProxyServer(
                        name = o.getString("name"),
                        host = o.getString("host"),
                        port = o.optInt("port", 1080),
                        region = o.optString("region", "??"),
                        dns = o.optString("dns", "1.1.1.1")
                    )
                )
            }
            if (list.isEmpty()) fallback else list
        }.getOrDefault(fallback)
    }
}

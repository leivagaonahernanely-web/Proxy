package com.northproxy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.northproxy.app.data.ProxyServer
import com.northproxy.app.data.ServerRepository
import com.northproxy.app.data.Settings
import com.northproxy.app.ui.*
import com.northproxy.app.vpn.NorthVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {

    private var servers by mutableStateOf<List<ProxyServer>>(emptyList())
    private var selected by mutableStateOf<ProxyServer?>(null)
    private var connected by mutableStateOf(false)
    private var pings by mutableStateOf<Map<String, Int>>(emptyMap())
    private var tab by mutableStateOf(0)

    private val vpnPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) launchVpn()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            servers = ServerRepository.load()
            selected = servers.firstOrNull()
            servers.take(6).forEach { s -> measurePing(s) }
        }
        setContent {
            NorthTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    if (tab == 0) HomeScreen() else ConfigScreen()
                }
            }
        }
    }

    private fun measurePing(s: ProxyServer) {
        lifecycleScope.launch {
            val ms = withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val ok = runCatching {
                    Socket().use { it.connect(InetSocketAddress(s.host, s.port), 1800) }
                }.isSuccess
                if (ok) (System.currentTimeMillis() - t0).toInt() else -1
            }
            pings = pings + (s.host to ms)
        }
    }

    private fun toggle() {
        if (connected) {
            startService(Intent(this, NorthVpnService::class.java).setAction(NorthVpnService.ACTION_DISCONNECT))
            connected = false
            return
        }
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPermLauncher.launch(prep) else launchVpn()
    }

    private fun launchVpn() {
        val s = selected ?: return
        startService(
            Intent(this, NorthVpnService::class.java)
                .setAction(NorthVpnService.ACTION_CONNECT)
                .putExtra(NorthVpnService.EXTRA_HOST, s.host)
                .putExtra(NorthVpnService.EXTRA_PORT, s.port)
                .putExtra(NorthVpnService.EXTRA_DNS, s.dns)
        )
        connected = true
    }

    @Composable
    private fun HomeScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(36.dp))
            TopBar()
            Spacer(Modifier.height(28.dp))
            ConnectButton()
            Spacer(Modifier.height(26.dp))
            StatusStrip()
            Spacer(Modifier.height(26.dp))
            Text("SERVIDORES", color = TextDim, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            servers.take(6).forEach { ServerRow(it) }
            Spacer(Modifier.height(24.dp))
            BottomNav()
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun TopBar() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.north_logo), null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, NorthRed.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("NORTH", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("PROXY", color = NorthRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Surface2).clickable { tab = 1 },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Settings, null, tint = TextDim, modifier = Modifier.size(19.dp)) }
        }
    }

    @Composable
    private fun ConnectButton() {
        val pulse by rememberInfiniteTransition(label = "p").animateFloat(
            0.92f, 1.06f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "s"
        )
        Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(230.dp).scale(if (connected) pulse else 1f).background(
                    Brush.radialGradient(
                        listOf((if (connected) NorthRed else NorthRedDark).copy(alpha = 0.45f), Color.Transparent)
                    ), CircleShape
                )
            )
            Box(
                Modifier.size(168.dp).clip(CircleShape).background(
                    Brush.verticalGradient(
                        if (connected) listOf(NorthRed, NorthRedDark) else listOf(Surface2, Surface1)
                    )
                ).border(2.dp, NorthRed.copy(alpha = if (connected) 0.9f else 0.3f), CircleShape)
                    .clickable { toggle() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Bolt, null, tint = if (connected) Color.White else NorthRed, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (connected) "DETENER" else "CONECTAR",
                        color = if (connected) Color.White else TextMain,
                        fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.5.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusStrip() {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1)
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Stat("ESTADO", if (connected) "Activo" else "Inactivo", if (connected) Online else TextDim)
            Stat("REGIÓN", selected?.region ?: "—", TextMain)
            Stat("PING", pings[selected?.host ?: ""]?.let { if (it < 0) "—" else "$it ms" } ?: "—", TextMain)
        }
    }

    @Composable
    private fun Stat(label: String, value: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = TextDim, fontSize = 10.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun ServerRow(s: ProxyServer) {
        val isSel = selected?.host == s.host
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(if (isSel) NorthRed.copy(alpha = 0.12f) else Surface1)
                .border(
                    1.dp,
                    if (isSel) NorthRed.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.05f),
                    RoundedCornerShape(14.dp)
                )
                .clickable { selected = s; measurePing(s) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Public, null, tint = TextDim, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(s.host + ":" + s.port, color = TextDim, fontSize = 11.sp)
            }
            val p = pings[s.host]
            Text(
                when { p == null -> "···"; p < 0 -> "—"; else -> "$p ms" },
                color = when {
                    p == null -> TextDim
                    p < 0 -> NorthRed
                    p < 120 -> Online
                    else -> Color(0xFFFFB020)
                },
                fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            if (isSel) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.CheckCircle, null, tint = NorthRed, modifier = Modifier.size(17.dp))
            }
        }
    }

    @Composable
    private fun ConfigScreen() {
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("1080") }
        var dns by remember { mutableStateOf("1.1.1.1") }
        val ctx = LocalContext.current

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Spacer(Modifier.height(30.dp))
            Text("CONFIGURACIÓN", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("Servidor SOCKS5 personalizado", color = TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            NorthField("Host / IP", host, { host = it }, KeyboardType.Uri)
            Spacer(Modifier.height(12.dp))
            NorthField("Puerto", port, { port = it }, KeyboardType.Number)
            Spacer(Modifier.height(12.dp))
            NorthField("DNS", dns, { dns = it }, KeyboardType.Uri)
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 1080
                    if (host.isNotBlank()) {
                        val s = ProxyServer("Custom", host.trim(), p, "CUSTOM", dns.trim())
                        Settings.saveCustom(ctx, s)
                        selected = s
                        tab = 0
                    }
                },
                Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NorthRed)
            ) { Text("GUARDAR Y USAR", fontWeight = FontWeight.Black, letterSpacing = 1.2.sp) }
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(16.dp)) {
                Column {
                    Text("North Proxy", color = TextMain, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Cliente VPN + relay SOCKS5. Requiere un servidor SOCKS5 propio.",
                        color = TextDim, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("v1.0.0", color = TextDim, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            BottomNav()
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun NorthField(label: String, value: String, onChange: (String) -> Unit, kb: KeyboardType) {
        Column {
            Text(label, color = TextDim, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = value, onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = kb),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NorthRed,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Surface1,
                    unfocusedContainerColor = Surface1,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain
                )
            )
        }
    }

    @Composable
    private fun BottomNav() {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(6.dp)) {
            NavItem("Inicio", 0, Modifier.weight(1f))
            NavItem("Ajustes", 1, Modifier.weight(1f))
        }
    }

    @Composable
    private fun NavItem(label: String, idx: Int, mod: Modifier) {
        val on = tab == idx
        Box(
            mod.clip(RoundedCornerShape(12.dp))
                .background(if (on) NorthRed.copy(alpha = 0.18f) else Color.Transparent)
                .clickable { tab = idx }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (on) NorthRed else TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

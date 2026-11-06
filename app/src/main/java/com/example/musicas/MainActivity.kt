package com.example.musicas

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Song(val uri: String, val name: String)

// Paleta: preto, grafite, cinzas e branco
private val Preto = Color(0xFF000000)
private val Grafite = Color(0xFF151515)
private val Linha = Color(0xFF333333)
private val Cinza = Color(0xFF8E8E8E)
private val CinzaEscuro = Color(0xFF5A5A5A)
private val Branco = Color(0xFFF2F2F2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Branco, onPrimary = Preto, background = Preto, surface = Preto, onSurface = Branco)
            ) { Surface(Modifier.fillMaxSize(), color = Preto) { App() } }
        }
    }
}

private fun save(p: SharedPreferences, s: List<Song>) {
    val a = JSONArray()
    s.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name)) }
    p.edit().putString("songs", a.toString()).apply()
}

private fun load(p: SharedPreferences): List<Song> = runCatching {
    val a = JSONArray(p.getString("songs", "[]"))
    (0 until a.length()).map { Song(a.getJSONObject(it).getString("u"), a.getJSONObject(it).getString("n")) }
}.getOrDefault(emptyList())

private fun readSong(ctx: Context, uri: Uri): Song {
    val nome = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    return Song(uri.toString(), nome?.substringBeforeLast('.') ?: "Música")
}

private fun item(s: Song) = MediaItem.Builder().setUri(s.uri).setMediaId(s.uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(s.name).build()).build()

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("musicas", Context.MODE_PRIVATE) }
    val songs = remember { mutableStateListOf<Song>().apply { addAll(load(prefs)) } }
    var loop by remember { mutableStateOf(prefs.getBoolean("loop", false)) }
    var current by remember { mutableStateOf(prefs.getString("current", null)) }
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaController?>(null) }

    // Android 13+: permissão para mostrar o controle de música na notificação / tela de bloqueio
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Conecta ao serviço de reprodução (o player vive no serviço, não na tela)
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, reason: Int) {
                m?.mediaId?.let { current = it; prefs.edit().putString("current", it).apply() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        val future = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        future.addListener({
            runCatching {
                val c = future.get()
                c.addListener(l)
                playing = c.isPlaying
                c.currentMediaItem?.mediaId?.let { current = it; prefs.edit().putString("current", it).apply() }
                player = c
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(future) } // desconecta; a música continua no serviço
    }

    LaunchedEffect(loop, player) {
        player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        prefs.edit().putBoolean("loop", loop).apply()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (songs.none { it.uri == u.toString() }) songs.add(readSong(ctx, u))
        }
        save(prefs, songs)
    }

    fun playOne(s: Song) { player?.run { setMediaItem(item(s)); prepare(); play() } }
    fun playAll() {
        val p = player ?: return
        if (songs.isEmpty()) return
        p.setMediaItems(songs.map(::item)); p.prepare(); p.play()
    }
    fun tap(s: Song) {
        val p = player ?: return
        val same = p.currentMediaItem?.mediaId == s.uri && p.playbackState != Player.STATE_ENDED
        if (same) { if (p.isPlaying) p.pause() else p.play() } else playOne(s)
    }

    val caixa = RoundedCornerShape(10.dp)

    Column(Modifier.systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Músicas", Modifier.weight(1f), fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
            Box(
                Modifier.size(44.dp).clip(CircleShape).border(1.5.dp, Branco, CircleShape)
                    .clickable { picker.launch(arrayOf("audio/*")) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Add, "Adicionar música", tint = Branco) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(caixa).background(if (loop) Branco else Preto)
                    .border(1.dp, if (loop) Branco else Linha, caixa).clickable { loop = !loop },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.RepeatOne, "Repetir uma música", tint = if (loop) Preto else Cinza) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (playing) player?.pause() else playAll() },
                shape = caixa,
                colors = ButtonDefaults.buttonColors(containerColor = Branco, contentColor = Preto),
                modifier = Modifier.height(44.dp)
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(if (playing) "Pausar" else "Tocar playlist", fontWeight = FontWeight.Medium)
            }
        }
        if (songs.isEmpty()) Text("Toque em + para adicionar músicas.", Modifier.padding(top = 24.dp), color = Cinza)
        LazyColumn(Modifier.fillMaxSize()) {
            items(songs, key = { it.uri }) { s ->
                val ativa = current == s.uri
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(caixa)
                        .background(if (ativa) Branco else Grafite)
                        .border(1.dp, if (ativa) Branco else Linha, caixa)
                        .clickable { tap(s) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(s.uri)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (ativa) Preto else Branco
                        )
                        if (ativa) Text(if (playing) "Tocando agora" else "Pausada", fontSize = 12.sp, color = CinzaEscuro)
                    }
                    IconButton(onClick = {
                        if (current == s.uri) { player?.stop(); current = null }
                        songs.remove(s); save(prefs, songs)
                    }) { Icon(Icons.Default.Delete, "Remover", tint = if (ativa) CinzaEscuro else Cinza) }
                }
            }
        }
    }
}

@Composable
fun Cover(uri: String) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                r.setDataSource(ctx, Uri.parse(uri))
                val b = r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                r.release(); b
            }.getOrNull()
        }
    }
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Linha), contentAlignment = Alignment.Center) {
        val capa = img
        if (capa != null) Image(capa, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = Cinza)
    }
}

package com.aurora.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

data class Track(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private var permissionGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) loadTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        permissionGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) loadTracks() else permissionLauncher.launch(permission)

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({ controller = controllerFuture?.get() }, ContextCompat.getMainExecutor(this))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF0C0D0F), surface = Color(0xFF17191D),
                primary = Color(0xFFE9E9E9), onBackground = Color(0xFFF4F4F4),
                onSurface = Color(0xFFF4F4F4), secondary = Color(0xFF9A9DA3)
            )) {
                AuroraScreen(tracks, controller, permissionGranted, onRequestPermission = { permissionLauncher.launch(permission) })
            }
        }
    }

    private fun loadTracks() {
        val list = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c: Cursor ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                list += Track(id, c.getString(titleCol) ?: "Unknown title", c.getString(artistCol) ?: "Unknown artist",
                    Uri.withAppendedPath(collection, id.toString()))
            }
        }
        tracks = list
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
private fun AuroraScreen(tracks: List<Track>, player: MediaController?, granted: Boolean, onRequestPermission: () -> Unit) {
    var selected by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    val current = player?.currentMediaItemIndex ?: -1
    LaunchedEffect(player) {
        while (true) {
            playing = player?.isPlaying == true
            kotlinx.coroutines.delay(400)
        }
    }
    Scaffold(containerColor = Color(0xFF0C0D0F)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("AURORA", fontSize = 13.sp, letterSpacing = 4.sp, color = Color(0xFF9A9DA3), fontWeight = FontWeight.SemiBold)
                    Text("Your sound, quietly.", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(27.dp))
            }
            Box(Modifier.fillMaxWidth().weight(0.92f).clip(RoundedCornerShape(28.dp)).background(Color(0xFF202329)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Album, contentDescription = null, tint = Color(0xFFBFC2C8), modifier = Modifier.size(82.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(if (current in tracks.indices) tracks[current].title else "A little space for music", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (current in tracks.indices) tracks[current].artist else "Choose a track from your library", color = Color(0xFF9A9DA3), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("YOUR LIBRARY", color = Color(0xFF9A9DA3), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("${tracks.size} TRACKS", color = Color(0xFF777B83), fontSize = 10.sp, letterSpacing = 1.sp)
            }
            if (!granted) {
                TextButton(onClick = onRequestPermission) { Text("Allow access to your music") }
            } else if (tracks.isEmpty()) {
                Text("No audio files found on this device.", color = Color(0xFF9A9DA3), modifier = Modifier.padding(vertical = 22.dp))
            } else {
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                            selected = index
                            val items = tracks.map { t -> MediaItem.Builder().setUri(t.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build()).build() }
                            player?.setMediaItems(items, index, 0L)
                            player?.prepare()
                            player?.play()
                        }.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFFB9BDC5))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text(track.artist, color = Color(0xFF858A93), maxLines = 1, fontSize = 12.sp)
                            }
                            if (index == current) Icon(Icons.Rounded.Equalizer, contentDescription = "Playing", tint = Color.White)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(30.dp)) }
                IconButton(onClick = { if (playing) player?.pause() else player?.play() }, modifier = Modifier.size(58.dp).clip(RoundedCornerShape(50)).background(Color.White)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play or pause", tint = Color.Black, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
        }
    }
}

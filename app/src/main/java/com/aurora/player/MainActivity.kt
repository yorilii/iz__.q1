package com.aurora.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture

private val Ink = Color(0xFF090A0C)
private val Panel = Color(0xFF15171B)
private val Muted = Color(0xFF8B8E96)
private val Paper = Color(0xFFF5F5F2)

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val artwork: Uri?
)

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
                background = Ink, surface = Panel, primary = Paper,
                onBackground = Paper, onSurface = Paper, secondary = Muted
            )) {
                AuroraScreen(
                    tracks = tracks,
                    player = controller,
                    granted = permissionGranted,
                    onRequestPermission = { permissionLauncher.launch(permission) }
                )
            }
        }
    }

    private fun loadTracks() {
        val list = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumCol)
                val art = if (albumId > 0) Uri.parse("content://media/external/audio/albumart/$albumId") else null
                list += Track(
                    id = id,
                    title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                    artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
                    album = cursor.getString(albumNameCol)?.takeIf { it.isNotBlank() } ?: "",
                    durationMs = cursor.getLong(durationCol),
                    uri = Uri.withAppendedPath(collection, id.toString()),
                    artwork = art
                )
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
private fun AuroraScreen(
    tracks: List<Track>,
    player: MediaController?,
    granted: Boolean,
    onRequestPermission: () -> Unit
) {
    var libraryOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    val currentIndex = player?.currentMediaItemIndex ?: -1
    val currentTrack = tracks.getOrNull(currentIndex)
    var lyricsResult by remember(currentTrack?.id) { mutableStateOf<LyricsResult?>(null) }
    var lyricsLoading by remember(currentTrack?.id) { mutableStateOf(false) }
    LaunchedEffect(currentTrack?.id) {
        lyricsResult = null
        if (currentTrack != null) {
            lyricsLoading = true
            lyricsResult = LyricsRepository.load(currentTrack)
            lyricsLoading = false
        }
    }
    val activeLyricIndex = lyricsResult?.lines?.indexOfLast { it.timeMs <= position } ?: -1
    val visibleTracks = remember(tracks, search) {
        if (search.isBlank()) tracks else tracks.filter {
            it.title.contains(search, true) || it.artist.contains(search, true)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            playing = player?.isPlaying == true
            duration = player?.duration?.takeIf { it > 0 } ?: 0L
            position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            kotlinx.coroutines.delay(500)
        }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            if (currentTrack != null) {
                MiniPlayer(
                    track = currentTrack,
                    playing = playing,
                    onToggle = { if (playing) player?.pause() else player?.play() },
                    onOpen = { libraryOpen = false }
                )
            }
        }
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 22.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("A U R O R A", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.8.sp)
                    Text(if (lyricsOpen) "Now singing" else if (libraryOpen) "Your collection" else "Made for your moments", color = Paper, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(
                    onClick = { if (lyricsOpen) lyricsOpen = false else libraryOpen = !libraryOpen },
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Panel)
                ) {
                    Icon(if (lyricsOpen) Icons.Rounded.ArrowBack else if (libraryOpen) Icons.Rounded.GraphicEq else Icons.Rounded.QueueMusic, contentDescription = "Switch view", tint = Paper)
                }
            }

            AnimatedContent(targetState = if (lyricsOpen) 2 else if (libraryOpen) 1 else 0, label = "main-content") { screen ->
                if (screen == 2) {
                    LyricsContent(result = lyricsResult, loading = lyricsLoading, activeIndex = activeLyricIndex)
                } else if (screen == 1) {
                    LibraryContent(
                        tracks = visibleTracks,
                        query = search,
                        onQueryChange = { search = it },
                        currentIndex = currentIndex,
                        onTrackClick = { index -> playTracks(visibleTracks, index, player) }
                    )
                } else {
                    HomeContent(
                        tracks = tracks,
                        currentTrack = currentTrack,
                        currentIndex = currentIndex,
                        playing = playing,
                        duration = duration,
                        position = position,
                        onOpenLibrary = { libraryOpen = true },
                        onOpenLyrics = { lyricsOpen = true },
                        onPlayTrack = { index -> playTracks(tracks, index, player) },
                        onToggle = { if (playing) player?.pause() else player?.play() },
                        onPrevious = { player?.seekToPreviousMediaItem() },
                        onNext = { player?.seekToNextMediaItem() },
                        onSeek = { player?.seekTo(it) },
                        granted = granted,
                        onRequestPermission = onRequestPermission
                    )
                }
            }
        }
    }
}

private fun playTracks(tracks: List<Track>, index: Int, player: MediaController?) {
    val items = tracks.map { track ->
        MediaItem.Builder().setUri(track.uri).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setArtworkUri(track.artwork)
                .build()
        ).build()
    }
    player?.setMediaItems(items, index, 0L)
    player?.prepare()
    player?.play()
}

@Composable
private fun HomeContent(
    tracks: List<Track>,
    currentTrack: Track?,
    currentIndex: Int,
    playing: Boolean,
    duration: Long,
    position: Long,
    onOpenLibrary: () -> Unit,
    onOpenLyrics: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    granted: Boolean,
    onRequestPermission: () -> Unit
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    LaunchedEffect(position, duration, isSeeking) {
        if (!isSeeking) sliderPosition = if (duration > 0) position.coerceAtMost(duration).toFloat() / duration else 0f
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF292B30), Color(0xFF111216), Color(0xFF25272C)))),
            contentAlignment = Alignment.Center
        ) {
            if (currentTrack?.artwork != null) {
                AsyncImage(
                    model = currentTrack.artwork,
                    contentDescription = "Album artwork",
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(116.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFF35383F)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Paper, modifier = Modifier.size(54.dp))
                    }
                    Text("A QUIET PLACE FOR MUSIC", color = Color(0xFFB7B9BF), fontSize = 9.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (currentTrack?.artwork != null) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))))
            }
            if (currentTrack == null) {
                Text("Your music, your space.", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(currentTrack?.title ?: "Nothing playing yet", color = Paper, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(currentTrack?.artist ?: "Choose a song from your library", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenLyrics) {
                    Icon(Icons.Rounded.Subtitles, contentDescription = "Open synced lyrics", tint = Paper)
                }
                IconButton(onClick = onOpenLibrary) {
                    Icon(Icons.Rounded.List, contentDescription = "Open library", tint = Paper)
                }
            }
        }
        Slider(
            value = sliderPosition,
            onValueChange = { fraction -> sliderPosition = fraction; isSeeking = true },
            onValueChangeFinished = { if (duration > 0) onSeek((sliderPosition * duration).toLong()); isSeeking = false },
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = Paper,
                activeTrackColor = Paper,
                inactiveTrackColor = Color(0xFF383A40)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Muted, fontSize = 10.sp)
            Text(formatTime(duration), color = Muted, fontSize = 10.sp)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Paper, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(22.dp))
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Paper)
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play or pause", tint = Ink, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(22.dp))
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Paper, modifier = Modifier.size(30.dp))
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("RECENTLY ADDED", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onOpenLibrary) { Text("VIEW ALL", color = Paper, fontSize = 10.sp, letterSpacing = 1.sp) }
        }
        if (!granted) {
            TextButton(onClick = onRequestPermission) { Text("Allow access to your music", color = Paper) }
        } else if (tracks.isEmpty()) {
            Text("No music found on this device", color = Muted, modifier = Modifier.padding(bottom = 22.dp))
        } else {
            tracks.take(3).forEachIndexed { index, track ->
                TrackRow(track = track, selected = index == currentIndex, onClick = { onPlayTrack(index) })
            }
        }
    }
}

@Composable
private fun LibraryContent(
    tracks: List<Track>,
    query: String,
    onQueryChange: (String) -> Unit,
    currentIndex: Int,
    onTrackClick: (Int) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search your music", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Muted) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Muted)
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF777A82),
                unfocusedBorderColor = Color(0xFF34363C),
                focusedTextColor = Paper,
                unfocusedTextColor = Paper,
                cursorColor = Paper
            )
        )
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ALL SONGS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text("${tracks.size} TRACKS", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        }
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching songs", color = Muted)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(track, index == currentIndex) { onTrackClick(index) }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF202228) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (track.artwork != null) {
            AsyncImage(model = track.artwork, contentDescription = null, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)))
        } else {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Panel), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFFBFC1C7))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(track.title, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.Equalizer, contentDescription = "Playing", tint = Paper, modifier = Modifier.size(20.dp))
        else Icon(Icons.Rounded.MoreHoriz, contentDescription = "More", tint = Muted)
    }
}

@Composable
private fun MiniPlayer(track: Track, playing: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp)).background(Color(0xFF1B1D22))
            .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (track.artwork != null) AsyncImage(model = track.artwork, contentDescription = null, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
        else Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF33363D)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Paper)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.title, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onToggle) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play or pause", tint = Paper)
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

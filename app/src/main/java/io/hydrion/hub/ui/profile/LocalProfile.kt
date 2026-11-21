package io.hydrion.hub.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.hydrion.hub.ui.messages.decodeAttachment
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LocalProfile(val name: String = "", val avatar: String? = null)

internal class LocalProfileStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("local_profile", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(LocalProfile(prefs.getString("name", "").orEmpty(), prefs.getString("avatar", null)))
    val profile = current.asStateFlow()
    private val writes = Mutex()

    suspend fun save(name: String, newPhoto: Uri?, removePhoto: Boolean) = withContext(Dispatchers.IO) {
        writes.withLock {
            val avatar = when {
                newPhoto != null -> {
                    val bitmap = decodeAttachment(app, newPhoto, 192)
                    try {
                        val bytes = ByteArrayOutputStream()
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw IOException()
                        Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                    } finally {
                        bitmap.recycle()
                    }
                }
                removePhoto -> null
                else -> current.value.avatar
            }
            val next = LocalProfile(name.trim(), avatar)
            if (!prefs.edit().putString("name", next.name).putString("avatar", avatar).commit()) throw IOException()
            current.value = next
        }
    }

    companion object {
        @Volatile private var instance: LocalProfileStore? = null
        fun shared(context: Context) = instance ?: synchronized(this) {
            instance ?: LocalProfileStore(context).also { instance = it }
        }
    }
}

@Composable
internal fun ProfileAvatar(profile: LocalProfile, modifier: Modifier = Modifier) {
    val photo = profile.avatar
    val bitmap by key(photo) {
        produceState<Bitmap?>(initialValue = null) {
            if (photo != null) value = withContext(Dispatchers.Default) {
                try {
                    val bytes = Base64.decode(photo, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) Icon(Icons.Outlined.PersonOutline, null, Modifier.fillMaxWidth(.55f))
        else Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

package com.danieldk.splatevpn.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.glassmorphism
import com.danieldk.splatevpn.ui.theme.AccentTeal
import com.danieldk.splatevpn.ui.theme.BackgroundDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Bitmap?
)

@Composable
fun PerAppScreen(
    viewModel: MainViewModel
) {
    val isGlobal by viewModel.isGlobalAppProxy.collectAsState()
    val selectedPackages by viewModel.perAppPackages.collectAsState()
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val items = packages
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("chrome") || it.packageName.contains("browser") || it.packageName.contains("telegram") || it.packageName.contains("youtube") }
                .map { appInfo ->
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val iconDrawable = pm.getApplicationIcon(appInfo)
                    val iconBitmap = drawableToBitmap(iconDrawable)
                    AppItem(name, appInfo.packageName, iconBitmap)
                }
                .sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                appList = items
                isLoadingApps = false
            }
        }
    }

    val filteredApps = remember(searchQuery, appList) {
        if (searchQuery.isBlank()) {
            appList
        } else {
            appList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BackgroundDark, Color(0xFF020617))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 20.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(
                text = "Маршрутизация приложений",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Настройте, какой трафик проходит через VPN",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Master Toggle Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassmorphism(16)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isGlobal) "Глобальный режим (Все приложения)" else "Выборочный режим (Per-App)",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isGlobal) "Весь трафик устройства идет через VPN" else "Только отмеченные приложения идут через VPN",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = !isGlobal,
                    onCheckedChange = { checked ->
                        viewModel.setGlobalAppProxy(!checked)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentTeal,
                        checkedTrackColor = AccentTeal.copy(alpha = 0.5f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (!isGlobal) {
                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск приложений...", fontSize = 13.sp, color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentTeal,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Выбрано: ${selectedPackages.size}",
                        color = AccentTeal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isLoadingApps) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentTeal)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isChecked = selectedPackages.contains(app.packageName)
                            AppRow(
                                app = app,
                                isChecked = isChecked,
                                onToggle = { viewModel.toggleAppPackage(app.packageName) }
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Глобальный режим активен",
                            color = Color(0xFF94A3B8),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Все установленные приложения защищены",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppRow(
    app: AppItem,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.5f))
            .clickable { onToggle() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Apps, contentDescription = null, tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AccentTeal,
                checkmarkColor = Color(0xFF020617)
            )
        )
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 72
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 72
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

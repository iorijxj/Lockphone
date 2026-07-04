package com.lockphone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.lockphone.apps.AppEntry

@Composable
fun LauncherScreen(
    apps: List<AppEntry>,
    onLaunch: (String) -> Unit,
    onParentClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(
            onClick = onParentClick,
            modifier = Modifier.align(Alignment.End).padding(8.dp),
        ) { Text("家长模式") }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLaunch(app.packageName) }
                        .padding(4.dp),
                ) {
                    Image(
                        bitmap = app.icon.toBitmap(144, 144).asImageBitmap(),
                        contentDescription = app.label,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        app.label,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

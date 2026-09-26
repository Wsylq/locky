package com.locky.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.locky.app.R
import com.locky.app.data.InstalledApp

/** The searchable list of every launchable app, each with a lock switch. */
@Composable
fun AppList(
    state: AppsUiState,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState(modifier)

        state.errorMessage != null -> ErrorState(
            message = state.errorMessage,
            modifier = modifier,
        )

        state.installed.isEmpty() -> EmptyState(
            title = stringResource(R.string.apps_empty_title),
            body = stringResource(R.string.apps_empty_body),
            modifier = modifier,
        )

        state.visible.isEmpty() -> EmptyState(
            title = stringResource(R.string.apps_no_results, state.query),
            body = "",
            modifier = modifier,
        )

        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(
                items = state.visible,
                key = { it.packageName },
            ) { app ->
                AppRow(
                    app = app,
                    isLocked = app.packageName in state.lockedPackages,
                    onToggle = { onToggle(app.packageName, it) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    isLocked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app)

        Spacer(Modifier.size(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(
            checked = isLocked,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun AppIcon(app: InstalledApp) {
    val icon = app.icon
    if (icon == null) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LockyIcons.App,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Launcher icons are adaptive drawables with layers; rasterising flattens
    // them to the shape the user actually sees on their home screen.
    val bitmap = remember(icon) {
        runCatching {
            icon.toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX).asImageBitmap()
        }.getOrNull()
    }

    if (bitmap == null) {
        Icon(
            imageVector = LockyIcons.App,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.apps_load_error, message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val ICON_SIZE_PX = 128

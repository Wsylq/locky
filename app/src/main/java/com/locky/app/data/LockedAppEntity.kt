package com.locky.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A launchable app on the device that Locky protects.
 *
 * Only protected apps have a row; unlocking an app deletes it. [label] is cached
 * so the list can render before the package manager answers, and [icon] is
 * carried alongside for display (see `com.locky.app.ui.AppUiModel`).
 */
@Entity(tableName = "locked_apps")
data class LockedAppEntity(
    @PrimaryKey
    val packageName: String,
    val label: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

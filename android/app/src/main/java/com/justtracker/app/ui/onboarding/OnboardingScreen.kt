package com.justtracker.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.ui.common.LocalAppContainer
import com.justtracker.app.ui.common.Permissions
import com.justtracker.app.util.AppLocale
import com.justtracker.app.util.labelRes
import kotlinx.coroutines.launch

private enum class Step { LANGUAGE, PERMISSIONS }

/**
 * First launch: explicit language choice (US-18), then the explanation and the
 * location → notifications permission chain (US-14).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.settingsFlow.collectAsStateWithLifecycle()
    // The language step is skipped once a choice exists (also after AppCompat recreates the activity).
    var step by rememberSaveable { mutableStateOf(if (settings.language == null) Step.LANGUAGE else Step.PERMISSIONS) }

    when (step) {
        Step.LANGUAGE -> LanguageStep(
            initial = AppLanguage.forDevice(),
            onContinue = { chosen ->
                container.appScope.launch {
                    // Persist before applying: the recreated activity must already see language != null.
                    container.settingsRepository.setLanguage(chosen)
                    step = Step.PERMISSIONS
                }
            },
        )
        Step.PERMISSIONS -> PermissionsStep(onDone)
    }
}

@Composable
private fun LanguageStep(initial: AppLanguage, onContinue: (AppLanguage) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(56.dp))
        }
        Text(
            stringResource(R.string.onboarding_language_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.onboarding_language_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(32.dp))
        Column(Modifier.selectableGroup()) {
            AppLanguage.entries.forEach { language ->
                ListItem(
                    headlineContent = { Text(stringResource(language.labelRes())) },
                    leadingContent = { RadioButton(selected = language == selected, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.selectable(
                        selected = language == selected,
                        role = Role.RadioButton,
                        onClick = { selected = language },
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                onContinue(selected)
                // Applies the locale; on API < 33 AppCompat recreates the activity when it differs from the device one.
                scope.launch { AppLocale.apply(selected) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(stringResource(R.string.action_continue), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PermissionsStep(onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun finish() {
        scope.launch {
            container.settingsRepository.setOnboardingDone()
            onDone()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.needsNotificationPermission() && !Permissions.hasNotifications(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            finish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Route, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(56.dp))
        }
        Text(
            stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(32.dp))
        PermissionRow(Icons.Filled.LocationOn, R.string.onboarding_location_title, R.string.onboarding_location_body)
        Spacer(Modifier.height(16.dp))
        PermissionRow(Icons.Filled.Notifications, R.string.onboarding_notifications_title, R.string.onboarding_notifications_body)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { locationLauncher.launch(Permissions.LOCATION) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(stringResource(R.string.action_continue), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, titleRes: Int, bodyRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package me.mudkip.moememos.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.model.Account
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.service.AccountService
import me.mudkip.moememos.data.service.MemoService
import me.mudkip.moememos.ui.security.AppLockGate
import me.mudkip.moememos.ui.theme.MoeMemosTheme
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class SingleMemoWidgetConfigurationActivity : FragmentActivity() {

    @Inject
    lateinit var memoService: MemoService

    @Inject
    lateinit var accountService: AccountService

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var currentAccount: Account? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_CANCELED, resultValue)

        setContent {
            MoeMemosTheme {
                AppLockGate {
                    ConfigurationScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ConfigurationScreen() {
        var selectedMemoId by remember { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        var newMemoContent by remember { mutableStateOf("") }
        var memos by remember { mutableStateOf<List<MemoEntity>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var createFailed by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            try {
                currentAccount = accountService.currentAccount.first()
                memoService.getRepository().listMemos().suspendOnSuccess {
                    memos = data
                        .filter { !it.isDeleted && !it.archived }
                        .sortedByDescending { it.date }
                        .take(200)
                }

                val glanceId = GlanceAppWidgetManager(this@SingleMemoWidgetConfigurationActivity)
                    .getGlanceIdBy(appWidgetId)
                val prefs = getAppWidgetState(
                    context = this@SingleMemoWidgetConfigurationActivity,
                    definition = PreferencesGlanceStateDefinition,
                    glanceId = glanceId
                )
                // Prefill only with a memo that can actually be shown. Prefilling a deleted or
                // archived memo would leave the confirm button enabled while saving it changes
                // nothing, which looks like a dead button.
                selectedMemoId = prefs[SingleMemoWidgetKeys.memoId]?.takeIf { boundId ->
                    memoService.getRepository().getMemo(boundId)
                        ?.let { !it.isDeleted && !it.archived } == true
                }
            } catch (e: Exception) {
                // Ignore error; the screen still allows picking a memo or creating one.
            } finally {
                isLoading = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.single_memo_widget_title)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = selectedMemoId != null,
                            onClick = { saveConfig(selectedMemoId) }
                        ) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.confirm))
                        }
                    }
                )
            }
        ) { padding ->
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val filtered = memos.filter { query.isBlank() || it.content.contains(query, ignoreCase = true) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = newMemoContent,
                            onValueChange = { newMemoContent = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.single_memo_widget_new_memo_hint)) },
                            minLines = 2
                        )
                    }
                    item {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = newMemoContent.isNotBlank(),
                            onClick = { createAndBind(newMemoContent) { createFailed = true } }
                        ) {
                            Text(stringResource(R.string.single_memo_widget_create_and_bind))
                        }
                    }
                    if (createFailed) {
                        item {
                            Text(
                                text = stringResource(R.string.error_unknown),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.single_memo_widget_pick_memo),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.single_memo_widget_search_hint)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true
                        )
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.no_memos),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    items(filtered, key = { it.identifier }) { memo ->
                        MemoRow(
                            memo = memo,
                            selected = selectedMemoId == memo.identifier,
                            onClick = { selectedMemoId = memo.identifier }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MemoRow(memo: MemoEntity, selected: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        memo.date.toEpochMilli(),
                        Instant.now().toEpochMilli(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = memo.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.confirm),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    private fun createAndBind(content: String, onFailure: () -> Unit) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        lifecycleScope.launch {
            val visibility = currentAccount?.toUser()?.defaultVisibility ?: MemoVisibility.PRIVATE
            val response = memoService.getRepository().createMemo(
                content = trimmed,
                visibility = visibility,
                resources = emptyList(),
                tags = emptyList()
            )
            response.suspendOnSuccess {
                saveConfig(data.identifier)
            }
            if (response !is ApiResponse.Success) {
                onFailure()
            }
        }
    }

    private fun saveConfig(identifier: String? = null) {
        if (identifier == null) return
        lifecycleScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(this@SingleMemoWidgetConfigurationActivity)
                    .getGlanceIdBy(appWidgetId)

                updateAppWidgetState(this@SingleMemoWidgetConfigurationActivity, glanceId) { prefs ->
                    prefs[SingleMemoWidgetKeys.memoId] = identifier
                    prefs[SingleMemoWidgetKeys.accountKey] = currentAccount?.accountKey().orEmpty()
                    prefs[SingleMemoWidgetKeys.appWidgetId] = appWidgetId
                    prefs[SingleMemoWidgetKeys.refreshKey] = 0L
                }

                SingleMemoGlanceWidget().update(this@SingleMemoWidgetConfigurationActivity, glanceId)

                val resultValue = Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                setResult(RESULT_OK, resultValue)
            } catch (e: Exception) {
                // The widget was removed while this screen was open; nothing left to configure.
                Timber.tag("SingleMemoWidget").e(e, "Exception saving single memo widget configuration")
            } finally {
                finish()
            }
        }
    }
}

package com.spendly.ui.importing

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendly.data.Money
import java.time.format.DateTimeFormatter

/**
 * Import spending from a bank or e-wallet PDF statement.
 *
 * Statement layouts are not standardised, so the parse is a best effort and the
 * review step is not optional: every row is shown with a tick box before
 * anything is written. Rows that look like reloads or money coming in are listed
 * separately, with the reason, so you can see what was left out rather than
 * wondering why the total is short.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    onDismiss: () -> Unit,
    viewModel: ImportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.pick(uri, context.displayNameOf(uri))
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.reset()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Import a statement",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))

            when (val s = state) {
                ImportState.Idle -> IdleBody { picker.launch(arrayOf("application/pdf")) }

                ImportState.Reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading the statement…", style = MaterialTheme.typography.bodyMedium)
                }

                is ImportState.NeedsPassword -> PasswordBody(
                    wrongAttempt = s.wrongAttempt,
                    onSubmit = viewModel::submitPassword,
                )

                is ImportState.Review -> ReviewBody(
                    review = s,
                    categories = categories,
                    onToggle = viewModel::toggle,
                    onSelectAll = viewModel::selectAll,
                    onCategory = viewModel::setCategory,
                    onImport = viewModel::confirmImport,
                )

                is ImportState.Imported -> ImportedBody(s) {
                    viewModel.reset()
                    onDismiss()
                }

                is ImportState.Failed -> FailedBody(s.message) {
                    viewModel.reset()
                    picker.launch(arrayOf("application/pdf"))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

private val rowDate = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun IdleBody(onPick: () -> Unit) {
    Column {
        Text(
            "Pick a PDF statement from your bank or e-wallet. Spendly reads it on " +
                "this phone — the file is not uploaded anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Reloads and top-ups are left out: moving money into a wallet is not " +
                "spending, and counting it would double up whatever you buy with it later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("Choose PDF")
        }
    }
}

@Composable
private fun PasswordBody(wrongAttempt: Boolean, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column {
        Text(
            "That statement is password protected. Banks usually use your IC number, " +
                "passport number or date of birth.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            isError = wrongAttempt,
            supportingText = if (wrongAttempt) {
                { Text("That password did not open the file") }
            } else {
                // Said plainly, because typing a bank password into a third-party
                // app is a reasonable thing to hesitate over.
                { Text("Used only to open this file. Not stored anywhere.") }
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onSubmit(password) },
            enabled = password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Unlock") }
    }
}

@Composable
private fun ReviewBody(
    review: ImportState.Review,
    categories: List<com.spendly.data.Category>,
    onToggle: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onCategory: (Int, com.spendly.data.Category) -> Unit,
    onImport: () -> Unit,
) {
    var expanded by remember { mutableStateOf<Int?>(null) }
    var showSkipped by remember { mutableStateOf(false) }

    val selected = review.rows.count { it.selected }
    val total = review.rows.filter { it.selected }.sumOf { it.row.amountMinor }

    Column {
        Text(
            "${review.fileName} · ${review.pages} page${if (review.pages == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Found ${review.rows.size} spends · ${Money.format(total, review.currency)} selected",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        if (review.rows.any { it.duplicate }) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${review.rows.count { it.duplicate }} already imported — left unticked.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row {
            TextButton(onClick = { onSelectAll(true) }) { Text("Select all") }
            TextButton(onClick = { onSelectAll(false) }) { Text("None") }
        }

        HorizontalDivider()

        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            itemsIndexed(review.rows) { index, item ->
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = item.selected, onCheckedChange = { onToggle(index) })
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.row.description,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row {
                                Text(
                                    item.row.date?.format(rowDate).orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    item.categoryName.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        expanded = if (expanded == index) null else index
                                    },
                                )
                            }
                        }
                        Text(
                            Money.format(item.row.amountMinor, item.row.currency),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    AnimatedVisibility(visible = expanded == index) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 48.dp, bottom = 6.dp,
                            ),
                        ) {
                            items(categories, key = { it.id }) { category ->
                                FilterChip(
                                    selected = category.id == item.categoryId,
                                    onClick = {
                                        onCategory(index, category)
                                        expanded = null
                                    },
                                    label = { Text("${category.emoji} ${category.name}") },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (review.skipped.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            TextButton(onClick = { showSkipped = !showSkipped }) {
                Text(
                    if (showSkipped) {
                        "Hide ${review.skipped.size} left out"
                    } else {
                        "Show ${review.skipped.size} left out (reloads and money in)"
                    },
                )
            }
            AnimatedVisibility(visible = showSkipped) {
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(review.skipped) { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    row.skipReason ?: "Not spending",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                Money.format(row.amountMinor, row.currency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onImport,
            enabled = selected > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (selected == 0) "Nothing selected" else "Import $selected")
        }
    }
}

@Composable
private fun ImportedBody(state: ImportState.Imported, onDone: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Imported ${state.count} " + if (state.count == 1) "spend" else "spends",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (state.skippedTransfers > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${state.skippedTransfers} reload${if (state.skippedTransfers == 1) "" else "s"} " +
                    "left out — moving money into a wallet is not spending.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun FailedBody(message: String, onRetry: () -> Unit) {
    Column {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try another file") }
    }
}

/** The file's display name, for the review header. */
private fun android.content.Context.displayNameOf(uri: Uri): String =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: "statement.pdf"

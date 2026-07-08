package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.discountscreener.android.domain.model.DashboardNotice
import com.discountscreener.android.domain.model.TickerSearchSuggestion

@Composable
internal fun TickerSearchBar(
    query: String,
    suggestions: List<TickerSearchSuggestion>,
    expanded: Boolean,
    notice: DashboardNotice?,
    label: String = "Ticker",
    placeholder: String? = null,
    onQueryChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    onQueryChange(it)
                    onExpandedChange(it.isNotBlank())
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(label) },
                placeholder = placeholder?.let { value -> ({ Text(value) }) },
            )
            Button(onClick = onSubmit) {
                Text("Open")
            }
        }
        if (expanded && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(suggestions, key = TickerSearchSuggestion::symbol) { suggestion ->
                        androidx.compose.material3.ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onExpandedChange(false)
                                    onSelect(suggestion.symbol)
                                },
                            headlineContent = {
                                Text(
                                    text = suggestion.companyName?.let { "${suggestion.symbol} - $it" } ?: suggestion.symbol,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = suggestion.profiles.joinToString(" · ") { profile -> profile.uppercase() },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            overlineContent = if (suggestion.inCurrentProfile) {
                                { Text("Current profile") }
                            } else {
                                null
                            },
                        )
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
            }
        }
        notice?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(it.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(it.message, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

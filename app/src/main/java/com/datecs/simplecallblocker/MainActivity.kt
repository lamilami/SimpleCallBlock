package com.datecs.simplecallblocker

import android.Manifest
import android.app.role.RoleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var roleManager: RoleManager
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** Bumped whenever stored data may have changed, so the UI re-reads it. */
    private var version by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        roleManager = getSystemService(RoleManager::class.java)
        setContent { MaterialTheme { Surface { Screen() } } }
    }

    override fun onResume() {
        super.onResume()
        version++
    }

    @Composable
    private fun Screen() {
        version // read so that the screen recomposes after every change
        val hasRole = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { version++ }
        val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
        val hasContacts = PhoneNumbers.hasContactsPermission(this)
        val blacklist = settings.blacklist.sorted()
        val prefixes = settings.prefixes.sorted()
        val log = settings.readLog()
        var newNumber by remember { mutableStateOf("") }
        var newPrefix by remember { mutableStateOf("") }

        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("SimpleCallBlocker", style = MaterialTheme.typography.headlineSmall) }

            item {
                Text("Call Screening role: " + if (hasRole) "GRANTED" else "NOT GRANTED - calls are not filtered")
                if (!hasRole) {
                    Button(onClick = {
                        roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                    }) { Text("Grant Call Screening role") }
                }
            }

            item {
                SwitchRow("Blocking enabled", settings.enabled) { settings.enabled = it; version++ }
                SwitchRow("Block Unknown (hidden + not in contacts)", settings.blockUnknown) {
                    settings.blockUnknown = it; version++
                    if (it && !hasContacts) contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
                if (settings.blockUnknown && !hasContacts) {
                    Text("Contacts permission missing: only hidden numbers are blocked.")
                    Button(onClick = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
                        Text("Grant contacts permission")
                    }
                }
                HorizontalDivider()
            }

            item { Text("Blacklist", style = MaterialTheme.typography.titleMedium) }
            item {
                AddRow(newNumber, { newNumber = it }, "Number, e.g. +359888123456") {
                    if (newNumber.any { it.isDigit() }) {
                        settings.addToBlacklist(newNumber); newNumber = ""; version++
                    }
                }
            }
            if (blacklist.isEmpty()) item { Text("(empty)") }
            items(blacklist, key = { "n:$it" }) { n -> EntryRow(n) { settings.removeFromBlacklist(n); version++ } }

            item { HorizontalDivider(); Text("Blocked prefixes", style = MaterialTheme.typography.titleMedium) }
            item {
                AddRow(newPrefix, { newPrefix = it }, "Prefix, e.g. +3592 or 0700") {
                    if (newPrefix.any { it.isDigit() }) {
                        settings.addPrefix(newPrefix); newPrefix = ""; version++
                    }
                }
            }
            if (prefixes.isEmpty()) item { Text("(empty)") }
            items(prefixes, key = { "p:$it" }) { p -> EntryRow(p) { settings.removePrefix(p); version++ } }

            item {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Blocked calls", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { settings.clearLog(); version++ }) { Text("Clear") }
                }
            }
            if (log.isEmpty()) item { Text("(none)") }
            items(log) { e -> Text("${timeFormat.format(Date(e.time))}  ${e.number}  [${e.reason}]") }

            item {
                HorizontalDivider()
                val isOwner = AdminReceiver.isDeviceOwner(this@MainActivity)
                Text("Device Owner: " + if (isOwner) "YES" else "NO (optional)")
                if (isOwner) {
                    SwitchRow("Prevent uninstall", AdminReceiver.isUninstallBlocked(this@MainActivity)) {
                        AdminReceiver.setUninstallBlocked(this@MainActivity, it); version++
                    }
                    if (!hasContacts) {
                        Button(onClick = { AdminReceiver.grantContactsPermission(this@MainActivity); version++ }) {
                            Text("Grant contacts permission by policy")
                        }
                    }
                    TextButton(onClick = { AdminReceiver.clearDeviceOwner(this@MainActivity); version++ }) {
                        Text("Remove Device Owner")
                    }
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun AddRow(value: String, onValue: (String) -> Unit, hint: String, onAdd: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value, onValueChange = onValue, singleLine = true,
                label = { Text(hint) }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            TextButton(onClick = onAdd) { Text("Add") }
        }
    }

    @Composable
    private fun EntryRow(text: String, onDelete: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

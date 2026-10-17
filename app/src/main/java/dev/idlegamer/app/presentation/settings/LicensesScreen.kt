package dev.idlegamer.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class License(val name: String, val license: String, val notice: String)

private val LICENSES = listOf(
    License("JavaSteam", "MIT License", "Copyright (c) 2018 Long Tran. https://github.com/Longi94/JavaSteam"),
    License("Bouncy Castle (bcprov-jdk18on)", "Bouncy Castle Licence (MIT-style)", "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. https://www.bouncycastle.org"),
    License("Protocol Buffers (protobuf-java)", "BSD 3-Clause", "Copyright 2008 Google Inc. https://github.com/protocolbuffers/protobuf"),
    License("Kotlin, kotlinx.coroutines", "Apache License 2.0", "Copyright JetBrains s.r.o. and contributors"),
    License("AndroidX / Jetpack Compose / Room / DataStore / Biometric", "Apache License 2.0", "Copyright The Android Open Source Project"),
    License("OkHttp, Okio", "Apache License 2.0", "Copyright Square, Inc."),
    License("Ktor", "Apache License 2.0", "Copyright JetBrains s.r.o."),
    License("Apache Commons Lang", "Apache License 2.0", "Copyright The Apache Software Foundation"),
    License("Coil", "Apache License 2.0", "Copyright Coil Contributors"),
    License("ZXing core", "Apache License 2.0", "Copyright ZXing authors"),
)

@Composable
fun LicensesScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "IdleGamer uses the following open-source components. Full licence texts: " +
                    "Apache-2.0 https://www.apache.org/licenses/LICENSE-2.0, MIT https://opensource.org/license/mit, " +
                    "BSD-3-Clause https://opensource.org/license/bsd-3-clause, Bouncy Castle https://www.bouncycastle.org/licence.html",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(LICENSES) { l ->
            Column {
                Text(l.name, style = MaterialTheme.typography.titleSmall)
                Text(l.license, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(l.notice, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

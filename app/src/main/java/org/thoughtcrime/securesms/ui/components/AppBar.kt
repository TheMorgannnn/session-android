package org.thoughtcrime.securesms.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.LocalType

@Preview
@Composable
fun AppBarPreview() {
    AppBar(title = "Title", {}, {})
}

@Composable
fun AppBar(title: String, onClose: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    Row(modifier = Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(painter = painterResource(id = R.drawable.ic_prev), contentDescription = "back")
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(text = title, style = LocalType.current.h4)
        Spacer(modifier = Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
            IconButton(onClick = onClose) {
                Icon(painter = painterResource(id = R.drawable.ic_x), contentDescription = "close")
            }
        }
    }
}

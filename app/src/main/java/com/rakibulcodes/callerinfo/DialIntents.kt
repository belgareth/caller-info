package com.rakibulcodes.callerinfo

import android.content.Intent
import android.net.Uri

fun buildDialIntent(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))

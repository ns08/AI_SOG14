package com.soni08.morseai.shopping

import android.content.Context
import android.content.Intent
import android.net.Uri

object AmazonOrderHelper {
    fun openAmazonSearch(context: Context, keyword: String) {
        val encoded = Uri.encode(keyword.trim())
        val amazonAppIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.amazon.co.jp/s?k=$encoded")
        ).apply {
            setPackage("com.amazon.mShop.android.shopping")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(amazonAppIntent)
        } catch (_: Exception) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.amazon.co.jp/s?k=$encoded")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
}

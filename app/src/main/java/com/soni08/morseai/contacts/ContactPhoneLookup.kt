package com.soni08.morseai.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.soni08.morseai.text.HiraganaNormalizer

data class MatchedContact(
    val name: String,
    val phoneNumber: String
)

object ContactPhoneLookup {

    fun lookupCallerByNumber(context: Context, phoneNumber: String): MatchedContact? {
        val cleanInput = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanInput.isEmpty()) return null

        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleanInput)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.NUMBER)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val num = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else cleanInput
                    if (name.isNotEmpty()) {
                        return MatchedContact(name = name, phoneNumber = num.ifEmpty { cleanInput })
                    }
                }
            }
        } catch (_: Exception) {
        }

        val tail = cleanInput.takeLast(8)
        if (tail.length >= 4) {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx)?.trim().orEmpty()
                        val rawNum = cursor.getString(numIdx)?.trim().orEmpty()
                        val digits = rawNum.filter { it.isDigit() }
                        if (name.isNotEmpty() && digits.endsWith(tail)) {
                            return MatchedContact(name = name, phoneNumber = rawNum)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun findCandidatesByName(
        context: Context,
        rawNameQuery: String,
        maxResults: Int = 5
    ): List<MatchedContact> {
        val cleanedQuery = normalizeNameQuery(rawNameQuery)
        if (cleanedQuery.isEmpty()) return emptyList()

        val hiraQuery = HiraganaNormalizer.toHiragana(cleanedQuery)

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val exactMatches = mutableListOf<MatchedContact>()
        val partialMatches = mutableListOf<MatchedContact>()
        val seenKeys = mutableSetOf<String>()

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx < 0 || numIdx < 0) return emptyList()

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIdx)?.trim().orEmpty()
                    val rawNumber = cursor.getString(numIdx)?.trim().orEmpty()
                    if (displayName.isEmpty() || rawNumber.isEmpty()) continue

                    val cleanNumber = rawNumber.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
                    if (cleanNumber.isEmpty()) continue

                    val dedupKey = "$displayName|$cleanNumber"
                    if (!seenKeys.add(dedupKey)) continue

                    val normalizedDisplay = normalizeNameQuery(displayName)
                    val hiraDisplay = HiraganaNormalizer.toHiragana(normalizedDisplay)
                    val candidate = MatchedContact(name = displayName, phoneNumber = cleanNumber)

                    when {
                        normalizedDisplay.equals(cleanedQuery, ignoreCase = true) ||
                            hiraDisplay == hiraQuery -> {
                            exactMatches.add(candidate)
                        }
                        normalizedDisplay.contains(cleanedQuery, ignoreCase = true) ||
                            cleanedQuery.contains(normalizedDisplay, ignoreCase = true) ||
                            hiraDisplay.contains(hiraQuery) ||
                            hiraQuery.contains(hiraDisplay) -> {
                            partialMatches.add(candidate)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val combined = if (exactMatches.isNotEmpty()) exactMatches else partialMatches
        return combined.take(maxResults)
    }

    private fun normalizeNameQuery(input: String): String {
        return input
            .trim()
            .replace(Regex("""(さん|ちゃん|くん|君|様|さま)$"""), "")
            .replace(Regex("""[\s ]+"""), "")
            .trim()
    }
}

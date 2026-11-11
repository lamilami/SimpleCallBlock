package com.datecs.simplecallblocker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

object PhoneNumbers {

    /** Country used to interpret national numbers: SIM, then network, then device locale. */
    fun countryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return iso.uppercase(Locale.ROOT)
    }

    /**
     * Brings a number to a comparable form:
     *  "+359 88 812-3456", "00359888123456", "0888123456" (iso=BG) -> "+359888123456".
     * Numbers that cannot be converted to E.164 are returned as plain digits.
     */
    fun normalize(raw: String, countryIso: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        if (trimmed.startsWith("+")) return "+$digits"
        if (digits.startsWith("00")) return "+" + digits.drop(2)
        return PhoneNumberUtils.formatNumberToE164(digits, countryIso) ?: digits
    }

    /**
     * Converts a prefix to international form so it can be compared with [normalize]d numbers:
     *  "+3592", "003592" -> "+3592";  "02" (iso=BG) -> "+3592";  "650" (iso=US) -> "+1650".
     * A national prefix is converted by padding it to a full-length number, formatting that
     * to E.164 and cutting the padding off again. Returns null if that is not possible.
     */
    fun internationalPrefix(raw: String, countryIso: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        if (trimmed.startsWith("+")) return "+$digits"
        if (digits.startsWith("00")) return "+" + digits.drop(2)
        val padded = PhoneNumberUtils.formatNumberToE164(digits + PADDING, countryIso) ?: return null
        return if (padded.endsWith(PADDING)) padded.dropLast(PADDING.length) else null
    }

    /**
     * True if [number] starts with [prefix]. Compared in international form; a prefix entered
     * without "+"/"00" is additionally compared with the digits exactly as the network sent them.
     */
    fun matchesPrefix(number: String, normalizedNumber: String, prefix: String, countryIso: String): Boolean {
        val intl = internationalPrefix(prefix, countryIso)
        if (intl != null && intl.length > 1 && normalizedNumber.startsWith(intl)) return true
        val p = prefix.trim()
        if (p.startsWith("+") || p.filter { it.isDigit() }.startsWith("00")) return false
        val prefixDigits = p.filter { it.isDigit() }
        return prefixDigits.isNotEmpty() && number.filter { it.isDigit() }.startsWith(prefixDigits)
    }

    fun hasContactsPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** True/false if the number is / is not a contact; null if this cannot be checked (no permission). */
    fun isContact(context: Context, number: String): Boolean? {
        if (!hasContactsPermission(context)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            .use { c -> return c?.let { it.count > 0 } } // null cursor -> unknown -> do not block
    }

    private const val PADDING = "0000000"
}

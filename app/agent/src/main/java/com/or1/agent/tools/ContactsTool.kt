package com.or1.agent.tools

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import org.json.JSONObject

/** Contacts through the contacts provider (no UI driving), saved on the phone without an account. */
class ContactsTool(context: Context) {
    private val resolver = context.contentResolver

    fun run(args: JSONObject): ToolOutput = when (val action = args.getString("action")) {
        "find" -> ToolOutput(find(args.optString("query")))
        "add" -> add(args)
        else -> throw IllegalArgumentException("Unknown action '$action'.")
    }

    /** Undo of add: deletes the raw contact outright (as its sync adapter, so it isn't left marked deleted). */
    fun deleteRawContact(id: Long): String {
        val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
        val name = resolver.query(uri, arrayOf(RawContacts.DISPLAY_NAME_PRIMARY, RawContacts.DELETED), null, null, null)?.use {
            if (it.moveToFirst() && it.getInt(1) == 0) it.getString(0).orEmpty() else null
        } ?: error("The contact was already deleted.")
        val asAdapter = uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        check(resolver.delete(asAdapter, null, null) > 0) { "The contacts provider didn't delete it." }
        return "Deleted contact $name."
    }

    private fun find(query: String): String {
        val q = query.trim().lowercase()
        val people = linkedMapOf<Long, Pair<String, MutableList<String>>>()
        resolver.query(
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Data.DISPLAY_NAME, Data.MIMETYPE, Data.DATA1),
            "${Data.MIMETYPE} IN (?, ?, ?)", arrayOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE),
            "${Data.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val person = people.getOrPut(c.getLong(0)) { (c.getString(1).orEmpty()) to mutableListOf() }
                if (c.getString(2) != StructuredName.CONTENT_ITEM_TYPE) c.getString(3)?.takeIf { it.isNotBlank() }?.let { person.second += it }
            }
        }
        val found = people.values.filter { (name, details) -> q in name.lowercase() || details.any { q in it.lowercase() } }
        if (found.isEmpty()) return "No contact matches '$query'."
        return found.joinToString("\n") { (name, details) -> "- $name: ${details.joinToString().ifEmpty { "no number or email" }}" }
    }

    private fun add(args: JSONObject): ToolOutput {
        val name = args.optString("name").ifBlank { throw IllegalArgumentException("add needs `name`.") }
        val phone = args.optString("phone").takeIf { it.isNotBlank() }
        val email = args.optString("email").takeIf { it.isNotBlank() }
        // One batch: the raw contact (no account: a phone-local contact) and its data rows.
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
        )
        phone?.let {
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, it).withValue(Phone.TYPE, Phone.TYPE_MOBILE).build()
        }
        email?.let {
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, it).withValue(Email.TYPE, Email.TYPE_OTHER).build()
        }
        val results = resolver.applyBatch("com.android.contacts", ops)
        val undo = results.firstOrNull()?.uri?.let { JSONObject().put("op", "contact_delete").put("raw_contact_id", ContentUris.parseId(it)) }
        return ToolOutput("Added $name" + listOfNotNull(phone, email).joinToString("") { ", $it" } + ".", undo = undo)
    }
}

package com.danieldk.splatevpn.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** An undo record for the stores outside Room. SQLite commits its token with the imported rows. */
internal class BackupImportJournal(
    context: Context,
    private val database: AppDatabase,
    private val settings: SettingsManager,
    private val tor: TorRepository?
) {
    private val file = AtomicFile(path(context))
    private val gson = Gson()

    suspend fun begin(includeTor: Boolean): String {
        val token = UUID.randomUUID().toString()
        val preferences = PreferenceValues.encode(settings.preferenceSnapshot())
        PreferenceValues.decode(preferences)
        val record = JsonObject().apply {
            addProperty("token", token)
            addProperty("preferences", preferences)
        }
        if (includeTor) record.add("tor", gson.toJsonTree(checkNotNull(tor).load()))
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        return token
    }

    fun markCommitted(token: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO backup_import_commit (slot, token) VALUES (1, ?)",
            arrayOf(token)
        )
    }

    suspend fun recover() {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return
        val record = file.openRead().use {
            JsonParser.parseString(it.readLimited(MAX_BYTES).toString(Charsets.UTF_8)).asJsonObject
        }
        val committedToken = database.openHelper.writableDatabase
            .query("SELECT token FROM backup_import_commit WHERE slot = 1").use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        if (record["token"].asString != committedToken) {
            record["tor"]?.let { checkNotNull(tor).save(gson.fromJson(it, TorSettings::class.java)) }
            settings.restorePreferenceSnapshot(PreferenceValues.decode(record["preferences"].asString))
        }
        file.delete()
        check(!file.baseFile.exists()) { "Could not clear backup import journal" }
    }

    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
        private fun path(context: Context) = File(context.noBackupFilesDir, "backup-import-undo.json")
        fun exists(context: Context): Boolean =
            path(context).let { it.exists() || File(it.path + ".bak").exists() }
    }
}

package com.castlevania.nes

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object HdPackManager {
    private const val TAG = "HdPackManager"
    private const val CURRENT_MOD_VERSION = "mods_cv_v4_title_fix"

    fun setupHdPack(context: Context): File {
        val homeDir = context.filesDir
        val hdPacksDir = File(homeDir, "HdPacks")
        val romHdDir = File(hdPacksDir, "rom")

        if (!romHdDir.exists()) {
            romHdDir.mkdirs()
        }

        val versionMarker = File(romHdDir, ".installed_version")
        val needsExtraction = !versionMarker.exists() || versionMarker.readText().trim() != CURRENT_MOD_VERSION

        if (needsExtraction) {
            Log.i(TAG, "Extracting Castlevania HD mod pack (audio + textures)...")
            try {
                // Delete existing old files in romHdDir to avoid obsolete or partial files
                romHdDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }

                context.assets.open("mods.zip").use { assetInput ->
                    ZipInputStream(BufferedInputStream(assetInput)).use { zipIn ->
                        val buffer = ByteArray(65536)
                        var entry = zipIn.nextEntry
                        var count = 0
                        while (entry != null) {
                            val name = entry.name
                            if (!entry.isDirectory && !name.startsWith("__MACOSX") && !name.startsWith("!readme")) {
                                val outFile = File(romHdDir, name)
                                FileOutputStream(outFile).use { out ->
                                    var bytesRead: Int
                                    while (zipIn.read(buffer).also { bytesRead = it } != -1) {
                                        out.write(buffer, 0, bytesRead)
                                    }
                                }
                                count++
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                        Log.i(TAG, "Extracted $count mod files into ${romHdDir.absolutePath}")
                    }
                }

                versionMarker.writeText(CURRENT_MOD_VERSION)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract HD mod pack", e)
            }
        } else {
            Log.i(TAG, "HD mod pack is up to date.")
        }

        return romHdDir
    }
}

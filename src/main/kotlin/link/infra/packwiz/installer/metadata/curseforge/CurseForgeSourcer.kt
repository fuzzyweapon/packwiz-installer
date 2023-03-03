package link.infra.packwiz.installer.metadata.curseforge

import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import link.infra.packwiz.installer.metadata.IndexFile
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.path.HttpUrlPath
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.closeQuietly
import okio.ByteString.Companion.decodeBase64
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

private class GetFilesRequest(val fileIds: List<Int>)
private class GetModsRequest(val modIds: List<Int>)

private class GetFilesResponse {
	class CfFile {
		var id = 0
		var modId = 0
		var downloadUrl: String? = null
	}
	val data = mutableListOf<CfFile>()
}

private class GetModsResponse {
	class CfMod {
		var id = 0
		var name = ""
		var links: CfLinks? = null
	}
	class CfLinks {
		var websiteUrl = ""
	}
	val data = mutableListOf<CfMod>()
}

private const val APIServer = "api.curseforge.com"
// If you fork/derive from packwiz, I request that you obtain your own API key.
private val APIKey = "\$2a\$10\$b0hmnNYMLqRZ80f7ltzjJuOI.4tbiK7stXsO1UcglyaRn8rhdUuOW".decodeBase64()!!
	.string(StandardCharsets.UTF_8)

@Throws(JsonSyntaxException::class, JsonIOException::class)
fun resolveCfMetadata(mods: List<IndexFile.File>, packFolder: PackwizFilePath, clientHolder: ClientHolder): List<ExceptionDetails> {
	val failures = mutableListOf<ExceptionDetails>()
	val fileIdMap = mutableMapOf<Int, IndexFile.File>()

	for (mod in mods) {
		if (!mod.linkedFile!!.update.contains("curseforge")) {
			failures.add(ExceptionDetails(mod.linkedFile!!.name, Exception("Failed to resolve CurseForge metadata: no CurseForge update section")))
			continue
		}
		fileIdMap[(mod.linkedFile!!.update["curseforge"] as CurseForgeUpdateData).fileId] = mod
	}

	val reqData = GetFilesRequest(fileIdMap.keys.toList())
	val req = Request.Builder()
		.url("https://${APIServer}/v1/mods/files")
		.header("Accept", "application/json")
		.header("User-Agent", "packwiz-installer")
		.header("X-API-Key", APIKey)
		.post(Gson().toJson(reqData, GetFilesRequest::class.java).toRequestBody("application/json".toMediaType()))
		.build()
	val res = clientHolder.okHttpClient.newCall(req).execute()
	if (!res.isSuccessful || res.body == null) {
		res.closeQuietly()
		failures.add(ExceptionDetails("Other", Exception("Failed to resolve CurseForge metadata for file data: error code ${res.code}")))
		return failures
	}

	val resData = Gson().fromJson(res.body!!.charStream(), GetFilesResponse::class.java)
	res.closeQuietly()

	val manualDownloadMods = mutableMapOf<Int, Pair<IndexFile.File, Int>>()
	for (file in resData.data) {
		if (!fileIdMap.contains(file.id)) {
			failures.add(ExceptionDetails(file.id.toString(),
				Exception("Failed to find file from result: ID ${file.id}, Project ID ${file.modId}")))
			continue
		}
		if (file.downloadUrl == null) {
			manualDownloadMods[file.modId] = Pair(fileIdMap[file.id]!!, file.id)
			continue
		}
		try {
			fileIdMap[file.id]!!.linkedFile!!.resolvedUpdateData["curseforge"] =
				HttpUrlPath(file.downloadUrl!!.toHttpUrl())
		} catch (e: IllegalArgumentException) {
			failures.add(ExceptionDetails(file.id.toString(),
				Exception("Failed to parse URL: ${file.downloadUrl} for ID ${file.id}, Project ID ${file.modId}", e)))
		}
	}

	// Some file types don't show up in the API at all! (e.g. shaderpacks)
	// Add unresolved files to manualDownloadMods
	for ((fileId, file) in fileIdMap) {
		if (file.linkedFile != null) {
			if (file.linkedFile!!.resolvedUpdateData["curseforge"] == null) {
				manualDownloadMods[(file.linkedFile!!.update["curseforge"] as CurseForgeUpdateData).projectId] = Pair(file, fileId)
			}
		}
	}

	if (manualDownloadMods.isNotEmpty()) {
		val reqModsData = GetModsRequest(manualDownloadMods.keys.toList())
		val reqMods = Request.Builder()
			.url("https://${APIServer}/v1/mods")
			.header("Accept", "application/json")
			.header("User-Agent", "packwiz-installer")
			.header("X-API-Key", APIKey)
			.post(Gson().toJson(reqModsData, GetModsRequest::class.java).toRequestBody("application/json".toMediaType()))
			.build()
		val resMods = clientHolder.okHttpClient.newCall(reqMods).execute()
		if (!resMods.isSuccessful || resMods.body == null) {
			resMods.closeQuietly()
			failures.add(ExceptionDetails("Other", Exception("Failed to resolve CurseForge metadata for mod data: error code ${resMods.code}")))
			return failures
		}

		val resModsData = Gson().fromJson(resMods.body!!.charStream(), GetModsResponse::class.java)
		resMods.closeQuietly()
 		// TODO: Refactor to it's own location
 		// TODO: Provide gui experience for file downloading and watching xp
		val instanceDirectory = manualDownloadMods[resModsData.data[1].id]?.first?.destURI?.rebase(packFolder)?.nioPath?.absolute()?.parent?.parent!!
		instanceDirectory.resolve("mods").createDirectories()
		instanceDirectory.resolve("resourcepacks").createDirectories()
		instanceDirectory.resolve("config").createDirectories()

		val downloadsDirectory  = File(System.getProperty("user.home")).resolve("Downloads")

		var moved = 0
		val totalMods = resModsData.data.size

		println("Opening mods to manually download...")
		for (mod in resModsData.data) {
			// Following block is from original code
			if (!manualDownloadMods.contains(mod.id)) {
				failures.add(ExceptionDetails(mod.name,
					Exception("Failed to find project from result: ID ${mod.id}")))
				continue
			}

			val modFile = manualDownloadMods[mod.id]!!
			val downloadsModFile = downloadsDirectory.resolve(modFile.first.destURI.filename.replace(' ', '+'))
			if (downloadsModFile.exists()) {
				Files.move(downloadsModFile.toPath(), modFile.first.destURI.rebase(packFolder).nioPath.absolute())
				moved++
			}
			else
				java.awt.Desktop.getDesktop().browse(java.net.URI.create("${mod.links?.websiteUrl}/download/${modFile.second}"))
		}

		val watchService = FileSystems.getDefault().newWatchService()
		val pathToWatch = downloadsDirectory.toPath()

		val pathKey = pathToWatch.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)

		println("Watching $downloadsDirectory for additional mod downloads...")
		while (moved < totalMods) {
			val watchKey = watchService.take()

			for (event in watchKey.pollEvents()) {
				// do something with the events
				try {
					val filename = event.context().toString()
					for (mod in resModsData.data) {
						val modFile = manualDownloadMods[mod.id]!!
						if (modFile.first.destURI.filename.replace(' ', '+') == filename) {
							Files.move(
								downloadsDirectory.resolve(filename).toPath(),
								modFile.first.destURI.rebase(packFolder).nioPath.absolute()
							)
							moved++
						}
					}
				} catch (ex: IOException) {
					ex.printStackTrace()
				}
			}

			if (!watchKey.reset()) {
				watchKey.cancel()
				watchService.close()
				break
			}
		}

		pathKey.cancel()
	}

	return failures
}
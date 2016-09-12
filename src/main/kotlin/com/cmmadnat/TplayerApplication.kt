package com.cmmadnat

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.turn.ttorrent.client.Client
import com.turn.ttorrent.client.SharedTorrent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter
import java.io.File
import java.net.InetAddress
import java.util.*
import javax.servlet.http.HttpServletRequest

@SpringBootApplication
open class TplayerApplication

@Configuration
open class WebConfig : WebMvcConfigurerAdapter() {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val filePath = File(System.getProperty("user.home"), "tplayer")
        val absolutePath = filePath.absolutePath
        registry.addResourceHandler("/resources/**").addResourceLocations("file:$absolutePath/")
    }
}


@Controller
@RequestMapping("/")
class HomeController {
    @Autowired lateinit var torrentFileService: TorrentFileService
    var init = false
    @RequestMapping
    fun index(model: Model): String {
        val findAll = torrentFileService.findAll()
        if (!init) {
            init = true
            torrentFileService.resume()
        }
        model.addAttribute("list", findAll)

        return "index"
    }

    @RequestMapping("upload")
    fun upload(@RequestParam file: MultipartFile): String {
        torrentFileService.newFile(file)
        return "redirect:/"
    }

    @RequestMapping("resume/{id}")
    fun resume(@PathVariable id: String): String {
        torrentFileService.resume(id)
        return "redirect:/"
    }

    @RequestMapping("pause/{id}")
    fun pause(@PathVariable id: String): String {
        torrentFileService.pause(id)
        return "redirect:/"
    }
}

@RequestMapping("browse")
@Controller
class BrowseController {
    @Autowired lateinit var fileService: FileService
    @RequestMapping fun index(model: Model, @RequestParam(required = false, defaultValue = "/") path: String): String {
        val directory = fileService.findDirectory(path)
        model.addAttribute("list", directory)
        val fileList = fileService.findFile(path)
        model.addAttribute("fileList", fileList)
        return "browse"
    }

    @RequestMapping("delete") fun delete(request: HttpServletRequest, @RequestParam path: String): String {
        fileService.deleteFile(path)
        val referer = request.getHeader("Referer")
        return "redirect:" + referer
    }

    @RequestMapping("deleteDir") fun deleteDir(request: HttpServletRequest, @RequestParam path: String): String {
        fileService.deleteDirectory(path)
        val referer = request.getHeader("Referer")
        return "redirect:" + referer
    }
}

@Service class FileService {
    val parent = File(System.getProperty("user.home"), "tplayer")

    fun findDirectory(path: String): List<FileEntry> {
        val out = mutableListOf<FileEntry>()
        val listFiles = File(parent, path).listFiles()
        for (listFile in listFiles) {
            if (listFile.isFile) continue
            val p = listFile.toRelativeString(parent)
            out.add(FileEntry(listFile.name, p))
        }
        return out.toList()
    }

    fun findFile(path: String): Any? {
        val out = mutableListOf<FileEntry>()
        val listFiles = File(parent, path).listFiles()
        for (listFile in listFiles) {
            if (listFile.isDirectory) continue
            var p = listFile.toRelativeString(parent)
            if (!p.startsWith("/")) p = "/" + p
            out.add(FileEntry(listFile.name, p))
        }
        return out.toList()

    }

    fun deleteFile(path: String) {
        File(parent, path).delete()
    }

    fun deleteDirectory(path: String) {
        File(parent, path).deleteRecursively()
    }

}

@Service
class TorrentFileService {
    @Autowired lateinit var objectMapper: ObjectMapper
    val parent = File(System.getProperty("user.home"), "tplayer")
    val file = File(parent, "db.json")
    var queue = HashMap<String, Client>()

    fun findAll(): List<TorrentFile> {
        parent.mkdir()
        if (!file.exists()) {
            file.createNewFile()
            file.writeText("[]", Charsets.UTF_8)
        }
        val readLines = file.readLines(charset = Charsets.UTF_8)
        return objectMapper.readValue(readLines[0], object : TypeReference<List<TorrentFile>>() {})
    }

    fun newFile(file: MultipartFile) {
        val findAll = findAll().toMutableList()
        val id = UUID.randomUUID().toString()
        findAll.add(TorrentFile(id = id, name = file.originalFilename.split(".torrent")[0], running = true))
        write(findAll)
        val torrentFile = File(parent, file.originalFilename)
        file.transferTo(torrentFile)

        val downloadFile = downloadFile(torrentFile, id)
        queue.put(id, downloadFile)

    }

    private fun downloadFile(file: File, id: String): Client {

        // First, instantiate the Client object.
        val client = Client(
                // This is the interface the client will listen on (you might need something
                // else than localhost here).
                InetAddress.getLocalHost(),

                // Load the torrent from the torrent file and use the given
                // output directory. Partials downloads are automatically recovered.
                SharedTorrent.fromFile(
                        file,
                        parent))

        // You can optionally set download/upload rate limits
        // in kB/second. Setting a limit to 0.0 disables rate
        // limits.
        //        client.setMaxDownloadRate(50.0)
        client.setMaxUploadRate(50.0)

        // At this point, can you either call download() to download the torrent and
        // stop immediately after...
        client.download()

        // Or call client.share(...) with a seed time in seconds:
        // client.share(3600);
        // Which would seed the torrent for an hour after the download is complete.

        // Downloading and seeding is done in background threads.
        // To wait for this process to finish, call:
        //        client.waitForCompletion()

        // At any time you can call client.stop() to interrupt the download.
        val x = Observer { observable, data ->
            val client2 = observable as Client
            val progress = client2.torrent.completion
            // Do something with progress.
            updateProgress(id, progress)
        }
        client.addObserver(x)
        return client
    }

    private fun updateProgress(id: String, progress: Float) {
        if (progress.equals(0f)) return
        else if (progress.equals(100f)) {
            pause(id)
            return
        }

        val findAll = findAll().toMutableList()
        for (torrentFile in findAll) {
            if (torrentFile.id == id) {
                torrentFile.progress = progress
            }
        }
        write(findAll)
    }

    private fun write(findAll: MutableList<TorrentFile>) {
        objectMapper.writeValue(file, findAll)
    }

    fun resume() {
        val findAll = findAll().toMutableList()
        for (file in findAll) {
            if (file.progress != 100f) {
                val downloadFile = downloadFile(File(parent, file.name + ".torrent"), file.id)
                queue.put(file.id, downloadFile)
                file.running = true
            } else file.running = false
        }
        write(findAll = findAll)
    }

    fun resume(id: String) {
        var found = false
        for ((key, client) in queue) {
            if (id.equals(key)) found = true
        }
        if (!found) {
            val findAll = findAll()
            for ((id1, name) in findAll) {
                if (id1.equals(id)) {
                    val downloadFile = downloadFile(File(parent, name + ".torrent"), id)
                    queue.put(id, downloadFile)

                }
            }
        }
        var findAll = findAll().toMutableList()
        for (torrentFile in findAll) {
            if (torrentFile.id.equals(id)) {
                torrentFile.running = true
                break
            }
        }
        write(findAll = findAll)
    }

    fun pause(id: String) {
        var get = queue[id]
        get?.stop()
        queue.remove(id)
        var findAll = findAll().toMutableList()
        for (torrentFile in findAll) {
            if (torrentFile.id.equals(id)) {
                torrentFile.running = false
                break
            }
        }
        write(findAll = findAll)
    }
}

data class TorrentFile(var id: String = "", var name: String = "", var progress: Float = 0f, var running: Boolean = false)
data class FileEntry(var name: String = "", var path: String = "")

fun main(args: Array<String>) {
    SpringApplication.run(TplayerApplication::class.java, *args)
}

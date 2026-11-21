import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Packs files from OrcaSlicer's `resources/` into one asset the app unpacks
 * into the engine's resources directory (see EngineResources in :core:engine).
 *
 * Writes `engine/resources.opk` and `engine/resources.opk.sha256` under
 * [outputDir]. The `.opk` format is a gzip stream of:
 *
 *     "OPK1"                       4 bytes, magic and format version
 *     entryCount                   u32
 *     entryCount × {
 *         pathLength, path         u16 + UTF-8, '/'-separated, relative to resources/
 *         dataLength, data         u32 + bytes
 *     }
 *
 * all big-endian (java.io.DataOutputStream). One stream over every file
 * compresses the ~15,000 small profile JSONs to about 2 MB, where per-file
 * compression (zip, APK assets) would not. A missing [resourcesRoot] (the
 * submodule is not checked out) produces no asset.
 */
@CacheableTask
abstract class PackEngineResourcesTask : DefaultTask() {

    @get:Internal
    abstract val resourcesRoot: DirectoryProperty

    /** Ant-style patterns relative to [resourcesRoot], e.g. `profiles/**/*.json`. */
    @get:Input
    abstract val includes: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val inputFiles: FileTree
        get() = resourcesRoot.asFileTree.matching { include(includes.get()) }

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val root = resourcesRoot.get().asFile
        val out = outputDir.get().asFile.resolve("engine")
        out.deleteRecursively()
        if (!root.isDirectory) {
            logger.warn("OrcaSlicer resources not found at $root; the app will have no bundled profiles.")
            return
        }
        out.mkdirs()

        val files = inputFiles.files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        val pack = File(out, "resources.opk")
        DataOutputStream(GZIPOutputStream(pack.outputStream().buffered(), 1 shl 16)).use { data ->
            data.write(MAGIC)
            data.writeInt(files.size)
            for (file in files) {
                val path = file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8)
                require(path.size <= 0xFFFF) { "Path too long for the pack format: $file" }
                val bytes = file.readBytes()
                data.writeShort(path.size)
                data.write(path)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(pack.readBytes())
        File(out, "resources.opk.sha256").writeText(digest.joinToString("") { "%02x".format(it) })
        logger.lifecycle("Packed ${files.size} engine resource files into ${pack.length() / 1024} KB")
    }

    private companion object {
        val MAGIC = "OPK1".toByteArray(Charsets.US_ASCII)
    }
}

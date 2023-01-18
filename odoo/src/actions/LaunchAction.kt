package com.github.hubvd.odootools.odoo.actions

import com.github.hubvd.odootools.odoo.RunConfiguration
import com.github.pgreze.process.process
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.zip.Adler32
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.system.exitProcess

interface Action {
    fun run(configuration: RunConfiguration)
}

class LaunchAction : Action {

    override fun run(configuration: RunConfiguration) {
        val useCustomLauncher = "no-patch" !in configuration.context.flags &&
            configuration.context.workspace.version < 14

        val main = if (useCustomLauncher) {
            "odoo/odoo-bin"
        } else {
            val launcherDir = unpackPatchedLauncher()
            (launcherDir / "main.py").toString()
        }

        val cmd = listOf("venv/bin/python", main, *configuration.args.toTypedArray())

        val process =
            ProcessBuilder()
                .command(cmd)
                .inheritIO()
                .apply { environment().putAll(configuration.env) }
                .directory(configuration.context.workspace.path.toFile())
                .start()

        Runtime.getRuntime().addShutdownHook(thread(start = false) { process.destroy() })

        val code = process.waitFor()

        if ("test-enable" in configuration.context.flags) {
            runBlocking {
                process(
                    "notify-send",
                    "-h",
                    *(
                        if (code == 0) {
                            arrayOf("string:frcolor:#00FF00", "Tests passed")
                        } else {
                            arrayOf("string:frcolor:#FF0000", "Tests failed")
                        }
                        ),
                )
            }
        }

        exitProcess(code)
    }

    private fun unpackPatchedLauncher(): Path {
        val dataDir = Path(System.getenv("XDG_DATA_DIRS") ?: (System.getProperty("user.home") + "/.local/share"))
        val launcherDir = (dataDir / "odoo-tools/launcher").apply { createDirectories() }

        val expectedChecksums = parseChecksums()

        val actualChecksums: HashMap<Long, String> =
            launcherDir.toFile().walkTopDown().filter { it.isFile && it.name.endsWith(".py") }
                .associateTo(HashMap()) { file ->
                    file.checksum() to file.relativeTo(launcherDir.toFile()).toString()
                }

        if (expectedChecksums != actualChecksums) {
            launcherDir.toFile().deleteRecursively()
            launcherDir.createDirectories()

            val files = expectedChecksums.values
            files.map { (launcherDir / it).parent }.toHashSet().forEach { it.createDirectories() }

            files.forEach { res ->
                val stream = javaClass.getResourceAsStream("/launcher/$res")
                    ?: throw FileNotFoundException("resource `$res` not in classpath")

                stream.use { `in` ->
                    (launcherDir / res).outputStream().use { out ->
                        `in`.copyTo(out)
                    }
                }
            }
        }

        return launcherDir
    }

    private fun parseChecksums(): HashMap<Long, String> =
        (javaClass.getResource("/launcher/checksums") ?: error("missing checksums"))
            .readText()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { it.trim().split(':', limit = 2) }
            .associateTo(HashMap()) { it[0].toLong() to it[1] }
    private fun File.checksum(): Long {
        ADLER_32.reset()
        ADLER_32.update(readBytes())
        return ADLER_32.value
    }
    companion object {
        val ADLER_32 by lazy { Adler32() }
    }
}

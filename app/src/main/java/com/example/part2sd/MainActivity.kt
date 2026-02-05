package com.example.part2sd

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.appbar.MaterialToolbar
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var dumpButton: Button
    private lateinit var listView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var topAppBar: MaterialToolbar

    private val mainHandler = Handler(Looper.getMainLooper())
    private val partitions = mutableListOf<Partition>()
    private val isWorking = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dumpButton = findViewById(R.id.dumpButton)
        listView = findViewById(R.id.partitionsList)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        topAppBar = findViewById(R.id.topAppBar)

        setSupportActionBar(topAppBar)

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, mutableListOf<String>())
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        listView.adapter = adapter

        dumpButton.setOnClickListener {
            if (!isWorking.get()) {
                val selected = getSelectedPartitions()
                if (selected.isEmpty()) {
                    setStatus("Selecione uma ou mais partições primeiro.")
                } else {
                    startDump(selected)
                }
            }
        }

        loadPartitions(adapter)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                if (!isWorking.get()) {
                    val adapter = listView.adapter as? ArrayAdapter<String>
                    if (adapter != null) {
                        loadPartitions(adapter)
                    }
                }
                true
            }
            R.id.action_about -> {
                showAbout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(R.layout.dialog_about)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun loadPartitions(adapter: ArrayAdapter<String>) {
        isWorking.set(true)
        setUiEnabled(false)
        setStatus("Carregando partições com root...")
        progressBar.progress = 0

        Thread {
            val cmd = "for f in /dev/block/by-name/*; do name=\$(basename \"\$f\"); real=\$(readlink -f \"\$f\" 2>/dev/null); base=\$(basename \"\$real\"); size=\$(cat /sys/class/block/\$base/size 2>/dev/null); echo \"\$name|\$f|\$real|\$size\"; done"
            val result = runSuCommand(cmd)

            val list = mutableListOf<Partition>()
            if (result.exitCode == 0 && result.stdout.isNotBlank()) {
                val lines = result.stdout.trim().split("\n")
                for (line in lines) {
                    val parts = line.split("|")
                    if (parts.size >= 4) {
                        val name = parts[0]
                        val path = parts[1]
                        val realPath = parts[2]
                        val sizeSectors = parts[3].toLongOrNull() ?: -1L
                        val totalBytes = if (sizeSectors > 0) sizeSectors * 512L else -1L
                        list.add(Partition(name, path, realPath, totalBytes))
                    }
                }
            }

            mainHandler.post {
                partitions.clear()
                partitions.addAll(list)
                adapter.clear()
                adapter.addAll(list.map { part ->
                    val sizeLabel = if (part.totalBytes > 0) humanBytes(part.totalBytes) else "?"
                    "${part.name}  ($sizeLabel)"
                })
                adapter.notifyDataSetChanged()
                listView.clearChoices()

                if (list.isEmpty()) {
                    setStatus("Não foi possível listar /dev/block/by-name. Root OK?")
                } else {
                    setStatus("Partições carregadas: ${list.size}")
                }
                setUiEnabled(true)
                isWorking.set(false)
            }
        }.start()
    }

    private fun startDump(selected: List<Partition>) {
        isWorking.set(true)
        setUiEnabled(false)
        progressBar.progress = 0
        setStatus("Preparando dump (${selected.size} selecionadas)...")

        Thread {
            var allOk = true
            for ((index, partition) in selected.withIndex()) {
                val ok = dumpPartition(partition, index + 1, selected.size)
                if (!ok) {
                    allOk = false
                    break
                }
            }

            mainHandler.post {
                if (allOk) {
                    setStatus("Dump concluído. Arquivos em /sdcard/Backup.")
                } else {
                    setStatus("Falha ao fazer dump. Verifique root e espaço livre.")
                }
                setUiEnabled(true)
                isWorking.set(false)
            }
        }.start()
    }

    private fun dumpPartition(partition: Partition, current: Int, total: Int): Boolean {
        mainHandler.post {
            setStatus("Dump ${current}/$total: ${partition.name}")
            progressBar.progress = 0
        }

        val outputDir = "/sdcard/Backup"
        val outputPath = "$outputDir/${partition.name}.img"
        runSuCommand("mkdir -p \"$outputDir\"")

        val totalBytes = if (partition.totalBytes > 0) partition.totalBytes else readBlockSize(partition.realPath)
        val ddCmd = "dd if=\"${partition.realPath}\" of=\"$outputPath\" bs=4M status=progress"
        val firstAttempt = runDdWithProgress(ddCmd, totalBytes)

        var success = firstAttempt.exitCode == 0
        if (!success && firstAttempt.usedStatusProgress && firstAttempt.err.contains("status", ignoreCase = true)) {
            val fallbackCmd = "dd if=\"${partition.realPath}\" of=\"$outputPath\" bs=4M"
            val fallbackExit = runDdWithPolling(fallbackCmd, totalBytes, outputPath)
            success = fallbackExit == 0
        }

        return success
    }

    private fun runDdWithProgress(cmd: String, totalBytes: Long): DdResult {
        val process = startSuProcess(cmd)
        val errSb = StringBuilder()
        val outSb = StringBuilder()
        val usedStatusProgress = true

        val errThread = Thread {
            errSb.append(readStreamLines(process.errorStream) { line ->
                val bytes = parseBytes(line)
                if (bytes != null) {
                    updateProgress(bytes, totalBytes)
                }
            })
        }

        val outThread = Thread {
            outSb.append(readStreamLines(process.inputStream, null))
        }

        errThread.start()
        outThread.start()

        val exitCode = process.waitFor()
        errThread.join()
        outThread.join()

        return DdResult(exitCode, outSb.toString(), errSb.toString(), usedStatusProgress)
    }

    private fun runDdWithPolling(cmd: String, totalBytes: Long, outputPath: String): Int {
        val process = startSuProcess(cmd)
        val stop = AtomicBoolean(false)

        val poller = Thread {
            while (!stop.get()) {
                val current = getFileSize(outputPath)
                if (current != null) {
                    updateProgress(current, totalBytes)
                }
                Thread.sleep(1000)
            }
        }

        val errThread = Thread { readStreamLines(process.errorStream, null) }
        val outThread = Thread { readStreamLines(process.inputStream, null) }

        poller.start()
        errThread.start()
        outThread.start()

        process.waitFor()
        stop.set(true)
        poller.join()
        errThread.join()
        outThread.join()
        return process.exitValue()
    }

    private fun updateProgress(bytes: Long, totalBytes: Long) {
        if (totalBytes <= 0) {
            mainHandler.post {
                statusText.text = "Copiado: ${humanBytes(bytes)}"
            }
            return
        }

        val percent = (bytes * 1000L / totalBytes).toInt().coerceIn(0, 1000)
        mainHandler.post {
            progressBar.progress = percent
            val pct = percent / 10.0
            statusText.text = "Copiando... ${"%.1f".format(Locale.US, pct)}% (${humanBytes(bytes)} / ${humanBytes(totalBytes)})"
        }
    }

    private fun parseBytes(line: String): Long? {
        val regex = Regex("(\\d+)\\s+bytes")
        val match = regex.find(line) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun setUiEnabled(enabled: Boolean) {
        dumpButton.isEnabled = enabled
        listView.isEnabled = enabled
    }

    private fun getSelectedPartitions(): List<Partition> {
        val checked = listView.checkedItemPositions
        val selected = mutableListOf<Partition>()
        for (i in 0 until checked.size()) {
            val position = checked.keyAt(i)
            if (checked.valueAt(i)) {
                partitions.getOrNull(position)?.let { selected.add(it) }
            }
        }
        return selected
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun humanBytes(bytes: Long): String {
        val unit = 1024.0
        if (bytes < unit) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(unit, exp.toDouble()), pre)
    }

    private fun runSuCommand(cmd: String): CommandResult {
        val process = startSuProcess(cmd)
        val out = readStreamLines(process.inputStream, null)
        val err = readStreamLines(process.errorStream, null)
        val exitCode = process.waitFor()
        return CommandResult(exitCode, out, err)
    }

    private fun startSuProcess(cmd: String): Process {
        return ProcessBuilder("su", "-c", cmd).start()
    }

    private fun readStreamLines(stream: InputStream, onLine: ((String) -> Unit)?): String {
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(stream))
        var line = reader.readLine()
        while (line != null) {
            sb.append(line).append('\n')
            onLine?.invoke(line)
            line = reader.readLine()
        }
        return sb.toString()
    }

    private fun readBlockSize(realPath: String): Long {
        val baseName = realPath.substringAfterLast('/')
        val result = runSuCommand("cat /sys/class/block/$baseName/size 2>/dev/null")
        val sectors = result.stdout.trim().toLongOrNull() ?: return -1L
        return sectors * 512L
    }

    private fun getFileSize(path: String): Long? {
        val result = runSuCommand("stat -c %s \"$path\" 2>/dev/null")
        return result.stdout.trim().toLongOrNull()
    }

    private data class Partition(
        val name: String,
        val path: String,
        val realPath: String,
        val totalBytes: Long
    )

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private data class DdResult(
        val exitCode: Int,
        val out: String,
        val err: String,
        val usedStatusProgress: Boolean
    )
}

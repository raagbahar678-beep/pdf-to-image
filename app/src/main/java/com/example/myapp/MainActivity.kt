package com.example.myapp

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream

class MainActivity : Activity() {

    private val REQ_PDFS = 101
    private val REQ_PDF_FOLDER = 102
    private val REQ_OUT_FOLDER = 103

    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var summaryView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPdfs: Button
    private lateinit var btnPdfFolder: Button
    private lateinit var btnOut: Button
    private lateinit var btnFmt: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val pdfUris = ArrayList<Uri>()
    private var outTree: Uri? = null
    private var usePng: Boolean = false
    private val logBuilder = StringBuilder()

    @Volatile private var running: Boolean = false
    @Volatile private var stopFlag: Boolean = false
    @Volatile private var lastUi: Long = 0L

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun mkBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.isFocusable = false
        b.isFocusableInTouchMode = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun mkRow(a: Button, b: Button): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val lp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(a, lp1)
        row.addView(b, lp2)
        return row
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pdf2img", MODE_PRIVATE)
        usePng = prefs.getBoolean("png", false)

        val savedOut = prefs.getString("out", null)
        if (savedOut != null) {
            val u = Uri.parse(savedOut)
            var ok = false
            for (p in contentResolver.persistedUriPermissions) {
                if (p.uri == u && p.isWritePermission) ok = true
            }
            if (ok) outTree = u
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(28), dp(10), dp(10))

        val title = TextView(this)
        title.text = "PDF to Image"
        title.textSize = 22f
        title.gravity = Gravity.CENTER
        root.addView(title)

        btnPdfs = mkBtn("Select PDF files") { pickPdfs() }
        btnPdfFolder = mkBtn("Select PDF folder") { pickPdfFolder() }
        root.addView(mkRow(btnPdfs, btnPdfFolder))

        btnOut = mkBtn("Output folder") { pickOutFolder() }
        btnFmt = mkBtn("") { toggleFormat() }
        root.addView(mkRow(btnOut, btnFmt))

        btnStart = mkBtn("Start") { startConversion() }
        btnStop = mkBtn("Stop") { stopConversion() }
        btnStop.isEnabled = false
        root.addView(mkRow(btnStart, btnStop))

        summaryView = TextView(this)
        summaryView.textSize = 14f
        summaryView.setPadding(0, dp(8), 0, dp(4))
        root.addView(summaryView)

        statusView = TextView(this)
        statusView.textSize = 15f
        statusView.text = "Ready."
        root.addView(statusView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        logScroll = ScrollView(this)
        logView = TextView(this)
        logView.textSize = 12f
        logView.setPadding(dp(4), dp(4), dp(4), dp(4))
        logScroll.addView(logView)
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        refreshSummary()
    }

    override fun onPause() {
        super.onPause()
        val ed = prefs.edit()
        ed.putBoolean("png", usePng)
        val o = outTree
        if (o != null) ed.putString("out", o.toString())
        ed.apply()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_O) {
                pickPdfs()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                startConversion()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                stopConversion()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refreshSummary() {
        val outText = if (outTree != null) "set" else "NOT set"
        summaryView.text = "PDFs selected: " + pdfUris.size + "\nOutput folder: " + outText
        btnFmt.text = if (usePng) "Format: PNG" else "Format: JPG"
    }

    private fun toggleFormat() {
        if (running) return
        usePng = !usePng
        refreshSummary()
    }

    private fun logLine(s: String) {
        runOnUiThread {
            logBuilder.append(s).append('\n')
            if (logBuilder.length > 20000) {
                logBuilder.delete(0, 10000)
            }
            logView.text = logBuilder.toString()
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------- Pickers

    private fun pickPdfs() {
        if (running) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_PDFS)
    }

    private fun pickPdfFolder() {
        if (running) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_PDF_FOLDER)
    }

    private fun pickOutFolder() {
        if (running) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(i, REQ_OUT_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == REQ_PDFS) {
            val list = ArrayList<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (k in 0 until cd.itemCount) {
                    list.add(cd.getItemAt(k).uri)
                }
            } else {
                val u = data.data
                if (u != null) list.add(u)
            }
            for (u in list) {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
            }
            pdfUris.clear()
            pdfUris.addAll(list)
            refreshSummary()
            statusView.text = "Selected " + pdfUris.size + " PDF file(s)."
        } else if (requestCode == REQ_PDF_FOLDER) {
            val tree = data.data ?: return
            try {
                contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            statusView.text = "Reading PDF folder..."
            Thread {
                val found = listPdfsInTree(tree)
                runOnUiThread {
                    pdfUris.clear()
                    pdfUris.addAll(found)
                    refreshSummary()
                    statusView.text = "Found " + found.size + " PDF file(s) in folder."
                }
            }.start()
        } else if (requestCode == REQ_OUT_FOLDER) {
            val tree = data.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
            outTree = tree
            prefs.edit().putString("out", tree.toString()).apply()
            refreshSummary()
            statusView.text = "Output folder set."
        }
    }

    // ------------------------------------------------ Folder helpers

    private fun listPdfsInTree(tree: Uri): List<Uri> {
        val names = ArrayList<String>()
        val uris = ArrayList<Uri>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cursor = contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )
            if (cursor != null) {
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: ""
                        val mime = c.getString(2) ?: ""
                        if (mime == "application/pdf" || name.lowercase().endsWith(".pdf")) {
                            names.add(name.lowercase())
                            uris.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logLine("Folder read error: " + e.message)
        }
        val order = ArrayList<Int>()
        for (k in names.indices) order.add(k)
        order.sortBy { names[it] }
        val result = ArrayList<Uri>()
        for (k in order) result.add(uris[k])
        return result
    }

    private fun highestNumberInTree(tree: Uri): Int {
        var maxNum = 0
        try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cursor = contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            if (cursor != null) {
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        val base = name.substringBeforeLast('.')
                        val n = base.toIntOrNull()
                        if (n != null && n > maxNum) maxNum = n
                    }
                }
            }
        } catch (e: Exception) {
            logLine("Could not scan output folder: " + e.message)
        }
        return maxNum
    }

    private fun displayName(uri: Uri): String {
        try {
            val c = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null) {
                c.use { cur ->
                    if (cur.moveToFirst()) {
                        val n = cur.getString(0)
                        if (n != null) return n
                    }
                }
            }
        } catch (e: Exception) {
        }
        return "file"
    }

    // ------------------------------------------------- Conversion

    private fun stopConversion() {
        if (!running) return
        stopFlag = true
        statusView.text = "Stopping after current page..."
    }

    private fun startConversion() {
        if (running) return
        val out = outTree
        if (out == null) {
            Toast.makeText(this, "Choose the output folder first", Toast.LENGTH_SHORT).show()
            return
        }
        if (pdfUris.isEmpty()) {
            Toast.makeText(this, "Select PDF file(s) or a PDF folder first", Toast.LENGTH_SHORT).show()
            return
        }
        val work = ArrayList<Uri>(pdfUris)
        running = true
        stopFlag = false
        setControlsEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusView.text = "Starting..."

        Thread {
            var counter = 0
            var totalSaved = 0
            try {
                counter = highestNumberInTree(out)
                logLine("Continuing after image number " + counter)
                var f = 0
                while (f < work.size && !stopFlag) {
                    val uri = work[f]
                    val name = displayName(uri)
                    logLine("Opening " + name)
                    try {
                        val before = counter
                        counter = convertPdf(uri, out, counter, f + 1, work.size, name)
                        totalSaved += counter - before
                        logLine("Done " + name + ": " + (counter - before) + " image(s)")
                    } catch (e: Exception) {
                        logLine("FAILED " + name + ": " + e.message)
                    } catch (e: OutOfMemoryError) {
                        logLine("FAILED " + name + ": out of memory")
                    }
                    f++
                }
            } catch (e: Exception) {
                logLine("Error: " + e.message)
            }
            val stopped = stopFlag
            runOnUiThread {
                running = false
                stopFlag = false
                setControlsEnabled(true)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusView.text = (if (stopped) "Stopped. " else "Finished. ") + totalSaved +
                    " image(s) saved. Last number: " + counter
            }
        }.start()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnPdfs.isEnabled = enabled
        btnPdfFolder.isEnabled = enabled
        btnOut.isEnabled = enabled
        btnFmt.isEnabled = enabled
        btnStart.isEnabled = enabled
        btnStop.isEnabled = !enabled
    }

    private fun postProgress(
        fileIdx: Int, fileTotal: Int, name: String,
        page: Int, pageCount: Int, saved: Int, force: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUi < 150L) return
        lastUi = now
        runOnUiThread {
            statusView.text = "File " + fileIdx + "/" + fileTotal + ": " + name +
                "\nPage " + page + " / " + pageCount + "   (saved so far: " + saved + ")"
            progressBar.max = if (pageCount > 0) pageCount else 1
            progressBar.progress = page
        }
    }

    private fun convertPdf(
        uri: Uri, out: Uri, startCounter: Int,
        fileIdx: Int, fileTotal: Int, name: String
    ): Int {
        var counter = startCounter
        val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw RuntimeException("Cannot open file")
        try {
            val renderer = PdfRenderer(pfd)
            try {
                val pc = renderer.pageCount
                var p = 0
                while (p < pc && !stopFlag) {
                    val ok = try {
                        renderPage(renderer, p, out, counter + 1)
                    } catch (e: Exception) {
                        logLine("Page " + (p + 1) + " of " + name + " failed: " + e.message)
                        false
                    }
                    if (ok) counter++
                    p++
                    postProgress(fileIdx, fileTotal, name, p, pc, counter - startCounter, p == pc)
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
        }
        return counter
    }

    private fun renderPage(renderer: PdfRenderer, index: Int, out: Uri, number: Int): Boolean {
        val page = renderer.openPage(index)
        try {
            val wPt = page.width
            val hPt = page.height
            val longestPt = if (wPt > hPt) wPt else hPt
            var scale = 150f / 72f
            if (longestPt * scale > 4096f) scale = 4096f / longestPt.toFloat()
            var w = (wPt * scale).toInt()
            var h = (hPt * scale).toInt()
            if (w < 1) w = 1
            if (h < 1) h = 1

            var bmp: Bitmap? = null
            var tries = 0
            while (bmp == null && tries < 6) {
                try {
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    w = (w * 0.7f).toInt()
                    h = (h * 0.7f).toInt()
                    if (w < 1) w = 1
                    if (h < 1) h = 1
                    tries++
                }
            }
            val b: Bitmap = bmp ?: return false
            try {
                b.eraseColor(Color.WHITE)
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return saveBitmap(b, out, number)
            } finally {
                b.recycle()
            }
        } finally {
            page.close()
        }
    }

    private fun saveBitmap(b: Bitmap, out: Uri, number: Int): Boolean {
        val parent = DocumentsContract.buildDocumentUriUsingTree(out, DocumentsContract.getTreeDocumentId(out))
        val ext = if (usePng) ".png" else ".jpg"
        val mime = if (usePng) "image/png" else "image/jpeg"
        val doc = DocumentsContract.createDocument(contentResolver, parent, mime, number.toString() + ext)
            ?: return false
        var okWrite = false
        try {
            val os = contentResolver.openOutputStream(doc)
            if (os != null) {
                BufferedOutputStream(os, 65536).use { bos ->
                    okWrite = if (usePng) {
                        b.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    } else {
                        b.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                    }
                    bos.flush()
                }
            }
        } catch (e: Exception) {
            okWrite = false
        }
        if (!okWrite) {
            try {
                DocumentsContract.deleteDocument(contentResolver, doc)
            } catch (e: Exception) {
            }
        }
        return okWrite
    }
}

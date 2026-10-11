package app.zendiary.io.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import androidx.core.content.ContextCompat
import app.zendiary.core.asClock
import app.zendiary.core.pretty
import app.zendiary.io.ZenBackupFormat
import app.zendiary.ui.theme.Mood
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Readable PDF (text + photos). A compressed machine-readable copy of the data is appended as PDF
 * comment lines after the document body, so Zen Diary can re-import its own PDFs. Viewers ignore it.
 */
class PdfExporter : Exporter {
    override val id = "pdf"
    override val label = "PDF"
    override val description = "Beautiful printable journal. Zen Diary can re-import PDFs it exported."
    override fun extension(includeImages: Boolean) = "pdf"
    override fun mimeType(includeImages: Boolean) = "application/pdf"

    override suspend fun export(ctx: ExportContext, out: OutputStream) {
        val cache = ctx.context.cacheDir
        val pdf = File.createTempFile("doc", ".pdf", cache)
        val zip = File.createTempFile("payload", ".zip", cache)
        try {
            PdfWriter(ctx).render(pdf)
            val imgs: ((String) -> ByteArray?)? = if (ctx.includeImages) ({ n -> ctx.images.downscaled(n, 800, 70) }) else null
            zip.outputStream().buffered().use { ZenBackupFormat.write(it, ctx.data, imgs) }
            PdfPayload.append(pdf, zip, out)
        } finally { pdf.delete(); zip.delete() }
    }
}

private class PdfWriter(private val ctx: ExportContext) {
    private val w = 595; private val h = 842; private val m = 40f
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas = Canvas()
    private var y = 0f
    private var pageNo = 0

    private fun paint(size: Float, bold: Boolean = false, color: Int = 0xFF2B332D.toInt(), serif: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    private val pTitle = paint(26f, true, serif = true)
    private val pDate = paint(13f, true, 0xFF4F6F55.toInt())
    private val pHead = paint(15f, true, serif = true)
    private val pBody = paint(11.5f)
    private val pSmall = paint(9.5f, color = 0xFF7A8179.toInt())

    private fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        canvas = page!!.canvas
        y = m
    }
    private fun ensure(height: Float) { if (page == null || y + height > h - m) newPage() }

    private fun text(s: String, p: TextPaint, gap: Float = 4f, indent: Float = 0f) {
        if (s.isEmpty()) { y += gap; return }
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, (w - 2 * m - indent).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        for (i in 0 until layout.lineCount) {
            val lh = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
            ensure(lh)
            val start = layout.getLineStart(i)
            var end = layout.getLineEnd(i)
            while (end > start && s[end - 1] == '\n') end--
            canvas.drawText(s, start, end, m + indent, y + layout.getLineBaseline(i) - layout.getLineTop(i), p)
            y += lh
        }
        y += gap
    }

    private fun image(name: String) {
        val bytes = runCatching { ctx.images.readBytes(name) }.getOrNull() ?: return
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return
        val maxW = w - 2 * m; val maxH = 280f
        val scale = minOf(maxW / bmp.width, maxH / bmp.height, 1f)
        val iw = bmp.width * scale; val ih = bmp.height * scale
        ensure(ih + 8)
        canvas.drawBitmap(bmp, null, RectF(m, y, m + iw, y + ih), null)
        y += ih + 8
        bmp.recycle()
    }

    fun render(file: File) {
        val data = ctx.data
        newPage()
        y = 120f
        text("Zen Diary", pTitle, 8f)
        text("${data.entries.size} entries" + (data.entries.firstOrNull()?.let { " · ${it.date.pretty()} – ${data.entries.last().date.pretty()}" } ?: ""), pSmall, 24f)
        data.entries.forEach { e ->
            ensure(90f)
            y += 14f
            val top = y
            text("${e.date.pretty()}  ·  ${e.minutes.asClock()}", pDate, 2f)
            ContextCompat.getDrawable(ctx.context, Mood.of(e.mood).icon)?.let {
                it.setBounds((w - m - 26).toInt(), top.toInt() - 4, (w - m).toInt(), top.toInt() + 22); it.draw(canvas)
            }
            if (e.title.isNotBlank()) text(e.title, pHead, 4f)
            if (e.event.isNotBlank()) text("★ Event: ${e.event}", pSmall, 3f)
            if (e.tags.isNotEmpty()) text(e.tags.joinToString("   ") { "#$it" }, pSmall, 5f)
            if (e.body.isNotBlank()) text(e.body, pBody, 6f)
            if (ctx.includeImages) e.images.forEach { image(it) }
            ensure(10f)
            canvas.drawLine(m, y, w - m, y, Paint().apply { color = 0xFFDDDACD.toInt(); strokeWidth = 0.8f })
            y += 6f
        }
        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}

/** Appends/reads the machine-readable payload hidden in PDF comment lines (see PdfExporter). */
object PdfPayload {
    private val BEGIN = "\n%ZDBEGIN\n".toByteArray()

    fun append(pdf: File, payloadZip: File, out: OutputStream) {
        val startxref = readStartXref(pdf)
        pdf.inputStream().use { it.copyTo(out) }
        out.write(BEGIN)
        val buf = ByteArray(57)  // 57 bytes -> 76 base64 chars per line
        payloadZip.inputStream().buffered().use { input ->
            while (true) {
                var n = 0
                while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                if (n == 0) break
                out.write('%'.code); out.write(Base64.encode(buf, 0, n, Base64.NO_WRAP)); out.write('\n'.code)
                if (n < buf.size) break
            }
        }
        out.write("%ZDEND\n".toByteArray())
        // Repeat the trailer pointer so viewers that look at the very end of the file still find the xref table.
        out.write("startxref\n$startxref\n%%EOF\n".toByteArray())
    }

    private fun readStartXref(pdf: File): Long {
        RandomAccessFile(pdf, "r").use { raf ->
            val len = minOf(raf.length(), 1024L).toInt()
            raf.seek(raf.length() - len)
            val tail = ByteArray(len).also { raf.readFully(it) }
            val s = String(tail, Charsets.ISO_8859_1)
            val i = s.lastIndexOf("startxref")
            require(i >= 0) { "Unexpected PDF structure" }
            return s.substring(i + 9).trim().lineSequence().first().trim().toLong()
        }
    }

    fun extract(pdf: File, outZip: File): Boolean {
        val pos = indexOf(pdf)
        if (pos < 0) return false
        FileInputStream(pdf).use { fis ->
            fis.channel.position(pos + BEGIN.size)
            val br = fis.bufferedReader(Charsets.ISO_8859_1)
            outZip.outputStream().buffered().use { o ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.startsWith("%ZDEND")) break
                    if (line.startsWith("%")) o.write(Base64.decode(line.substring(1), Base64.NO_WRAP))
                }
            }
        }
        return true
    }

    private fun indexOf(file: File): Long {
        var state = 0; var pos = 0L
        file.inputStream().buffered(1 shl 16).use { ins ->
            while (true) {
                val b = ins.read(); if (b < 0) return -1
                pos++
                state = if (b.toByte() == BEGIN[state]) state + 1 else if (b == '\n'.code) 1 else 0
                if (state == BEGIN.size) return pos - BEGIN.size
            }
        }
    }
}

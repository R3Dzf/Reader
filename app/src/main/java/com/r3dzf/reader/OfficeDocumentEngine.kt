package com.r3dzf.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

enum class OfficeFormat(val extension: String, val mimeType: String) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    DOCM("docm", "application/vnd.ms-word.document.macroEnabled.12"),
    DOTX("dotx", "application/vnd.openxmlformats-officedocument.wordprocessingml.template"),
    DOTM("dotm", "application/vnd.ms-word.template.macroEnabled.12"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    XLSM("xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12"),
    XLTX("xltx", "application/vnd.openxmlformats-officedocument.spreadsheetml.template"),
    XLTM("xltm", "application/vnd.ms-excel.template.macroEnabled.12"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    PPTM("pptm", "application/vnd.ms-powerpoint.presentation.macroEnabled.12"),
    PPSX("ppsx", "application/vnd.openxmlformats-officedocument.presentationml.slideshow"),
    PPSM("ppsm", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12"),
    POTX("potx", "application/vnd.openxmlformats-officedocument.presentationml.template"),
    POTM("potm", "application/vnd.ms-powerpoint.template.macroEnabled.12"),
    DOC("doc", "application/msword"),
    DOT("dot", "application/msword"),
    XLS("xls", "application/vnd.ms-excel"),
    XLT("xlt", "application/vnd.ms-excel"),
    XLSB("xlsb", "application/vnd.ms-excel.sheet.binary.macroEnabled.12"),
    PPS("pps", "application/vnd.ms-powerpoint"),
    PPT("ppt", "application/vnd.ms-powerpoint");

    companion object {
        fun fromName(name: String): OfficeFormat? =
            entries.firstOrNull { it.extension == name.substringAfterLast('.', "").lowercase(Locale.ROOT) }

        fun fromMimeType(mimeType: String?): OfficeFormat? =
            entries.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }
    }
}

data class PreparedOfficeDocument(val file: File, val displayName: String, val format: OfficeFormat)

private const val MAX_OFFICE_FILE_BYTES = 250L * 1024L * 1024L
private const val MAX_XML_ENTRY_BYTES = 32L * 1024L * 1024L
private const val MAX_XLSX_SHEETS = 128
private const val MAX_XLSX_ROW_ENTRIES = 100_000
private const val MAX_XLSX_ROW_INDEX = 1_048_576
private const val MAX_XLSX_COLUMNS = 512
private const val MAX_XLSX_CELLS = 250_000
private const val MAX_XLSX_STYLES = 4_096
private const val MAX_XLSX_MERGED_RANGES = 10_000
private const val MAX_PPTX_SLIDES = 256
private const val MAX_PPTX_SHAPES_PER_SLIDE = 5_000
private const val MAX_PPTX_TOTAL_SHAPES = 25_000
private const val MAX_PPTX_TABLE_ROWS = 100
private const val MAX_PPTX_TABLE_COLUMNS = 64
private const val MAX_PPTX_TOTAL_TABLE_CELLS = 25_000
private const val MAX_OFFICE_IMAGE_BYTES = 10L * 1024L * 1024L
private const val MAX_TOTAL_OFFICE_IMAGE_BYTES = 50L * 1024L * 1024L
private const val EMU_PER_POINT = 12_700f
private const val WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

class OfficeFormatNotImplementedException(format: OfficeFormat) : IOException(
    "The built-in ${format.extension.uppercase(Locale.ROOT)} reader is on the implementation roadmap."
)

class InvalidOfficeDocumentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Copies an input document to app-private storage. The parser never writes to the selected file. */
fun prepareOfficeDocument(context: Context, uri: Uri): PreparedOfficeDocument {
    val resolver = context.contentResolver
    val originalName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "office-document"
    val format = OfficeFormat.fromName(originalName)
        ?: OfficeFormat.fromMimeType(resolver.getType(uri))
        ?: throw IOException("Choose a Word, Excel, or PowerPoint document.")

    val incoming = File(context.cacheDir, "office-input").apply { mkdirs() }
    val target = File(incoming, "${UUID.randomUUID()}.${format.extension}")
    try {
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not read the selected document.")
        input.use { source ->
            FileOutputStream(target).use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_OFFICE_FILE_BYTES) {
                        throw IOException("The selected document is larger than the 250 MB limit.")
                    }
                    destination.write(buffer, 0, count)
                }
            }
        }
        return PreparedOfficeDocument(target, originalName, format)
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}

sealed interface NativeOfficeDocument

data class NativeWordDocument(
    val blocks: List<WordBlock>,
    val pageSettings: WordPageSettings = WordPageSettings()
) : NativeOfficeDocument

data class WordPageSettings(
    val widthPoints: Float = 612f,
    val heightPoints: Float = 792f,
    val marginTopPoints: Float = 72f,
    val marginRightPoints: Float = 72f,
    val marginBottomPoints: Float = 72f,
    val marginLeftPoints: Float = 72f
)

sealed interface WordBlock

data class WordParagraph(
    val runs: List<WordRun>,
    val alignment: WordAlignment = WordAlignment.START,
    val rightToLeft: Boolean = false,
    val spaceBeforePoints: Float = 0f,
    val spaceAfterPoints: Float = 4f,
    val headingLevel: Int? = null,
    val listMarker: String? = null,
    val listLevel: Int = 0,
    val startsNewPage: Boolean = false,
    val endsPage: Boolean = false,
    val images: List<NativeWordImage> = emptyList()
) : WordBlock {
    val plainText: String get() = runs.joinToString("") { it.text }
}

data class WordRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val fontSizePoints: Float? = null,
    val colorRgb: Long? = null
)

data class NativeWordImage(
    val imageBytes: ByteArray,
    val widthPoints: Float,
    val heightPoints: Float,
    val description: String? = null
)

data class WordTable(val rows: List<List<WordTableCell>>) : WordBlock
data class WordTableCell(val text: String, val images: List<NativeWordImage> = emptyList())

data class NativeWorkbook(val sheets: List<NativeWorksheet>) : NativeOfficeDocument
data class NativeWorksheet(
    val name: String,
    val rows: List<NativeSheetRow>,
    val columnCount: Int,
    val rightToLeft: Boolean,
    val horizontalMerges: List<NativeMergedRange> = emptyList()
)
data class NativeMergedRange(val row: Int, val firstColumn: Int, val lastColumn: Int)
data class NativeSheetRow(val rowIndex: Int, val cells: Map<Int, NativeSheetCell>)
data class NativeSheetCell(
    val value: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSizePoints: Float? = null,
    val textRgb: Long? = null,
    val fillRgb: Long? = null
)

data class NativePresentation(
    val slides: List<NativeSlide>,
    val slideWidthPoints: Float,
    val slideHeightPoints: Float
) : NativeOfficeDocument

data class NativeSlide(val number: Int, val shapes: List<NativeSlideShape>)

enum class NativeSlideShapeKind { TEXT, SHAPE, IMAGE, TABLE, OTHER }
enum class SlideCellVerticalAlignment { TOP, CENTER, BOTTOM }
enum class SlideBorderDash { SOLID, DASH, DOT }

data class NativeSlideCellBorder(
    val widthPoints: Float,
    val colorRgb: Long? = null,
    val dash: SlideBorderDash = SlideBorderDash.SOLID
)

data class NativeSlideTableCell(
    val text: String,
    val fillRgb: Long? = null,
    val textRgb: Long? = null,
    val fontSizePoints: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val centered: Boolean = false,
    val rightToLeft: Boolean = false,
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
    val horizontalMerge: Boolean = false,
    val verticalMerge: Boolean = false,
    val verticalAlignment: SlideCellVerticalAlignment = SlideCellVerticalAlignment.TOP,
    val marginLeftPoints: Float = 7.2f,
    val marginRightPoints: Float = 7.2f,
    val marginTopPoints: Float = 3.6f,
    val marginBottomPoints: Float = 3.6f,
    val borderLeft: NativeSlideCellBorder? = null,
    val borderRight: NativeSlideCellBorder? = null,
    val borderTop: NativeSlideCellBorder? = null,
    val borderBottom: NativeSlideCellBorder? = null
)

data class NativeSlideTable(
    val rows: List<List<NativeSlideTableCell>>,
    val columnWidths: List<Float>,
    val rowHeights: List<Float>,
    val rightToLeft: Boolean = false
) {
    val placedCells: List<SlideTableCellPlacement> = layoutSlideTable(this)
}

data class NativeSlideShape(
    val kind: NativeSlideShapeKind,
    val text: String?,
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
    val fillRgb: Long? = null,
    val textRgb: Long? = null,
    val fontSizePoints: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val centered: Boolean = false,
    val rightToLeft: Boolean = false,
    val imageBytes: ByteArray? = null,
    val table: NativeSlideTable? = null
) {
    val tableRows: List<List<NativeSlideTableCell>> get() = table?.rows.orEmpty()
}

private class OfficeImageCache {
    private val images = mutableMapOf<String, ByteArray>()
    private var totalBytes = 0L

    fun read(zip: ZipFile, partName: String): ByteArray = images.getOrPut(partName) {
        val entry = zip.getEntry(partName)
            ?: throw InvalidOfficeDocumentException("An embedded Office image is missing from the package.")
        if (entry.size > MAX_OFFICE_IMAGE_BYTES || totalBytes + entry.size > MAX_TOTAL_OFFICE_IMAGE_BYTES) {
            throw InvalidOfficeDocumentException("Embedded Office images exceed the image reading limits.")
        }
        zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var currentSize = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                currentSize += count
                if (currentSize > MAX_OFFICE_IMAGE_BYTES || totalBytes + currentSize > MAX_TOTAL_OFFICE_IMAGE_BYTES) {
                    throw InvalidOfficeDocumentException("Embedded Office images exceed the image reading limits.")
                }
                output.write(buffer, 0, count)
            }
            totalBytes += currentSize
            output.toByteArray()
        }
    }
}

enum class WordAlignment { START, CENTER, END, JUSTIFY }

private data class WordNumberingLevel(val format: String, val text: String)
private data class ParsedWorksheet(val sheet: NativeWorksheet, val cellCount: Int, val rowCount: Int)
private data class WordNumberingCatalog(
    val numberingById: Map<Int, Int>,
    val levelsByAbstractId: Map<Pair<Int, Int>, WordNumberingLevel>
)

/** Parses supported Office Open XML parts into read-only models for the native Android viewer. */
object NativeOfficeEngine {
    fun open(file: File, format: OfficeFormat): NativeOfficeDocument = when (format) {
        OfficeFormat.DOCX, OfficeFormat.DOCM, OfficeFormat.DOTX, OfficeFormat.DOTM -> readDocx(file)
        OfficeFormat.XLSX, OfficeFormat.XLSM, OfficeFormat.XLTX, OfficeFormat.XLTM -> readXlsx(file)
        OfficeFormat.PPTX, OfficeFormat.PPTM, OfficeFormat.PPSX, OfficeFormat.PPSM,
        OfficeFormat.POTX, OfficeFormat.POTM -> readPptx(file)
        else -> throw OfficeFormatNotImplementedException(format)
    }

    fun openWorkbook(file: File): NativeWorkbook = readXlsx(file)

    private fun readXlsx(file: File): NativeWorkbook {
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_OFFICE_FILE_BYTES) {
            throw InvalidOfficeDocumentException("The workbook is missing, empty, or larger than the 250 MB limit.")
        }
        try {
            ZipFile(file).use { packageFile ->
                val contentTypesEntry = packageFile.getEntry("[Content_Types].xml")
                    ?: throw InvalidOfficeDocumentException("This is not a valid XLSX package.")
                val contentTypes = secureXml(readBounded(packageFile, contentTypesEntry))
                if (contentTypes.documentElement.localName() != "Types") {
                    throw InvalidOfficeDocumentException("This is not a valid Office Open XML package.")
                }
                val workbookEntry = packageFile.getEntry("xl/workbook.xml")
                    ?: throw InvalidOfficeDocumentException("The XLSX package has no workbook definition.")
                val relationshipsEntry = packageFile.getEntry("xl/_rels/workbook.xml.rels")
                    ?: throw InvalidOfficeDocumentException("The XLSX package has no workbook relationships.")
                val workbook = secureXml(readBounded(packageFile, workbookEntry))
                val relationships = readRelationships(
                    secureXml(readBounded(packageFile, relationshipsEntry)),
                    "xl/workbook.xml"
                )
                val sharedStrings = packageFile.getEntry("xl/sharedStrings.xml")?.let { entry ->
                    val xml = secureXml(readBounded(packageFile, entry))
                    xml.documentElement.elementChildren().filter { it.localName() == "si" }
                        .map(::allTextContent)
                }.orEmpty()
                val cellStyles = packageFile.getEntry("xl/styles.xml")?.let { entry ->
                    parseXlsxCellStyles(secureXml(readBounded(packageFile, entry)))
                }.orEmpty()

                val sheetElements = workbook.documentElement.directChild("sheets")
                    ?.elementChildren()?.filter { it.localName() == "sheet" }.orEmpty()
                if (sheetElements.size > MAX_XLSX_SHEETS) {
                    throw InvalidOfficeDocumentException("The workbook contains more than $MAX_XLSX_SHEETS sheets.")
                }
                var totalCells = 0
                var totalRows = 0
                val sheets = buildList {
                    sheetElements.forEach { sheet ->
                    val name = sheet.attributeByLocalName("name")?.takeIf { it.isNotBlank() } ?: "Sheet"
                    val relationId = sheet.relationshipAttribute("id")
                        ?: throw InvalidOfficeDocumentException("An XLSX sheet has no relationship identifier.")
                    val target = relationships[relationId]
                        ?: throw InvalidOfficeDocumentException("An XLSX sheet points to a missing workbook part.")
                        val parsedSheet = readWorksheet(
                            packageFile,
                            target,
                            name,
                            sharedStrings,
                            cellStyles,
                            MAX_XLSX_CELLS - totalCells,
                            MAX_XLSX_ROW_ENTRIES - totalRows
                        )
                        totalCells += parsedSheet.cellCount
                        totalRows += parsedSheet.rowCount
                        add(parsedSheet.sheet)
                    }
                }
                if (sheets.isEmpty()) throw InvalidOfficeDocumentException("The workbook contains no worksheets.")
                return NativeWorkbook(sheets)
            }
        } catch (error: InvalidOfficeDocumentException) {
            throw error
        } catch (error: Throwable) {
            throw InvalidOfficeDocumentException("Could not read this XLSX file. It may be damaged or encrypted.", error)
        }
    }

    private fun readRelationships(document: org.w3c.dom.Document, sourcePart: String): Map<String, String> {
        val relationships = linkedMapOf<String, String>()
        document.documentElement.elementChildren()
            .filter { it.localName() == "Relationship" }
            .forEach { relationship ->
                if (relationship.attributeByLocalName("TargetMode") == "External") return@forEach
                val id = relationship.attributeByLocalName("Id") ?: return@forEach
                val target = relationship.attributeByLocalName("Target") ?: return@forEach
                relationships[id] = resolveOfficeRelationship(sourcePart, target)
            }
        return relationships
    }

    private fun resolveOfficeRelationship(sourcePart: String, target: String): String {
        if (target.contains(':') || target.contains('\\')) {
            throw InvalidOfficeDocumentException("The Office package contains an invalid relationship target.")
        }
        val pathParts = mutableListOf<String>()
        val combined = if (target.startsWith('/')) target.drop(1) else {
            sourcePart.substringBeforeLast('/') + "/" + target
        }
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (pathParts.isEmpty()) {
                    throw InvalidOfficeDocumentException("The Office package contains a relationship outside its root.")
                } else pathParts.removeAt(pathParts.lastIndex)
                else -> pathParts.add(part)
            }
        }
        return pathParts.joinToString("/")
    }

    private fun readPptx(file: File): NativePresentation {
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_OFFICE_FILE_BYTES) {
            throw InvalidOfficeDocumentException("The presentation is missing, empty, or larger than the 250 MB limit.")
        }
        try {
            ZipFile(file).use { packageFile ->
                val contentTypesEntry = packageFile.getEntry("[Content_Types].xml")
                    ?: throw InvalidOfficeDocumentException("This is not a valid PPTX package.")
                val contentTypes = secureXml(readBounded(packageFile, contentTypesEntry))
                if (contentTypes.documentElement.localName() != "Types") {
                    throw InvalidOfficeDocumentException("This is not a valid Office Open XML package.")
                }
                val presentationEntry = packageFile.getEntry("ppt/presentation.xml")
                    ?: throw InvalidOfficeDocumentException("The PPTX package has no presentation definition.")
                val relationshipsEntry = packageFile.getEntry("ppt/_rels/presentation.xml.rels")
                    ?: throw InvalidOfficeDocumentException("The PPTX package has no presentation relationships.")
                val presentation = secureXml(readBounded(packageFile, presentationEntry))
                val relationships = readRelationships(
                    secureXml(readBounded(packageFile, relationshipsEntry)),
                    "ppt/presentation.xml"
                )
                val size = presentation.documentElement.directChild("sldSz")
                    ?: throw InvalidOfficeDocumentException("The presentation has no slide dimensions.")
                val widthEmu = size.attributeByLocalName("cx")?.toFloatOrNull()
                    ?: throw InvalidOfficeDocumentException("The presentation has invalid slide dimensions.")
                val heightEmu = size.attributeByLocalName("cy")?.toFloatOrNull()
                    ?: throw InvalidOfficeDocumentException("The presentation has invalid slide dimensions.")
                if (!widthEmu.isFinite() || !heightEmu.isFinite() || widthEmu <= 0f || heightEmu <= 0f) {
                    throw InvalidOfficeDocumentException("The presentation has invalid slide dimensions.")
                }
                val widthPoints = (widthEmu / EMU_PER_POINT).coerceIn(144f, 2000f)
                val heightPoints = (heightEmu / EMU_PER_POINT).coerceIn(144f, 2000f)
                val slideIds = presentation.documentElement.directChild("sldIdLst")
                    ?.elementChildren()?.filter { it.localName() == "sldId" }.orEmpty()
                if (slideIds.isEmpty()) throw InvalidOfficeDocumentException("The presentation contains no slides.")
                if (slideIds.size > MAX_PPTX_SLIDES) {
                    throw InvalidOfficeDocumentException("The presentation contains more than $MAX_PPTX_SLIDES slides.")
                }
                var totalShapes = 0
                var remainingTableCells = MAX_PPTX_TOTAL_TABLE_CELLS
                val imageCache = OfficeImageCache()
                val slides = slideIds.mapIndexed { index, slideId ->
                    val relationshipId = slideId.relationshipAttribute("id")
                        ?: throw InvalidOfficeDocumentException("A slide has no relationship identifier.")
                    val target = relationships[relationshipId]
                        ?: throw InvalidOfficeDocumentException("A slide points to a missing presentation part.")
                    val remainingShapes = MAX_PPTX_TOTAL_SHAPES - totalShapes
                    val slide = readSlide(
                        packageFile, target, index + 1, widthEmu, heightEmu,
                        remainingShapes, remainingTableCells, imageCache
                    )
                    totalShapes += slide.shapes.size
                    remainingTableCells -= slide.shapes.sumOf { shape -> shape.tableRows.sumOf { it.size } }
                    slide
                }
                return NativePresentation(slides, widthPoints, heightPoints)
            }
        } catch (error: InvalidOfficeDocumentException) {
            throw error
        } catch (error: Throwable) {
            throw InvalidOfficeDocumentException("Could not read this PPTX file. It may be damaged or encrypted.", error)
        }
    }

    private fun readSlide(
        zip: ZipFile,
        partName: String,
        slideNumber: Int,
        slideWidthEmu: Float,
        slideHeightEmu: Float,
        remainingShapeLimit: Int,
        remainingTableCellLimit: Int,
        imageCache: OfficeImageCache
    ): NativeSlide {
        val entry = zip.getEntry(partName)
            ?: throw InvalidOfficeDocumentException("Slide $slideNumber is missing from the PPTX package.")
        val document = secureXml(readBounded(zip, entry))
        val shapeTree = document.documentElement.directChild("cSld")?.directChild("spTree")
            ?: throw InvalidOfficeDocumentException("Slide $slideNumber has no shape tree.")
        val shapes = shapeTree.elementChildren().filter {
            it.localName() in setOf("sp", "pic", "graphicFrame", "cxnSp", "grpSp")
        }
        if (shapes.size > MAX_PPTX_SHAPES_PER_SLIDE || shapes.size > remainingShapeLimit) {
            throw InvalidOfficeDocumentException("The presentation exceeds the current shape reading limit at slide $slideNumber.")
        }
        val relationshipPart = officeRelationshipPart(partName)
        val relationships = zip.getEntry(relationshipPart)?.let { relationshipEntry ->
            readRelationships(secureXml(readBounded(zip, relationshipEntry)), partName)
        }.orEmpty()
        var tableCellsRead = 0
        val parsed = shapes.mapNotNull { shape ->
            val transform = shape.directChild("spPr")?.directChild("xfrm")
                ?: shape.directChild("xfrm")
            val offset = transform?.directChild("off")
            val extent = transform?.directChild("ext")
            val x = slideCoordinate(offset, "x")
            val y = slideCoordinate(offset, "y")
            val width = slideCoordinate(extent, "cx")
            val height = slideCoordinate(extent, "cy")
            if (width < 0f || height < 0f) {
                throw InvalidOfficeDocumentException("A slide element has invalid dimensions.")
            }
            val textBody = shape.directChild("txBody")
            val text = textBody?.let(::slideText)?.takeIf { it.isNotBlank() }
            val table = if (shape.localName() == "graphicFrame") {
                parseSlideTable(shape, remainingTableCellLimit - tableCellsRead).also { parsedTable ->
                    tableCellsRead += parsedTable?.rows?.sumOf { it.size } ?: 0
                }
            } else {
                null
            }
            val imageRelationshipId = shape.directChild("blipFill")?.directChild("blip")?.relationshipAttribute("embed")
            val imageBytes = imageRelationshipId?.let(relationships::get)?.let { imagePart -> imageCache.read(zip, imagePart) }
            val kind = when (shape.localName()) {
                "pic" -> NativeSlideShapeKind.IMAGE
                "graphicFrame" -> if (table != null) NativeSlideShapeKind.TABLE else NativeSlideShapeKind.OTHER
                "sp", "cxnSp" -> if (text != null) NativeSlideShapeKind.TEXT else NativeSlideShapeKind.SHAPE
                else -> NativeSlideShapeKind.OTHER
            }
            val properties = shape.directChild("spPr")
            val firstRun = textBody?.let(::firstSlideRunProperties)
            val paragraphProperties = textBody?.elementChildren()
                ?.firstOrNull { it.localName() == "p" }?.directChild("pPr")
            NativeSlideShape(
                kind = kind,
                text = text,
                xFraction = (x / slideWidthEmu).coerceIn(-1f, 1f),
                yFraction = (y / slideHeightEmu).coerceIn(-1f, 1f),
                widthFraction = (width / slideWidthEmu).coerceIn(0f, 2f),
                heightFraction = (height / slideHeightEmu).coerceIn(0f, 2f),
                fillRgb = properties?.directChild("solidFill")?.directChild("srgbClr")
                    ?.attributeByLocalName("val")?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16),
                textRgb = firstRun?.directChild("solidFill")?.directChild("srgbClr")
                    ?.attributeByLocalName("val")?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16),
                fontSizePoints = firstRun?.attributeByLocalName("sz")?.toFloatOrNull()?.div(100f)
                    ?.takeIf { it in 4f..288f },
                bold = firstRun?.attributeByLocalName("b")?.let(::isEnabled) ?: false,
                italic = firstRun?.attributeByLocalName("i")?.let(::isEnabled) ?: false,
                centered = paragraphProperties?.attributeByLocalName("algn") in setOf("ctr", "center"),
                rightToLeft = paragraphProperties?.attributeByLocalName("rtl")?.let(::isEnabled) == true ||
                    text?.any(::isArabicCharacter) == true,
                imageBytes = imageBytes,
                table = table
            )
        }
        return NativeSlide(slideNumber, parsed)
    }

    private fun officeRelationshipPart(sourcePart: String): String {
        val directory = sourcePart.substringBeforeLast('/', "")
        val fileName = sourcePart.substringAfterLast('/')
        return listOf(directory, "_rels", "$fileName.rels").filter { it.isNotEmpty() }.joinToString("/")
    }

    private fun parseSlideTable(
        graphicFrame: Element,
        remainingCellLimit: Int
    ): NativeSlideTable? {
        val table = graphicFrame.directChild("graphic")
            ?.directChild("graphicData")
            ?.directChild("tbl")
            ?: return null
        val rows = table.elementChildren().filter { it.localName() == "tr" }
        if (rows.isEmpty()) return null
        if (rows.size > MAX_PPTX_TABLE_ROWS) {
            throw InvalidOfficeDocumentException("A PowerPoint table contains more than $MAX_PPTX_TABLE_ROWS rows.")
        }
        val grid = table.directChild("tblGrid")?.elementChildren()?.filter { it.localName() == "gridCol" }.orEmpty()
        if (grid.size > MAX_PPTX_TABLE_COLUMNS) {
            throw InvalidOfficeDocumentException("A PowerPoint table contains more than $MAX_PPTX_TABLE_COLUMNS columns.")
        }
        var cellCount = 0
        val parsedRows = rows.map { row ->
            val cells = row.elementChildren().filter { it.localName() == "tc" }
            if (cells.size > MAX_PPTX_TABLE_COLUMNS) {
                throw InvalidOfficeDocumentException("A PowerPoint table contains more than $MAX_PPTX_TABLE_COLUMNS columns.")
            }
            cellCount += cells.size
            if (cellCount > remainingCellLimit) {
                throw InvalidOfficeDocumentException("The presentation exceeds the current table cell reading limit.")
            }
            cells.map { cell ->
                val textBody = cell.directChild("txBody")
                val runProperties = textBody?.let(::firstSlideRunProperties)
                val paragraphProperties = textBody?.elementChildren()
                    ?.firstOrNull { it.localName() == "p" }?.directChild("pPr")
                val text = textBody?.let(::slideText).orEmpty()
                val properties = cell.directChild("tcPr")
                NativeSlideTableCell(
                    text = text,
                    fillRgb = pptxSolidFillRgb(properties),
                    textRgb = pptxSolidFillRgb(runProperties),
                    fontSizePoints = runProperties?.attributeByLocalName("sz")?.toFloatOrNull()?.div(100f)
                        ?.takeIf { it in 4f..288f },
                    bold = runProperties?.attributeByLocalName("b")?.let(::isEnabled) ?: false,
                    italic = runProperties?.attributeByLocalName("i")?.let(::isEnabled) ?: false,
                    centered = paragraphProperties?.attributeByLocalName("algn") in setOf("ctr", "center"),
                    rightToLeft = paragraphProperties?.attributeByLocalName("rtl")?.let(::isEnabled) == true ||
                        text.any(::isArabicCharacter),
                    columnSpan = pptxSpan(cell, "gridSpan"),
                    rowSpan = pptxSpan(cell, "rowSpan"),
                    horizontalMerge = pptxBoolean(cell.attributeByLocalName("hMerge")),
                    verticalMerge = pptxBoolean(cell.attributeByLocalName("vMerge")),
                    verticalAlignment = when (properties?.attributeByLocalName("anchor")) {
                        "ctr" -> SlideCellVerticalAlignment.CENTER
                        "b" -> SlideCellVerticalAlignment.BOTTOM
                        else -> SlideCellVerticalAlignment.TOP
                    },
                    marginLeftPoints = pptxCellMargin(properties, "marL", 7.2f),
                    marginRightPoints = pptxCellMargin(properties, "marR", 7.2f),
                    marginTopPoints = pptxCellMargin(properties, "marT", 3.6f),
                    marginBottomPoints = pptxCellMargin(properties, "marB", 3.6f),
                    borderLeft = pptxCellBorder(properties?.directChild("lnL")),
                    borderRight = pptxCellBorder(properties?.directChild("lnR")),
                    borderTop = pptxCellBorder(properties?.directChild("lnT")),
                    borderBottom = pptxCellBorder(properties?.directChild("lnB"))
                )
            }
        }
        return NativeSlideTable(
            parsedRows,
            if (grid.isEmpty()) List(parsedRows.maxOf { it.size }) { 1f } else grid.map { pptxTableSize(it, "w") },
            rows.map { pptxTableSize(it, "h") },
            pptxBoolean(table.directChild("tblPr")?.attributeByLocalName("rtl"))
        )
    }

    private fun slideCoordinate(element: Element?, name: String): Float {
        val value = element?.attributeByLocalName(name) ?: return 0f
        return value.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw InvalidOfficeDocumentException("A slide element has an invalid coordinate.")
    }

    private fun pptxTableSize(element: Element, name: String): Float {
        val value = element.attributeByLocalName(name) ?: return 1f
        return value.toLongOrNull()?.takeIf { it > 0L }?.toFloat()
            ?: throw InvalidOfficeDocumentException("A PowerPoint table has an invalid row or column size.")
    }

    private fun pptxSpan(cell: Element, name: String): Int {
        val value = cell.attributeByLocalName(name) ?: return 1
        return value.toIntOrNull()?.takeIf { it > 0 }
            ?: throw InvalidOfficeDocumentException("A PowerPoint table has an invalid merge span.")
    }

    private fun pptxBoolean(value: String?): Boolean = when (value) {
        null, "0", "false" -> false
        "1", "true" -> true
        else -> throw InvalidOfficeDocumentException("A PowerPoint table has an invalid merge or direction flag.")
    }

    private fun pptxCellMargin(properties: Element?, name: String, default: Float): Float {
        val value = properties?.attributeByLocalName(name) ?: return default
        return value.toLongOrNull()?.takeIf { it >= 0L }?.toFloat()?.div(EMU_PER_POINT)?.coerceAtMost(1000f)
            ?: throw InvalidOfficeDocumentException("A PowerPoint table cell has an invalid margin.")
    }

    private fun pptxCellBorder(line: Element?): NativeSlideCellBorder? {
        if (line == null) return null
        if (line.directChild("noFill") != null) return NativeSlideCellBorder(0f)
        val width = line.attributeByLocalName("w")?.let { value ->
            value.toLongOrNull()?.takeIf { it >= 0L }?.toFloat()?.div(EMU_PER_POINT)?.coerceAtMost(72f)
                ?: throw InvalidOfficeDocumentException("A PowerPoint table has an invalid border width.")
        } ?: 0.75f
        return NativeSlideCellBorder(width, pptxSolidFillRgb(line), when (line.directChild("prstDash")?.attributeByLocalName("val")) {
            "dash", "lgDash", "sysDash", "dashDot", "lgDashDot", "lgDashDotDot", "sysDashDot", "sysDashDotDot" -> SlideBorderDash.DASH
            "dot", "sysDot" -> SlideBorderDash.DOT
            else -> SlideBorderDash.SOLID
        })
    }

    private fun pptxSolidFillRgb(parent: Element?): Long? {
        val fill = parent?.directChild("solidFill") ?: return null
        val color = fill.elementChildren().firstOrNull { it.localName() in setOf("srgbClr", "sysClr") } ?: return null
        val value = if (color.localName() == "sysClr") {
            color.attributeByLocalName("lastClr")
        } else {
            color.attributeByLocalName("val")
        }
        return value?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)
    }

    private fun slideText(textBody: Element): String = textBody.elementChildren()
        .filter { it.localName() == "p" }
        .joinToString("\n") { paragraph ->
            buildString {
                fun collect(element: Element) {
                    element.elementChildren().forEach { child ->
                        when (child.localName()) {
                            "t" -> append(child.textContent)
                            "br" -> append('\n')
                            else -> collect(child)
                        }
                    }
                }
                collect(paragraph)
            }
        }

    private fun firstSlideRunProperties(textBody: Element): Element? {
        fun find(element: Element): Element? {
            for (child in element.elementChildren()) {
                if (child.localName() == "r") return child.directChild("rPr")
                    ?: child.directChild("defRPr")
                    ?: child.directChild("endParaRPr")
                find(child)?.let { return it }
            }
            return null
        }
        return find(textBody)
    }

    private fun readWorksheet(
        zip: ZipFile,
        partName: String,
        name: String,
        sharedStrings: List<String>,
        cellStyles: List<NativeSheetCell>,
        remainingCellLimit: Int,
        remainingRowLimit: Int
    ): ParsedWorksheet {
        val entry = zip.getEntry(partName)
            ?: throw InvalidOfficeDocumentException("Worksheet '$name' is missing from the XLSX package.")
        val document = secureXml(readBounded(zip, entry))
        val horizontalMerges = parseHorizontalMerges(document)
        val isRtl = document.documentElement.directChild("sheetViews")
            ?.elementChildren()?.firstOrNull { it.localName() == "sheetView" }
            ?.attributeByLocalName("rightToLeft")?.let(::isEnabled) ?: false
        val sheetData = document.documentElement.directChild("sheetData")
            ?: return ParsedWorksheet(NativeWorksheet(name, emptyList(), 0, isRtl, horizontalMerges), 0, 0)
        val rows = sortedMapOf<Int, MutableMap<Int, NativeSheetCell>>()
        var maximumColumn = -1
        var cellCount = 0
        sheetData.elementChildren().filter { it.localName() == "row" }.forEachIndexed { rowPosition, row ->
            val rowIndex = row.attributeByLocalName("r")?.toIntOrNull()?.minus(1) ?: rowPosition
            if (rowIndex !in 0 until MAX_XLSX_ROW_INDEX) {
                throw InvalidOfficeDocumentException("An XLSX row address is outside the spreadsheet range.")
            }
            if (rows.containsKey(rowIndex)) {
                throw InvalidOfficeDocumentException("The worksheet contains a duplicate row address.")
            }
            if (rows.size >= remainingRowLimit) {
                throw InvalidOfficeDocumentException("The workbook exceeds the current 100,000-row reading limit.")
            }
            val rowCells = rows.getOrPut(rowIndex) { sortedMapOf() }
            var nextColumn = 0
            row.elementChildren().filter { it.localName() == "c" }.forEach { cell ->
                val reference = cell.attributeByLocalName("r")
                val (columnIndex, referencedRow) = reference?.let(::parseCellReference)
                    ?: (nextColumn to rowIndex)
                if (referencedRow != rowIndex) {
                    throw InvalidOfficeDocumentException("An XLSX cell address does not match its row.")
                }
                if (columnIndex !in 0 until MAX_XLSX_COLUMNS) {
                    throw InvalidOfficeDocumentException("The workbook exceeds the current 512-column reading limit.")
                }
                nextColumn = columnIndex + 1
                val type = cell.attributeByLocalName("t").orEmpty()
                val rawValue = cell.directChild("v")?.textContent.orEmpty()
                val formula = cell.directChild("f")?.textContent
                val value = when (type) {
                    "s" -> rawValue.toIntOrNull()?.let { index -> sharedStrings.getOrNull(index) } ?: rawValue
                    "inlineStr" -> cell.directChild("is")?.let(::allTextContent).orEmpty()
                    "b" -> if (rawValue == "1") "TRUE" else if (rawValue == "0") "FALSE" else rawValue
                    else -> rawValue.ifBlank { formula?.let { "=$it" }.orEmpty() }
                }
                val styleIndex = cell.attributeByLocalName("s")?.toIntOrNull() ?: 0
                val style = cellStyles.getOrNull(styleIndex) ?: NativeSheetCell("")
                if (value.isNotEmpty() || styleIndex > 0) rowCells[columnIndex] = style.copy(value = value)
                maximumColumn = maxOf(maximumColumn, columnIndex)
                cellCount++
                if (cellCount > remainingCellLimit) {
                    throw InvalidOfficeDocumentException("The workbook exceeds the current 250,000-cell reading limit.")
                }
            }
        }
        return ParsedWorksheet(
            sheet = NativeWorksheet(
                name = name,
                rows = rows.map { (row, cells) -> NativeSheetRow(row, cells.toMap()) },
                columnCount = maxOf(maximumColumn, horizontalMerges.maxOfOrNull { it.lastColumn } ?: -1) + 1,
                rightToLeft = isRtl,
                horizontalMerges = horizontalMerges
            ),
            cellCount = cellCount,
            rowCount = rows.size
        )
    }

    private fun parseHorizontalMerges(document: org.w3c.dom.Document): List<NativeMergedRange> {
        val mergeCells = document.documentElement.directChild("mergeCells") ?: return emptyList()
        val merges = mergeCells.elementChildren().filter { it.localName() == "mergeCell" }
        if (merges.size > MAX_XLSX_MERGED_RANGES) {
            throw InvalidOfficeDocumentException("The worksheet contains too many merged cell ranges.")
        }
        return merges.mapNotNull { merge ->
            val reference = merge.attributeByLocalName("ref")
                ?: throw InvalidOfficeDocumentException("A merged cell range has no address.")
            val endpoints = reference.split(':')
            if (endpoints.size != 2) throw InvalidOfficeDocumentException("A merged cell range has an invalid address.")
            val (firstColumn, firstRow) = parseCellReference(endpoints[0])
            val (lastColumn, lastRow) = parseCellReference(endpoints[1])
            if (firstColumn !in 0 until MAX_XLSX_COLUMNS || lastColumn !in 0 until MAX_XLSX_COLUMNS ||
                firstRow !in 0 until MAX_XLSX_ROW_INDEX || lastRow !in 0 until MAX_XLSX_ROW_INDEX
            ) {
                throw InvalidOfficeDocumentException("A merged cell range is outside the supported worksheet limits.")
            }
            if (firstColumn > lastColumn || firstRow > lastRow) {
                throw InvalidOfficeDocumentException("A merged cell range has reversed boundaries.")
            }
            if (firstRow == lastRow && firstColumn < lastColumn) {
                NativeMergedRange(firstRow, firstColumn, lastColumn)
            } else null
        }
    }

    private fun parseXlsxCellStyles(document: org.w3c.dom.Document): List<NativeSheetCell> {
        fun childItems(parentName: String, itemName: String): List<Element> {
            val items = document.documentElement.directChild(parentName)
                ?.elementChildren()?.filter { it.localName() == itemName }.orEmpty()
            if (items.size > MAX_XLSX_STYLES) {
                throw InvalidOfficeDocumentException("The workbook contains too many cell styles.")
            }
            return items
        }

        val fonts = childItems("fonts", "font").map { font ->
            val size = font.directChild("sz")?.attributeByLocalName("val")?.toFloatOrNull()
                ?.takeIf { it in 4f..288f }
            val color = font.directChild("color")?.attributeByLocalName("rgb")?.let(::parseOfficeRgb)
            val bold = font.directChild("b")?.let { it.attributeByLocalName("val")?.let(::isEnabled) ?: true } ?: false
            val italic = font.directChild("i")?.let { it.attributeByLocalName("val")?.let(::isEnabled) ?: true } ?: false
            Triple(bold, italic, size to color)
        }
        val fills = childItems("fills", "fill").map { fill ->
            val pattern = fill.directChild("patternFill")
            if (pattern?.attributeByLocalName("patternType") != "solid") null
            else pattern.directChild("fgColor")?.attributeByLocalName("rgb")?.let(::parseOfficeRgb)
        }
        val xfs = childItems("cellXfs", "xf")
        if (xfs.isEmpty()) return listOf(NativeSheetCell(""))
        return xfs.map { xf ->
            val fontIndex = xf.attributeByLocalName("fontId")?.toIntOrNull() ?: 0
            val fillIndex = xf.attributeByLocalName("fillId")?.toIntOrNull() ?: 0
            val font = fonts.getOrNull(fontIndex)
            val applyFont = xf.attributeByLocalName("applyFont")?.let(::isEnabled) != false
            val applyFill = xf.attributeByLocalName("applyFill")?.let(::isEnabled) != false
            NativeSheetCell(
                value = "",
                bold = applyFont && font?.first == true,
                italic = applyFont && font?.second == true,
                fontSizePoints = if (applyFont) font?.third?.first else null,
                textRgb = if (applyFont) font?.third?.second else null,
                fillRgb = if (applyFill) fills.getOrNull(fillIndex) else null
            )
        }
    }

    private fun parseOfficeRgb(value: String): Long? {
        val rgb = value.removePrefix("#").takeLast(6)
        return rgb.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)
    }

    private fun parseCellReference(reference: String): Pair<Int, Int> {
        val match = Regex("^([A-Za-z]+)([1-9][0-9]*)$").matchEntire(reference)
            ?: throw InvalidOfficeDocumentException("The workbook contains an invalid cell address.")
        var column = 0L
        for (char in match.groupValues[1].uppercase(Locale.ROOT)) {
            column = column * 26 + (char - 'A' + 1)
            if (column > MAX_XLSX_COLUMNS) break
        }
        val row = match.groupValues[2].toIntOrNull()
            ?: throw InvalidOfficeDocumentException("The workbook contains an invalid row address.")
        return (column - 1).toInt() to (row - 1)
    }

    private fun allTextContent(element: Element): String = buildString {
        fun collect(current: Element) {
            when (current.localName()) {
                "rPh", "phoneticPr" -> Unit
                "t" -> append(current.textContent)
                else -> current.elementChildren().forEach(::collect)
            }
        }
        collect(element)
    }

    private fun readDocx(file: File): NativeWordDocument {
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_OFFICE_FILE_BYTES) {
            throw InvalidOfficeDocumentException("The document is missing, empty, or larger than the 250 MB limit.")
        }

        try {
            ZipFile(file).use { packageFile ->
                val contentTypes = packageFile.getEntry("[Content_Types].xml")
                    ?: throw InvalidOfficeDocumentException("This is not a valid DOCX package.")
                val contentTypesDocument = secureXml(readBounded(packageFile, contentTypes))
                if (contentTypesDocument.documentElement.localName() != "Types") {
                    throw InvalidOfficeDocumentException("This is not a valid Office Open XML package.")
                }
                val documentEntry = packageFile.getEntry("word/document.xml")
                    ?: throw InvalidOfficeDocumentException("The DOCX package has no main Word document.")
                val xml = readBounded(packageFile, documentEntry)
                val document = secureXml(xml)
                val body = document.documentElement.directChild("body")
                    ?: throw InvalidOfficeDocumentException("The Word document body is empty.")
                val numbering = packageFile.getEntry("word/numbering.xml")?.let { entry ->
                    readNumbering(secureXml(readBounded(packageFile, entry)))
                } ?: WordNumberingCatalog(emptyMap(), emptyMap())
                val relationships = packageFile.getEntry("word/_rels/document.xml.rels")?.let { entry ->
                    readRelationships(secureXml(readBounded(packageFile, entry)), "word/document.xml")
                }.orEmpty()
                val imageCache = OfficeImageCache()
                val counters = mutableMapOf<Pair<Int, Int>, Int>()
                val blocks = body.elementChildren().mapNotNull { element ->
                    when (element.localName()) {
                        "p" -> parseParagraph(element, numbering, counters, relationships, imageCache, packageFile)
                        "tbl" -> parseTable(element, numbering, counters, relationships, imageCache, packageFile)
                        else -> null
                    }
                }
                val section = body.directChild("sectPr")
                    ?: body.elementChildren().lastOrNull { it.localName() == "p" }
                        ?.directChild("pPr")?.directChild("sectPr")
                return NativeWordDocument(blocks, parsePageSettings(section))
            }
        } catch (error: InvalidOfficeDocumentException) {
            throw error
        } catch (error: Throwable) {
            throw InvalidOfficeDocumentException("Could not read this DOCX file. It may be damaged or encrypted.", error)
        }
    }

    private fun parseParagraph(
        element: Element,
        numbering: WordNumberingCatalog,
        counters: MutableMap<Pair<Int, Int>, Int>,
        relationships: Map<String, String>,
        imageCache: OfficeImageCache,
        zip: ZipFile
    ): WordParagraph {
        val properties = element.directChild("pPr")
        val alignment = when (properties?.directChild("jc")?.wordAttribute("val")?.lowercase(Locale.ROOT)) {
            "center" -> WordAlignment.CENTER
            "right", "end" -> WordAlignment.END
            "both", "distribute" -> WordAlignment.JUSTIFY
            else -> WordAlignment.START
        }
        val rightToLeft = properties?.directChild("bidi")?.wordAttribute("val")?.let(::isEnabled) ?: false
        val spacing = properties?.directChild("spacing")
        val before = spacing?.wordAttribute("before")?.toFloatOrNull()?.div(20f) ?: 0f
        val after = spacing?.wordAttribute("after")?.toFloatOrNull()?.div(20f) ?: 4f
        val heading = properties?.directChild("outlineLvl")?.wordAttribute("val")?.toIntOrNull()?.plus(1)
            ?: properties?.directChild("pStyle")?.wordAttribute("val")?.let(::headingFromStyle)
        val listLevelElement = properties?.directChild("numPr")
        val listLevel = listLevelElement?.directChild("ilvl")?.wordAttribute("val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
        val numberId = listLevelElement?.directChild("numId")?.wordAttribute("val")?.toIntOrNull()
        val listMarker = numberId?.takeIf { it > 0 }?.let { createListMarker(it, listLevel, numbering, counters) }
        val pageBreakBefore = properties?.directChild("pageBreakBefore")
        val startsNewPage = pageBreakBefore?.wordAttribute("val")?.let(::isEnabled) ?: (pageBreakBefore != null)

        val runs = mutableListOf<WordRun>()
        element.elementChildren().forEach { collectRuns(it, runs) }
        return WordParagraph(
            runs = runs,
            alignment = alignment,
            rightToLeft = rightToLeft || runs.any { it.text.any(::isArabicCharacter) },
            spaceBeforePoints = before.coerceIn(0f, 144f),
            spaceAfterPoints = after.coerceIn(0f, 144f),
            headingLevel = heading,
            listMarker = listMarker,
            listLevel = listLevel,
            startsNewPage = startsNewPage,
            endsPage = containsPageBreak(element),
            images = parseWordImages(element, relationships, imageCache, zip)
        )
    }

    private fun parseWordImages(
        paragraph: Element,
        relationships: Map<String, String>,
        imageCache: OfficeImageCache,
        zip: ZipFile
    ): List<NativeWordImage> {
        fun descendants(element: Element, name: String): List<Element> = buildList {
            element.elementChildren().forEach { child ->
                if (child.localName() == name) add(child)
                addAll(descendants(child, name))
            }
        }
        val drawingImages = descendants(paragraph, "blip")
        val imageNodes = drawingImages.ifEmpty { descendants(paragraph, "imagedata") }
        return imageNodes.mapNotNull { imageNode ->
            val relationId = imageNode.relationshipAttribute("embed")
                ?: imageNode.relationshipAttribute("id")
                ?: imageNode.relationshipAttribute("link")
                ?: return@mapNotNull null
            val imagePart = relationships[relationId] ?: return@mapNotNull null
            val drawing = generateSequence(imageNode.parentNode) { it.parentNode }
                .filterIsInstance<Element>()
                .firstOrNull { it.localName() in setOf("inline", "anchor", "pict") }
                ?: paragraph
            val extent = drawing.directChild("extent")
                ?: drawing.elementChildren().firstOrNull { it.localName() == "extent" }
            val width = extent?.attributeByLocalName("cx")?.toFloatOrNull()?.div(EMU_PER_POINT)
                ?.coerceIn(12f, 2000f) ?: 144f
            val height = extent?.attributeByLocalName("cy")?.toFloatOrNull()?.div(EMU_PER_POINT)
                ?.coerceIn(12f, 2000f) ?: 108f
            val description = descendants(drawing, "docPr").firstOrNull()
                ?.attributeByLocalName("descr")?.takeIf { it.isNotBlank() }
            NativeWordImage(imageCache.read(zip, imagePart), width, height, description)
        }
    }

    private fun collectRuns(element: Element, output: MutableList<WordRun>) {
        if (element.localName() == "r") {
            parseRun(element)?.let(output::add)
            return
        }
        element.elementChildren().forEach { collectRuns(it, output) }
    }

    private fun parseRun(run: Element): WordRun? {
        val text = buildString {
            fun appendTextNodes(node: Element) {
                node.elementChildren().forEach { child ->
                    when (child.localName()) {
                        "t", "delText", "instrText" -> append(child.textContent)
                        "tab" -> append('\t')
                        "br", "cr" -> append('\n')
                        else -> if (child.localName() != "rPr") appendTextNodes(child)
                    }
                }
            }
            appendTextNodes(run)
        }
        if (text.isEmpty()) return null

        val properties = run.directChild("rPr")
        val size = properties?.directChild("sz")?.wordAttribute("val")?.toFloatOrNull()?.div(2f)
        val color = properties?.directChild("color")?.wordAttribute("val")
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)
        return WordRun(
            text = text,
            bold = properties?.directChild("b")?.wordAttribute("val")?.let(::isEnabled) ?: (properties?.directChild("b") != null),
            italic = properties?.directChild("i")?.wordAttribute("val")?.let(::isEnabled) ?: (properties?.directChild("i") != null),
            underline = properties?.directChild("u")?.wordAttribute("val")?.let { it !in setOf("none", "0", "false") }
                ?: (properties?.directChild("u") != null),
            fontSizePoints = size?.takeIf { it in 4f..288f },
            colorRgb = color
        )
    }

    private fun readNumbering(document: org.w3c.dom.Document): WordNumberingCatalog {
        val root = document.documentElement
        val levels = mutableMapOf<Pair<Int, Int>, WordNumberingLevel>()
        root.elementChildren().filter { it.localName() == "abstractNum" }.forEach abstractLoop@ { abstractNumbering ->
            val abstractId = abstractNumbering.wordAttribute("abstractNumId")?.toIntOrNull() ?: return@abstractLoop
            abstractNumbering.elementChildren().filter { it.localName() == "lvl" }.forEach levelLoop@ { level ->
                val index = level.wordAttribute("ilvl")?.toIntOrNull()?.coerceIn(0, 8) ?: return@levelLoop
                val format = level.directChild("numFmt")?.wordAttribute("val") ?: "bullet"
                val text = level.directChild("lvlText")?.wordAttribute("val") ?: "%${index + 1}."
                levels[abstractId to index] = WordNumberingLevel(format, text)
            }
        }
        val numberingById = root.elementChildren()
            .filter { it.localName() == "num" }
            .mapNotNull { number ->
                val numberId = number.wordAttribute("numId")?.toIntOrNull() ?: return@mapNotNull null
                val abstractId = number.directChild("abstractNumId")?.wordAttribute("val")?.toIntOrNull()
                    ?: return@mapNotNull null
                numberId to abstractId
            }.toMap()
        return WordNumberingCatalog(numberingById, levels)
    }

    private fun createListMarker(
        numberId: Int,
        level: Int,
        catalog: WordNumberingCatalog,
        counters: MutableMap<Pair<Int, Int>, Int>
    ): String {
        val abstractId = catalog.numberingById[numberId]
        val currentStyle = abstractId?.let { catalog.levelsByAbstractId[it to level] }
        val style = currentStyle ?: WordNumberingLevel("bullet", "•")
        val currentKey = numberId to level
        val currentValue = (counters[currentKey] ?: 0) + 1
        counters.keys.removeAll { (id, itemLevel) -> id == numberId && itemLevel > level }
        counters[currentKey] = currentValue
        if (style.format == "bullet") return "•"

        val markerTemplate = style.text.ifBlank { "%${level + 1}." }
        return Regex("%([1-9])").replace(markerTemplate) { match ->
            val targetLevel = match.groupValues[1].toInt() - 1
            val value = if (targetLevel == level) currentValue else counters[numberId to targetLevel] ?: 1
            val targetStyle = abstractId?.let { catalog.levelsByAbstractId[it to targetLevel] }
            formatNumber(value, targetStyle?.format ?: style.format)
        }
    }

    private fun formatNumber(value: Int, format: String): String = when (format) {
        "lowerLetter" -> alphabeticNumber(value).lowercase(Locale.ROOT)
        "upperLetter" -> alphabeticNumber(value)
        "lowerRoman" -> romanNumber(value).lowercase(Locale.ROOT)
        "upperRoman" -> romanNumber(value)
        "decimalZero" -> value.toString().padStart(2, '0')
        else -> value.toString()
    }

    private fun alphabeticNumber(value: Int): String {
        var number = value.coerceAtLeast(1)
        val output = StringBuilder()
        while (number > 0) {
            number--
            output.append(('A'.code + number % 26).toChar())
            number /= 26
        }
        return output.reverse().toString()
    }

    private fun romanNumber(value: Int): String {
        var number = value.coerceIn(1, 3999)
        val symbols = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        return buildString {
            for ((amount, symbol) in symbols) while (number >= amount) {
                append(symbol)
                number -= amount
            }
        }
    }

    private fun parsePageSettings(section: Element?): WordPageSettings {
        val size = section?.directChild("pgSz")
        val width = size?.wordAttribute("w")?.toFloatOrNull()?.div(20f) ?: 612f
        val height = size?.wordAttribute("h")?.toFloatOrNull()?.div(20f) ?: 792f
        val landscape = size?.wordAttribute("orient") == "landscape"
        val margins = section?.directChild("pgMar")
        val leftRightWidth = if (landscape && width < height) height to width else width to height
        val pageWidth = leftRightWidth.first.coerceIn(144f, 2000f)
        val pageHeight = leftRightWidth.second.coerceIn(144f, 2000f)
        val marginLeft = margins?.wordAttribute("left")?.toFloatOrNull()?.div(20f)?.coerceIn(0f, pageWidth * 0.4f) ?: 72f
        val marginRight = margins?.wordAttribute("right")?.toFloatOrNull()?.div(20f)?.coerceIn(0f, pageWidth * 0.4f) ?: 72f
        return WordPageSettings(
            widthPoints = pageWidth,
            heightPoints = pageHeight,
            marginTopPoints = margins?.wordAttribute("top")?.toFloatOrNull()?.div(20f)?.coerceIn(0f, 400f) ?: 72f,
            marginRightPoints = marginRight,
            marginBottomPoints = margins?.wordAttribute("bottom")?.toFloatOrNull()?.div(20f)?.coerceIn(0f, 400f) ?: 72f,
            marginLeftPoints = marginLeft
        )
    }

    private fun containsPageBreak(element: Element): Boolean =
        element.elementChildren().any { child ->
            (child.localName() == "lastRenderedPageBreak") ||
                (child.localName() == "br" && child.wordAttribute("type") == "page") ||
                containsPageBreak(child)
        }

    private fun parseTable(
        table: Element,
        numbering: WordNumberingCatalog,
        counters: MutableMap<Pair<Int, Int>, Int>,
        relationships: Map<String, String>,
        imageCache: OfficeImageCache,
        zip: ZipFile
    ): WordTable {
        val rows = table.elementChildren().filter { it.localName() == "tr" }.map { row ->
            row.elementChildren().filter { it.localName() == "tc" }.map { cell ->
                val textParts = mutableListOf<String>()
                val images = mutableListOf<NativeWordImage>()
                cell.elementChildren().forEach { child ->
                    when (child.localName()) {
                        "p" -> parseParagraph(child, numbering, counters, relationships, imageCache, zip).let { paragraph ->
                            textParts += paragraph.plainText
                            images += paragraph.images
                        }
                        "tbl" -> parseTable(child, numbering, counters, relationships, imageCache, zip).let { nested ->
                            textParts += nested.rows.joinToString("\n") { cells ->
                                cells.joinToString(" | ") { it.text }
                            }
                            images += nested.rows.flatten().flatMap { it.images }
                        }
                    }
                }
                WordTableCell(textParts.joinToString("\n"), images)
            }
        }
        return WordTable(rows)
    }

    private fun readBounded(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray {
        if (entry.size > MAX_XML_ENTRY_BYTES) throw InvalidOfficeDocumentException("An Office XML part exceeds the 32 MB safety limit.")
        zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_XML_ENTRY_BYTES) {
                    throw InvalidOfficeDocumentException("An Office XML part exceeds the 32 MB safety limit.")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun secureXml(bytes: ByteArray): org.w3c.dom.Document {
        val xmlProbe = bytes.toString(Charsets.ISO_8859_1).filterNot { it == '\u0000' }.uppercase(Locale.ROOT)
        if ("<!DOCTYPE" in xmlProbe || "<!ENTITY" in xmlProbe) {
            throw InvalidOfficeDocumentException("Office XML must not contain a document type or entity declaration.")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        return factory.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(bytes)))
    }

    private fun headingFromStyle(style: String): Int? =
        Regex("(?i)^heading([1-6])$").matchEntire(style)?.groupValues?.get(1)?.toIntOrNull()

    private fun isEnabled(value: String): Boolean = value !in setOf("0", "false", "off", "none")

    private fun isArabicCharacter(char: Char): Boolean =
        char in '\u0600'..'\u06FF' || char in '\u0750'..'\u077F' || char in '\u08A0'..'\u08FF'
}

private fun Element.localName(): String = localName ?: tagName.substringAfter(':')

private fun Element.elementChildren(): List<Element> = buildList {
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node.nodeType == Node.ELEMENT_NODE) add(node as Element)
    }
}

private fun Element.directChild(name: String): Element? =
    elementChildren().firstOrNull { it.localName() == name }

private fun Element.wordAttribute(name: String): String? {
    val direct = getAttributeNS(WORD_NAMESPACE, name)
    if (direct.isNotEmpty()) return direct
    val attributes = attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if (attribute.localName == name || attribute.nodeName.substringAfter(':') == name) return attribute.nodeValue
    }
    return null
}

private fun Element.attributeByLocalName(name: String): String? {
    val attributes = attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if (attribute.localName == name || attribute.nodeName.substringAfter(':') == name) {
            return attribute.nodeValue
        }
    }
    return null
}

private fun Element.relationshipAttribute(name: String): String? {
    val attributes = attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if (attribute.localName == name &&
            attribute.namespaceURI == "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        ) {
            return attribute.nodeValue
        }
    }
    return null
}

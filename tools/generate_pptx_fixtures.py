"""Generate a test document with python-pptx (test tooling, not an app dependency).

Run: python tools/generate_pptx_fixtures.py
"""
from pathlib import Path
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.xmlchemy import OxmlElement
from pptx.util import Inches, Pt


def border(properties, tag, color, width, dash="solid"):
    line = OxmlElement(f"a:{tag}")
    line.set("w", str(int(Pt(width))))
    fill = OxmlElement("a:solidFill")
    rgb = OxmlElement("a:srgbClr")
    rgb.set("val", color)
    fill.append(rgb)
    line.append(fill)
    pattern = OxmlElement("a:prstDash")
    pattern.set("val", dash)
    line.append(pattern)
    properties.append(line)


root = Path(__file__).resolve().parents[1]
presentation = Presentation()
presentation.slide_width = Inches(10)
presentation.slide_height = Inches(5.625)
slide = presentation.slides.add_slide(presentation.slide_layouts[6])
shape = slide.shapes.add_table(3, 3, Inches(1), Inches(1), Inches(6), Inches(2))
table = shape.table
for column, width in zip(table.columns, (1, 2, 3)):
    column.width = Inches(width)
for row, height in zip(table.rows, (0.4, 0.6, 1)):
    row.height = Inches(height)
table.cell(0, 0).merge(table.cell(1, 1))
anchor = table.cell(0, 0)
anchor.text = "عنوان مدمج"
anchor.fill.solid()
anchor.fill.fore_color.rgb = RGBColor.from_string("FFF2CC")
anchor.vertical_anchor = MSO_ANCHOR.MIDDLE
anchor.margin_left = anchor.margin_right = Inches(0.05)
anchor.margin_top = anchor.margin_bottom = Inches(0.025)
paragraph = anchor.text_frame.paragraphs[0]
paragraph.alignment = PP_ALIGN.CENTER
paragraph._p.get_or_add_pPr().set("rtl", "1")
run = paragraph.runs[0]
run.font.size = Pt(18)
run.font.bold = True
run.font.italic = True
run.font.color.rgb = RGBColor.from_string("CC0000")
table.cell(0, 2).text = "رأس"
table.cell(1, 2).text = "القيمة"
for index, value in enumerate(("أحمد", "١٢٣", "نهاية")):
    table.cell(2, index).text = value
table._tbl.tblPr.set("rtl", "1")
properties = anchor._tc.get_or_add_tcPr()
border(properties, "lnT", "123456", 2, "dash")
border(properties, "lnB", "654321", 1)
no_fill_line = OxmlElement("a:lnL")
no_fill_line.append(OxmlElement("a:noFill"))
properties.append(no_fill_line)
target = root / "app/src/test/resources/fixtures/merged-arabic-table.pptx"
target.parent.mkdir(parents=True, exist_ok=True)
presentation.save(target)
print(target)

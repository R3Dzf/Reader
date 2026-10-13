"""Generate a test document with python-pptx (test tooling, not an app dependency).

Run: python tools/generate_pptx_fixtures.py
"""
from pathlib import Path
from copy import deepcopy
from zipfile import ZipFile, ZIP_DEFLATED
from lxml import etree
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


def xml_child(parent, tag, **attributes):
    child = OxmlElement(tag)
    for key, value in attributes.items():
        child.set(key, str(value))
    parent.append(child)
    return child


def theme_color(parent, key, **modifiers):
    fill = xml_child(parent, "a:solidFill")
    color = xml_child(fill, "a:schemeClr", val=key)
    for modifier, value in modifiers.items():
        xml_child(color, f"a:{modifier}", val=value)


# A second fixture exercises style inheritance and mixed run formatting.
presentation = Presentation()
presentation.slide_width = Inches(10)
presentation.slide_height = Inches(5.625)
layout = presentation.slide_layouts[0]
layout_title = layout.placeholders[0]
layout_title.left = Inches(1)
layout_title.top = Inches(0.5)
layout_title.width = Inches(8)
layout_title.height = Inches(1.6)
master = presentation.slide_master
title_style = master._element.find("{http://schemas.openxmlformats.org/presentationml/2006/main}txStyles").find("{http://schemas.openxmlformats.org/presentationml/2006/main}titleStyle")
master_default = title_style.find("{http://schemas.openxmlformats.org/drawingml/2006/main}lvl1pPr").find("{http://schemas.openxmlformats.org/drawingml/2006/main}defRPr")
for child in list(master_default):
    master_default.remove(child)
master_default.set("sz", "2800")
theme_color(master_default, "tx1")
xml_child(master_default, "a:latin", typeface="+mj-lt")
xml_child(master_default, "a:cs", typeface="+mj-cs")
layout_body = layout_title._element.find("{http://schemas.openxmlformats.org/presentationml/2006/main}txBody")
layout_list_style = layout_body.find("{http://schemas.openxmlformats.org/drawingml/2006/main}lstStyle")
for child in list(layout_list_style):
    layout_list_style.remove(child)
xml_child(xml_child(layout_list_style, "a:lvl1pPr"), "a:defRPr", sz=3600, b=1)
background = xml_child(layout._element.find("{http://schemas.openxmlformats.org/presentationml/2006/main}cSld"), "p:bg")
background_properties = xml_child(background, "p:bgPr")
theme_color(background_properties, "accent3", lumMod=50000, lumOff=25000)
background.getparent().remove(background)
layout._element.find("{http://schemas.openxmlformats.org/presentationml/2006/main}cSld").insert(0, background)

slide = presentation.slides.add_slide(layout)
for shape in list(slide.shapes):
    if shape._element is not slide.shapes.title._element:
        shape._element.getparent().remove(shape._element)
frame = slide.shapes.title.text_frame
frame.clear()
paragraph = frame.paragraphs[0]
properties = paragraph._p.get_or_add_pPr()
properties.set("rtl", "1")
properties.set("algn", "r")
xml_child(properties, "a:defRPr", sz=2400)
first = paragraph.add_run()
first.text = "عنوان "
theme_color(first._r.get_or_add_rPr(), "accent1", tint=75000)
second = paragraph.add_run()
second.text = "عربي"
properties = second._r.get_or_add_rPr()
properties.set("b", "0")
properties.set("i", "1")
properties.set("u", "sng")
properties.set("strike", "sngStrike")
properties.set("sz", "3000")
theme_color(properties, "accent2")
xml_child(properties, "a:cs", typeface="+mn-cs")
paragraph = frame.add_paragraph()
properties = paragraph._p.get_or_add_pPr()
properties.set("rtl", "0")
properties.set("algn", "l")
xml_child(xml_child(properties, "a:spcBef"), "a:spcPts", val=600)
xml_child(xml_child(properties, "a:spcAft"), "a:spcPts", val=300)
defaults = xml_child(properties, "a:defRPr")
xml_child(defaults, "a:latin", typeface="+mn-lt")
xml_child(defaults, "a:cs", typeface="+mn-cs")
paragraph.add_run().text = "English "
xml_child(paragraph._p, "a:br")
paragraph.add_run().text = "نص"
field = xml_child(paragraph._p, "a:fld", id="{34DA7095-DFA5-4C81-94B6-51F5E217C28A}", type="slidenum")
xml_child(field, "a:t").text = "٧"
table_shape = slide.shapes.add_table(1, 1, Inches(1), Inches(3), Inches(8), Inches(1))
cell = table_shape.table.cell(0, 0)
cell.text_frame.clear()
properties = cell._tc.get_or_add_tcPr()
theme_color(properties, "accent2", tint=25000)
border_properties = xml_child(properties, "a:lnB", w=int(Pt(2)))
theme_color(border_properties, "accent1", shade=50000)
paragraph = cell.text_frame.paragraphs[0]
defaults = xml_child(paragraph._p.get_or_add_pPr(), "a:defRPr", sz=1600)
xml_child(defaults, "a:latin", typeface="+mn-lt")
xml_child(defaults, "a:cs", typeface="+mn-cs")
theme_color(defaults, "tx1")
paragraph.add_run().text = "قيمة "
run = paragraph.add_run()
run.text = "مهمة"
properties = run._r.get_or_add_rPr()
properties.set("b", "1")
properties.set("sz", "2200")
theme_color(properties, "accent1", shade=50000)
mapping = slide._element.find("{http://schemas.openxmlformats.org/presentationml/2006/main}clrMapOvr")
for child in list(mapping):
    mapping.remove(child)
xml_child(mapping, "a:overrideClrMapping", **{**dict(master._element.find("{http://schemas.openxmlformats.org/presentationml/2006/main}clrMap").attrib), "tx1": "accent2"})
second_slide = presentation.slides.add_slide(layout)
for shape in list(second_slide.shapes):
    if shape._element is not second_slide.shapes.title._element:
        shape._element.getparent().remove(shape._element)
second_slide.shapes.title.text = "Second"
second_slide.background.fill.solid()
second_slide.background.fill.fore_color.rgb = RGBColor.from_string("ABCDEF")
target = root / "app/src/test/resources/fixtures/theme-rich-text.pptx"
presentation.save(target)

with ZipFile(target) as archive:
    parts = {name: archive.read(name) for name in archive.namelist()}
theme = etree.fromstring(parts["ppt/theme/theme1.xml"])
ns = {"a": "http://schemas.openxmlformats.org/drawingml/2006/main"}
palette = {"dk1": "001122", "lt1": "FFFFFF", "accent1": "FF0000", "accent2": "0000FF", "accent3": "808080"}
for name, value in palette.items():
    element = theme.find(f".//a:clrScheme/a:{name}", ns)
    for child in list(element):
        element.remove(child)
    etree.SubElement(element, f"{{{ns['a']}}}srgbClr", val=value)
for group, font in (("majorFont", "serif"), ("minorFont", "monospace")):
    for family in ("latin", "cs"):
        theme.find(f".//a:fontScheme/a:{group}/a:{family}", ns).set("typeface", font)
parts["ppt/theme/theme1.xml"] = etree.tostring(theme, xml_declaration=True, encoding="UTF-8", standalone=True)
override = etree.Element(f"{{{ns['a']}}}themeOverride", nsmap={"a": ns["a"]})
colors = deepcopy(theme.find(".//a:clrScheme", ns))
colors.find("a:accent1/a:srgbClr", ns).set("val", "00FF00")
override.append(colors)
parts["ppt/theme/themeOverride1.xml"] = etree.tostring(override, xml_declaration=True, encoding="UTF-8", standalone=True)
relationships = etree.fromstring(parts["ppt/slides/_rels/slide1.xml.rels"])
etree.SubElement(relationships, "{http://schemas.openxmlformats.org/package/2006/relationships}Relationship", Id="rIdThemeOverride", Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/themeOverride", Target="../theme/themeOverride1.xml")
parts["ppt/slides/_rels/slide1.xml.rels"] = etree.tostring(relationships, xml_declaration=True, encoding="UTF-8", standalone=True)
types = etree.fromstring(parts["[Content_Types].xml"])
etree.SubElement(types, "{http://schemas.openxmlformats.org/package/2006/content-types}Override", PartName="/ppt/theme/themeOverride1.xml", ContentType="application/vnd.openxmlformats-officedocument.themeOverride+xml")
parts["[Content_Types].xml"] = etree.tostring(types, xml_declaration=True, encoding="UTF-8", standalone=True)
with ZipFile(target, "w", ZIP_DEFLATED) as archive:
    for name, content in parts.items():
        archive.writestr(name, content)
print(target)

"""Developer-only independent XLSX fixture generator (openpyxl 3.1.5).
Run: python tools/generate_xlsx_fixtures.py
"""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from xml.etree import ElementTree as ET
from openpyxl import Workbook

root = Path(__file__).resolve().parents[1]
target = root / "app/src/test/resources/fixtures/number-formats-arabic.xlsx"
target.parent.mkdir(parents=True, exist_ok=True)
book = Workbook()
sheet = book.active
sheet.title = "الميزانية"
sheet.sheet_view.rightToLeft = True
examples = [
    (1234.567, "#,##0.00"), (0.125, "0.0%"), (45292.5, "yyyy-mm-dd hh:mm:ss"),
    (-1234.5, '#,##0.00;[Red](#,##0.00)'), (1234.5, '"ج.م "#,##0.00'),
    (1.5, "[h]:mm:ss"), (60, "yyyy-mm-dd"), ("00123", "0.00"),
    (True, "0.00"), ("#DIV/0!", "0.00"), (0.333, "# ?/?"),
    ("=1/4", "0.00%"), ("=SUM(2,3)", "0.00"), (1234, "0.00E+00"),
]
for row, (value, code) in enumerate(examples, 1):
    sheet.cell(row, 1, value).number_format = code
    sheet.cell(row, 2, f"اختبار {row}")
book.save(target)
# A saved formula result, independently of Reader's parser; no formula calculation at runtime.
with ZipFile(target) as archive:
    parts = {name: archive.read(name) for name in archive.namelist()}
ns = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
xml = ET.fromstring(parts["xl/worksheets/sheet1.xml"])
xml.find('.//s:c[@r="A12"]/s:v', ns).text = "0.25"
parts["xl/worksheets/sheet1.xml"] = ET.tostring(xml, encoding="utf-8", xml_declaration=True)
with ZipFile(target, "w", ZIP_DEFLATED) as archive:
    for name, data in parts.items():
        archive.writestr(name, data)
print(target)

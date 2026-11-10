"""Preserve invoice description columns and read every scanned PDF page."""
import re


def invoice_table_text(page, text):
    words = page.get_text("words")
    descriptions = [w for w in words if w[4].lower() == "description"]
    for header in descriptions:
        quantities = [w for w in words if w[4].lower() in ("qty", "quantity")
                      and abs(w[1] - header[1]) < 4 and w[0] > header[0]]
        summaries = [w for w in words if w[4].lower() == "summary" and w[1] > header[1]]
        if not quantities or not summaries:
            continue
        quantity = min(quantities, key=lambda w: w[0])
        bottom = min(w[1] for w in summaries)
        quantity_cells = [w for w in words if header[3] < w[1] < bottom
                          and abs(w[2] - quantity[2]) < 3
                          and re.fullmatch(r"\d+\.\d{2}", w[4])]
        if not quantity_cells:
            continue
        # Header and numeric cells are right-aligned. A two-digit quantity
        # extends left of "Qty" and must not enter the description column.
        right = min(w[0] for w in quantity_cells) - 2
        rows = sorted([w for w in words if re.fullmatch(r"\d+\.", w[4])
                       and w[0] < header[0] and header[3] < w[1] < bottom], key=lambda w: w[1])
        products = []
        for i, row in enumerate(rows):
            end = rows[i + 1][1] - 2 if i + 1 < len(rows) else bottom
            cells = [w for w in words if row[1] - 2 <= w[1] < end]
            description = sorted([w for w in cells if header[0] - 2 <= w[0] < right],
                                 key=lambda w: (round(w[1] / 3), w[0]))
            prices = [w for w in cells if w[0] > right and abs(w[1] - row[1]) < 4
                      and re.fullmatch(r"\d[\d,]*\.\d{2}", w[4])]
            if not description or not prices:
                return text  # Unknown layout: preserve source rather than fabricate rows.
            products.append(" ".join(w[4] for w in description) + " " + max(prices, key=lambda w: w[0])[4])
        if products:
            return re.sub(r"(?ims)^ITEMS\s*\n.*?(?=^SUMMARY\s*$)",
                          lambda _: "ITEMS\n" + "\n".join(products) + "\n", text, count=1)
    return text


def extract_pdf_text(data, read_image):
    import pymupdf
    texts = []
    with pymupdf.open(stream=data, filetype="pdf") as document:
        for page in document:
            text = page.get_text()
            if len(text.strip()) < 10:
                pix = page.get_pixmap(matrix=pymupdf.Matrix(300 / 72, 300 / 72),
                                      colorspace=pymupdf.csRGB)
                text = read_image(pix.tobytes("png"), "scanned-page.png")
            else:
                text = invoice_table_text(page, text)
            texts.append(text)
    return "\n".join(texts)

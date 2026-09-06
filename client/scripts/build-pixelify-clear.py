"""Build the tiny 2/3/5/8/9 override font used by the poker UI.

The cells overlap slightly on every edge. This keeps the approved 5x7
silhouettes intact while giving the custom numerals the same visual mass as
Pixelify Sans in both regular labels and bold chip stacks.
"""

from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen


ASSET_DIR = Path(__file__).resolve().parents[1] / "src" / "assets"
WEIGHTS = {
    # Pixelify Sans 400's zero has roughly 101-unit horizontal and vertical
    # strokes. The 93-unit custom cells reach 105 units with this overlap.
    400: {"x_bold": 6, "y_bold": 6, "x_shift": 0, "y_shift": 0, "advance": 586, "y_min": -12, "y_max": 631},
    # Pixelify Sans 700's zero has ~127-unit side strokes and ~137-unit top/
    # bottom strokes. Keep those two axes optically matched here.
    700: {"x_bold": 17, "y_bold": 22, "x_shift": 9, "y_shift": 4, "advance": 603, "y_min": -11, "y_max": 638},
}

# Dense silhouettes need less geometric expansion to look equally heavy.
# Eight is the visual reference; two gets a small correction and five a
# stronger one because its long top/middle bars accumulate more apparent mass.
GLYPH_WEIGHT_SCALE = {
    "two": {"x": 0.76, "y": 0.88},
    "three": {"x": 1.0, "y": 1.0},
    "five": {"x": 0.72, "y": 0.72},
    "eight": {"x": 1.0, "y": 1.0},
    "nine": {"x": 1.0, "y": 1.0},
}

# Three, eight and nine share one double-bowl silhouette, so the only thing
# telling them apart is where the bowl is left open. Nine's lower-left spur
# closes that gap almost as tightly as eight's full left column, so shorten it
# from the top; the trim stops short of a whole cell because the spur's bottom
# corner is what joins it to the base bar. Trims are font units taken off one
# cell after the weight expansion, so both weights keep the same silhouette.
CELL_EDGE_TRIMS: dict[tuple[str, int, int], dict[str, int]] = {
    ("nine", 5, 0): {"top": 60},
}

# A partial-cell terminal shift echoes 7's stepped curve without the abrupt
# full-cell hook: upward at 2's baseline, downward at 5's cap line.
CURVED_TERMINALS = {
    ("two", 6, 4): "up",
    ("five", 0, 4): "down",
}

# Five's middle bar sits slightly above the mathematical center on the 5x7
# grid. Lower it by roughly one-third of a cell for a better optical center.
ROW_Y_ADJUSTMENTS = {
    ("five", 2): -48,
}

ROW_Y_WEIGHT_SCALE = {
    ("five", 2): 0.75,
}

ROW_HEIGHT_SCALE = {
    ("five", 2): 0.8,
}

PATTERNS = {
    "two": (
        ".XXX.",
        "X...X",
        "....X",
        "..XX.",
        ".X...",
        "X....",
        "XXXXX",
    ),
    # Three's middle bar is a cell shorter than eight's on the open side, so
    # the left gap reads as a gap rather than as a dropped pixel.
    "three": (
        ".XXX.",
        "X...X",
        "....X",
        "..XX.",
        "....X",
        "X...X",
        ".XXX.",
    ),
    "five": (
        "XXXXX",
        "X....",
        "XXXX.",
        "....X",
        "....X",
        "X...X",
        ".XXX.",
    ),
    "eight": (
        ".XXX.",
        "X...X",
        "X...X",
        ".XXX.",
        "X...X",
        "X...X",
        ".XXX.",
    ),
    "nine": (
        ".XXX.",
        "X...X",
        "X...X",
        ".XXXX",
        "....X",
        "X...X",
        ".XXX.",
    ),
}

X_EDGES = (60, 153, 246, 339, 432, 525)
Y_EDGES = (-12, 80, 172, 264, 355, 447, 539, 631)


def build_glyph(
    glyph_name: str,
    pattern: tuple[str, ...],
    *,
    x_bold: int,
    y_bold: int,
    x_shift: int,
    y_shift: int,
    y_min: int | None = None,
    y_max: int | None = None,
):
    pen = TTGlyphPen(None)
    for top_row, row in enumerate(pattern):
        bottom_row = 6 - top_row
        row_adjustment = ROW_Y_ADJUSTMENTS.get((glyph_name, top_row), 0)
        row_y_bold = round(y_bold * ROW_Y_WEIGHT_SCALE.get((glyph_name, top_row), 1.0))
        y0 = Y_EDGES[bottom_row] + y_shift + row_adjustment
        y1 = Y_EDGES[bottom_row + 1] + y_shift + row_adjustment
        for column, cell in enumerate(row):
            if cell != "X":
                continue
            x0 = X_EDGES[column] + x_shift
            x1 = X_EDGES[column + 1] + x_shift
            drawn_y0 = max(y0 - row_y_bold, y_min) if y_min is not None else y0 - row_y_bold
            drawn_y1 = min(y1 + row_y_bold, y_max) if y_max is not None else y1 + row_y_bold
            row_height_scale = ROW_HEIGHT_SCALE.get((glyph_name, top_row), 1.0)
            if row_height_scale != 1.0:
                row_center = (drawn_y0 + drawn_y1) / 2
                half_height = round((drawn_y1 - drawn_y0) * row_height_scale / 2)
                drawn_y0 = round(row_center - half_height)
                drawn_y1 = round(row_center + half_height)
            drawn_x0 = x0 - x_bold
            drawn_x1 = x1 + x_bold
            trims = CELL_EDGE_TRIMS.get((glyph_name, top_row, column), {})
            drawn_x0 += trims.get("left", 0)
            drawn_x1 -= trims.get("right", 0)
            drawn_y0 += trims.get("bottom", 0)
            drawn_y1 -= trims.get("top", 0)
            terminal = CURVED_TERMINALS.get((glyph_name, top_row, column))
            curve = round((drawn_y1 - drawn_y0) * 0.3)
            corner_cut = round(min(drawn_x1 - drawn_x0, drawn_y1 - drawn_y0) * 0.15)

            above = top_row > 0 and pattern[top_row - 1][column] == "X"
            below = top_row < len(pattern) - 1 and pattern[top_row + 1][column] == "X"
            left = column > 0 and row[column - 1] == "X"
            right = column < len(row) - 1 and row[column + 1] == "X"
            cut_tl = corner_cut if glyph_name in {"two", "five"} and not above and not left else 0
            cut_tr = corner_cut if glyph_name in {"two", "five"} and not above and not right else 0
            cut_bl = corner_cut if glyph_name in {"two", "five"} and not below and not left else 0
            cut_br = corner_cut if glyph_name in {"two", "five"} and not below and not right else 0

            points = [
                (drawn_x0 + cut_bl, drawn_y0),
                (drawn_x0 + cut_bl, drawn_y0 + cut_bl),
                (drawn_x0, drawn_y0 + cut_bl),
                (drawn_x0, drawn_y1 - cut_tl),
                (drawn_x0 + cut_tl, drawn_y1 - cut_tl),
                (drawn_x0 + cut_tl, drawn_y1),
            ]
            if terminal == "up":
                points.extend([
                    (drawn_x1 - curve, drawn_y1),
                    (drawn_x1 - curve, drawn_y1 + curve),
                    (drawn_x1 - cut_tr, drawn_y1 + curve),
                    (drawn_x1 - cut_tr, drawn_y1 + curve - cut_tr),
                    (drawn_x1, drawn_y1 + curve - cut_tr),
                    (drawn_x1, drawn_y0 + curve + cut_br),
                    (drawn_x1 - cut_br, drawn_y0 + curve + cut_br),
                    (drawn_x1 - cut_br, drawn_y0 + curve),
                    (drawn_x1 - curve, drawn_y0 + curve),
                    (drawn_x1 - curve, drawn_y0),
                ])
            elif terminal == "down":
                points.extend([
                    (drawn_x1 - curve, drawn_y1),
                    (drawn_x1 - curve, drawn_y1 - curve),
                    (drawn_x1 - cut_tr, drawn_y1 - curve),
                    (drawn_x1 - cut_tr, drawn_y1 - curve - cut_tr),
                    (drawn_x1, drawn_y1 - curve - cut_tr),
                    (drawn_x1, drawn_y0 - curve + cut_br),
                    (drawn_x1 - cut_br, drawn_y0 - curve + cut_br),
                    (drawn_x1 - cut_br, drawn_y0 - curve),
                    (drawn_x1 - curve, drawn_y0 - curve),
                    (drawn_x1 - curve, drawn_y0),
                ])
            else:
                points.extend([
                    (drawn_x1 - cut_tr, drawn_y1),
                    (drawn_x1 - cut_tr, drawn_y1 - cut_tr),
                    (drawn_x1, drawn_y1 - cut_tr),
                    (drawn_x1, drawn_y0 + cut_br),
                    (drawn_x1 - cut_br, drawn_y0 + cut_br),
                    (drawn_x1 - cut_br, drawn_y0),
                ])

            pen.moveTo(points[0])
            for point in points[1:]:
                pen.lineTo(point)
            pen.closePath()
    return pen.glyph()


def build_weight(weight: int, settings: dict[str, int]) -> None:
    glyph_order = [".notdef", "two", "three", "five", "eight", "nine"]
    glyphs = {".notdef": TTGlyphPen(None).glyph()}
    glyphs.update(
        {
            name: build_glyph(
                name,
                pattern,
                x_bold=round(settings["x_bold"] * GLYPH_WEIGHT_SCALE[name]["x"]),
                y_bold=round(settings["y_bold"] * GLYPH_WEIGHT_SCALE[name]["y"]),
                x_shift=settings["x_shift"],
                y_shift=settings["y_shift"],
                y_min=settings["y_min"],
                y_max=settings["y_max"],
            )
            for name, pattern in PATTERNS.items()
        }
    )

    font = FontBuilder(1000, isTTF=True)
    font.setupGlyphOrder(glyph_order)
    font.setupCharacterMap({
        ord("2"): "two",
        ord("3"): "three",
        ord("5"): "five",
        ord("8"): "eight",
        ord("9"): "nine",
    })
    font.setupGlyf(glyphs)
    left_side_bearing = 60 + settings["x_shift"] - settings["x_bold"]
    font.setupHorizontalMetrics({
        name: (settings["advance"], 0 if name == ".notdef" else left_side_bearing)
        for name in glyph_order
    })
    font.setupHorizontalHeader(ascent=920, descent=-280)
    font.setupOS2(
        sTypoAscender=920,
        sTypoDescender=-280,
        sTypoLineGap=0,
        usWinAscent=923,
        usWinDescent=289,
        sxHeight=450,
        sCapHeight=700,
        usWeightClass=weight,
        fsType=4,
    )
    style_name = "Regular" if weight == 400 else "Bold"
    font.setupNameTable({"familyName": "PixelifyClear", "styleName": style_name})
    font.setupPost()
    font.setupMaxp()
    font.font["head"].lowestRecPPEM = 3
    font.font.flavor = "woff2"

    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    filename = "pixelify-clear.woff2" if weight == 400 else f"pixelify-clear-{weight}.woff2"
    font.save(ASSET_DIR / filename)


def main() -> None:
    for weight, settings in WEIGHTS.items():
        build_weight(weight, settings)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate and validate the AgentsFlex Skills Runtime Report deck."""

import os
import shutil
import sys
import zipfile

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.util import Inches, Pt


NAVY = RGBColor(27, 42, 74)
BLUE = RGBColor(59, 130, 246)
CYAN = RGBColor(14, 165, 233)
GREEN = RGBColor(22, 163, 74)
INK = RGBColor(31, 41, 55)
MUTED = RGBColor(100, 116, 139)
LIGHT = RGBColor(241, 245, 249)
WHITE = RGBColor(255, 255, 255)


def add_text(slide, text, x, y, w, h, size=18, color=INK, bold=False,
             align=PP_ALIGN.LEFT, font="Aptos"):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    frame = box.text_frame
    frame.clear()
    frame.word_wrap = True
    frame.vertical_anchor = MSO_ANCHOR.MIDDLE
    paragraph = frame.paragraphs[0]
    paragraph.alignment = align
    run = paragraph.add_run()
    run.text = text
    run.font.name = font
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    return box


def add_title(slide, title, number):
    add_text(slide, title, 0.7, 0.32, 11.3, 0.55, 35, NAVY, True)
    add_text(slide, "%02d" % number, 12.0, 0.32, 0.6, 0.45, 16, BLUE, True,
             PP_ALIGN.RIGHT)
    line = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.7), Inches(0.98),
                                  Inches(11.9), Inches(0.035))
    line.fill.solid()
    line.fill.fore_color.rgb = BLUE
    line.line.fill.background()


def add_footer(slide):
    add_text(slide, "AgentsFlex Skills Demo", 0.7, 7.05, 4.0, 0.2, 10, MUTED)
    add_text(slide, "Discover  Prepare  Execute  Verify  Deliver", 7.0, 7.05,
             5.6, 0.2, 10, MUTED, False, PP_ALIGN.RIGHT)


def add_panel(slide, x, y, w, h, title, body, accent):
    panel = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y),
                                   Inches(w), Inches(h))
    panel.fill.solid()
    panel.fill.fore_color.rgb = LIGHT
    panel.line.color.rgb = RGBColor(203, 213, 225)
    stripe = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y),
                                    Inches(0.08), Inches(h))
    stripe.fill.solid()
    stripe.fill.fore_color.rgb = accent
    stripe.line.fill.background()
    add_text(slide, title, x + 0.25, y + 0.15, w - 0.4, 0.4, 20, NAVY, True)
    add_text(slide, body, x + 0.25, y + 0.58, w - 0.45, h - 0.72, 16, INK)


def build_deck(output_path):
    deck = Presentation()
    deck.slide_width = Inches(13.333)
    deck.slide_height = Inches(7.5)
    deck.core_properties.title = "AgentsFlex Skills Runtime Report"
    deck.core_properties.author = "AgentsFlex Skills Demo"
    deck.core_properties.subject = "Skill runtime verification"

    blank = deck.slide_layouts[6]

    slide = deck.slides.add_slide(blank)
    background = slide.background.fill
    background.solid()
    background.fore_color.rgb = NAVY
    accent = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.75), Inches(1.1),
                                    Inches(0.12), Inches(4.9))
    accent.fill.solid()
    accent.fill.fore_color.rgb = CYAN
    accent.line.fill.background()
    add_text(slide, "AgentsFlex Skills\nRuntime Report", 1.2, 1.45, 10.8, 1.7,
             50, WHITE, True)
    add_text(slide, "Hello world from isolated skill execution", 1.2, 3.35,
             10.2, 0.55, 24, RGBColor(186, 230, 253))
    add_text(slide, "One contract. Multiple execution boundaries. Verifiable artifacts.",
             1.2, 4.25, 10.3, 0.6, 18, WHITE)
    add_text(slide, "AgentsFlex Skills Demo", 1.2, 6.45, 4.0, 0.3, 12,
             RGBColor(148, 163, 184))

    slide = deck.slides.add_slide(blank)
    add_title(slide, "SkillRuntime creates one execution contract", 2)
    add_panel(slide, 0.8, 1.35, 3.75, 4.75, "Discover",
              "Load Skill metadata and resources from configured directories.", BLUE)
    add_panel(slide, 4.8, 1.35, 3.75, 4.75, "Prepare",
              "Map resources into the selected runtime and expose runtime-backed tools.", CYAN)
    add_panel(slide, 8.8, 1.35, 3.75, 4.75, "Execute",
              "Run commands and file operations inside Local, OpenSandbox, or AIO Sandbox.", GREEN)
    add_footer(slide)

    slide = deck.slides.add_slide(blank)
    add_title(slide, "Runtime choice changes the security boundary", 3)
    rows, cols = 5, 4
    table_shape = slide.shapes.add_table(rows, cols, Inches(0.75), Inches(1.35),
                                         Inches(11.85), Inches(4.9))
    table = table_shape.table
    widths = [2.25, 3.2, 3.2, 3.2]
    for index, width in enumerate(widths):
        table.columns[index].width = Inches(width)
    values = [
        ["Dimension", "LocalSkillRuntime", "OpenSandbox", "AIO Sandbox"],
        ["Execution", "Host process", "Managed sandbox", "AIO service/container"],
        ["Preparation", "Direct local paths", "SDK file upload", "HTTP file upload"],
        ["Lifecycle", "Application-owned", "Runtime-owned sandbox", "Shared external service"],
        ["Boundary", "Host permissions", "Sandbox isolation", "Container/API isolation"],
    ]
    for row in range(rows):
        for col in range(cols):
            cell = table.cell(row, col)
            cell.text = values[row][col]
            cell.margin_left = Inches(0.1)
            cell.margin_right = Inches(0.1)
            cell.margin_top = Inches(0.08)
            cell.margin_bottom = Inches(0.08)
            cell.fill.solid()
            cell.fill.fore_color.rgb = NAVY if row == 0 else (LIGHT if row % 2 else WHITE)
            for paragraph in cell.text_frame.paragraphs:
                paragraph.alignment = PP_ALIGN.CENTER if row == 0 else PP_ALIGN.LEFT
                for run in paragraph.runs:
                    run.font.name = "Aptos"
                    run.font.size = Pt(16)
                    run.font.bold = row == 0 or col == 0
                    run.font.color.rgb = WHITE if row == 0 else INK
    add_footer(slide)

    slide = deck.slides.add_slide(blank)
    add_title(slide, "Every artifact follows the same five-step path", 4)
    steps = ["Discover", "Prepare", "Execute", "Verify", "Deliver"]
    details = ["Find the Skill", "Upload resources", "Run in runtime",
               "Inspect output", "Download artifact"]
    colors = [BLUE, CYAN, GREEN, RGBColor(234, 88, 12), RGBColor(147, 51, 234)]
    for index, step in enumerate(steps):
        x = 0.75 + index * 2.45
        if index < len(steps) - 1:
            arrow = slide.shapes.add_shape(MSO_SHAPE.RIGHT_ARROW, Inches(x + 1.85),
                                           Inches(2.3), Inches(0.65), Inches(0.55))
            arrow.fill.solid()
            arrow.fill.fore_color.rgb = RGBColor(203, 213, 225)
            arrow.line.fill.background()
        node = slide.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x), Inches(1.75),
                                      Inches(1.85), Inches(1.65))
        node.fill.solid()
        node.fill.fore_color.rgb = colors[index]
        node.line.fill.background()
        add_text(slide, str(index + 1), x + 0.55, 1.9, 0.75, 0.45, 24,
                 WHITE, True, PP_ALIGN.CENTER)
        add_text(slide, step, x - 0.1, 3.65, 2.05, 0.45, 18, NAVY, True,
                 PP_ALIGN.CENTER)
        add_text(slide, details[index], x - 0.2, 4.2, 2.25, 0.65, 16, MUTED,
                 False, PP_ALIGN.CENTER)
    add_footer(slide)

    slide = deck.slides.add_slide(blank)
    add_title(slide, "Verification makes the artifact deliverable", 5)
    checks = [
        "PPTX is a valid Office Open XML package",
        "Exactly five widescreen slides are present",
        "Title, author, and subject metadata are correct",
        "Required runtime names and workflow terms are extractable",
        "Remote artifact can be downloaded to the host",
    ]
    for index, check in enumerate(checks):
        y = 1.35 + index * 0.92
        marker = slide.shapes.add_shape(MSO_SHAPE.OVAL, Inches(0.95), Inches(y),
                                        Inches(0.48), Inches(0.48))
        marker.fill.solid()
        marker.fill.fore_color.rgb = GREEN
        marker.line.fill.background()
        add_text(slide, str(index + 1), 1.0, y + 0.01, 0.38, 0.38, 13,
                 WHITE, True, PP_ALIGN.CENTER)
        add_text(slide, check, 1.65, y - 0.02, 10.5, 0.55, 18, INK)
    add_text(slide, "Verified output is not the end of the runtime flow. Delivery is.",
             0.95, 6.15, 11.2, 0.5, 20, BLUE, True, PP_ALIGN.CENTER)
    add_footer(slide)

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    deck.save(output_path)


def validate(output_path):
    if not zipfile.is_zipfile(output_path):
        raise RuntimeError("Output is not a valid PPTX package")
    deck = Presentation(output_path)
    if len(deck.slides) != 5:
        raise RuntimeError("Expected 5 slides, got %d" % len(deck.slides))
    properties = deck.core_properties
    expected = ("AgentsFlex Skills Runtime Report", "AgentsFlex Skills Demo",
                "Skill runtime verification")
    actual = (properties.title, properties.author, properties.subject)
    if actual != expected:
        raise RuntimeError("Unexpected metadata: %r" % (actual,))
    text_parts = []
    for slide in deck.slides:
        for shape in slide.shapes:
            if getattr(shape, "has_text_frame", False):
                text_parts.append(shape.text)
            if getattr(shape, "has_table", False):
                for row in shape.table.rows:
                    text_parts.extend(cell.text for cell in row.cells)
    text = "\n".join(text_parts)
    required = ["Hello world", "LocalSkillRuntime", "OpenSandbox",
                "AIO Sandbox", "Discover", "Deliver"]
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError("Missing text: %s" % ", ".join(missing))
    if os.path.getsize(output_path) < 10 * 1024:
        raise RuntimeError("PPTX is unexpectedly small")
    print("Validated: %s" % output_path)
    print("Slides: 5")
    print("Bytes: %d" % os.path.getsize(output_path))
    print("Metadata: %s | %s | %s" % actual)
    print("Text checks: passed")


def main():
    if len(sys.argv) != 2 or not sys.argv[1].lower().endswith(".pptx"):
        raise SystemExit("Usage: create_runtime_report.py /absolute/output/report.pptx")
    output_path = os.path.abspath(sys.argv[1])
    build_deck(output_path)
    validate(output_path)
    script_copy = os.path.join(os.path.dirname(output_path), "generate_presentation.py")
    if os.path.abspath(__file__) != os.path.abspath(script_copy):
        shutil.copyfile(__file__, script_copy)
    print("Generator: %s" % script_copy)


if __name__ == "__main__":
    main()

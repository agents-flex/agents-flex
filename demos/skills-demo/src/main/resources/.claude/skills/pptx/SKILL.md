---
name: pptx
description: Create and validate Microsoft PowerPoint presentations (.pptx), including structured slide decks, tables, workflows, metadata, and reproducible generation scripts. Use for presentation, slides, PowerPoint, PPT, or PPTX creation and verification tasks.
---

# PPTX Creation

Create PowerPoint files with `python-pptx`. Keep the generation script beside the output presentation so the result is reproducible.

## Runtime Report

For the AgentsFlex Skills Runtime Report, run the bundled deterministic generator:

```bash
python3 -m pip install python-pptx
python3 <skill-base-directory>/scripts/create_runtime_report.py /absolute/output/report.pptx
```

The script creates and validates a five-slide widescreen deck, sets the required metadata, and copies itself beside the output as `generate_presentation.py`.

## General Workflow

1. Use an absolute `.pptx` output path outside this Skill directory.
2. Prefer a concise narrative with one clear purpose per slide.
3. Use native PowerPoint text, tables, and shapes so content remains editable.
4. Use at least 32 pt for slide titles and 16 pt for body text.
5. Set title, author, and subject metadata.
6. Reopen the generated file with `python-pptx` and verify slide count, expected text, metadata, and file size.
7. Treat any validation failure as incomplete work; fix the script and rerun it.

Do not modify files inside the Skill directory.

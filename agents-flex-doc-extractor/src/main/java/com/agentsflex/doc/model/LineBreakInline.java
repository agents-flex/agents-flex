package com.agentsflex.doc.model;

/**
 * 显式行内换行。
 */
public final class LineBreakInline implements InlineContent {
    /**
     * {@inheritDoc}
     */
    @Override
    public String getPlainText() {
        return "\n";
    }
}

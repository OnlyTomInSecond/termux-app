package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.util.SparseArray;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    /** Cap for {@link #mCodePointWidthCache} to bound memory in unicode-dump workloads. */
    private static final int MAX_CACHED_CODE_POINT_WIDTHS = 16384;


    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();
    /**
     * Dedicated paint used only for {@link Paint#measureText} of run widths. It is kept free of
     * the effect state (fake bold, skew, underline, ...) that {@link #drawTextRun} sets on
     * {@link #mTextPaint}, so measurements are deterministic and independent of the draw order.
     */
    private final Paint mMeasurePaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    /**
     * Bounded cache of measured width for non-ASCII code points (T2.2). Paint.measureText() on
     * every non-ASCII code point every redraw is a dominant native cost in CJK-heavy workloads;
     * widths only depend on the (fixed) typeface/size of this renderer instance, so caching by
     * code point removes almost all native measure calls after the first use. The cache is
     * capped and reset when full to bound memory for unicode-dump workloads. SparseArray keeps
     * lookups free of Integer boxing.
     */
    private final SparseArray<Float> mCodePointWidthCache = new SparseArray<>();

    /** One draw run of a row. Mutable and reused through {@link RunBuffer}. */
    private static final class RowRun {
        int startColumn;
        int widthColumns;
        int startCharIndex;
        int chars;
        long style;
        float measuredWidth;
        boolean insideCursor;
        boolean insideSelection;
    }

    /** Growable, reusable list of {@link RowRun}s. */
    private static final class RunBuffer {
        RowRun[] runs = new RowRun[16];
        int count;

        void reset() {
            count = 0;
        }

        RowRun add() {
            if (count == runs.length) {
                final RowRun[] grown = new RowRun[count * 2];
                System.arraycopy(runs, 0, grown, 0, count);
                runs = grown;
            }
            RowRun run = runs[count];
            if (run == null) {
                run = new RowRun();
                runs[count] = run;
            }
            count++;
            return run;
        }
    }

    /** Cached runs of one visual row, valid while {@link #version} matches the row's render version. */
    private static final class RowCache {
        TerminalRow row;
        int version = -1;
        final RunBuffer runs = new RunBuffer();
    }

    /** Per-visual-row run cache, indexed by the visual row passed to {@link #render}. */
    private RowCache[] mRowCaches = new RowCache[0];
    /** Reused list for rows that cannot be cached (rows with the cursor or a selection). */
    private final RunBuffer mScratchRuns = new RunBuffer();

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mMeasurePaint.setTypeface(typeface);
        mMeasurePaint.setAntiAlias(true);
        mMeasurePaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /**
     * Render a range of terminal rows to a canvas.
     *
     * @param topRow the top row of the display (0 or negative when scrolled into history)
     * @param firstRow 0-based index of the first visual row to draw
     * @param rowCount number of visual rows to draw. Callers only repaint rows whose content
     *                 actually changed (damage-list rendering, see TerminalView), so rows not
     *                 in this range keep their previously drawn pixels.
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow, int firstRow, int rowCount,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        // The pixel position of visual row v is independent of topRow: the original incremental
        // heightOffset equals mFontLineSpacingAndAscent + (v + 1) * mFontLineSpacing after the
        // rows above it have been drawn, which is what this formula computes directly.
        for (int v = firstRow; v < firstRow + rowCount; v++) {
            final float heightOffset = mFontLineSpacingAndAscent + (v + 1) * mFontLineSpacing;
            final int row = topRow + v;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final boolean cacheable = cursorX == -1 && selx1 == -1 && selx2 == -1;

            // A row that is known to draw nothing (only spaces with the default style) can be
            // skipped: drawing spaces is a no-op, so leaving its previously drawn pixels intact is
            // equivalent. Rows with the cursor or a selection are never skipped.
            if (cacheable && lineObject.isBlankForRender()) continue;

            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            final RunBuffer runs;
            if (cacheable) {
                // The run boundaries of this row do not depend on the cursor/selection, so they can
                // be reused across frames until the row content/style changes (render version bump).
                final RowCache cache = rowCache(v);
                if (cache.row == lineObject && cache.version == lineObject.getRenderVersion()) {
                    runs = cache.runs;
                } else {
                    cache.runs.reset();
                    computeRuns(cache.runs, lineObject, line, charsUsedInLine, columns, -1, -1, -1);
                    cache.row = lineObject;
                    cache.version = lineObject.getRenderVersion();
                    runs = cache.runs;
                }
            } else {
                mScratchRuns.reset();
                computeRuns(mScratchRuns, lineObject, line, charsUsedInLine, columns, cursorX, selx1, selx2);
                runs = mScratchRuns;
            }

            drawRuns(canvas, line, palette, heightOffset, runs, cursorShape, reverseVideo);
        }
    }

    private RowCache rowCache(int v) {
        if (v >= mRowCaches.length) {
            final int newLength = Math.max(v + 1, 16);
            final RowCache[] grown = new RowCache[newLength];
            System.arraycopy(mRowCaches, 0, grown, 0, mRowCaches.length);
            mRowCaches = grown;
        }
        RowCache cache = mRowCaches[v];
        if (cache == null) {
            cache = new RowCache();
            mRowCaches[v] = cache;
        }
        return cache;
    }

    /**
     * Build the draw runs of one row without drawing them. The run boundaries depend only on the
     * row content/style plus the cursor/selection passed in, so the result for a row without a
     * cursor or selection can be cached until the row is mutated again.
     */
    private void computeRuns(RunBuffer out, TerminalRow lineObject, char[] line, int charsUsedInLine, int columns,
                             int cursorX, int selx1, int selx2) {
        // A row without wide/surrogate chars has exactly one java char and one cell per column, so
        // the char index equals the column and no wcwidth lookup or combining-char handling is
        // needed. This much tighter loop covers the common ASCII/colored-log case.
        if (!lineObject.hasNonOneWidthOrSurrogateChars()) {
            computeRunsSingleWidth(out, lineObject, line, columns, cursorX, selx1, selx2);
        } else {
            computeRunsGeneral(out, lineObject, line, charsUsedInLine, columns, cursorX, selx1, selx2);
        }
    }

    /**
     * Run computation for rows where every cell is exactly one BMP char of display width 1. When
     * the row is also known to have a single style (common for a prompt/output line), the style is
     * read once instead of per column, so a uniform row usually yields a single draw run.
     */
    private void computeRunsSingleWidth(RunBuffer out, TerminalRow lineObject, char[] line, int columns,
                                        int cursorX, int selx1, int selx2) {
        final boolean uniformStyle = lineObject.hasUniformStyle();
        final long uniformStyleValue = uniformStyle ? lineObject.getUniformStyle() : 0L;

        long lastRunStyle = 0;
        boolean lastRunInsideCursor = false;
        boolean lastRunInsideSelection = false;
        int lastRunStartColumn = -1;
        boolean lastRunFontWidthMismatch = false;
        float measuredWidthForRun = 0.f;

        for (int column = 0; column < columns; column++) {
            final int codePoint = line[column];
            final boolean insideCursor = (cursorX == column);
            final boolean insideSelection = column >= selx1 && column <= selx2;
            final long style = uniformStyle ? uniformStyleValue : lineObject.getStyle(column);
            final float measuredCodePointWidth = measureCodePointWidth(codePoint, line, column, 1);
            final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - 1) > 0.01;

            if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                if (column != 0) {
                    addRun(out, lastRunStartColumn, column - lastRunStartColumn, lastRunStartColumn, column - lastRunStartColumn,
                        measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
                }
                measuredWidthForRun = 0.f;
                lastRunStyle = style;
                lastRunInsideCursor = insideCursor;
                lastRunInsideSelection = insideSelection;
                lastRunStartColumn = column;
                lastRunFontWidthMismatch = fontWidthMismatch;
            }
            measuredWidthForRun += measuredCodePointWidth;
        }

        addRun(out, lastRunStartColumn, columns - lastRunStartColumn, lastRunStartColumn, columns - lastRunStartColumn,
            measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
    }

    private void computeRunsGeneral(RunBuffer out, TerminalRow lineObject, char[] line, int charsUsedInLine, int columns,
                                    int cursorX, int selx1, int selx2) {
        long lastRunStyle = 0;
        boolean lastRunInsideCursor = false;
        boolean lastRunInsideSelection = false;
        int lastRunStartColumn = -1;
        int lastRunStartIndex = 0;
        boolean lastRunFontWidthMismatch = false;
        int currentCharIndex = 0;
        float measuredWidthForRun = 0.f;

        for (int column = 0; column < columns; ) {
            final char charAtIndex = line[currentCharIndex];
            final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
            final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
            final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
            final int codePointWcWidth = WcWidth.width(codePoint);
            final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
            final boolean insideSelection = column >= selx1 && column <= selx2;
            final long style = lineObject.getStyle(column);

            // Check if the measured text width for this code point is not the same as that expected by wcwidth().
            // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
            // smileys which android font renders as wide.
            // If this is detected, we draw this code point scaled to match what wcwidth() expects.
            final float measuredCodePointWidth = measureCodePointWidth(codePoint, line, currentCharIndex, charsForCodePoint);
            final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

            if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                if (column == 0) {
                    // Skip first column as there is nothing to draw, just record the current style.
                } else {
                    addRun(out, lastRunStartColumn, column - lastRunStartColumn, lastRunStartIndex, currentCharIndex - lastRunStartIndex,
                        measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
                }
                measuredWidthForRun = 0.f;
                lastRunStyle = style;
                lastRunInsideCursor = insideCursor;
                lastRunInsideSelection = insideSelection;
                lastRunStartColumn = column;
                lastRunStartIndex = currentCharIndex;
                lastRunFontWidthMismatch = fontWidthMismatch;
            }
            measuredWidthForRun += measuredCodePointWidth;
            column += codePointWcWidth;
            currentCharIndex += charsForCodePoint;
            while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                // Eat combining chars so that they are treated as part of the last non-combining code point,
                // instead of e.g. being considered inside the cursor in the next run.
                currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
            }
        }

        addRun(out, lastRunStartColumn, columns - lastRunStartColumn, lastRunStartIndex, currentCharIndex - lastRunStartIndex,
            measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
    }

    /**
     * The measured (font) width of a code point, using the ASCII table when possible and a bounded
     * per-code-point cache for the native {@link Paint#measureText} result otherwise.
     */
    private float measureCodePointWidth(int codePoint, char[] line, int charIndex, int charsForCodePoint) {
        if (codePoint < asciiMeasures.length) return asciiMeasures[codePoint];
        final Float cachedWidth = mCodePointWidthCache.get(codePoint);
        if (cachedWidth != null) return cachedWidth;
        final float measuredCodePointWidth = mMeasurePaint.measureText(line, charIndex, charsForCodePoint);
        if (mCodePointWidthCache.size() >= MAX_CACHED_CODE_POINT_WIDTHS) {
            // Bound memory: reset when full instead of an LRU (keep it simple).
            mCodePointWidthCache.clear();
        }
        mCodePointWidthCache.put(codePoint, measuredCodePointWidth);
        return measuredCodePointWidth;
    }

    private static void addRun(RunBuffer out, int startColumn, int widthColumns, int startCharIndex, int chars,
                               float measuredWidth, long style, boolean insideCursor, boolean insideSelection) {
        final RowRun run = out.add();
        run.startColumn = startColumn;
        run.widthColumns = widthColumns;
        run.startCharIndex = startCharIndex;
        run.chars = chars;
        run.measuredWidth = measuredWidth;
        run.style = style;
        run.insideCursor = insideCursor;
        run.insideSelection = insideSelection;
    }

    private void drawRuns(Canvas canvas, char[] line, int[] palette, float heightOffset, RunBuffer runs,
                          int cursorShape, boolean reverseVideo) {
        for (int i = 0; i < runs.count; i++) {
            final RowRun run = runs.runs[i];
            final int cursorColor = run.insideCursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0;
            final boolean invertCursorTextColor = run.insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
            drawTextRun(canvas, line, palette, heightOffset, run.startColumn, run.widthColumns, run.startCharIndex, run.chars,
                run.measuredWidth, cursorColor, cursorShape, run.style, reverseVideo || invertCursorTextColor || run.insideSelection);
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}

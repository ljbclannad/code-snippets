package com.lx.governance.aichat.stream;

/**
 * Normalize streaming markdown so downstream renderers only receive segments
 * that are unlikely to break formatting across chunk boundaries.
 */
public class MarkdownStreamNormalizer {

    private final StringBuilder pending = new StringBuilder();

    public String onChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }

        pending.append(chunk);
        ParseState state = analyze(pending, false);
        int safeFlushPosition = state.lastSafePosition();
        if (safeFlushPosition <= 0) {
            return autoCloseBoldIfNeeded(state);
        }

        String normalized = pending.substring(0, safeFlushPosition);
        pending.delete(0, safeFlushPosition);
        return normalized;
    }

    public String finish() {
        if (pending.isEmpty()) {
            return "";
        }

        ParseState state = analyze(pending, true);
        StringBuilder finalized = new StringBuilder(pending);

        if (state.inCodeFence()) {
            finalized.append("\n```");
        }
        if (state.linkUrlOpen()) {
            finalized.append(')');
        }
        if (state.linkTextDepth() > 0) {
            finalized.append("]".repeat(state.linkTextDepth()));
        }
        if (state.inInlineCode()) {
            finalized.append('`');
        }
        if (state.inBold()) {
            finalized.append("**");
        }

        pending.setLength(0);
        return finalized.toString();
    }

    private String autoCloseBoldIfNeeded(ParseState state) {
        if (!state.inBold() || state.boldAutoClosePosition() <= 0) {
            return "";
        }

        int flushPosition = state.boldAutoClosePosition();
        String normalized = pending.substring(0, flushPosition) + "**";
        pending.delete(0, flushPosition);
        return normalized;
    }

    private ParseState analyze(CharSequence text, boolean endOfInput) {
        boolean inBold = false;
        boolean inInlineCode = false;
        boolean inCodeFence = false;
        boolean linkUrlOpen = false;
        int linkTextDepth = 0;
        int lastSafePosition = 0;
        int boldAutoClosePosition = 0;

        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);

            if (current == '`') {
                int runLength = countRun(text, index, '`');
                if (!endOfInput && index + runLength == text.length()) {
                    break;
                }

                if (!inInlineCode && runLength >= 3) {
                    if (((runLength / 3) & 1) == 1) {
                        inCodeFence = !inCodeFence;
                    }
                } else if (!inCodeFence && runLength == 1) {
                    inInlineCode = !inInlineCode;
                }

                index += runLength;
                if (isSafe(inBold, inInlineCode, inCodeFence, linkTextDepth, linkUrlOpen)) {
                    lastSafePosition = index;
                }
                continue;
            }

            if (inCodeFence) {
                index++;
                continue;
            }

            if (current == '*') {
                int runLength = countRun(text, index, '*');
                if (!endOfInput && index + runLength == text.length()) {
                    break;
                }

                if (runLength >= 2) {
                    if (((runLength / 2) & 1) == 1) {
                        inBold = !inBold;
                    }
                }

                index += runLength;
                if (isSafe(inBold, inInlineCode, inCodeFence, linkTextDepth, linkUrlOpen)) {
                    lastSafePosition = index;
                }
                continue;
            }

            if (!inInlineCode) {
                if (current == '[') {
                    linkTextDepth++;
                } else if (current == ']' && linkTextDepth > 0
                        && index + 1 < text.length() && text.charAt(index + 1) == '(') {
                    linkTextDepth--;
                    linkUrlOpen = true;
                    index += 2;
                    if (isSafe(inBold, inInlineCode, inCodeFence, linkTextDepth, linkUrlOpen)) {
                        lastSafePosition = index;
                    }
                    continue;
                } else if (current == ')' && linkUrlOpen) {
                    linkUrlOpen = false;
                }
            }

            index++;
            if (inBold && !inInlineCode && !inCodeFence && linkTextDepth == 0 && !linkUrlOpen
                    && isBoldAutoCloseBoundary(current)) {
                boldAutoClosePosition = index;
            }
            if (isSafe(inBold, inInlineCode, inCodeFence, linkTextDepth, linkUrlOpen)) {
                lastSafePosition = index;
                boldAutoClosePosition = 0;
            }
        }

        return new ParseState(lastSafePosition, inBold, inInlineCode, inCodeFence,
                linkTextDepth, linkUrlOpen, boldAutoClosePosition);
    }

    private int countRun(CharSequence text, int start, char target) {
        int index = start;
        while (index < text.length() && text.charAt(index) == target) {
            index++;
        }
        return index - start;
    }

    private boolean isSafe(boolean inBold, boolean inInlineCode, boolean inCodeFence,
            int linkTextDepth, boolean linkUrlOpen) {
        return !inBold && !inInlineCode && !inCodeFence && linkTextDepth == 0 && !linkUrlOpen;
    }

    private boolean isBoldAutoCloseBoundary(char current) {
        return current == '\n'
                || current == '。'
                || current == '；'
                || current == '！'
                || current == '？'
                || current == '：'
                || current == ':';
    }

    private record ParseState(
            int lastSafePosition,
            boolean inBold,
            boolean inInlineCode,
            boolean inCodeFence,
            int linkTextDepth,
            boolean linkUrlOpen,
            int boldAutoClosePosition) {
    }
}

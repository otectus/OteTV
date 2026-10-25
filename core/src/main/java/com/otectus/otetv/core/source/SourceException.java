package com.otectus.otetv.core.source;

/** A source descriptor failed validation. */
public final class SourceException extends Exception {
    private final SourceIssue issue;

    public SourceException(SourceIssue issue, String detail) {
        super(issue + (detail == null ? "" : ": " + detail));
        this.issue = issue;
    }

    public SourceException(SourceIssue issue) {
        this(issue, null);
    }

    public SourceIssue issue() {
        return issue;
    }
}

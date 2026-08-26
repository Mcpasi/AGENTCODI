package de.agentcodi.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WorkspaceBrowserPage {
    private final String relativeDirectory;
    private final String parentRelativeDirectory;
    private final List<WorkspaceBreadcrumb> breadcrumbs;
    private final List<WorkspaceBrowserEntry> entries;
    private final int pageIndex;
    private final int pageCount;
    private final int totalEntryCount;
    private final boolean scanTruncated;

    public WorkspaceBrowserPage(
        String relativeDirectory,
        String parentRelativeDirectory,
        List<WorkspaceBreadcrumb> breadcrumbs,
        List<WorkspaceBrowserEntry> entries,
        int pageIndex,
        int pageCount,
        int totalEntryCount,
        boolean scanTruncated
    ) {
        if (relativeDirectory == null || parentRelativeDirectory == null
            || breadcrumbs == null || entries == null
            || relativeDirectory.length()
                > WorkspaceBrowserLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS
            || parentRelativeDirectory.length()
                > WorkspaceBrowserLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS
            || breadcrumbs.size() > WorkspaceBrowserLimits.MAXIMUM_DIRECTORY_DEPTH + 1
            || entries.size() > WorkspaceBrowserLimits.MAXIMUM_DIRECTORY_PAGE_SIZE
            || pageIndex < 0 || pageCount <= 0 || pageIndex >= pageCount
            || pageCount > WorkspaceBrowserLimits.MAXIMUM_SCANNED_DIRECTORY_ENTRIES
            || totalEntryCount < entries.size()
            || totalEntryCount > WorkspaceBrowserLimits.MAXIMUM_SCANNED_DIRECTORY_ENTRIES) {
            throw new IllegalArgumentException("Workspace browser page is invalid");
        }
        this.relativeDirectory = relativeDirectory;
        this.parentRelativeDirectory = parentRelativeDirectory;
        this.breadcrumbs = immutableCopy(breadcrumbs);
        this.entries = immutableCopy(entries);
        this.pageIndex = pageIndex;
        this.pageCount = pageCount;
        this.totalEntryCount = totalEntryCount;
        this.scanTruncated = scanTruncated;
    }

    public String getRelativeDirectory() {
        return relativeDirectory;
    }

    public String getParentRelativeDirectory() {
        return parentRelativeDirectory;
    }

    public List<WorkspaceBreadcrumb> getBreadcrumbs() {
        return breadcrumbs;
    }

    public List<WorkspaceBrowserEntry> getEntries() {
        return entries;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getPageCount() {
        return pageCount;
    }

    public int getTotalEntryCount() {
        return totalEntryCount;
    }

    public boolean isScanTruncated() {
        return scanTruncated;
    }

    public boolean hasPreviousPage() {
        return pageIndex > 0;
    }

    public boolean hasNextPage() {
        return pageIndex + 1 < pageCount;
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        ArrayList<T> copy = new ArrayList<T>(source.size());
        for (T value : source) {
            if (value == null) {
                throw new IllegalArgumentException("Browser page values must not be null");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }
}

package com.example;

/**
 * An optional subset {@code WHERE} fragment plus its bind parameter for SELECT/COUNT queries.
 * {@link #NONE} represents "no subset" (empty fragment, no parameter).
 */
record SubsetFilter(String sqlFragment, String param)
{
    static final SubsetFilter NONE = new SubsetFilter("", null);
}

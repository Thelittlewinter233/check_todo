package com.checktodo.app.wakeup_parser;

/** Common contract for rewritten WakeUp parser families. */
public interface WakeupParser {
    String name();

    WakeupParserResult parse(String source);
}

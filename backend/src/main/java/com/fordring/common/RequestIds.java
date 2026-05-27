package com.fordring.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

final class RequestIds {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private RequestIds() {
    }

    static String next() {
        return "req-" + LocalDate.now().format(FORMATTER) + "-" + String.format("%06d", SEQUENCE.incrementAndGet());
    }
}


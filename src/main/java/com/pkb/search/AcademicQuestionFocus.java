package com.pkb.search;

import java.util.Optional;

/** Removes a graduate cohort/program prefix so the remaining information need can be checked separately. */
public final class AcademicQuestionFocus {
    private AcademicQuestionFocus() { }

    public static Optional<String> extract(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        String stripped = question.strip().replaceFirst("^20\\d{2}级", "");
        int marker = stripped.indexOf("学硕");
        if (marker <= 0 || marker > 18) return Optional.empty();
        String focus = stripped.substring(marker + 2).strip();
        return focus.length() >= 4 ? Optional.of(focus) : Optional.empty();
    }
}
